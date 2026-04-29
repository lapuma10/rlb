package net.runelite.client.sequence.affordance;

/**
 * Categorises the class of action a step or dispatcher intends to perform.
 * Used by {@link Affordance} and {@link AffordanceReport} to gate actions
 * against the current world-interaction mode.
 */
public enum ActionKind {
    WALK,
    INTERACT_WORLD,
    INTERACT_INVENTORY,
    OPEN_BANK_BOOTH,
    USE_BANK_WIDGET,
    USE_INVENTORY_ON_OBJECT,
    USE_INVENTORY_ON_INVENTORY,
    CLOSE_BLOCKING_INTERFACE,
    DISMISS_DIALOG
}
