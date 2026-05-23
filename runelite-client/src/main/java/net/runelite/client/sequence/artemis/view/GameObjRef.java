package net.runelite.client.sequence.artemis.view;

import net.runelite.api.coords.WorldPoint;

/**
 * Immutable view of an in-world object at the moment {@code findObject}
 * returned. Spec §8. {@link #kind} disambiguates which RuneLite-API
 * type ({@code GameObject} / {@code WallObject} / {@code DecorativeObject}
 * / {@code GroundObject}) the engine should re-resolve against.
 */
public record GameObjRef(
	int id,
	String name,
	WorldPoint originalLoc,
	ObjectKind kind,
	long observedTick
)
{
}
