package net.runelite.client.sequence.artemis;

import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.recorder.RecorderManager;
import net.runelite.client.plugins.recorder.nav.Navigator;
import net.runelite.client.plugins.recorder.session.AccountRng;
import net.runelite.client.plugins.recorder.session.SessionShape;

/**
 * Bundle of runtime dependencies for {@link ArtemisImpl}. Introduced in
 * Phase 1A.4a so the constructor stays a single argument as new engine
 * pieces land (Navigator for walkTo, LogoutAction for logout, future
 * additions). Tests that don't exercise a given dep may pass
 * {@code null} for the {@link Nullable} fields — {@link ArtemisImpl}
 * throws a clear error at the use site rather than at construction.
 *
 * <p>Record (immutable), positional. No builder — 8 fields is the
 * inflection point but the construction surface is small (4 test
 * sites + future production wiring). Add a builder if/when callers
 * proliferate.
 *
 * <p>Field rationale:
 * <ul>
 *   <li>{@code client} / {@code clientThread} — engine handles for all
 *       reads (marshaled to client thread per spec §8).
 *   <li>{@code accountRng} — per-account RNG provider; ArtemisImpl
 *       seeds {@code "artemis-rotation"} for {@link
 *       net.runelite.client.sequence.artemis.query.RotationPolicySelector}
 *       and {@code "artemis-idle"} for {@code IdleStep} (1A.4b).
 *   <li>{@code session} — injected by the runtime per spec §3 design
 *       principle 5 (ownership is runtime, not script).
 *   <li>{@code itemManager} — for resolving item / object names on
 *       reads. May be {@code null} in tests that don't assert on names.
 *   <li>{@code recorder} — StepEvent sink (Phase 0A.3 producer hook).
 *       {@code null} = no recording (tests).
 *   <li>{@code navigator} — walker contract for {@code walkTo} (Phase
 *       1A.4c/d). {@code null} acceptable in 1A.4a; {@code WalkToStep}
 *       fails loud when invoked without one.
 *   <li>{@code logoutAction} — logout contract for {@code logout()}
 *       (Phase 1A.4b). {@code null} acceptable in 1A.4a; {@code
 *       LogoutStep} fails loud when invoked without one.
 * </ul>
 */
public record ArtemisDeps(
	Client client,
	ClientThread clientThread,
	AccountRng accountRng,
	SessionShape session,
	@Nullable ItemManager itemManager,
	@Nullable RecorderManager recorder,
	@Nullable Navigator navigator,
	@Nullable LogoutAction logoutAction)
{
}
