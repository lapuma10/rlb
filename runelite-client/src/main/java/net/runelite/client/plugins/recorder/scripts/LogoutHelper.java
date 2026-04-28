package net.runelite.client.plugins.recorder.scripts;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * Clicks the in-game Logout button. The Logout panel (InterfaceID.Logout)
 * must already be open — if it is not, {@link #tryLogout()} returns
 * {@code false} and the caller retries next tick.
 *
 * <p>Caller is responsible for ensuring the player is logged in and idle
 * (e.g. not in combat) — clicks during combat are silently dropped by the
 * engine.
 */
@Slf4j
@RequiredArgsConstructor
public final class LogoutHelper
{
    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;

    /**
     * Try to click the Logout button.
     *
     * @return {@code true} if the CLICK_WIDGET request was dispatched;
     *         {@code false} if the panel is not open yet — caller retries
     *         next tick.
     */
    public boolean tryLogout() throws InterruptedException
    {
        Widget logoutBtn = onClient(
            () -> client.getWidget(InterfaceID.Logout.LOGOUT));
        if (logoutBtn != null && !logoutBtn.isHidden())
        {
            log.info("logout: clicking LOGOUT widget (id={})", InterfaceID.Logout.LOGOUT);
            ActionRequest req = ActionRequest.builder()
                .kind(ActionRequest.Kind.CLICK_WIDGET)
                .channel(ActionRequest.Channel.MOUSE)
                .widgetId(InterfaceID.Logout.LOGOUT)
                .build();
            dispatcher.dispatch(req);
            return true;
        }
        log.debug("logout: panel not open — caller should retry");
        return false;
    }

    private <T> T onClient(Supplier<T> sup) throws InterruptedException
    {
        AtomicReference<T> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        clientThread.invokeLater(() ->
        {
            try { ref.set(sup.get()); }
            catch (Throwable th) { log.warn("logout: onClient threw", th); }
            finally { latch.countDown(); }
        });
        latch.await();
        return ref.get();
    }
}
