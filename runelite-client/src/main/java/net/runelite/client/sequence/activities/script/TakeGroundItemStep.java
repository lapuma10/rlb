package net.runelite.client.sequence.activities.script;

import java.util.Optional;
import java.util.function.Consumer;
import net.runelite.client.plugins.recorder.analyse.StepEvent;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Recovery;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.DiagnosticReason;
import net.runelite.client.sequence.artemis.Artemis;
import net.runelite.client.sequence.artemis.query.ItemQuery;
import net.runelite.client.sequence.artemis.view.GroundItemRef;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardKey;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * Step backing {@code Artemis.take(GroundItemRef)}.
 *
 * <p>Re-resolves the {@link GroundItemRef} per spec §8 — ground items
 * are tile-static (they don't drift) so the live item must be on the
 * exact original tile with the same item id. If the player has moved
 * onto a different tile and the original tile is no longer visible the
 * Step fails with {@code TARGET_NOT_FOUND}.
 *
 * <p>Inventory full ⇒ {@code Recovery.Abort} (spec §12.6 — not
 * retriable without script-level state change). All other failures
 * default to {@code Recovery.Retry(3)} via {@link ArtemisActionStep}.
 *
 * <p>Success: inventory's count of {@code item.itemId} increases by
 * ≥1 from the pre-dispatch baseline within {@code TIMEOUT_TICKS} (6,
 * per spec §11). Watching inventory rather than ground-item disappearance
 * avoids the "another player picked it up" false-positive — if their pickup
 * landed first, our inventory never grew, so we fail with
 * {@code TARGET_NOT_FOUND} (or wait out the budget for {@code TIMEOUT}).
 */
public final class TakeGroundItemStep extends ArtemisActionStep
{
	private static final BlackboardKey<Integer> K_BASELINE_COUNT =
		BlackboardKey.of("takeGroundItem.baselineCount", Integer.class);
	/** Tick we first observed the dispatcher worker reach idle — i.e.
	 *  when the press chain finished. The 4-tick spec budget for
	 *  "inventory gained" starts from this tick, not from {@code onStart}. */
	private static final BlackboardKey<Long> K_DISPATCH_IDLE_TICK =
		BlackboardKey.of("takeGroundItem.dispatchIdleTick", Long.class);

	private static final int TIMEOUT_TICKS = 6;
	/** Spec §11: success requires inventory gain within 4 ticks. We
	 *  measure those 4 ticks from when the dispatcher reached idle
	 *  (press chain ended), not from onStart — the cursor path +
	 *  press itself can consume 1-2 ticks. */
	private static final int INVENTORY_GAIN_BUDGET_TICKS = 4;
	/** Distinct diagnostic so "click landed but item didn't appear"
	 *  (other player snatched it / click resolved to a different verb)
	 *  is distinguishable from a hard timeout. */
	private static final String REASON_INVENTORY_NOT_GAINED = "INVENTORY_NOT_GAINED";

	private final GroundItemRef ref;

	public TakeGroundItemStep(Artemis artemis, Consumer<StepEvent> stepEventSink, GroundItemRef ref)
	{
		super(artemis, stepEventSink, false);
		if (ref == null)
		{
			throw new IllegalArgumentException("GroundItemRef must not be null");
		}
		this.ref = ref;
	}

	@Override
	public String name()
	{
		return "TakeGroundItem(" + (ref.name() != null ? ref.name() : ("id=" + ref.itemId())) + ")";
	}

	@Override public int timeoutTicks()       { return TIMEOUT_TICKS; }
	@Override protected String targetType()   { return "item"; }
	@Override protected String targetId()     { return "item:" + ref.itemId() + "@" + locKey(ref); }
	@Override protected String targetName()   { return ref.name(); }
	@Override protected String verb()         { return "Take"; }

	@Override
	public Recovery onFailure(net.runelite.client.sequence.Failure f, WorldSnapshot s, Blackboard bb)
	{
		// Inventory-full failures are not retriable; the script needs
		// to bank/drop before another take attempt makes sense.
		String reason = f.reason() == null ? "" : f.reason();
		if (reason.contains(REASON_INVENTORY_FULL))
		{
			return new Recovery.Abort("inventory full");
		}
		return super.onFailure(f, s, bb);
	}

	@Override
	protected void doStart(StepContext ctx)
	{
		long now = ctx.currentTick();
		if (isStaleByAge(ref.observedTick(), now))
		{
			failOnStart(ctx, REASON_STALE_REF,
				"age=" + ticksSince(ref.observedTick(), now) + "t > " + STALE_REF_BUDGET_TICKS);
			return;
		}

		// Inventory-full check before any click. Avoids dispatching a
		// take that can't possibly succeed AND avoids the "drop one,
		// take one" race where the engine resolves to a different verb.
		// Read inventory ONCE — reads marshal to the client thread
		// and we want the baseline count to be taken from the same
		// view as the fullness check.
		net.runelite.client.sequence.artemis.view.InventoryView inv = artemis.inventory();
		if (inv != null && inv.isFull())
		{
			failOnStart(ctx, REASON_INVENTORY_FULL, "all 28 slots occupied");
			return;
		}

		// Re-resolve — ground items are tile-static, so re-find at the
		// ref's plane with a small range; identity = same tile + same
		// itemId.
		ItemQuery query = ItemQuery.byId(ref.itemId()).within(STALE_REF_REFIND_RANGE_TILES);
		if (ref.originalLoc() != null)
		{
			query = query.onPlane(ref.originalLoc().getPlane());
		}
		Optional<GroundItemRef> live = artemis.findItem(query);
		if (live.isEmpty())
		{
			failOnStart(ctx, REASON_TARGET_NOT_FOUND, "no item id=" + ref.itemId()
				+ " within " + STALE_REF_REFIND_RANGE_TILES + "t");
			return;
		}
		GroundItemRef fresh = live.get();
		if (!identityMatches(ref, fresh))
		{
			failOnStart(ctx, REASON_STALE_REF,
				"identity mismatch ref=" + ref.originalLoc() + " live=" + fresh.originalLoc());
			return;
		}

		// Baseline inventory count for the success check (re-use the
		// view captured above).
		Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
		int baseline = inv == null ? 0 : inv.count(ref.itemId());
		step.put(K_BASELINE_COUNT, baseline);

		ActionRequest req = ActionRequest.builder()
			.kind(ActionRequest.Kind.CLICK_GROUND_ITEM)
			.channel(ActionRequest.Channel.MOUSE)
			.tile(fresh.originalLoc())
			.itemId(fresh.itemId())
			.verb("Take")
			.build();
		ctx.dispatcher().dispatch(req);
	}

	@Override
	protected Completion doCheck(WorldSnapshot s, Blackboard bb, long elapsed)
	{
		if (elapsed >= TIMEOUT_TICKS)
		{
			return Completion.failed(new DiagnosticReason.ActionTimedOut(name(), (int) elapsed));
		}
		Blackboard step = bb.scope(BlackboardScope.STEP);
		Integer baseline = step.get(K_BASELINE_COUNT).orElse(null);
		if (baseline == null)
		{
			// Should not happen — onStart pinned it. Be defensive.
			return Completion.RUNNING;
		}
		net.runelite.client.sequence.artemis.view.InventoryView invNow = artemis.inventory();
		int current = invNow == null ? 0 : invNow.count(ref.itemId());
		if (current > baseline)
		{
			return new Completion.Succeeded("inventory gained " + (current - baseline)
				+ " of itemId=" + ref.itemId());
		}
		// Still waiting on the press chain — RUNNING (within total 6-tick
		// timeout already guarded above).
		if (!dispatcherIdle())
		{
			return Completion.RUNNING;
		}
		// Press chain finished. Spec §11 grants 4 ticks after that for the
		// inventory to update; if no gain by then, fail with a typed
		// "click landed but item didn't appear" diagnostic distinct from
		// the generic TIMEOUT (item taken by someone else, click resolved
		// to a different verb, etc.).
		Long idleTick = step.get(K_DISPATCH_IDLE_TICK).orElse(null);
		if (idleTick == null)
		{
			step.put(K_DISPATCH_IDLE_TICK, (long) s.tick());
			return Completion.RUNNING;
		}
		long ticksSinceIdle = s.tick() - idleTick;
		if (ticksSinceIdle >= INVENTORY_GAIN_BUDGET_TICKS)
		{
			return Completion.failed(new DiagnosticReason.Unknown(REASON_INVENTORY_NOT_GAINED));
		}
		return Completion.RUNNING;
	}

	private static boolean identityMatches(GroundItemRef ref, GroundItemRef live)
	{
		if (ref.itemId() != live.itemId())
		{
			return false;
		}
		if (ref.originalLoc() != null && live.originalLoc() != null)
		{
			if (!ref.originalLoc().equals(live.originalLoc()))
			{
				return false;
			}
		}
		return true;
	}

	private static String locKey(GroundItemRef ref)
	{
		return ref.originalLoc() == null ? "null"
			: (ref.originalLoc().getX() + "," + ref.originalLoc().getY() + "," + ref.originalLoc().getPlane());
	}
}
