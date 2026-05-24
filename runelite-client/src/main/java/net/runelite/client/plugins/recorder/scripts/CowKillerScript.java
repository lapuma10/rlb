package net.runelite.client.plugins.recorder.scripts;

import java.util.Objects;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.artemis.Artemis;

/**
 * Phase 2 Artemis pilot script — proves a real script can use the
 * Artemis v1.0 surface to drive a real loop without reaching around the
 * engine. Lands in slices:
 *
 * <ul>
 *   <li><b>Phase 2B (this commit):</b> skeleton only. Constructor takes
 *       {@link Artemis}; {@link #plan()} throws so the file passes the
 *       Phase 1B grep gate and proves the wiring is reachable.</li>
 *   <li><b>Phase 2C:</b> {@link #plan()} returns the real composed plan
 *       described in
 *       {@code docs/superpowers/plans/2026-05-24-artemis-phase-2-cow-pilot.md}
 *       §4 — {@code LinearSequence(walkTo, Selector(RepeatStep(body =
 *       DynamicStep), Logout))}.</li>
 *   <li><b>Phase 2D:</b> operator-facing test-profile session protocol +
 *       Tier 1 acceptance checklist.</li>
 * </ul>
 *
 * <p>Import policy (spec §14, enforced by Phase 1B grep gate): this file
 * is allowed to import only {@link Artemis}, {@link Step}, the Artemis
 * sub-packages, and {@code sequence.composite.*} when explicitly
 * composing. It MUST NOT import {@code HumanizedInputDispatcher},
 * {@code ActionRequest}, {@code SceneScanner}, {@code NpcSelector},
 * {@code WidgetActions}, {@code BankInteraction}, {@code GeInteraction},
 * any {@code recorder.walker.*} / {@code nav.*} / {@code trail.*} /
 * {@code transport.*}, raw {@code NPC} / {@code GameObject} /
 * {@code Widget} types, {@code ClientThread}, {@code Robot}, or
 * {@code MouseEvent}. The grep gate fails the build if any of those
 * appear and there is no allow-list entry — and there will not be one
 * for this file.
 */
public final class CowKillerScript
{
	private final Artemis artemis;

	public CowKillerScript(Artemis artemis)
	{
		this.artemis = Objects.requireNonNull(artemis, "artemis");
	}

	/**
	 * The composed plan for the kill loop. Phase 2C wires this; Phase 2B
	 * deliberately throws so a panel launch path that calls
	 * {@code script.plan()} fails loudly rather than silently producing
	 * no-op work.
	 */
	public Step plan()
	{
		throw new UnsupportedOperationException(
			"CowKillerScript.plan() — Phase 2C wires the loop");
	}
}
