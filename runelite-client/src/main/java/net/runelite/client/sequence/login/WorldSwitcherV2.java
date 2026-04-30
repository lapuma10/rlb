package net.runelite.client.sequence.login;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.World;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.dispatch.SequenceSleep;

/**
 * Pre-login world switcher for Login V2.
 *
 * Spec §7 chose Option B1 (canvas-pixel humanized) for this. In practice
 * the title-screen world-list panel doesn't expose a documented widget
 * interface (the WORLD_SWITCHER constant in InterfaceID.java is the
 * in-game Logout panel, not the title-screen world picker), so picking
 * a row by widget click isn't viable without per-row sprite calibration.
 *
 * Pragmatic compromise for V2 v1: click the title-screen "Worlds" button
 * via TitleFrame to surface the panel humanly, then call
 * {@link Client#changeWorld(World)} to actually set the world. The
 * "Worlds" button click is the visible humanized step; changeWorld is
 * the engine setter that upstream WorldHopperPlugin and DefaultWorldPlugin
 * also use, so it's not bot-distinctive. Strict B1 (per-row sprite click)
 * is a follow-up if needed.
 *
 * Threading: caller is on worker thread. We hop to client thread for
 * canvas/world reads via dispatcher.runOnClient.
 */
@Slf4j
public final class WorldSwitcherV2
{
    private static final long WORLD_CHANGE_TIMEOUT_MS = 5_000L;
    private static final long POLL_SLEEP_MS = 200L;

    private WorldSwitcherV2() {}

    /**
     * Switch to {@code targetWorldId}. Caller has already verified that
     * current != target. Returns true on success; false if the world id
     * doesn't exist, the click failed to land, or the world didn't update
     * within the timeout.
     */
    public static boolean switchTo(LoginContextV2 ctx, int targetWorldId) throws InterruptedException
    {
        Client client = ctx.getClient();
        HumanizedInputDispatcher dispatcher = ctx.getDispatcher();

        // Click the "Worlds" button first — this is what causes the OSRS client
        // to fetch and populate client.getWorldList(). Looking up the world
        // before this click returns null because the list hasn't loaded yet.
        TitleFrame.Frame frame = dispatcher.runOnClient(() -> TitleFrame.current(client));
        java.awt.Point btn = TitleFrame.button(frame, TitleFrame.ButtonId.WORLDS_BUTTON);
        log.info("[login-v2] click WORLDS_BUTTON canvas=({},{}) frame=({},{}) target=({},{})",
            client.getCanvasWidth(), client.getCanvasHeight(), frame.x(), frame.y(), btn.x, btn.y);
        dispatcher.clickCanvas(btn.x, btn.y);

        // Poll until the world list is populated, then resolve our target world.
        World target = null;
        long listDeadline = System.currentTimeMillis() + WORLD_CHANGE_TIMEOUT_MS;
        while (System.currentTimeMillis() < listDeadline)
        {
            if (Thread.interrupted()) throw new InterruptedException();
            target = dispatcher.runOnClient(() -> findWorld(client, targetWorldId));
            if (target != null) break;
            SequenceSleep.sleep(client, POLL_SLEEP_MS);
        }
        if (target == null)
        {
            log.warn("[login-v2] world {} not in world list after {}ms wait",
                targetWorldId, WORLD_CHANGE_TIMEOUT_MS);
            return false;
        }

        SequenceSleep.sleep(client, 600 + ctx.getRng().nextInt(300));

        // Apply the world change via the engine setter. See class javadoc.
        final World finalTarget = target;
        try
        {
            dispatcher.runOnClient(() -> { client.changeWorld(finalTarget); return null; });
        }
        catch (Exception ex)
        {
            log.warn("[login-v2] changeWorld threw", ex);
            return false;
        }

        long deadline = System.currentTimeMillis() + WORLD_CHANGE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            if (Thread.interrupted()) throw new InterruptedException();
            Integer cur;
            try { cur = dispatcher.runOnClient(client::getWorld); }
            catch (Exception ex) { return false; }
            if (cur != null && cur == targetWorldId)
            {
                log.info("[login-v2] world switch confirmed: {}", targetWorldId);
                return true;
            }
            SequenceSleep.sleep(client, POLL_SLEEP_MS);
        }
        log.warn("[login-v2] world switch timed out (target={}, current={})",
            targetWorldId, safeWorld(client));
        return false;
    }

    private static World findWorld(Client client, int worldId)
    {
        World[] worlds = client.getWorldList();
        if (worlds == null) return null;
        for (World w : worlds)
        {
            if (w != null && w.getId() == worldId) return w;
        }
        return null;
    }

    private static int safeWorld(Client client)
    {
        try { return client.getWorld(); } catch (Exception e) { return -1; }
    }
}
