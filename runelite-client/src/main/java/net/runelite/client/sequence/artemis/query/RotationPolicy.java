package net.runelite.client.sequence.artemis.query;

/**
 * Target-selection policy: when a query matches multiple candidates,
 * which one does Artemis pick? See spec §7. Per-query-type defaults:
 * {@link NpcQuery} → {@code ClosestWithSlack(2)},
 * {@link ObjectQuery} → {@code ClosestWithSlack(1)},
 * {@link ItemQuery} → {@code ClosestWithSlack(1)},
 * {@code NamedZone} via walkTo → {@code UniformWithinRange()}.
 * {@link WidgetQuery} carries no rotation field — widget id resolves
 * to one widget by construction.
 *
 * <p>Typed weighted variants (WeightedNpc, WeightedObject) deferred to
 * v1.1+.
 */
public sealed interface RotationPolicy
{
	/** Strict closest. Tiebreak: random among ties (never NPC-index tiebreak). */
	record Closest() implements RotationPolicy {}

	/** Random pick from any target within {@code slack} tiles of the closest. */
	record ClosestWithSlack(int slack) implements RotationPolicy {}

	/** Uniform random over every match in range. */
	record UniformWithinRange() implements RotationPolicy {}

	/** Sticky pick — per-account-seeded RNG biases toward a specific
	 *  instance for {@code stickyMs}, then drifts. Models "this account
	 *  tends to use the SE booth most of the time but sometimes uses
	 *  the others." */
	record SessionSticky(int stickyMs, String stickinessKey) implements RotationPolicy {}
}
