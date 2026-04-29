package net.runelite.client.sequence.views;

/**
 * Side of a Grand Exchange offer. {@link #NONE} is reserved for empty slots
 * (no offer present); {@link #BUY} and {@link #SELL} match the player-facing
 * buy / sell tabs at the GE clerk.
 */
public enum OfferSide {
    BUY,
    SELL,
    NONE
}
