package net.runelite.client.sequence.artemis.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.ToIntFunction;

/**
 * Applies a {@link RotationPolicy} to a candidate list and returns one
 * pick. Engine-internal — not part of the public Artemis surface.
 *
 * <p>Distance is provided by the caller as a {@link ToIntFunction}
 * (typically Chebyshev distance from the player); the selector itself
 * is shape-agnostic. Seeded {@link Random} is provided at construction
 * (per spec §3 design principle #7: per-account seed propagates to
 * RotationPolicy).
 *
 * <p>Spec §7. v1 implements four policies: Closest, ClosestWithSlack,
 * UniformWithinRange, SessionSticky.
 */
public final class RotationPolicySelector
{
	private final Random rng;
	/** Snapshotted once at construction so {@link RotationPolicy.SessionSticky}
	 *  picks can be account-differentiated without consuming a draw
	 *  from {@link #rng} on every sticky call (which would perturb the
	 *  state seen by subsequent {@code Closest} / {@code UniformWithinRange}
	 *  picks within the same session). */
	private final long accountMix;

	public RotationPolicySelector(Random rng)
	{
		this.rng = rng;
		// Single one-time draw → fixed per-instance mix value. Sticky
		// picks never touch the main stream after this point.
		this.accountMix = rng.nextLong();
	}

	/** Pick one candidate per the policy. Returns {@code null} when the
	 *  list is empty. Single-candidate lists short-circuit. */
	public <T> T pick(List<T> candidates, ToIntFunction<T> distance, RotationPolicy policy)
	{
		if (candidates == null || candidates.isEmpty())
		{
			return null;
		}
		if (candidates.size() == 1)
		{
			return candidates.get(0);
		}

		if (policy instanceof RotationPolicy.Closest)
		{
			return pickClosest(candidates, distance);
		}
		if (policy instanceof RotationPolicy.ClosestWithSlack cs)
		{
			return pickClosestWithSlack(candidates, distance, cs.slack());
		}
		if (policy instanceof RotationPolicy.UniformWithinRange)
		{
			return candidates.get(rng.nextInt(candidates.size()));
		}
		if (policy instanceof RotationPolicy.SessionSticky sticky)
		{
			return pickSessionSticky(candidates, distance, sticky);
		}
		throw new IllegalStateException("Unhandled RotationPolicy: " + policy);
	}

	private <T> T pickClosest(List<T> cs, ToIntFunction<T> d)
	{
		int minDist = Integer.MAX_VALUE;
		for (T c : cs)
		{
			int dist = d.applyAsInt(c);
			if (dist < minDist)
			{
				minDist = dist;
			}
		}
		List<T> ties = collectAtDistance(cs, d, minDist);
		// Random among ties — NEVER NPC-index tiebreak per spec §7.
		return ties.get(rng.nextInt(ties.size()));
	}

	private <T> T pickClosestWithSlack(List<T> cs, ToIntFunction<T> d, int slack)
	{
		int minDist = Integer.MAX_VALUE;
		for (T c : cs)
		{
			int dist = d.applyAsInt(c);
			if (dist < minDist)
			{
				minDist = dist;
			}
		}
		int cutoff = minDist + slack;
		List<T> within = new ArrayList<>();
		for (T c : cs)
		{
			if (d.applyAsInt(c) <= cutoff)
			{
				within.add(c);
			}
		}
		return within.get(rng.nextInt(within.size()));
	}

	/** v1 simplification: a deterministic per-key pick. The spec
	 *  describes drift over {@code stickyMs} for full impl; v1 omits
	 *  the drift component until a script needs it. Per-account seed
	 *  separation still applies — sticky key is mixed with the
	 *  one-time {@link #accountMix} snapshot (NOT a fresh
	 *  {@code rng.nextLong()} on every call, which would perturb the
	 *  main stream seen by other policies within the same session). */
	private <T> T pickSessionSticky(List<T> cs, ToIntFunction<T> d,
		RotationPolicy.SessionSticky sticky)
	{
		long stickySeed = sticky.stickinessKey() == null
			? 0L
			: sticky.stickinessKey().hashCode();
		long mixed = stickySeed ^ accountMix;
		// Fresh Random for the local pick — doesn't touch the main stream.
		Random local = new Random(mixed);
		return cs.get(local.nextInt(cs.size()));
	}

	private static <T> List<T> collectAtDistance(List<T> cs, ToIntFunction<T> d, int target)
	{
		List<T> out = new ArrayList<>();
		for (T c : cs)
		{
			if (d.applyAsInt(c) == target)
			{
				out.add(c);
			}
		}
		return out;
	}
}
