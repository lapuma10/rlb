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
    private enum State { NONE, NPC_CONTINUES, PLAYER_CONTINUES, OPTION_MENU, LEVELUP }

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
