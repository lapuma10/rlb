package net.runelite.client.plugins.recorder.scripts;

import java.util.Objects;
import java.util.Optional;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.artemis.Artemis;
import net.runelite.client.sequence.artemis.query.NpcQuery;
import net.runelite.client.sequence.artemis.query.RotationPolicy;
import net.runelite.client.sequence.artemis.session.IdlePolicy;
import net.runelite.client.sequence.artemis.view.NpcRef;
import net.runelite.client.sequence.artemis.view.PlayerState;
import net.runelite.client.sequence.artemis.zones.NamedZone;
import net.runelite.client.sequence.composite.DynamicStep;
import net.runelite.client.sequence.composite.FailStep;
import net.runelite.client.sequence.composite.LinearSequence;
import net.runelite.client.sequence.composite.RepeatStep;
import net.runelite.client.sequence.composite.Selector;

/**
 * Phase 2 Artemis pilot script. Composes a real {@link Step} tree using
 * the Artemis v1.0 surface only — no engine bypass, no allow-list rows
 * in the Phase 1B grep gate.
 *
 * <p><b>Plan shape</b> (Phase 2C):
 * <pre>{@code
 * LinearSequence("cow-killer-v1")
 *  ├─ walkTo(NamedZone.LUMBRIDGE_COW_FIELD)
 *  └─ Selector("cow-killer-after-walk")
 *       ├─ RepeatStep("cow-killer-loop", body=DynamicStep, times=0)
 *       │    body: 4-branch supplier — see plan() for details
 *       └─ logout()
 * }</pre>
 *
 * <p><b>Supplier branches</b> (order matters — Branch 0 must run first):
 * <ol start="0">
 *   <li><b>Session terminator.</b> {@code !session.shouldContinue()} →
 *       {@code FailStep("SESSION_EXHAUSTED")}. {@link IdlePolicy} backs
 *       {@code IdleStep}, which is a maintenance Step that bypasses the
 *       session gate — without this explicit check the loop would idle
 *       forever after Phase 0B lands real budgets.</li>
 *   <li><b>Busy gate.</b> {@code player == null || !player.idle()} →
 *       {@code artemis.idle(SHORT_IDLE)}. Prevents the base-click loop
 *       from spam-clicking while combat is in progress; the base click
 *       Step Succeeds on dispatcher complete (spec §11), not on combat
 *       end.</li>
 *   <li><b>No cow.</b> {@code findNpc.isEmpty()} →
 *       {@code artemis.idle(SHORT_IDLE)}. Cows respawn; other players
 *       may be holding ours; tick-timing reads can miss. Yield and
 *       retry — empty reads are not a terminal failure.</li>
 *   <li><b>Engage.</b> {@code artemis.click(cow, "Attack")} — base
 *       2-arg overload, no {@link
 *       net.runelite.client.sequence.artemis.outcome.OutcomeCheck}
 *       because {@code InteractingWithMe} / {@code TargetAnimChanged}
 *       evaluate to {@code OUTCOME_NOT_SUPPORTED_V1} in v1
 *       ({@code OutcomeChecks.java:87-91}).</li>
 * </ol>
 *
 * <p><b>Termination today (2C):</b> {@code SessionShape(Long.MAX_VALUE)}
 * per the launch wiring in {@code RecorderPlugin.launchCowKillerPilot()}
 * means Branch 0 never trips. The loop runs until operator Stop. Phase 0B
 * wires a real daily budget and Branch 0 activates without further
 * code changes here.
 */
public final class CowKillerScript
{
	/** Search radius for cows, in tiles, from the player's current tile.
	 *  East-of-Lumbridge field is 10×22; 15 tiles from any arrival tile
	 *  covers the field without bleeding into the chicken pen
	 *  (~5 tiles south) or the Al-Kharid path (~7 tiles east). */
	static final int COW_FIELD_RADIUS_TILES = 15;

	/** Short maintenance pause between supplier ticks when the loop
	 *  needs to yield (busy player or no cow available). 600 ms = 1
	 *  engine tick; 1200 ms = 2 ticks. Long enough that RepeatStep
	 *  doesn't burn CPU between iterations; short enough that
	 *  combat-end detection is timely. */
	static final IdlePolicy SHORT_IDLE = new IdlePolicy(600, 1200, false);

	private final Artemis artemis;

	public CowKillerScript(Artemis artemis)
	{
		this.artemis = Objects.requireNonNull(artemis, "artemis");
	}

	/**
	 * Compose the cow-killer plan. See class Javadoc for the four-branch
	 * supplier contract.
	 */
	public Step plan()
	{
		NpcQuery cowQuery = NpcQuery.byName("Cow")
			.within(COW_FIELD_RADIUS_TILES)
			.onPlane(NamedZone.LUMBRIDGE_COW_FIELD.plane())
			.unengagedOnly()
			.rotation(new RotationPolicy.ClosestWithSlack(2));

		DynamicStep tick = DynamicStep.of("cow-killer-tick", () ->
		{
			// Branch 0 — session terminator. MUST run first; see class
			// Javadoc for the IdleStep-bypasses-session-gate rationale.
			if (!artemis.session().shouldContinue())
			{
				return FailStep.of("SESSION_EXHAUSTED");
			}
			// Branch 1 — busy gate.
			PlayerState player = artemis.player();
			if (player == null || !player.idle())
			{
				return artemis.idle(SHORT_IDLE);
			}
			// Branch 2 — no cow available; yield + retry next iteration.
			Optional<NpcRef> cow = artemis.findNpc(cowQuery);
			if (cow.isEmpty())
			{
				return artemis.idle(SHORT_IDLE);
			}
			// Branch 3 — engage. Base 2-arg click overload.
			return artemis.click(cow.get(), "Attack");
		});

		Selector afterWalk = new Selector("cow-killer-after-walk")
			.option(new RepeatStep("cow-killer-loop", tick, 0))
			.option(artemis.logout());

		return new LinearSequence("cow-killer-v1")
			.then(artemis.walkTo(NamedZone.LUMBRIDGE_COW_FIELD))
			.then(afterWalk);
	}
}
