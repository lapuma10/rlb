package net.runelite.client.sequence.artemis;

import java.util.OptionalInt;

/**
 * Engine-side <b>observation-only</b> contract for advancing an in-game
 * logout. Returns the next widget the {@code LogoutStep} should click
 * to drive the logout sequence forward (side-panel tab → inner Logout
 * button → login screen). Implementations <b>must not</b> dispatch
 * input, must not block, and must not call
 * {@code clientThread.invoke*} / {@code latch.await} — those threading
 * choices belong to the consumer.
 *
 * <p><b>Why observation-only.</b> The legacy
 * {@code recorder/scripts/LogoutHelper.tryLogout()} bakes in two
 * threading assumptions that prevent safe composition under the
 * Artemis Step lifecycle:
 * <ol>
 *   <li>It uses {@code clientThread.invokeLater + latch.await()},
 *       which deadlocks when called from {@code Step.doStart} (which
 *       runs on the client thread).</li>
 *   <li>It calls {@code dispatcher.dispatch(CLICK_WIDGET)} internally,
 *       which is silently dropped when called from inside a
 *       {@code RUN_TASK} (the dispatcher is already busy with the
 *       outer task — see {@code HumanizedInputDispatcher.dispatch}).</li>
 * </ol>
 * Both traps are the exact "looks fine, fails silently" failure mode
 * the project has spent significant effort eliminating. The clean
 * separation is: this interface <b>observes</b> the current UI state
 * and returns the next click target; {@code LogoutStep} <b>dispatches</b>
 * exactly one {@code CLICK_WIDGET} per Step attempt; the engine's
 * {@code Recovery.Retry} drives the multi-stage sequence.
 *
 * <p>Threading: free to call from any thread. Implementations that
 * need to read widgets safely from off the client thread are expected
 * to marshal internally (e.g. via a brief {@code ClientThread.invoke}
 * + {@code Future.get}) — but the marshal must NOT block in a way
 * that deadlocks the client thread (no {@code invokeLater + latch}
 * from the client thread itself). A pure read from the client thread,
 * or a brief blocking read from a worker thread, are both acceptable.
 *
 * <p>Return value: {@link OptionalInt#empty()} when no logout-related
 * widget is currently visible (the Step then surfaces
 * {@code LOGOUT_FAILED} for its current attempt; the engine retries).
 * Otherwise the packed widget id that {@code Client.getWidget(int)}
 * would resolve — same encoding used elsewhere in the engine
 * ({@code InterfaceID.Logout.LOGOUT}, {@code InterfaceID.Toplevel.STONE10},
 * and the resizable variants).
 */
public interface LogoutAction
{
	/** The next widget id to click to advance the logout sequence, or
	 *  {@link OptionalInt#empty()} if no logout-related widget is
	 *  currently visible. Pure observation — see interface Javadoc
	 *  for the contract. */
	OptionalInt nextLogoutWidgetId();
}
