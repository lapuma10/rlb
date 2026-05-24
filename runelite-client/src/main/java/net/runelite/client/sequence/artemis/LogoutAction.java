package net.runelite.client.sequence.artemis;

/**
 * Engine-side abstraction for triggering an in-game logout. Decouples
 * {@link ArtemisImpl} from any concrete logout helper (the legacy
 * {@code recorder/scripts/LogoutHelper} ships an implementation behind
 * this interface; the {@code §14} import policy bans Artemis from
 * importing recorder/scripts/ directly).
 *
 * <p>One call drives one step of the multi-step logout sequence
 * (open the side-panel tab if needed → click the inner Logout button).
 * Returns {@code true} when an input was dispatched (caller stops or
 * waits for the engine to settle); returns {@code false} when neither
 * the side-panel tab nor the inner panel was visible — caller retries
 * on the next tick.
 *
 * <p>Threading: invoked from the dispatcher worker (never the client
 * thread — the legacy helper itself marshals reads back). Implementations
 * propagate {@link InterruptedException} so the worker can stop cleanly.
 */
public interface LogoutAction
{
	/** Drive one step of the logout sequence. See interface Javadoc for
	 *  semantics. {@code true} = click dispatched this call; {@code false}
	 *  = nothing to click yet, retry. */
	boolean tryLogout() throws InterruptedException;
}
