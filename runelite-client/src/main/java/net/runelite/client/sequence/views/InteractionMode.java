package net.runelite.client.sequence.views;

/**
 * High-level interaction mode the player is currently in. Steps consult
 * this to decide whether a world click is even possible.
 */
public enum InteractionMode {
    /** Free to walk, click world objects, use inventory on world. */
    WORLD,
    /** Bank widget is open. */
    BANKING,
    /** Grand Exchange offers / offer-setup / collect interface is open. */
    GRAND_EXCHANGE,
    /** A modal dialog (chat, level-up, world-hop nag) is up. */
    DIALOG,
    /** Shop interface is open. */
    SHOP,
    /** Right-click menu is currently expanded. */
    MENU_OPEN,
    /** Game state is not LOGGED_IN (loading, login screen, hop). */
    LOADING,
    /** Some interface allows inventory clicks but blocks world clicks. */
    INVENTORY_ONLY,
    /** Cannot determine — observer hasn't classified this state. */
    UNKNOWN
}
