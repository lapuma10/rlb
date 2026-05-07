# Pizza AFK breaks — design (2026-05-07)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans
> when this is approved. Steps use checkbox (`- [ ]`) syntax for tracking.
> Per project memory: keep code work inline; subagents reserved for QC.
> Per project memory: don't paste full Java sources — describe behavior;
> inspect APIs before specifying signatures.

**Goal:** Insert randomized AFK breaks into the pizza-making loop so the
bot looks less like a 7-hours-without-pause click farm. Break the pattern
that contributed to the 2026-05-07 ban.

**Architecture:** A standalone `BreakScheduler` class — pure logic with an
injectable clock + RNG so it's unit-testable — that PizzaScript queries
at safe state-machine boundaries. Two tiers (micro / medium), both under
the OSRS 5-minute auto-kick threshold so v1 doesn't need logout/login
plumbing. Panel checkbox to toggle + countdown label so the user can
see what's about to happen.

**Tech stack:** Java 17, JUnit 4 (matching repo convention), no new
dependencies.

---

## Why this exists

`PizzaScript.AUDIT.md` item #6 is "no long AFK breaks." The ban incident
log (2026-05-06 → 2026-05-07) shows ~7h33m of botting in a 9.5h
wall-clock window with the longest continuous run at ~3h. Per-action
jitter (bank pacing, cook clicks, etc.) was added mid-session, but the
*macro* pattern of "always doing something" was never broken. That's the
single loudest tell once per-action timing is humanized — Jagex's
heuristics weight session-shape signals heavily, and a real player with
the bot's pizza throughput would take phone breaks.

`CookingScriptV3` already has a related primitive — a one-shot session
cap (`maxDurationMs`) that flips the script to `IDLE_AFK` or
`LOGGING_OUT` once exceeded. That's not what we want here: V3's logic
ends the session, this feature inserts mid-session pauses and resumes
the loop. Different shape. We won't build on V3's `IDLE_AFK` state
machine — it stops the worker thread. We need an in-loop sleep.

## Scope

**In:**
- A `BreakScheduler` that decides when a break is due, rolls a tier +
  duration, tracks "currently in break" / "time remaining," and exposes
  status for the panel.
- Integration into `PizzaScript` at one safe boundary: the start of
  `tickDecide`, before the bank booth click. Empty-inventory + at-bank
  is the natural "between trips" pause point.
- Panel toggle + countdown labels in the Pizza tab.
- Two tiers, both <5min: micro (30–90s, 70%) and medium (2.5–4.5min,
  30%). Activity period between breaks: 25–60min random.

**Out (deferred):**
- Long breaks that exceed the 5-min auto-kick threshold. Requires
  logout-and-resume infrastructure; V3 has `LogoutHelper` we could
  hook later but resume-from-logged-out for the pizza FSM is a
  separate spec.
- Adoption by `CookingScriptV3`, `ChickenFarmV3`, etc. The scheduler
  is reusable but each script has its own "safe boundary" — wire-up
  per script, separate plans.
- Mid-cook breaks (during the 5-min cook batch). Player standing at the
  range while pizzas auto-cook is already a believable AFK state — the
  cook-loop's existing wait-for-empty-inventory is the closest thing
  to a natural break and adding a forced sleep on top would just be
  redundant.
- Camera nudges / mouse moves during the break itself. v1 = pure
  sleep; the OSRS server doesn't care about camera, only player input,
  and the goal is "look AFK" so doing things during a break would
  defeat the purpose.

## Package

`net.runelite.client.plugins.recorder.afk` (new). Lives under the
recorder package because it's bot-side humanization, not engine
infrastructure.

## File map

**Create:**
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/afk/BreakScheduler.java`
  — the scheduler logic (~120 lines).
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/afk/BreakConfig.java`
  — tier + interval constants, exposed as a final class with public
  static fields so a future panel could let the user override them
  (not in v1).
- `runelite-client/src/test/java/net/runelite/client/plugins/recorder/afk/BreakSchedulerTest.java`
  — unit tests with injected clock + seeded RNG.

**Modify:**
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/PizzaScript.java`
  — instantiate the scheduler in `start()`, query it at top of
  `tickDecide`, expose `breakStatus()` for the panel.
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java`
  — add `pizzaBreaksBox` checkbox + `pizzaBreakLabel` countdown to the
  Pizza tab; refresh in `refreshPizza()`.

## Behavior spec

### Tiers + activity periods

| Constant | Range | Notes |
|---|---|---|
| `ACTIVITY_MIN_MS` | 25 min | Time after a break ends before next break is due. |
| `ACTIVITY_MAX_MS` | 60 min | Each activity period rolls fresh in `[MIN, MAX]`. |
| `MICRO_MIN_MS` | 30 s | "Phone glance." |
| `MICRO_MAX_MS` | 90 s | |
| `MICRO_PROB` | 70 (%) | |
| `MEDIUM_MIN_MS` | 150 s (2.5 min) | "Bathroom / drink." |
| `MEDIUM_MAX_MS` | 270 s (4.5 min) | Below 5-min auto-kick. |

`MICRO_PROB + MEDIUM_PROB == 100`. Medium prob is the complement.
Both tier durations stay ≤270s so we never hit the OSRS auto-kick at
~5min stationary. If the user wants longer breaks they Stop the bot
manually — that's a v2 feature.

### Scheduler state

The scheduler is a tiny FSM: `ACTIVE` → (at boundary, break due) →
`IN_BREAK` → (timer expired) → `ACTIVE` (rolls new activity period) →
… Initial state is `ACTIVE` with `activityEndMs` rolled from the
constants above; first break can fire after the first 25–60min.

Internal fields. All four mutable fields are declared `volatile` so the
EDT panel listener can call `disable()`/`enable()` and the worker
thread sees the update on its next tick without a lock. (Repo precedent
elsewhere is `AtomicBoolean`/`AtomicReference` for cross-thread state;
volatile is the lighter equivalent for primitive longs and a single
boolean — same happens-before guarantee, less ceremony.)

- `volatile long activityEndMs` — wall-clock at which the next break
  is allowed.
- `volatile long breakEndMs` — wall-clock at which the current break
  ends; 0 when not in break.
- `volatile Tier currentTier` — `null` when not in break, `MICRO` or
  `MEDIUM` otherwise; logged so the user can see in the panel "in
  MEDIUM break, 3m 12s remaining."
- `volatile boolean disabled` — set by EDT when the panel checkbox
  flips. Read by the worker on every `isBreakDue` call.

Clock source is `System.currentTimeMillis()` for production
(unit-test injects a `LongSupplier` so tests are deterministic). Note
that wall-clock can move backwards if the OS NTP-syncs mid-session;
the `breakEndMs > nowMs` checks would then re-trigger an "active"
break. Acceptable for v1 — NTP jumps in a typical session are
sub-second and the break would just be effectively extended by that
amount. If this ever becomes a real-world problem, switch to
`System.nanoTime()` deltas internally and only convert to wall-clock
for status display.

### Public surface

The scheduler exposes:

- `boolean isBreakDue(long nowMs, boolean atSafeBoundary)` — true when
  `!disabled` AND not currently in a break AND `atSafeBoundary` AND
  `nowMs >= activityEndMs`. PizzaScript calls this at the top of
  `tickLoop` with `atSafeBoundary = state.get() == State.DECIDE`; if
  true, it calls `startBreak`, sets a status string, and skips the rest
  of the tick.
- `void startBreak(long nowMs)` — picks a tier (weighted RNG roll),
  rolls a duration in the tier's range, sets `breakEndMs`. Logs at
  INFO: tier name, duration ms, end-time formatted.
- `boolean isInBreak(long nowMs)` — true when `breakEndMs > nowMs`.
  PizzaScript checks this on every tick; if true, just sets a status
  string and returns.
- `void endBreakIfDue(long nowMs)` — no-op unless `breakEndMs > 0 &&
  nowMs >= breakEndMs`. When the condition holds: rolls a fresh
  `activityEndMs`, clears `breakEndMs` and `currentTier`, logs at
  INFO. PizzaScript calls this each tick after the in-break and
  break-due checks; safe to call always.
- `String statusLine(long nowMs)` — formatted for the panel:
  "next break in 12m 04s" / "in MICRO break, 0m 47s remaining" /
  "breaks: off" (when disabled at the script level).
- `void disable()` / `void enable(long nowMs)` — script toggles. When
  disabled, `isBreakDue` always returns false; on re-enable, rolls a
  fresh `activityEndMs`.

### Safe boundary in PizzaScript

The break check sits at the *very top* of the worker thread's
`while (running.get())` body in `tickLoop`, **before**
`safeDismissLevelUp()` and before the state-switch dispatch.
Reasoning: `safeDismissLevelUp` will dispatch a click if a level-up
popup is up, and the state-switch routes into a tick handler that
may dispatch immediately. If we gate the break only inside
`tickDecide`, those two earlier sites still fire mid-break and
defeat AFK.

Concretely: if a break is active, `tickLoop` skips `safeDismissLevelUp`,
skips the state-switch, sleeps `TICK_MS`, and continues. A break can
only START at a safe boundary — the script script enters this gate
ONLY when `state == DECIDE`, because that's the only state where
`isBreakDue` is allowed to flip to "yes" (see public-surface change
below). DECIDE is reached after a bank-arrival or cook-batch-completion,
both of which are natural pauses — never mid-bank, never mid-cook,
never mid-walk. Cost: occasional drift (if the player is in
`WALK_TO_BANK` when a break would have fired, it's delayed until the
next DECIDE entry), but that's the right trade.

Public-surface delta from the original sketch: `isBreakDue(nowMs)`
takes a second parameter `boolean atSafeBoundary`. PizzaScript passes
`state.get() == State.DECIDE`. The scheduler returns false unless BOTH
the activity timer has expired AND the boundary flag is true. That
keeps the FSM-vs-scheduler coupling explicit at the call site.

### Logging

INFO level, `[pizza-script]` tag, two events per cycle:

- `pizza: AFK break starting — tier=MICRO duration=72s (cumulative
  activity 23m 41s)` — at `startBreak`.
- `pizza: AFK break over — back to work (next break in ~24m 12s)` —
  at `endBreakIfDue` when it actually ends a break.

That gives a future debugger a single grep (`AFK break`) to reconstruct
the session shape.

### Panel UX

In the Pizza tab below the loop checkboxes, ABOVE the Start/Stop
button row:

- `JCheckBox` labeled `AFK breaks (humanizer)` — default selected.
  Live-pushes to `pizzaScript.setAfkBreaksEnabled(...)`. When
  unchecked mid-run: scheduler's `disable()` is called; if currently
  in a break, the break is cut short on the next tick (safe — the
  scheduler clears state, script's status updates).
- `JLabel` labeled with the scheduler's `statusLine(...)`. Refreshed
  in `refreshPizza()` (which already runs on the EDT timer). When the
  scheduler is disabled, label reads `breaks: off`.

Layout: same `LEFT_ALIGNMENT` + `capHeight` treatment as the existing
Pizza-tab components.

## Open decisions — RESOLVED 2026-05-07

1. **Tier weights:** 70% micro / 30% medium — confirmed.
2. **Activity period:** 25–60 min between breaks (matches audit doc
   #6 guidance). Plan + constants table above already reflect this.
3. **Stop-button behavior:** scheduler state dies with the worker
   thread; next Start rolls a fresh activity period from scratch.
   Matches every other script in the repo. No persistence across
   Stop/Start.

Constants live in `BreakConfig` for one-line tuning if any of these
need to change.

## Tasks

### Task 1 — `BreakConfig` constants

**Files:**
- Create: `recorder/afk/BreakConfig.java`

- [ ] **Step 1: Create the file.** Final class, package-private
  constructor, public static `final long` fields for the 6 constants
  in the spec table above plus `MICRO_PROB` (int, 0..100). Include a
  one-line javadoc on each that names the band's intent (e.g.
  "phone glance"). No logic.

- [ ] **Step 2: Commit.**
  Message: `afk: add BreakConfig constants`.

### Task 2 — `BreakScheduler` skeleton + first failing test

**Files:**
- Create: `recorder/afk/BreakScheduler.java`
- Create: `test/.../afk/BreakSchedulerTest.java`

- [ ] **Step 1: Sketch the scheduler skeleton.** Constructor takes a
  `LongSupplier clockMs` and `Random rng`. Fields per "Scheduler
  state" above. Empty method bodies for the public surface; return
  `false` / `0` / `"unimplemented"` so the file compiles.

- [ ] **Step 2: Write the first test — fresh scheduler is not in a
  break and not due.** Construct with a clock returning `0L` and a
  `Random(42)`. Assert `isInBreak(0)` is false. Assert
  `isBreakDue(0)` is false (the rolled `activityEndMs` is in the
  range `[15, 30]` minutes — well above 0). Run; expect FAIL on the
  `isBreakDue` assertion (current stub returns false — actually
  passes). If the test passes by accident on the stub, change it to
  verify a behavior the stub can't fake (e.g. assert
  `statusLine(0)` matches `next break in \d+m \d+s` regex). Re-run;
  this WILL fail.

- [ ] **Step 3: Make the constructor roll a real `activityEndMs`.**
  Use `rng.nextLong(ACTIVITY_MIN_MS, ACTIVITY_MAX_MS + 1) +
  clockMs.getAsLong()`. Implement `statusLine` enough to satisfy the
  regex case: "next break in {mins}m {secs}s" computed from
  `activityEndMs - nowMs`. Tests pass.

- [ ] **Step 4: Commit.** Message: `afk: BreakScheduler skeleton +
  fresh-state test`.

### Task 3 — `isBreakDue` + `startBreak`

- [ ] **Step 1: Test — break is due once activity period elapses.**
  Construct with a mutable clock (an `AtomicLong`-backed
  `LongSupplier`). After construction, advance the clock past
  `ACTIVITY_MAX_MS + 1ms` to guarantee the activity period ended.
  Assert `isBreakDue(nowMs)` is true.

- [ ] **Step 2: Implement.** `isBreakDue` returns `breakEndMs == 0
  && nowMs >= activityEndMs`. Tests pass.

- [ ] **Step 3: Test — `startBreak` picks a tier per the weighted
  prob and sets a duration in-range.** Two complementary tests:
  (a) **fixture test** — single seed (e.g. 42), 1000 iterations,
  tally counts, assert micro-share is within ±2% (deterministic
  given the seed; tightening the band catches accidental algorithm
  changes that would shift the seed-42 outcome). (b) **distribution
  test** — five distinct seeds (42, 7, 1234, 99, 314), 1000
  iterations each, assert micro-share is within ±5% per seed
  (catches actual algorithm bias without flaking on a single seed
  fingerprint). Both tests also assert each rolled duration is
  inside the tier's `[MIN_MS, MAX_MS]` band — that's a property,
  not a distribution.

- [ ] **Step 4: Implement.** `startBreak` rolls
  `rng.nextInt(100) < MICRO_PROB ? MICRO : MEDIUM`, picks duration
  via `rng.nextLong(min, max + 1)`, sets `breakEndMs = nowMs +
  duration`, sets `currentTier`. Logs INFO line per spec. Tests
  pass.

- [ ] **Step 5: Commit.** Message: `afk: BreakScheduler isBreakDue +
  startBreak with weighted tier roll`.

### Task 4 — `isInBreak` + `endBreakIfDue`

- [ ] **Step 1: Test — break ends after duration elapses.** Start
  break, advance clock past `breakEndMs`, call `endBreakIfDue`.
  Assert `isInBreak` returns false. Assert a NEW `activityEndMs` was
  rolled (not zero, in-range from the new "now"). Assert
  `currentTier` is null.

- [ ] **Step 2: Implement.** `isInBreak` returns `breakEndMs >
  nowMs`. `endBreakIfDue` is a no-op when `isInBreak` is true OR
  `breakEndMs == 0`; otherwise resets `breakEndMs`, `currentTier`,
  rolls a fresh `activityEndMs`, logs the "back to work" line. Tests
  pass.

- [ ] **Step 3: Test — `statusLine` reports the right phase.** Three
  cases: not in break / in break / disabled. Each asserts a regex.

- [ ] **Step 4: Implement `statusLine` fully.** Branch on
  `disabled`, `isInBreak`, otherwise "next break in...". Tests pass.

- [ ] **Step 5: Commit.** Message: `afk: BreakScheduler isInBreak,
  endBreakIfDue, statusLine`.

### Task 5 — `enable` / `disable`

- [ ] **Step 1: Test — disabled scheduler never reports a break
  due.** Advance clock far past `activityEndMs`. Call `disable()`.
  Assert `isBreakDue` is false even though the activity-period
  window has elapsed.

- [ ] **Step 2: Implement.** Add `disabled` boolean field. `disable`
  sets it. `enable` clears it AND rolls a fresh `activityEndMs` from
  `nowMs` (so the user doesn't get an instant break the moment they
  re-enable mid-session). `isBreakDue` short-circuits on `disabled`.

- [ ] **Step 3: Test — re-enable mid-break ends the break
  immediately.** Start a break, then call `disable()` then
  `enable(nowMs)`. Assert `isInBreak(nowMs)` is false.

- [ ] **Step 4: Implement.** `disable` ALSO clears `breakEndMs` and
  `currentTier` so re-enable starts clean.

- [ ] **Step 5: Commit.** Message: `afk: BreakScheduler enable /
  disable`.

### Task 6 — Wire `BreakScheduler` into `PizzaScript`

**Files:**
- Modify: `recorder/scripts/PizzaScript.java`

- [ ] **Step 1: Add the field.** `private BreakScheduler breaks;`
  next to the existing AtomicReferences. Don't initialize at field
  declaration — `start()` constructs it so a stop+restart resets the
  schedule.

- [ ] **Step 2: Construct in `start()`.** Inside the existing reset
  block (next to `cookHeatMissCount = 0`): `this.breaks = new
  BreakScheduler(System::currentTimeMillis,
  ThreadLocalRandom.current(), afkBreaksEnabled.get())`. Add an
  `AtomicBoolean afkBreaksEnabled = new AtomicBoolean(true)` field
  next to the other panel-pushed flags (`addTomato`, `addCheese`,
  `cookPizza`, `addAnchovies`).

- [ ] **Step 3: Add the public setter.** `public void
  setAfkBreaksEnabled(boolean v)` — flip the AtomicBoolean AND if
  `breaks != null`, call `breaks.enable(now)` or `breaks.disable()`
  to push the change live (matches the live-flag pattern of the
  loop checkboxes).

- [ ] **Step 4: Hook the break check at the TOP of the worker
  while-loop body in `tickLoop`.** This is the outer loop in
  `tickLoop()` (the one currently starting with the player-null
  check + `safeDismissLevelUp()`). The break check must run
  BEFORE `safeDismissLevelUp` and BEFORE the state-switch dispatch,
  otherwise level-up dismissals or in-flight state ticks fire
  mid-break and defeat AFK.
  ```
  long now = System.currentTimeMillis();
  if (breaks != null) breaks.endBreakIfDue(now);
  if (breaks != null && breaks.isInBreak(now)) {
      status.set(breaks.statusLine(now));
      SequenceSleep.sleep(client, TICK_MS);
      continue;
  }
  if (breaks != null
      && breaks.isBreakDue(now, state.get() == State.DECIDE)) {
      breaks.startBreak(now);
      status.set(breaks.statusLine(now));
      SequenceSleep.sleep(client, TICK_MS);
      continue;
  }
  // ... existing safeDismissLevelUp + switch ...
  ```
  Order: `endBreakIfDue` first (clears an expired break before the
  in-break check, so the wake-up tick proceeds to normal work
  immediately rather than wasting a tick), then `isInBreak`, then
  `isBreakDue` (gated on DECIDE — the only state where starting a
  break is safe). Steady-state cost: 3 volatile-long reads + 2
  comparisons per tick. Negligible.

- [ ] **Step 5: Add panel accessor.** `public String breakStatus()
  { return breaks == null ? "breaks: off (not started)" :
  breaks.statusLine(System.currentTimeMillis()); }`. Used by the
  panel's `refreshPizza`.

- [ ] **Step 6: Manual smoke test.** `./gradlew :client:compileJava`
  passes. Don't restart the client yet — Task 8 wires the panel.

- [ ] **Step 7: Commit.** Message: `pizza: integrate BreakScheduler
  at tickDecide entry`.

### Task 7 — Panel toggle + countdown label

**Files:**
- Modify: `recorder/RecorderPanel.java`

- [ ] **Step 1: Add the field declarations.** Next to
  `pizzaAnchoviesBox`:
  ```
  private final javax.swing.JCheckBox pizzaBreaksBox =
      new javax.swing.JCheckBox("AFK breaks (humanizer)", true);
  private final JLabel pizzaBreakLabel = new JLabel("breaks: idle");
  ```

- [ ] **Step 2: Add to `buildPizzaTab()`.** Apply `LEFT_ALIGNMENT`
  and `capHeight`. Add the checkbox above the Start/Stop button
  row, the label below the buttons (so it shows status during a
  run). Mirror the alignment/cap pattern of the loop checkboxes.

- [ ] **Step 3: Wire the action listener.** When the checkbox flips,
  call `pizzaScript.setAfkBreaksEnabled(...)` if `pizzaScript !=
  null`.

- [ ] **Step 4: Wire `setPizzaScript` and `onPizzaStart` to push the
  initial value** (matches the existing pattern for tomato/cheese/
  cook/anchovies).

- [ ] **Step 5: Update `refreshPizza`.** Set
  `pizzaBreakLabel.setText(pizzaScript.breakStatus())` each refresh
  cycle.

- [ ] **Step 6: Compile + manual smoke.** `./gradlew
  :client:shadowJar`, kill old client, launch new. Open Pizza tab.
  Verify the checkbox + label appear with the right alignment.
  Start the script. Watch the label count down. Don't wait the full
  15min — just confirm the countdown is decrementing.

- [ ] **Step 7: Commit.** Message: `pizza-panel: AFK breaks toggle +
  countdown label`.

### Task 8 — End-to-end verification

- [ ] **Step 1: Tune the constants down for testing.** Temporarily
  set `ACTIVITY_MIN_MS = 60_000` (1 min) in `BreakConfig`. Rebuild,
  relaunch, start the script. Watch one full activity → break →
  resume cycle in the panel + log within ~3 minutes.

- [ ] **Step 2: Verify the log lines.** Grep
  `~/.runelite/logs/client.log` for `AFK break`. Should see one
  "starting" + one "over" line per cycle.

- [ ] **Step 3: Verify the break fires AT a state boundary, not
  mid-action.** The "starting" log line should appear immediately
  after a `pizza: WALK_TO_BANK → DECIDE` transition or similar
  arrival-at-bank line, never mid-cook / mid-walk.

- [ ] **Step 4: Restore production constants.** Revert `BreakConfig`
  to the spec values (25–60min activity).

- [ ] **Step 5: Commit.** Message: `afk: verified break cycle
  end-to-end on pizza loop`.

## Verification

This plan is done when:

1. `./gradlew :client:compileJava :client:compileTestJava
   :client:test --tests '*BreakScheduler*'` passes.
2. The pizza bot, run with default constants, takes its first
   AFK break between 15 and 30 minutes after Start, then resumes
   automatically.
3. The panel countdown reflects the scheduler state correctly.
4. Disabling the checkbox mid-run cancels any active break and
   stops scheduling new ones; re-enabling rolls a fresh activity
   window without firing an immediate break.
5. Log lines for break-start and break-over appear at INFO with
   tier name and duration, greppable as `AFK break`.

## Out-of-band notes

- The Audit doc's other items (cook pacing constants, abortWith
  bank-close, sidebar-tab restoration) are unrelated and not blocked
  by this work.
- Adoption by Cooking V3 / ChickenFarmV3 / Pie Dish is a follow-up.
  Each script picks its own safe-boundary call site; the scheduler
  itself is reusable as-is.
- v2: long breaks (5min+) requires logout + resume-from-logged-out
  for the pizza FSM. `LogoutHelper` already exists in V3; resume
  needs `decideResume` to handle "logged out at bank" / "logged out
  in kitchen." Separate spec.

## How to continue this in a new chat

Read this file end-to-end, confirm the three Open-decisions, then
either start at Task 1 inline (per the user's "code work inline"
preference) or fire executing-plans to step through. Don't re-litigate
the architecture — that decision is locked: a separate scheduler class,
not embedded in PizzaScript, with injected clock + RNG so tests are
deterministic.
