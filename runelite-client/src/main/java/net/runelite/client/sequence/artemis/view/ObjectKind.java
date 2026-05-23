package net.runelite.client.sequence.artemis.view;

/**
 * Categorises the kind of in-world object a {@link GameObjRef} points
 * at, since the RuneLite API splits these into separate types.
 */
public enum ObjectKind
{
	GAME_OBJECT,
	WALL_OBJECT,
	DECORATIVE_OBJECT,
	GROUND_OBJECT
}
