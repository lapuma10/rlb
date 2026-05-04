# Menu dismissal policy

## TL;DR

Use `HumanizedInputDispatcher.dismissMenu()` to close any open right-click
context menu. Don't tap `VK_ESCAPE` directly.

## Why

A stuck right-click menu blocks every OSRS game thread (cs2, NPC ticks,
queued click effects) until dismissed — see `CLAUDE.md` §8. So menu-miss
recovery paths *must* dismiss.

`VK_ESCAPE` is overloaded: when no menu is on top it closes the **topmost
interface** (Grand Exchange, bank, shop, prayer book, …). On a verb-miss
recovery in a GE flow, ESC closed the GE out from under the next step —
cascading retry pain.

`dismissMenu()` first moves the cursor a few dozen pixels outside the
menu's bounds (the engine auto-dismisses on cursor-out — no keystroke,
no side effects). It only falls back to `VK_ESCAPE` if move-away fails
twice — rare, and strictly preferable to a frozen client.

## Where it's used

Every menu-miss recovery site in the dispatcher:
`npcClick`, `gameObjectClick` (via `tryGameObjectAttempt`),
`groundItemClick`, `invSlotClick`, `boundsClick`,
`boundsClickVerifiedAction`, `widgetVerbClick`,
`invSlotClickVerifiedOnWorker`, `rightClickAndPickMenu`.

External callers: `CookingInteraction.lightFire`, `PieDishScript`
craft-retry recovery.

## Exemptions (intentional ESC)

- `GeInteraction.closeGrandExchange()` — closing the GE *is* the goal.
- `dismissMenu()`'s own ESC fallback — last resort.
- `BankInteraction` / `LumbridgeBankPenScript` close-bank fallbacks —
  closing the bank is the goal.

## Cancelling use-mode is different

Use-mode (selected inventory item / spell target) is engine state, not
a context menu. ESC just closes the inventory tab without clearing the
selection. Two correct paths:

- `cancelUseModeIfActive()` — humanized re-click on the source widget
  (visible to anti-cheat heuristics).
- `clearSelectedWidgetTargetMode()` — fast `client.setWidgetSelected(false)`
  for tight recovery loops.

## Verb-routed widget clicks (GE)

`clickWidgetVerb(widgetId, verb)` adds a hover-menu pre-check before
clicking; on verb mismatch it right-clicks and selects the verb. With
`dismissMenu()` in place its failure path is now safe in interface flows
— previously the ESC fallback closed the GE.

GE switched the documented-verb sites:
- `collectAll` → `"Collect to inventory"`
- `openOfferDetail` → `"View offer"`
- per-slot collect children → runtime `actions[0]`

Left as plain `widgetClick` (no observed silent-miss + verb undocumented):
- `confirmOffer` (`SETUP_CONFIRM`)
- `dismissPriceWarning` (popup Yes/No)

To migrate either: capture `actions[0]` with click-inspector first;
`VerbMatcher.matches` uses strict normalized equality, so a guessed
verb that doesn't match exactly will fail every click.
