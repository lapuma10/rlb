package net.runelite.client.sequence.artemis.query;

import java.util.List;
import java.util.Random;
import java.util.function.ToIntFunction;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins {@link RotationPolicySelector} behaviour for the four v1
 * policies. Seeded {@link Random}(42) for deterministic tests.
 */
public class RotationPolicySelectorTest
{
	/** Just label a candidate with its distance — the test pre-supplies
	 *  the distance via the distance function, no spatial logic here. */
	private record Cand(String tag, int dist) {}

	private static final ToIntFunction<Cand> DIST = Cand::dist;

	private RotationPolicySelector newSelector()
	{
		return new RotationPolicySelector(new Random(42));
	}

	// ─── edge cases ────────────────────────────────────────────────

	@Test
	public void emptyListReturnsNull()
	{
		assertNull(newSelector().pick(List.of(), DIST, new RotationPolicy.Closest()));
	}

	@Test
	public void nullListReturnsNull()
	{
		assertNull(newSelector().pick(null, DIST, new RotationPolicy.Closest()));
	}

	@Test
	public void singleCandidateAlwaysWins()
	{
		Cand only = new Cand("only", 99);
		// All four policies must agree on a single-candidate list.
		assertEquals(only, newSelector().pick(List.of(only), DIST, new RotationPolicy.Closest()));
		assertEquals(only, newSelector().pick(List.of(only), DIST, new RotationPolicy.ClosestWithSlack(5)));
		assertEquals(only, newSelector().pick(List.of(only), DIST, new RotationPolicy.UniformWithinRange()));
		assertEquals(only, newSelector().pick(List.of(only), DIST, new RotationPolicy.SessionSticky(1000, "k")));
	}

	// ─── Closest ───────────────────────────────────────────────────

	@Test
	public void closestPicksMinDistance()
	{
		Cand near = new Cand("near", 1);
		Cand mid  = new Cand("mid",  3);
		Cand far  = new Cand("far",  5);
		assertEquals(near, newSelector().pick(List.of(far, mid, near), DIST, new RotationPolicy.Closest()));
	}

	@Test
	public void closestRandomisesAmongTies()
	{
		// Three candidates all at distance 2 — over many seeds, all three
		// should appear at least once. Tiebreak is RNG, never index.
		Cand a = new Cand("a", 2);
		Cand b = new Cand("b", 2);
		Cand c = new Cand("c", 2);
		List<Cand> cs = List.of(a, b, c);

		boolean sawA = false, sawB = false, sawC = false;
		for (int seed = 1; seed <= 50; seed++)
		{
			RotationPolicySelector s = new RotationPolicySelector(new Random(seed));
			Cand picked = s.pick(cs, DIST, new RotationPolicy.Closest());
			if (picked == a) sawA = true;
			else if (picked == b) sawB = true;
			else if (picked == c) sawC = true;
		}
		assertTrue("Closest tiebreak must hit candidate a across many seeds", sawA);
		assertTrue("Closest tiebreak must hit candidate b across many seeds", sawB);
		assertTrue("Closest tiebreak must hit candidate c across many seeds", sawC);
	}

	// ─── ClosestWithSlack ──────────────────────────────────────────

	@Test
	public void closestWithSlackZeroEqualsClosest()
	{
		Cand near = new Cand("near", 1);
		Cand far  = new Cand("far",  3);
		assertEquals(near, newSelector().pick(List.of(near, far), DIST,
			new RotationPolicy.ClosestWithSlack(0)));
	}

	@Test
	public void closestWithSlackPicksWithinBand()
	{
		// Closest = 2; slack = 2; so candidates at distance 2, 3, 4 are
		// eligible; the one at distance 5 is not.
		Cand a = new Cand("a", 2);
		Cand b = new Cand("b", 3);
		Cand c = new Cand("c", 4);
		Cand d = new Cand("d", 5);
		List<Cand> cs = List.of(a, b, c, d);

		boolean sawA = false, sawBorC = false, sawD = false;
		for (int seed = 1; seed <= 50; seed++)
		{
			RotationPolicySelector s = new RotationPolicySelector(new Random(seed));
			Cand picked = s.pick(cs, DIST, new RotationPolicy.ClosestWithSlack(2));
			if (picked == a) sawA = true;
			else if (picked == b || picked == c) sawBorC = true;
			else if (picked == d) sawD = true;
		}
		assertTrue("must sometimes pick the strict closest", sawA);
		assertTrue("must sometimes pick a within-slack candidate", sawBorC);
		assertTrue("must NEVER pick beyond slack distance", !sawD);
	}

	// ─── UniformWithinRange ────────────────────────────────────────

	@Test
	public void uniformIgnoresDistance()
	{
		// 5 candidates — over many seeds, each should appear at least
		// once. Distance values irrelevant to UniformWithinRange.
		Cand[] cands = {
			new Cand("a", 1), new Cand("b", 2), new Cand("c", 3),
			new Cand("d", 4), new Cand("e", 5)
		};
		List<Cand> cs = List.of(cands);

		boolean[] seen = new boolean[5];
		for (int seed = 1; seed <= 100; seed++)
		{
			RotationPolicySelector s = new RotationPolicySelector(new Random(seed));
			Cand picked = s.pick(cs, DIST, new RotationPolicy.UniformWithinRange());
			for (int i = 0; i < cands.length; i++)
			{
				if (picked == cands[i]) seen[i] = true;
			}
		}
		for (int i = 0; i < seen.length; i++)
		{
			assertTrue("UniformWithinRange must eventually pick candidate " + i, seen[i]);
		}
	}

	// ─── SessionSticky ─────────────────────────────────────────────

	@Test
	public void sessionStickyIsDeterministicForSameAccountAndKey()
	{
		List<Cand> cs = List.of(
			new Cand("a", 1), new Cand("b", 2), new Cand("c", 3));

		// Same selector seed (account) + same stickyKey + same candidate
		// list must produce same pick across repeated calls.
		RotationPolicySelector s1 = new RotationPolicySelector(new Random(42));
		RotationPolicySelector s2 = new RotationPolicySelector(new Random(42));
		Cand p1 = s1.pick(cs, DIST, new RotationPolicy.SessionSticky(1000, "key-A"));
		Cand p2 = s2.pick(cs, DIST, new RotationPolicy.SessionSticky(1000, "key-A"));
		assertEquals("same account-seed + same stickyKey must pick the same candidate", p1, p2);
	}

	@Test
	public void sessionStickyDiffersForDifferentKeys()
	{
		// Same account seed, different stickyKeys, same candidate list
		// — at least one pair must differ (otherwise the key has no
		// effect, which would be a bug).
		List<Cand> cs = List.of(
			new Cand("a", 1), new Cand("b", 2), new Cand("c", 3),
			new Cand("d", 4), new Cand("e", 5));
		boolean anyDiffer = false;
		for (int i = 0; i < 8; i++)
		{
			RotationPolicySelector s1 = new RotationPolicySelector(new Random(42));
			RotationPolicySelector s2 = new RotationPolicySelector(new Random(42));
			Cand p1 = s1.pick(cs, DIST, new RotationPolicy.SessionSticky(1000, "key-" + i));
			Cand p2 = s2.pick(cs, DIST, new RotationPolicy.SessionSticky(1000, "different-" + i));
			if (p1 != p2)
			{
				anyDiffer = true;
				break;
			}
		}
		assertTrue("different stickyKeys must influence the SessionSticky pick", anyDiffer);
	}

	// ─── per-account seed sanity ───────────────────────────────────

	@Test
	public void differentSeedsCanDifferentiateOtherwiseIdenticalCalls()
	{
		// Two selectors with different seeds, same candidates, same policy
		// (ClosestWithSlack with ties) — at least one of N attempts must
		// produce different picks, otherwise per-account variance is broken.
		Cand[] cands = new Cand[6];
		for (int i = 0; i < 6; i++) cands[i] = new Cand("c" + i, 1);   // all tied
		List<Cand> cs = List.of(cands);

		boolean anyDiffer = false;
		for (int seed = 1; seed <= 20; seed++)
		{
			RotationPolicySelector sA = new RotationPolicySelector(new Random(seed));
			RotationPolicySelector sB = new RotationPolicySelector(new Random(seed + 100_000));
			Cand pa = sA.pick(cs, DIST, new RotationPolicy.Closest());
			Cand pb = sB.pick(cs, DIST, new RotationPolicy.Closest());
			if (pa != pb)
			{
				anyDiffer = true;
				break;
			}
		}
		assertTrue("different per-account seeds must produce divergent picks among ties", anyDiffer);
	}

	@Test
	public void shapeAgnosticAcceptsAnyType()
	{
		// Type parameter T is meant to be opaque to the selector.
		// Use String candidates with explicit distance fn.
		List<String> strs = List.of("alpha", "beta", "gamma");
		ToIntFunction<String> df = String::length;   // alpha=5, beta=4, gamma=5
		String picked = newSelector().pick(strs, df, new RotationPolicy.Closest());
		assertNotNull(picked);
		assertEquals("beta", picked);   // length 4 is the unique min
	}
}
