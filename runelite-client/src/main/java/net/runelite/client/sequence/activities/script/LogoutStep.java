package net.runelite.client.sequence.activities.script;

import java.util.OptionalInt;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.plugins.recorder.analyse.StepEvent;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Failure;
import net.runelite.client.sequence.Recovery;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.DiagnosticReason;
import net.runelite.client.sequence.artemis.Artemis;
import net.runelite.client.sequence.artemis.LogoutAction;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * Step backing {@code Artemis.logout()}. One {@code CLICK_WIDGET} per
 * Step attempt; the engine's {@link Recovery.Retry} budget drives the
 * multi-stage logout sequence (side-panel tab → inner Logout button →
 * login screen).
 *
 * <p>Clean Option B lifecycle (1A.4b design):
 * <pre>
 *   doStart:  ask LogoutAction which widget to click next.
 *             dispatch exactly one CLICK_WIDGET.
 *   doCheck:  observe GameState + dispatcher idle.
 *   onFailure: Retry(2) for LOGOUT_FAILED / TIMEOUT; Abort otherwise.
 * </pre>
 * No {@code RUN_TASK}, no hidden multi-step click sequence inside one
 * Step. Each attempt is observable as one started/succeeded-or-failed
 * StepEvent pair. The {@link LogoutAction} interface is
 * observation-only by design — see its Javadoc for the threading
 * rationale.
 *
 * <p>Maintenance Step: bypasses {@code artemis.session().shouldContinue()}
 * per spec §3 principle 5. Refusing to log out because the session
 * is over would be a deadlock.
 *
 * <p>Diagnostic vocabulary:
 * <ul>
 *   <li>{@code LOGOUT_ACTION_MISSING} — constructed without a
 *       {@link LogoutAction}; doStart fails fast.</li>
 *   <li>{@code LOGOUT_FAILED} — the click landed but the GameState
 *       didn't transition to {@code LOGIN_SCREEN} (typical of the
 *       intermediate tab-open click), <i>or</i> no logout widget was
 *       visible to click in the first place.</li>
 *   <li>{@code TIMEOUT} — 8 ticks elapsed without resolution.</li>
 * </ul>
 */
public final class LogoutStep extends ArtemisActionStep
{
	/** Per-attempt tick budget. Spec §11. Each Retry restarts the
	 *  budget. */
	private static final int TIMEOUT_TICKS = 8;

	/** Maximum Retry attempts. 2 retries (3 total attempts) covers the
	 *  worst-case sequence: open tab → click inner Logout → 1 safety
	 *  margin for engine timing jitter. */
	private static final int MAX_RETRIES = 2;

	/** Constructed without a {@link LogoutAction} — Artemis was wired
	 *  with a null {@code logoutAction} dep. Surfaces in {@code doStart};
	 *  triggers {@link Recovery.Abort} because retrying with the same
	 *  null dep does nothing. */
	static final String REASON_LOGOUT_ACTION_MISSING = "LOGOUT_ACTION_MISSING";

	/** Either {@link LogoutAction#nextLogoutWidgetId()} returned empty
	 *  (no widget visible), or a click was dispatched and the
	 *  dispatcher reported idle but {@code GameState} did not transition
	 *  to {@code LOGIN_SCREEN}. Both surface the same diagnostic; both
	 *  trigger {@link Recovery.Retry} so the engine drives the next
	 *  attempt. */
	static final String REASON_LOGOUT_FAILED = "LOGOUT_FAILED";

	private final Client client;
	@Nullable private final LogoutAction logoutAction;

	public LogoutStep(Artemis artemis, Consumer<StepEvent> stepEventSink,
		Client client, @Nullable LogoutAction logoutAction)
	{
		super(artemis, stepEventSink, /* maintenance */ true);
		if (client == null)
		{
			throw new IllegalArgumentException("Client must not be null");
		}
		this.client = client;
		this.logoutAction = logoutAction;
	}

	@Override public String name()              { return "Logout"; }
	@Override public int timeoutTicks()         { return TIMEOUT_TICKS; }
	@Override protected String targetType()     { return "game-state"; }
	@Override protected String targetId()       { return "game-state:LOGIN_SCREEN"; }
	@Override protected String targetName()     { return null; }
	@Override protected String verb()           { return "Logout"; }

	@Override
	protected void doStart(StepContext ctx)
	{
		if (logoutAction == null)
		{
			failOnStart(ctx, REASON_LOGOUT_ACTION_MISSING,
				"ArtemisDeps.logoutAction is null — wire a LogoutAction at construction");
			return;
		}

		OptionalInt nextWidget = logoutAction.nextLogoutWidgetId();
		if (nextWidget.isEmpty())
		{
			failOnStart(ctx, REASON_LOGOUT_FAILED,
				"LogoutAction.nextLogoutWidgetId() returned empty — no logout-related widget visible");
			return;
		}

		ActionRequest req = ActionRequest.builder()
			.kind(ActionRequest.Kind.CLICK_WIDGET)
			.channel(ActionRequest.Channel.MOUSE)
			.widgetId(nextWidget.getAsInt())
			.verb("Logout")
			.build();
		ctx.dispatcher().dispatch(req);
	}

	@Override
	protected Completion doCheck(WorldSnapshot s, Blackboard bb, long elapsed)
	{
		// Order matters: succeed FIRST so a late success at the timeout
		// tick still wins over the timeout failure. Spec §11 base contract
		// + LogoutStep observation.
		if (client.getGameState() == GameState.LOGIN_SCREEN)
		{
			return new Completion.Succeeded("logged out (GameState=LOGIN_SCREEN)");
		}
		if (elapsed >= TIMEOUT_TICKS)
		{
			return Completion.failed(new DiagnosticReason.ActionTimedOut(name(), (int) elapsed));
		}
		if (!dispatcherIdle())
		{
			return Completion.RUNNING;
		}
		// Dispatcher idle, GameState still LOGGED_IN. The click landed
		// but didn't log us out (typical of the intermediate tab-open
		// click). Fail this attempt; onFailure returns Retry(2) so the
		// engine drives the next attempt with a fresh nextLogoutWidgetId().
		return Completion.failed(new DiagnosticReason.Unknown(REASON_LOGOUT_FAILED));
	}

	@Override
	public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb)
	{
		String reason = extractReason(f);
		return switch (reason)
		{
			case REASON_LOGOUT_FAILED, REASON_TIMEOUT -> new Recovery.Retry(MAX_RETRIES);
			case REASON_LOGOUT_ACTION_MISSING -> new Recovery.Abort(reason);
			default -> new Recovery.Abort("unrecognized logout failure: " + reason);
		};
	}

	/** Map a {@link Failure} (engine-canonical) back to our diagnostic
	 *  vocabulary. Mirrors how {@link ArtemisActionStep#check}'s
	 *  emit() path maps DiagnosticReason → diagnosticReason string, so
	 *  the value used by {@link #onFailure} matches the value emitted
	 *  in the {@code failed} StepEvent. */
	private static String extractReason(Failure f)
	{
		DiagnosticReason d = f.diagnostic();
		if (d instanceof DiagnosticReason.ActionTimedOut)
		{
			return REASON_TIMEOUT;
		}
		if (d instanceof DiagnosticReason.Unknown unk)
		{
			return unk.detail() != null ? unk.detail() : REASON_TIMEOUT;
		}
		return f.reason() != null ? f.reason() : REASON_TIMEOUT;
	}
}
