package net.runelite.client.plugins.recorder.npc;

import java.awt.Polygon;
import java.awt.Rectangle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.dispatch.SequenceSleep;

/**
 * Drives NPC dialogue navigation — advancing Continue prompts and selecting
 * menu options by text until no dialogue widget is visible.
 *
 * <p><b>Threading:</b> {@link #inDialogue} marshals to the client thread
 * internally — safe to call from any worker thread or a Step's {@code check()}.
 * {@link #completeDialogue} blocks the calling thread between each click and
 * must run on the dispatcher worker thread (inside a {@code RUN_TASK}).
 */
@Slf4j
public final class NpcInteraction
{
    /** Outcome of one full {@link #talkTo} cycle. */
    public enum TalkResult
    {
        /** Click landed, dialogue widget rendered, completeDialogue
         *  drove it through to its natural close. */
        OPENED_AND_COMPLETED,
        /** Click was issued but no dialogue widget became visible
         *  inside the timeout. The caller's next tick should retry
         *  (re-resolve npcIndex first — the scene may have shifted). */
        NEVER_OPENED
    }

    private enum State { NONE, NPC_CONTINUES, PLAYER_CONTINUES, OPTION_MENU, LEVELUP }

    /** Cadence for the dialogue-open poll inside {@link #talkTo}. Each
     *  poll marshals to the client thread to read the dialogue widgets,
     *  so going below 250 ms wastes hops without meaningfully improving
     *  responsiveness — server tick is 600 ms, the dialog won't render
     *  faster than that. */
    private static final long DIALOGUE_POLL_INTERVAL_MS = 250L;

    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;

    public NpcInteraction(Client client, ClientThread clientThread,
                          HumanizedInputDispatcher dispatcher)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
    }

    /**
     * Locate an NPC on the loaded scene that matches one of {@code preferredIds}
     * or, if no id matches, the {@code nameFallback} (markup stripped,
     * case-insensitive).
     *
     * <p>Must run on the OSRS client thread — the scene iteration and
     * {@link NPC#getCanvasTilePoly()} call assert client thread under
     * {@code -ea}, and a stale-scene read mid-frame can produce
     * inconsistent results. Caller is responsible for marshalling
     * (typically via {@link ClientThread#invokeLater}). Throws
     * {@link IllegalStateException} when called off-thread so silent
     * misuse is impossible.
     *
     * <p>Match priority: first NPC whose {@code getId()} is in
     * {@code preferredIds} wins (id is invariant against name markup
     * and quest-completion id swaps); failing that, the first NPC whose
     * composition name strips to {@code nameFallback} (case-insensitive)
     * wins. Only NPCs on the same plane as the local player AND within
     * {@code scanRadius} (Chebyshev) tiles are considered.
     *
     * <p>The returned {@link NpcScan#diagnostic} is always populated:
     * on hit it includes {@code matched-by=id|name} and
     * {@code hullPoly=present|null}; on miss it lists up to 8 nearby
     * NPCs with id and Chebyshev distance — invaluable for grepping
     * the log when a match doesn't fire.
     *
     * @param scanRadius   Chebyshev tile distance from local player.
     * @param preferredIds NPC ids to prefer, in order. Pass {@code null}
     *                     or empty if only matching by name.
     * @param nameFallback NPC composition name to match (case-insensitive,
     *                     markup stripped) when no id matches. Pass
     *                     {@code null} to disable the name fallback.
     */
    public NpcScan findOnScene(int scanRadius, @Nullable int[] preferredIds,
                               @Nullable String nameFallback)
    {
        if (client != null && !client.isClientThread())
        {
            throw new IllegalStateException(
                "NpcInteraction.findOnScene called off the OSRS client thread — "
                    + "marshal via ClientThread.invokeLater first. Scene iteration "
                    + "and getCanvasTilePoly() assert client thread under -ea.");
        }
        Player self = client.getLocalPlayer();
        if (self == null) return NpcScan.miss("no local player");
        WorldPoint here = self.getWorldLocation();
        if (here == null) return NpcScan.miss("no player tile");

        NPC byId = null;
        NPC byName = null;
        StringBuilder nearby = new StringBuilder();
        int count = 0;
        for (NPC npc : client.getTopLevelWorldView().npcs())
        {
            if (npc == null) continue;
            WorldPoint loc = npc.getWorldLocation();
            if (loc == null) continue;
            if (loc.getPlane() != here.getPlane()) continue;
            int dist = chebyshev(loc, here);
            if (dist > scanRadius) continue;

            if (count++ < 8)
            {
                NPCComposition c = npc.getComposition();
                String n = c == null ? npc.getName() : c.getName();
                if (n == null) n = "?";
                nearby.append(stripMarkup(n)).append("(id=").append(npc.getId())
                    .append(",d=").append(dist).append(") ");
            }

            if (byId == null && preferredIds != null)
            {
                for (int wantedId : preferredIds)
                {
                    if (npc.getId() == wantedId) { byId = npc; break; }
                }
                if (byId != null) continue;
            }
            if (byName == null && nameFallback != null)
            {
                NPCComposition c = npc.getComposition();
                String n = c == null ? npc.getName() : c.getName();
                if (n != null && nameFallback.equalsIgnoreCase(stripMarkup(n)))
                {
                    byName = npc;
                }
            }
        }

        NPC hit = byId != null ? byId : byName;
        if (hit == null)
        {
            String diag = "scanned " + count + " NPCs within "
                + scanRadius + " tiles plane " + here.getPlane()
                + ": " + (nearby.length() == 0 ? "(none)" : nearby.toString().trim());
            return NpcScan.miss(diag);
        }
        Polygon poly = hit.getCanvasTilePoly();
        boolean onCanvas = poly != null;
        String matchedBy = (hit == byId) ? "id" : "name";
        return new NpcScan(hit.getIndex(), hit.getWorldLocation(), onCanvas,
            "matched-by=" + matchedBy + " hullPoly=" + (onCanvas ? "present" : "null"));
    }

    private static int chebyshev(WorldPoint a, WorldPoint b)
    {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }

    private static String stripMarkup(String s)
    {
        return s == null ? "" : s.replaceAll("<[^>]+>", "").trim();
    }

    /**
     * True when any dialogue widget is currently visible — NPC chat,
     * player chat, option menu, or level-up popup.
     * Safe to call from any thread.
     */
    public boolean inDialogue() throws InterruptedException
    {
        Boolean result = onClient(() ->
            isVisible(InterfaceID.ChatLeft.UNIVERSE)
            || isVisible(InterfaceID.ChatRight.UNIVERSE)
            || isVisible(InterfaceID.Chatmenu.UNIVERSE)
            || isVisible(InterfaceID.LevelupDisplay.UNIVERSE));
        return Boolean.TRUE.equals(result);
    }

    /**
     * Run one full talk cycle: click {@code npcIndex} with {@code verb},
     * wait for any dialogue widget to render, then drive
     * {@link #completeDialogue(String...)} until the dialogue closes.
     *
     * <p>Must run on the dispatcher worker thread (i.e. inside a
     * {@link net.runelite.client.sequence.internal.ActionRequest.Kind#RUN_TASK}).
     * Calling on the OSRS client thread would freeze the game during the
     * {@link SequenceSleep} between polls and the cs2 that renders the
     * dialogue would never run — see the "Threading model" section at
     * top of {@code CLAUDE.md}. The runtime guard throws
     * {@link IllegalStateException} on misuse so the failure mode is
     * loud, not a silent hang.
     *
     * <p>Returns {@link TalkResult#OPENED_AND_COMPLETED} once the dialogue
     * closes naturally (no widget visible). Returns
     * {@link TalkResult#NEVER_OPENED} if the dialogue never appeared inside
     * {@code dialogueOpenTimeoutMs} — covers both "click was rejected"
     * (dispatcher's {@code lastError} carries the reason) and "click
     * succeeded but the cs2 didn't render the dialog (NPC out of range,
     * busy, etc.)". Caller decides what to do next: retry, abort,
     * relocate.
     *
     * @param npcIndex              the NPC's scene index from a prior
     *                              {@link #findOnScene}; must still be
     *                              valid when this runs (NPC indices
     *                              are stable while the chunk stays
     *                              loaded).
     * @param verb                  e.g. {@code "Talk-to"}, {@code "Trade with"}.
     *                              Passed verbatim to
     *                              {@link HumanizedInputDispatcher#npcClickOnWorker}.
     * @param dialogueOpenTimeoutMs how long to wait for any dialogue
     *                              widget to appear after the click.
     *                              5 seconds is the cooks-assistant
     *                              default.
     * @param options               picked by {@link #completeDialogue}
     *                              when an option-menu state appears.
     *                              Empty array advances Continue prompts
     *                              only.
     */
    public TalkResult talkTo(int npcIndex, String verb,
                             long dialogueOpenTimeoutMs, String... options)
        throws InterruptedException
    {
        if (client != null && client.isClientThread())
        {
            throw new IllegalStateException(
                "NpcInteraction.talkTo called on the OSRS client thread — "
                    + "this would freeze the game during the dialogue-open "
                    + "poll and the cs2 that renders the dialog would never "
                    + "run. Wrap the call in an ActionRequest.Kind.RUN_TASK "
                    + "and dispatcher.dispatch(...) it from your tick loop.");
        }

        log.info("npc: talkTo idx={} verb='{}' (dialogue timeout {}ms, options={})",
            npcIndex, verb, dialogueOpenTimeoutMs, options == null ? 0 : options.length);

        // Step 1: click. npcClickOnWorker is synchronous — blocks until the
        // click chain (camera rotate → WindMouse → hover/verify → click)
        // completes. On click failure it sets dispatcher.lastError and
        // returns; we don't bail here, we let the dialogue-open poll
        // catch it as NEVER_OPENED. That keeps the contract simple — one
        // failure mode, not two.
        dispatcher.npcClickOnWorker(npcIndex, verb);

        // Step 2: poll for the dialogue widget. The cs2 that renders the
        // dialogue runs on the client thread; we sleep between polls so
        // that thread isn't starved.
        long deadline = System.currentTimeMillis() + dialogueOpenTimeoutMs;
        while (System.currentTimeMillis() < deadline)
        {
            if (inDialogue())
            {
                log.info("npc: talkTo idx={} — dialogue opened, completing", npcIndex);
                completeDialogue(options);
                log.info("npc: talkTo idx={} → OPENED_AND_COMPLETED", npcIndex);
                return TalkResult.OPENED_AND_COMPLETED;
            }
            SequenceSleep.sleep(client, DIALOGUE_POLL_INTERVAL_MS);
        }

        log.warn("npc: talkTo idx={} verb='{}' → NEVER_OPENED (waited {}ms; lastError='{}')",
            npcIndex, verb, dialogueOpenTimeoutMs,
            dispatcher == null ? "n/a" : dispatcher.lastErrorMessage());
        return TalkResult.NEVER_OPENED;
    }

    /**
     * Loops through the active dialogue, clicking Continue and/or picking
     * the first option whose text matches an entry in {@code options}
     * (case-insensitive), until no dialogue is visible.
     *
     * <p>Must be called from the dispatcher worker thread (inside a
     * {@link net.runelite.client.sequence.internal.ActionRequest.Kind#RUN_TASK}).
     *
     * @param options texts to match when the option-menu state is active;
     *                options are matched in display order — first match wins.
     *                Pass an empty array to only advance Continue prompts.
     */
    public void completeDialogue(String... options) throws InterruptedException
    {
        int safety = 60;
        while (safety-- > 0)
        {
            State state = onClient(this::dialogueState);
            if (state == null || state == State.NONE) return;

            switch (state)
            {
                case NPC_CONTINUES ->
                {
                    log.info("dialogue: NPC continue");
                    dispatcher.widgetClickOnWorker(InterfaceID.ChatLeft.CONTINUE);
                    SequenceSleep.sleep(client, 700L + (long) (Math.random() * 500L));
                }
                case PLAYER_CONTINUES ->
                {
                    log.info("dialogue: player continue");
                    dispatcher.widgetClickOnWorker(InterfaceID.ChatRight.CONTINUE);
                    SequenceSleep.sleep(client, 700L + (long) (Math.random() * 500L));
                }
                case OPTION_MENU ->
                {
                    Rectangle bounds = onClient(() -> findMatchingOptionBounds(options));
                    if (bounds == null)
                    {
                        log.warn("dialogue: option menu visible but no match for wanted={}", (Object) options);
                        return;
                    }
                    log.info("dialogue: picking option at bounds={}", bounds);
                    dispatcher.boundsClickOnWorker(bounds, null);
                    SequenceSleep.sleep(client, 700L + (long) (Math.random() * 500L));
                }
                case LEVELUP ->
                {
                    log.info("dialogue: dismissing level-up");
                    dispatcher.widgetClickOnWorker(InterfaceID.LevelupDisplay.CONTINUE);
                    SequenceSleep.sleep(client, 700L + (long) (Math.random() * 500L));
                }
            }
        }
        log.warn("dialogue: safety counter exhausted — dialogue still active?");
    }

    // ─── Client-thread helpers ──────────────────────────────────────────────

    private State dialogueState()
    {
        // Level-up takes priority — dismiss it first so underlying NPC chat resumes.
        if (isVisible(InterfaceID.LevelupDisplay.UNIVERSE)) return State.LEVELUP;
        if (isVisible(InterfaceID.ChatLeft.UNIVERSE))       return State.NPC_CONTINUES;
        if (isVisible(InterfaceID.ChatRight.UNIVERSE))      return State.PLAYER_CONTINUES;
        if (isVisible(InterfaceID.Chatmenu.UNIVERSE))       return State.OPTION_MENU;
        return State.NONE;
    }

    private boolean isVisible(int widgetId)
    {
        Widget w = client.getWidget(widgetId);
        return w != null && !w.isHidden();
    }

    /**
     * Client-thread: scan Chatmenu option children for the first text that
     * matches any entry in {@code wanted} (case-insensitive). Returns the
     * matched widget's bounds, or null if no match or widget unavailable.
     */
    private Rectangle findMatchingOptionBounds(String[] wanted)
    {
        Widget container = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
        if (container == null || container.isHidden()) return null;
        Widget[] children = container.getChildren();
        if (children == null) return null;
        for (Widget child : children)
        {
            if (child == null || child.isSelfHidden()) continue;
            String text = child.getText();
            if (text == null) continue;
            // getText() returns plain text (non-breaking spaces included);
            // strip and compare case-insensitively.
            String stripped = text.replace(' ', ' ').strip();
            for (String w : wanted)
            {
                if (w.equalsIgnoreCase(stripped))
                {
                    Rectangle r = child.getBounds();
                    return (r == null || r.isEmpty()) ? null : r;
                }
            }
        }
        return null;
    }

    private <T> T onClient(Supplier<T> s) throws InterruptedException
    {
        if (client != null && client.isClientThread())
        {
            try { return s.get(); }
            catch (Throwable th) { log.warn("npc: onClient threw inline", th); return null; }
        }
        AtomicReference<T> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try { ref.set(s.get()); }
            catch (Throwable th) { log.warn("npc: onClient threw", th); }
            finally { latch.countDown(); }
        });
        if (!latch.await(2000, TimeUnit.MILLISECONDS))
        {
            log.warn("npc: onClient timed out");
            return null;
        }
        return ref.get();
    }
}
