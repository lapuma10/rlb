package net.runelite.client.sequence.login;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import javax.annotation.Nullable;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Login V2 entry point. Parallel to {@link LoginAssistant}. V1 stays
 * untouched; V2 is wired to a separate panel button so V1 can keep being
 * used for testing while V2 is field-tested.
 */
@Slf4j
public final class LoginAssistantV2
{
    private final HumanizedInputDispatcher dispatcher;
    private final Client client;
    private final ClientThread clientThread;
    private final Random rng;

    public LoginAssistantV2(HumanizedInputDispatcher dispatcher, Client client, ClientThread clientThread)
    {
        this(dispatcher, client, clientThread, new Random());
    }

    public LoginAssistantV2(HumanizedInputDispatcher dispatcher, Client client,
                            ClientThread clientThread, Random rng)
    {
        if (dispatcher == null) throw new IllegalArgumentException("dispatcher required");
        if (client == null) throw new IllegalArgumentException("client required");
        this.dispatcher = dispatcher;
        this.client = client;
        this.clientThread = clientThread;
        this.rng = rng;
    }

    /**
     * Run the V2 login flow. Caller picks the thread — this method blocks.
     * Use a daemon worker thread, never the EDT.
     *
     * @param creds          username + lazy password supplier
     * @param targetWorldId  world to switch to before login; null = no switch
     * @param status         optional status sink (called from worker thread;
     *                       impl is responsible for hopping to EDT if needed)
     * @return               true on LOGGED_IN, false on classified failure
     */
    public boolean login(LoginCredentials creds, @Nullable Integer targetWorldId, Consumer<String> status)
    {
        if (creds == null) throw new IllegalArgumentException("credentials required");
        Consumer<String> sink = status != null ? status : s -> {};
        LoginContextV2 ctx = new LoginContextV2(creds, dispatcher, client, clientThread, rng, sink, targetWorldId);
        return LoginRunnerV2.run(ctx);
    }
}
