package net.runelite.client.sequence.login;

import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import javax.annotation.Nullable;
import java.util.Random;
import java.util.function.Consumer;

/**
 * V2 context. Parallel to {@link LoginContext} but adds {@code targetWorldId}
 * and drops V1's recovery-loop fields (retryCount, lastError) since V2 is
 * one-shot — no auto-retry. User typed an explicit world; surface failures
 * to the panel so the user can correct and re-click.
 *
 * Single-threaded access by the V2 runner; no synchronization needed.
 */
public final class LoginContextV2
{
    private final LoginCredentials credentials;
    private final HumanizedInputDispatcher dispatcher;
    private final Client client;
    @Nullable private final ClientThread clientThread;
    private final Random rng;
    private final Consumer<String> statusSink;
    @Nullable private final Integer targetWorldId;

    public LoginContextV2(LoginCredentials credentials,
                          HumanizedInputDispatcher dispatcher,
                          Client client,
                          @Nullable ClientThread clientThread,
                          Random rng,
                          Consumer<String> statusSink,
                          @Nullable Integer targetWorldId)
    {
        this.credentials = credentials;
        this.dispatcher = dispatcher;
        this.client = client;
        this.clientThread = clientThread;
        this.rng = rng;
        this.statusSink = statusSink;
        this.targetWorldId = targetWorldId;
    }

    public LoginCredentials getCredentials() { return credentials; }
    public HumanizedInputDispatcher getDispatcher() { return dispatcher; }
    public Client getClient() { return client; }
    @Nullable public ClientThread getClientThread() { return clientThread; }
    public Random getRng() { return rng; }
    @Nullable public Integer getTargetWorldId() { return targetWorldId; }
    public void status(String msg) { if (statusSink != null) statusSink.accept(msg); }
}
