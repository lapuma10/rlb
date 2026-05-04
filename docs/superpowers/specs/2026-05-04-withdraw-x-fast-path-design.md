# Withdraw-X menu-aware fast path — design

## Context

`BankInteraction.tryWithdrawX(itemId, qty)` always opens the chatbox
quantity prompt, types the digits one-by-one with humanized cadence
(180–380 ms base + 20 % chance of a 350–1100 ms thinking pause per
char), and presses Enter. For a 5-digit qty (e.g. `50432`) that's
~1.5–4 s of typing per call, repeated every cycle.

Two structural inefficiencies:

1. **The menu already shows what we need.** The right-click menu on a
   bank slot includes a `Withdraw-N` entry for each fixed verb (1, 5,
   10) AND a dynamic `Withdraw-Y` where Y is the last value anyone
   typed at the bank. The cache persists across slots and across
   bank-close/reopen. So if our requested qty matches the slot's
   cached Y (or one of the fixed verbs), we can pick the menu entry
   directly — no chatbox.
2. **OSRS parses `Nk`/`Nm` shorthand.** `"51k"` is 3 chars vs
   `"51000"` is 5; `"1m"` is 2 chars vs `"1000000"` is 7. We currently
   always type the literal `Integer.toString(qty)`.

## Goal

Cut Withdraw-X latency without changing caller semantics. Three
layered changes — each independently useful, no regression on the
fallback path.

## Non-goals

- No `Withdraw-All` shortcut. Callers already partition stack-vs-qty
  and route All-sized withdraws to `tryWithdrawAll` (PieDishScript:915,
  940 — `bankAmt <= need ? tryWithdrawAll : tryWithdrawX`). Adding it
  to `tryWithdrawX`'s candidate list would be unreachable code.
- No `Withdraw-All-but-1` handling — low value, edge case.
- No left-click default-verb shortcut — rare hit, complicates flow.
- No state caching inside `BankInteraction` — the engine's menu IS
  the source of truth for the cached Y.

## Phase 1 — verb-scan (universal, no caller changes)

**New on `HumanizedInputDispatcher`:**

```java
/** Right-click at (x,y), pick the first menu entry whose verb matches
 *  ANY candidate in priority order.  Returns the matched verb on
 *  success, or null if none matched (and dismisses the menu). */
public String rightClickAndPickFirstMatching(int x, int y, List<String> verbs)
    throws InterruptedException;
```

Built on the existing `findMenuRow(Predicate<MenuEntry>)`
(HumanizedInputDispatcher.java:1160) — predicate matches any candidate
verb via `VerbMatcher.matches`. Mirrors `rightClickAndPickMenu`
(line 1968) including the `isTopMenuVerb` left-click shortcut.

**New on `BankInteraction`:**

```java
private boolean withdrawWithFirstMatching(int itemId, List<String> verbs)
    throws InterruptedException;
```

Mirror of `withdrawWithVerb` (line 1376) — same scroll-and-find loop,
just dispatches `rightClickAndPickFirstMatching` instead.

**Modified `withdrawXClickChain` (line 1028):**

```java
// Phase 1: try the cached "Withdraw-Y" verb first — one click, no
// chatbox.  Withdraw-All is intentionally NOT in the candidate list
// (see Non-goals).  Single-element candidate keeps the menu scan O(N
// menu entries).
if (withdrawWithFirstMatching(itemId, java.util.List.of("Withdraw-" + qty)))
    return true;

// Fallback: existing chatbox path.  Typed string goes through
// formatChatboxQty (Phase 2a) so clean multiples become "Nk"/"Nm".
if (!withdrawWithVerb(itemId, "Withdraw-X")) return false;
return dispatcher.typeChatboxAndEnter(formatChatboxQty(qty), 3500L);
```

Verb format confirmed by user-supplied screenshot of a Pot of flour
slot menu (cached Y = 9):

```
Withdraw-1 Pot of flour
Withdraw-5 Pot of flour
Withdraw-10 Pot of flour
Withdraw-9 Pot of flour          ← cached Y, full digits
Withdraw-X Pot of flour
Withdraw-All Pot of flour
Withdraw-All-but-1 Pot of flour
```

OSRS parses chatbox input to integer before caching, so typing
`"51k"` produces a cached Y of `51000` (not the literal `"51K"`) —
which means our `"Withdraw-" + qty` candidate matches regardless of
whether the typed input was `"51000"` or `"51k"`. The Phase 2b round-
up + Phase 1 verb-scan compose as designed.

`MenuEntry.getOption()` returns just the verb (`"Withdraw-9"`); the
`" Pot of flour"` suffix is the entry's `target` and is rendered
separately. `VerbMatcher.matches("Withdraw-9", "Withdraw-9")` is
normalized-exact and matches cleanly.

Same menu structure for noted vs unnoted withdraws (varbit
`BANK_WITHDRAWNOTES` only changes what arrives in inventory, not the
menu entries), so `tryWithdrawAsNoteX` benefits from Phase 1 too.

## Phase 2a — format-on-type in `withdrawXClickChain` (universal)

**New static helper on `BankInteraction`:**

```java
/** Format qty for the bank/GE chatbox. OSRS parses "Nk"/"Nm" as
 *  N×1000 / N×1_000_000 so clean multiples save typing without
 *  overshooting:
 *    51000   → "51k"
 *    1000000 → "1m"
 *    50432   → "50432" (no clean factor — plain digits)
 *  Pure formatter, no rounding. */
static String formatChatboxQty(int qty);
```

Applied wherever the chatbox typing is dispatched — `withdrawXClickChain`
plus the noted-X variant if it has its own typing call. Universal
benefit for any caller that happens to pass a clean multiple.

## Phase 2b — round-up helper for coin sites (caller opt-in)

**New static helper on `BankInteraction`:**

```java
/** Round qty UP to the smallest 1000-boundary so formatChatboxQty
 *  collapses it to "Nk".  Returns qty unchanged for qty < 10_000 —
 *  overshoot ratio isn't worth it.
 *
 *  <p>Caller is responsible for using this only where a small
 *  overshoot is harmless (coins, not item batches). */
public static int roundUpForFastTyping(int qty);
```

Examples: `50432 → 51000`, `1002213 → 1003000`, `447 → 447`,
`9999 → 9999` (under threshold).

**One caller change** — `PieDishScript.tickCheckBank` line 417:

```java
ok = bank.tryWithdrawX(COINS,
    BankInteraction.roundUpForFastTyping(plannedBuyCost));
```

The other coin/item callers stay exact-qty:
- `PieDishScript.tickCraftBanking` (lines 929, 954) pulls items —
  overshoot would withdraw more than the batch needs.
- `PieDishScript.tickSellBanking` (line 1093) is `tryWithdrawAsNoteX`
  with the precise sell stack count.
- `WithdrawItemStep` (sequence engine) takes a typed `WithdrawQuantity`
  spec from its caller — opt-in there too if needed.

## How the phases compose (worked example)

`PieDishScript.tickCheckBank` wants `50432` coins withdrawn:

1. **Caller** wraps qty: `roundUpForFastTyping(50432) = 51000`.
2. **First call** to `withdrawXClickChain(COINS, 51000)`:
   - Phase 1 candidate: `["Withdraw-51000"]`. The slot's cached Y is
     whatever was typed last (e.g. nothing, or a different number).
     Miss. Fall through.
   - Chatbox path: `formatChatboxQty(51000) = "51k"` — type 3 chars
     instead of 5.
   - After this call, the engine's cached Y for the bank session is
     now `51000`.
3. **Subsequent call** with the same `qty=51000`:
   - Phase 1 candidate: `["Withdraw-51000"]`. The menu now contains
     this entry. Hit. One right-click → one verb pick. No chatbox.

For `PieDishScript.tickCraftBanking` pulling 9 jugs of water per
batch:
1. First batch: Phase 1 misses (cached Y is something else), chatbox
   types `"9"`.
2. Second batch onward: menu now has `Withdraw-9`, Phase 1 hits.

## Threading

All three changes preserve the existing thread contract:

- `tryWithdrawX` is a worker-thread primitive (asserts in
  `assertWorkerThread` at line 1018).
- `withdrawWithFirstMatching` runs the scroll-and-find loop on the
  worker, marshalling slot reads via `onClient` like `withdrawWithVerb`.
- `dispatcher.rightClickAndPickFirstMatching` runs on the worker —
  same threading as `rightClickAndPickMenu` it's modelled on.
- `formatChatboxQty` and `roundUpForFastTyping` are pure functions —
  thread-agnostic.

No new thread-marshalling needed.

## Tests

- **`BankInteractionFormatTest.formatChatboxQtyTable`** — table-driven:
  clean 1k boundary, clean 10k, clean 1m, plain digits below 1k, just-
  off-clean, edge cases (`1000` → `"1k"`, `999` → `"999"`, `1` → `"1"`,
  `0` → `"0"`).
- **`BankInteractionFormatTest.roundUpForFastTypingTable`** — table-
  driven: under-threshold passthrough (`9999` → `9999`), at-threshold
  (`10000` → `10000`), just-over (`10001` → `11000`), large
  (`1002213` → `1003000`), already-clean (`51000` → `51000`).
- **`BankInteractionWithdrawXTest.verbScanHits_noChatbox`** — Mockito-
  stubbed menu with `Withdraw-9` entry, qty=9 → verifies no
  `typeChatboxAndEnter` dispatch.
- **`BankInteractionWithdrawXTest.verbScanMisses_fallsBackToChatbox`** —
  menu without matching verb → verifies fallback to chatbox path
  with `formatChatboxQty`-formatted qty.
- **`HumanizedInputDispatcherMultiVerbTest`** — first-match semantics
  in priority order, dismiss-on-no-match, top-menu left-click
  shortcut.

## What we are NOT doing

(See Non-goals.)
