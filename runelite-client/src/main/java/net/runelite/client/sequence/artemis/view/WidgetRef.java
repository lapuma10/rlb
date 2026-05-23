package net.runelite.client.sequence.artemis.view;

/**
 * Immutable view of a Widget at the moment {@code findWidget} returned.
 * Spec §8. {@link #childSlot} is {@code null} for leaf widgets and set
 * for child-by-slot references inside container widgets (e.g.
 * inventory slot 0..27 inside {@code InterfaceID.Inventory.ITEMS}).
 * {@link #itemId} is {@code -1} when not an item-bearing widget.
 */
public record WidgetRef(
	int widgetId,
	Integer childSlot,
	int itemId,
	long observedTick
)
{
	/** Sentinel for non-item-bearing widgets. */
	public static final int NO_ITEM = -1;
}
