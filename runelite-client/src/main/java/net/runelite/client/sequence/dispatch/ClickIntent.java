package net.runelite.client.sequence.dispatch;

/** Why a press is firing — drives which last-mile guards the
 *  {@code press(...)} primitive applies. See the spec at
 *  {@code docs/superpowers/specs/2026-05-22-chatbox-deadzone-click-guard.md}.
 *
 *  <p>The intent is the caller's responsibility — only the caller
 *  knows whether the cursor at click time is supposed to be on the
 *  world, on a UI widget, on a freshly-opened context menu, or on
 *  the minimap. The dispatcher cannot infer it from cursor
 *  coordinates alone (chatbox-bound pixels can be either "wrong
 *  world click" or "right chat tab click"). */
public enum ClickIntent
{
    /** World tile / NPC / object / ground item target. Cursor must
     *  not be sitting in a UI dead-zone (chatbox, side panels,
     *  orbs, minimap, etc.); the press primitive blocks otherwise. */
    WORLD,
    /** Minimap disc walk-fallback. Dead-zone bypass — minimap is
     *  itself a dead-zone for WORLD intent. Off-canvas check still
     *  applies. */
    MINIMAP,
    /** Widget / bounds / inventory slot. Legitimately clicks inside
     *  chatbox / side panels / orb cluster. Dead-zone bypass; off-
     *  canvas check still applies. */
    UI,
    /** Row click on a right-click context menu we just opened. The
     *  menu is drawn on top of the chatbox in the engine, so the
     *  cursor being inside chatbox bounds is fine — the row click
     *  resolves to the menu. Dead-zone bypass. */
    MENU_ROW,
    /** Low-level escape hatch: test hooks, raw {@code clickCanvas},
     *  manual diagnostics. No guards run. */
    RAW
}
