# PizzaScript — audit follow-ups

Punch list for the next session touching `PizzaScript.java`. Line numbers
verified against current head; re-grep before editing if the file has moved.

---

## 1. `COOK_PACE_MS` is a flat 1500ms constant

- **Where:** const at `PizzaScript.java:168`; gate at `PizzaScript.java:1093`.
- **Issue:** flat 1500ms between cook-batch dispatch attempts mirrors the
  fingerprint we just removed from bank pacing.
- **Fix:** delete `COOK_PACE_MS`, add `scheduleNextCookClick(min, max)` that
  sets a `nextCookClickAtMs` deadline (random 500–1300ms), gate at line 1093
  on `now < nextCookClickAtMs` and call the scheduler at line 1161 instead of
  stamping `lastCookActionMs`. Mirrors `scheduleInBankAction` /
  `nextBankClickAtMs` shape (PizzaScript.java:1241, 250).
- **Effort:** small.

## 2. Constant 350ms gap between Use-source and click-target during craft

- **Where:** `SequenceSleep.sleep(client, 350L)` at `PizzaScript.java:671`.
- **Issue:** fixed inter-click delay is fingerprintable.
- **Fix:** replace with `SequenceSleep.sleep(client, ThreadLocalRandom.current().nextLong(250L, 451L))`.
- **Effort:** trivial.

## 3. Constant 400ms gap between Use-raw and click-Range during cook

- **Where:** `SequenceSleep.sleep(client, 400L)` at `PizzaScript.java:1119`
  (inside the `runExclusive` cook chain).
- **Issue:** same flat-delay fingerprint.
- **Fix:** replace with random 250–500ms via `ThreadLocalRandom`.
- **Effort:** trivial.

## 4. `abortWith()` does not close the bank

- **Where:** definition at `PizzaScript.java:1382`.
- **Issue:** several call sites abort while the bank widget is open and never
  close it — a stale bank UI swallows the next click attempt and is also a
  visible "frozen" tell to anyone watching. Leaking sites (verified — no
  `bank.tryCloseBank()` on path):
  - line 464  (bank-PIN abort in `tickDecide`, bank just opened)
  - line 527  (deposit-all retry exhaustion in DECIDE)
  - line 574  (withdraw uncooked retry exhaustion in DECIDE)
  - line 785  (bank-PIN abort in `tickCombineBanking`)
  - line 846  (combine deposit-all retry exhaustion)
  - line 894  (combine withdraw use-item exhaustion)
  - line 914  (combine withdraw target-item exhaustion)
  Already-clean sites (510, 769, 823 close before aborting; 490, 804, 960,
  985, 1080 never opened the bank).
- **Fix:** prepend `if (bank.isBankOpen()) bank.tryCloseBank();` inside
  `abortWith` at line 1383 — defensive, idempotent, no behaviour change for
  the already-clean sites.
- **Effort:** trivial.

## 5. `start()` / `stop()` do not preserve the active sidebar tab

- **Where:** `start()` at `PizzaScript.java:300`, `stop()` at `:335`. Tab
  forced to INVENTORY at `:400`.
- **Issue:** `start()` opens the inventory tab unconditionally; `stop()`
  never restores. After a stop the user is left on a tab they didn't pick.
- **Fix:** snapshot `client.getVarcIntValue(VarClientID.TOPLEVEL_PANEL)`
  (varc int 171) at the top of `start()`, store on a `previousSidebarPanel`
  field, restore via `sidebarTabs.openTabAndWait(...)` at the end of
  `stop()` only if the value differs.
  - **Pitfall:** do NOT use `Varbits.SIDE_PANELS` (4607) — wrong varbit;
    documented in the user's memory file `feedback_active_sidebar_tab_varc.md`.
    `STONE#` ordinal matches `SidebarTab` ordinal (`STONE3 = INVENTORY = 3`).
- **Effort:** small.

## 6. No idle humanization while a cook batch renders

- **Where:** `cook.isCooking()` true branch at `PizzaScript.java:1062`;
  also the `COOK_BATCH_SETTLE_MS` window at `:1068`.
- **Issue:** the bot freezes 5–30s per batch (28 raw cooks back-to-back).
  Static cursor + zero camera movement for that long is the loudest tell
  in the script.
- **Search result:** no existing idle-humanizer / camera-jitter / scroll-
  cadence module exists in `runelite-client/src/main/java/net/runelite/client/sequence`
  or `recorder/`. The only humanization lives in
  `dispatch/HumanizedInputDispatcher.java` (per-click cursor pathing) and
  `login/HumanizedTyping.java` (typed-key cadence). User memory file
  `feedback_bot_humanization.md` references rules (camera force-rotate,
  scroll cadence, target jitter) but no shared helper has been built yet.
- **Fix:** needs design pass — build a reusable `IdleHumanizer` in
  `sequence/dispatch/` covering (a) probabilistic camera nudge via
  middle-mouse-drag on canvas, (b) light scroll cadence on inventory/skills
  panel, (c) tiny cursor jitter within a few px. Then call it from the cook
  idle branches above. Don't inline ad-hoc logic in PizzaScript.
- **Effort:** medium.

## 7. No long AFK breaks across multi-hour sessions

- **Where:** script-wide; safest insertion points are after `craftBankDone`
  reset at `PizzaScript.java:747` (post-batch) and after the cook-batch
  finishes around `:1068`.
- **Issue:** continuous click activity for hours straight is a stronger
  signal than any per-click timing tweak. No human cooks 600+ pizzas without
  pausing.
- **Fix:** needs design pass. Sketch: a `BreakScheduler` that picks a random
  next-break-deadline every 25–60 min runtime, an `AFK_PAUSE` pseudo-state
  the FSM can enter only at safe gates (after a cook batch settles, after
  bank close — never mid-bank, never mid-craft, never mid-walk), and a
  randomized 60–600s sleep with light camera nudges from item 6's helper.
  Track in panel UI so user sees "next break in 14m / on AFK 3m12s left".
- **Effort:** large.

---

## Already done in this session

- **Bank-pacing humanization.** Replaced flat `BANK_PACE_MS = 1500L`
  constant with a three-window scheduler:
  - pre-open: random 800–2400ms once per bank trip
    (`schedulePreOpen`, line 1248)
  - in-bank: random 220–520ms per click action
    (`scheduleInBankAction`, line 1241)
  - post-close: random 200–380ms before the next non-bank step
- Renamed `lastBankActionMs` → `nextBankClickAtMs` (line 250) with deadline
  semantics — gates now read `now < nextBankClickAtMs`, not
  `now - lastBankActionMs < pace`. Sentinel `0L` triggers `schedulePreOpen`
  on the next bank touch (see `setState`, line 1358).
