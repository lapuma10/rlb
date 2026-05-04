# GE close-after-batch — design

## Context

When `PieDishScript` finishes a batch of GE buys it transitions
`BUYING_SUPPLIES → CHECKING_BANK` and immediately tries to right-click a
banker. The banker right-click menu doesn't contain `Bank` because the
GE main widget (`GeOffers.UNIVERSE`) is still rendered — the widget
overlay occludes the world click. Symptom logged 2026-05-04 20:23 →
20:31: 8.5 minutes of `right-click menu did not contain 'Bank' for npc
2466` before the script finally aborted into IDLE.

Root cause: `GrandExchangeSequenceFactory.buyCore` / `sellCore` end with
`CollectOfferStep`. Nothing in the engine or the script closes the GE
between the last collect and the next world interaction.

`CooksAssistantScript.tickBuyAtGe` has the same shape (after buying all
ingredients it transitions to `DEPOSIT_CASH_AT_GE` which opens a bank
booth at the GE) and is exposed to the same bug.

## Goal

A caller-controlled, verified primitive on the GE script that closes
the GE main interface and confirms it's gone before returning, so any
subsequent world-interaction (banker click, NPC talk) starts from a
clean state.

The close stays *caller-driven* (not auto-appended to `buyCore` /
`sellCore`) so back-to-back GE operations don't pay a wasteful
close-then-reopen.

## Non-goals

- Not modifying `buyCore` / `sellCore`.
- Not adding a generic "ensure no blocking widget" guard inside
  `BankInteraction`. Narrower fix, easier to reason about.
- Not introducing a multi-tap ESC retry (KISS — one ESC + verified
  poll. If the chatbox-overlay edge case turns out to be common in
  practice, layer it on later.)
- Not changing `GeInteraction.closeGrandExchange()` — the new
  primitive delegates to it.

## Public API (new)

In `GrandExchangeScript`:

```java
/** True iff the GE main widget (GeOffers.UNIVERSE) is currently
 *  rendered. Safe to call from any thread — marshals the widget read
 *  to the client thread. */
public boolean isGrandExchangeOpen();

/** Verified close. Returns true if the GE was already closed OR
 *  closes within {@code 1500 ms}. Returns false if the widget is
 *  still visible after the poll deadline; caller is responsible for
 *  retrying on false. Safe to call from any worker thread; throws if
 *  interrupted. */
public boolean tryCloseGrandExchange() throws InterruptedException;
```

The 1500 ms default mirrors `BankInteraction.tryCloseBank`.

## Implementation sketch

`isGrandExchangeOpen()`:
- Marshal a one-shot read to the client thread:
  `Widget w = client.getWidget(InterfaceID.GeOffers.UNIVERSE);
   return w != null && !w.isHidden();`
- `InterfaceID.GeOffers.UNIVERSE` is the same constant `GE_ROOTS`
  already references in `GrandExchangeSequenceFactory` — no new
  widget ID.

`tryCloseGrandExchange()`:
1. If `!isGrandExchangeOpen()` → return `true` immediately.
2. Call `geActions.closeGrandExchange()` (existing — single
   `dispatcher.tapKey(VK_ESCAPE)`).
3. Poll `isGrandExchangeOpen()` every ~80 ms via
   `SequenceSleep.sleep(client, 80)` until it returns `false` or
   1500 ms have elapsed.
4. Return final result.

## Caller changes

**`PieDishScript.tickBuySupplies`** (around line 577–581) — gate the
state transition on the close:

```java
if (dishComplete && flourComplete && waterComplete) {
    if (!geScript.tryCloseGrandExchange()) {
        log.warn("pie-dish buying: GE failed to close, retrying next tick");
        return;  // stay in BUYING_SUPPLIES
    }
    log.info("pie-dish buying: all planned buys submitted — re-checking bank");
    setState(State.CHECKING_BANK);
    return;
}
```

**`PieDishScript.tickSellPieShells`** (around line 1017) — same wrap
before `setState(State.CHECKING_BANK)` in the "GE sell complete"
branch.

**`CooksAssistantScript.tickBuyAtGe`** (around line 727) — same wrap
before `setState(State.DEPOSIT_CASH_AT_GE)` in the "GE supplied
everything" branch.

## Edge cases

- **GE already closed** (server-side timeout, prior step closed it):
  step 1 short-circuits to `true`, no ESC dispatched.
- **Chatbox prompt overlay still up**: single ESC closes the chatbox
  first, GE stays open, poll times out, returns `false`. Caller
  retries next tick → next ESC closes GE. No multi-tap loop.
- **No abort threshold in this iteration**: callers already have their
  own failure-counter pattern (`MAX_BANK_FAILURES`). If GE-close
  failures pile up, the downstream bank flow will fail-fast on its
  own.

## Test

Add a unit test alongside existing GE tests:

1. Stub `Widget.isHidden()` for `GeOffers.UNIVERSE` to return `true`
   from the start. `tryCloseGrandExchange()` returns `true` without
   dispatching ESC.
2. Stub returns `false`, then `true` after one poll cycle.
   `tryCloseGrandExchange()` dispatches ESC once and returns `true`.
3. Stub returns `false` indefinitely. `tryCloseGrandExchange()`
   dispatches ESC and returns `false` after the 1500 ms deadline.

## Threading

`tryCloseGrandExchange()` is called from worker threads (the
`pie-dish-maker` daemon, `cooks-assistant` daemon). The function
internally:
- marshals widget reads to the client thread,
- dispatches the ESC tap through the existing dispatcher (worker-safe),
- sleeps via `SequenceSleep.sleep(client, ...)` (worker-safe; throws on
  client thread).

Per CLAUDE.md threading rules: this is the "multi-step blocking flow
with internal waits" shape — must run on a worker, never on the
client thread. The signature throwing `InterruptedException` makes the
worker-thread requirement explicit at the type level.
