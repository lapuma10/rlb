package net.runelite.client.sequence.affordance;

/**
 * Snapshot of a blocking UI interface — e.g. a dialog, the bank widget, a right-click menu.
 * Used by {@link BlockReason.WorldInteractionBlocked} and
 * {@link net.runelite.client.sequence.views.InteractionView}.
 *
 * <p>{@code rootWidgetId} identifies the topmost widget that, when visible,
 * indicates this interface is up. Steps consult the id against allow-lists
 * (e.g., the bank itself is "blocking world clicks" but allowed during a
 * banking sequence).
 *
 * <p>{@code blocksWorld} is true when this interface intercepts world clicks
 * (most modals do); false for purely informational overlays.
 *
 * <p>{@code canBeClosed} is true when a generic dismiss action (Escape,
 * Space) can recover. False for fatal interfaces like the bank-pin keypad
 * that need a domain-specific resolution.
 */
public record BlockingInterface(
    String name,
    int rootWidgetId,
    boolean blocksWorld,
    boolean canBeClosed
) {}
