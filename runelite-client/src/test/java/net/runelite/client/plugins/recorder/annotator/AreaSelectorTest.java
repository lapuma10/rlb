package net.runelite.client.plugins.recorder.annotator;

import java.util.HashSet;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class AreaSelectorTest
{
	@Test
	public void rectangleBetweenTwoTilesIncludesEveryEnclosedTile()
	{
		Set<WorldPoint> rect = AreaSelector.tilesInRect(
			new WorldPoint(3, 5, 0),
			new WorldPoint(5, 7, 0));
		assertEquals(9, rect.size());
		assertTrue(rect.contains(new WorldPoint(3, 5, 0)));
		assertTrue(rect.contains(new WorldPoint(4, 6, 0)));
		assertTrue(rect.contains(new WorldPoint(5, 7, 0)));
	}

	@Test
	public void rectangleNormalisesCornerOrder()
	{
		Set<WorldPoint> a = AreaSelector.tilesInRect(
			new WorldPoint(5, 7, 0), new WorldPoint(3, 5, 0));
		Set<WorldPoint> b = AreaSelector.tilesInRect(
			new WorldPoint(3, 5, 0), new WorldPoint(5, 7, 0));
		assertEquals(a, b);
	}

	@Test
	public void rectangleWithSameCornerIsSingleTile()
	{
		WorldPoint p = new WorldPoint(3, 5, 2);
		Set<WorldPoint> single = AreaSelector.tilesInRect(p, p);
		assertEquals(Set.of(p), single);
	}

	@Test
	public void rectangleAcrossPlanesUsesTheSecondTilesPlane()
	{
		Set<WorldPoint> rect = AreaSelector.tilesInRect(
			new WorldPoint(3, 5, 0),
			new WorldPoint(4, 6, 1));
		assertEquals(4, rect.size());
		for (WorldPoint p : rect) assertEquals(1, p.getPlane());
	}

	@Test
	public void applyAddCombinesSets()
	{
		Set<WorldPoint> base = new HashSet<>(Set.of(new WorldPoint(0, 0, 0)));
		Set<WorldPoint> add = Set.of(new WorldPoint(1, 0, 0), new WorldPoint(2, 0, 0));
		Set<WorldPoint> result = AreaSelector.applyAdd(base, add);
		assertEquals(3, result.size());
		assertTrue(result.contains(new WorldPoint(2, 0, 0)));
	}

	@Test
	public void applySubtractRemovesTiles()
	{
		Set<WorldPoint> base = new HashSet<>(Set.of(
			new WorldPoint(0, 0, 0),
			new WorldPoint(1, 0, 0),
			new WorldPoint(2, 0, 0)));
		Set<WorldPoint> sub = Set.of(new WorldPoint(1, 0, 0));
		Set<WorldPoint> result = AreaSelector.applySubtract(base, sub);
		assertEquals(2, result.size());
		assertFalse(result.contains(new WorldPoint(1, 0, 0)));
	}

	@Test
	public void applyToggleAddsAbsentTile()
	{
		Set<WorldPoint> base = new HashSet<>(Set.of(new WorldPoint(0, 0, 0)));
		Set<WorldPoint> result = AreaSelector.applyToggle(base, new WorldPoint(1, 0, 0));
		assertEquals(2, result.size());
		assertTrue(result.contains(new WorldPoint(1, 0, 0)));
	}

	@Test
	public void applyToggleRemovesPresentTile()
	{
		Set<WorldPoint> base = new HashSet<>(Set.of(
			new WorldPoint(0, 0, 0), new WorldPoint(1, 0, 0)));
		Set<WorldPoint> result = AreaSelector.applyToggle(base, new WorldPoint(1, 0, 0));
		assertEquals(1, result.size());
		assertFalse(result.contains(new WorldPoint(1, 0, 0)));
	}
}
