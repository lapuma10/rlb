package net.runelite.client.sequence.artemis.query;

/**
 * Selection query for {@link net.runelite.client.sequence.artemis.Artemis#findWidget}.
 * Spec §6. Widgets are addressed by {@code int widgetId} (from
 * {@code InterfaceID.*}); no {@link RotationPolicy} field because a
 * widget id resolves to one widget by construction. Multi-child
 * selection inside a container goes through {@link #child(int)} or, for
 * "any visible row of this list", a Step-level loop.
 *
 * <p>{@code requireVisible} walks {@code Widget.isHidden()} up the
 * parent chain (CLAUDE.md §1 ban on dispatching to hidden widgets);
 * {@code findWidget} returns {@code Optional.empty()} if any ancestor is
 * hidden.
 */
public record WidgetQuery(
	int widgetId,
	Integer childSlot,
	boolean requireVisible
)
{
	public static WidgetQuery byId(int widgetId)
	{
		return new WidgetQuery(widgetId, null, true);
	}

	public WidgetQuery child(int slotIdx)
	{
		return new WidgetQuery(widgetId, slotIdx, requireVisible);
	}

	public WidgetQuery visible()
	{
		return new WidgetQuery(widgetId, childSlot, true);
	}

	/** Opt out of the parent-chain visibility gate — rarely needed.
	 *  Use only when intentionally inspecting a hidden widget (e.g.
	 *  reading state of a closed dialog). Will not pass re-resolution
	 *  for click Steps. */
	public WidgetQuery anyVisibility()
	{
		return new WidgetQuery(widgetId, childSlot, false);
	}
}
