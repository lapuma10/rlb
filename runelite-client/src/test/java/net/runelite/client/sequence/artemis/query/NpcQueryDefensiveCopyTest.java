package net.runelite.client.sequence.artemis.query;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins {@link NpcQuery}'s compact-constructor defensive-copy contract
 * for {@code excludeIndices}: a caller handing in a mutable set
 * cannot mutate the record's view afterwards, and the record's
 * accessor returns an unmodifiable set.
 */
public class NpcQueryDefensiveCopyTest
{
	@Test
	public void canonicalConstructorCopiesExcludeIndices()
	{
		Set<Integer> mutable = new HashSet<>();
		mutable.add(1);
		mutable.add(2);

		NpcQuery q = new NpcQuery(
			"Cow", null, 14, NpcQuery.ANY_PLANE,
			mutable, false, new RotationPolicy.Closest());

		// Verify pre-mutation state.
		assertTrue(q.excludeIndices().contains(1));
		assertTrue(q.excludeIndices().contains(2));

		// Mutate the original Set after handoff.
		mutable.add(99);

		// The record's view must NOT see the post-handoff mutation.
		assertFalse("excludeIndices must be defensively copied on construction",
			q.excludeIndices().contains(99));
	}

	@Test(expected = UnsupportedOperationException.class)
	public void excludeIndicesAccessorReturnsUnmodifiableSet()
	{
		NpcQuery q = NpcQuery.byName("Cow").exclude(42);
		q.excludeIndices().add(100);   // expected to throw
	}

	@Test
	public void excludeBuilderReturnsNewQueryWithIndexAdded()
	{
		NpcQuery base = NpcQuery.byName("Cow");
		NpcQuery extended = base.exclude(42).exclude(99);

		assertFalse("base should not be mutated by .exclude(...)",
			base.excludeIndices().contains(42));
		assertTrue(extended.excludeIndices().contains(42));
		assertTrue(extended.excludeIndices().contains(99));
	}
}
