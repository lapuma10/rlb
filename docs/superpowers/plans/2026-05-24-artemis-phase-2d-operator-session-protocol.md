# Phase 2D — Tier 1 Operator Session Protocol (Cow Killer Pilot)

**Status:** DRAFT — first supervised run protocol; awaiting operator approval before doc-commit and run.
**Date:** 2026-05-24
**Commit under test:** b60aff29c (Phase 2C.1) — to be re-confirmed at run time.

---

## 1. Purpose

This is a **supervised Tier 1 integration test**. The single goal is to prove the new
launch path works end-to-end on a real account:

- Artemis dispatcher → DynamicStep tree → 2C.1 UI Start/Stop can run one real loop.
- The busy gate inside DynamicStep paces attack dispatch correctly during combat.
- The no-cow branch idles + retries instead of failing the session.

**Out of scope for this run** — do NOT evaluate, do NOT block on:

- Production migration of any existing script.
- Long unattended runtime — operator is at the keyboard the entire run.
- Click-quality / click-diversity evaluation (Phase 3 PixelResolver work).
- Bank / GE flow (not implemented).
- Loot pickup (not implemented).
- Inventory-full termination (not implemented).
- Natural session-length stop (`SessionShape` is `Long.MAX_VALUE`).
- Logout-on-Stop (Phase 2B.1.a logout action is observation-only).

Pass/fail of Tier 1 is purely: **does the engine drive ONE loop, and can the operator
stop it cleanly?**

---

## 2. Pre-run checklist

Operator confirms each item before clicking Start. Any "no" → fix or abort.

- [ ] **Test account only.** Use a designated test account for this integration
  test. See memory `project_f2p_two_account_strategy`.
- [ ] **Quiet test world/location.** Use a quiet test world/location to reduce
  test noise and competition for cows.
- [ ] **Logged in at or adjacent to Lumbridge cow field.** Pilot's walkTo is a thin
  wrapper for Tier 1; assume short path only. Spawning at GE or Karamja means
  a long uncontrolled walk.
- [ ] **Inventory state:** any state acceptable (loot not implemented). Empty
  inventory recommended — any drift toward picking things up becomes visually
  obvious.
- [ ] **Gear/combat:** any combat-capable loadout. Tier 1 does not assert level
  requirements or DPS. Bring food if you want a longer run.
- [ ] **Recorder plugin enabled** in RuneLite's plugin list.
- [ ] **Config flag `Cow Killer pilot (test) → cowKillerPilotEnabled` set to ON.**
  Default is OFF; toggle explicitly. Section is closed by default.
- [ ] **Recorder panel "Cow Killer pilot (test)" controls visible.** Start button,
  Stop button, status label all rendered.
- [ ] **Recording decision made.** Decide BEFORE clicking Start whether to also
  start a recorder recording session for post-hoc analysis. StepEvents are
  persisted to the session JSON only when the recorder is `RECORDING`
  (`RecorderManager.recordStepEvent`, RecorderManager.java:269); if not
  recording, Tier 1 evaluation is limited to live observation + client.log,
  and the Step-tree behavior counts in §9 will be operator-estimated rather
  than authoritative. **Recommended:** start a recording before clicking Start.
- [ ] **client.log tailed in a terminal:** `tail -f ~/.runelite/logs/client.log`
  (see CLAUDE.md "Bug investigation checklist"). Use it for exceptions and
  high-level warnings — **NOT for StepEvents** (those go to the recorder
  session JSON, not the log file).
- [ ] **No other recorder script running.** ChickenFarmV3, Cooking, etc. must be
  idle. Pilot owns the dispatcher.
- [ ] **Operator briefed on Stop behavior:**
  - Stop button remains available even if config is toggled OFF mid-run.
    Config gates Start only.
  - **Stop does NOT log out.** After clicking Stop, the operator must manually
    log out in-game if desired. Phase 2B.1.a logout action is observation-only
    and does not fire.

---

## 3. Launch steps

Operator executes in order:

1. Open RuneLite → side panel → Recorder.
2. In RuneLite's config panel, open the "Cow Killer pilot (test)" section
   (closed by default — click to expand) and toggle `cowKillerPilotEnabled` ON.
3. Verify panel state in the Recorder side panel:
   - Start button: **enabled**
   - Stop button: **disabled**
   - Status label: **"Pilot: idle"**
4. Click **Start**.
5. Within ≤1 second, observe panel state:
   - Start button: **disabled**
   - Stop button: **enabled**
   - Status label: **"Pilot: running"**
6. Within the next ~5 seconds, observe:
   - On-canvas walk activity if not already inside the cow field.
   - No exception stacktraces in client.log.
7. Once inside the cow field:
   - Cow target selection (exact-name "Cow" match).
   - First attack dispatched (visible in-game).
   - Busy gate prevents a second Attack while the first is in flight.
8. **Do not leave unattended.** Eyes on screen + log tail for the entire run.

---

## 4. What to watch live

Operator watches the game canvas, the Recorder panel, and the client.log tail
together. Anything trending bad → see §6.

**Two things are NOT live-visible — do not block the run on them:**

- **StepEvent stream is not in client.log.** StepEvents persist to the recorder
  session JSON only when a recording is active (see §2 recording-decision
  item) and are designed for post-run analysis. Do not try to read Step
  lifecycle from client.log during the run.
- **ActionRequest provenance is not in the runtime.**
  `sequence/internal/ActionRequest.java` carries no source/origin field. The
  "every dispatch comes from Artemis" property is enforced at code level by
  the **Phase 1B grep gate** and verified by **Phase 2C structural tests**,
  not by runtime introspection. If the build was green at the Phase 2C.1
  commit (b60aff29c), this property holds for this run.

**In client.log (live):**

- No exception stacktraces.
- No "Navigator: NO_ROUTE" storm.
- No repeated `LogoutAction` firing (should be silent — Phase 2B.1.a is
  observation-only; appearing means the observation hook is misclassifying
  state).
- No repeated dispatcher-failure log lines (e.g., "verb not in menu", "click
  resolved to wrong tile") in rapid succession.

**On the game canvas (live):**

- Player walks toward LUMBRIDGE_COW_FIELD when not in zone.
- In-zone: cursor hovers a Cow model before clicking, not random tiles.
- Attack click resolves to "Attack → Cow", not "Walk here" or another verb.
- During combat: no rapid double-click spam. Busy gate should produce visible
  single-click cadence per kill.
- Clicks land on cow models. If clicks consistently miss the intended target
  (no cow takes damage / combat doesn't start), that is a basic-operation
  blocker — see §6 — not a click-quality refinement (which is deferred —
  see §8).
- No-cow scenario (contested world): player idles or paces within zone, does
  not log out, does not wander outside.

**On the Recorder panel (live):**

- Status label remains "Pilot: running" while loop is active.
- Stop button remains clickable throughout.
- No UI freeze (panel timer is 500 ms; status should refresh visibly).

**Post-run (only if a recording was active — see §2):**

- Open the persisted session JSON and inspect `Events.Step` entries for
  per-Step `name` / `phase` / `verb` / `targetType` / `diagnosticReason`
  counts. This is the authoritative source for the Step-tree summary in
  the §9 run-log template.

---

## 5. Stop protocol

**Normal stop:**

1. Click **Stop** in the Recorder panel.
2. Verify within ≤2 seconds:
   - Status label returns to **"Pilot: idle"** (if config still ON) or
     **"Pilot: disabled in config"** (if config OFF).
   - Start button: enabled (config ON) or disabled (config OFF).
   - Stop button: disabled.
3. Verify dispatcher activity stops (no further on-canvas clicks; no further
   dispatcher log lines in client.log).
4. **Manually log out in-game.** Click the in-game Logout button. Pilot does
   NOT do this for you.

**Fallback if Stop does not respond:**

1. **Disable the Recorder plugin** in RuneLite's plugin panel. This triggers
   `shutDown()` and tears down `pilotSequenceManager`.
2. If plugin disable does not stop dispatcher activity within ~5 seconds:
   **close the client window.** Hard kill is acceptable for Tier 1.
3. Note the failure in the run log (§9) with timestamp.

---

## 6. Failure triggers — stop immediately if

Any of these → click Stop now, even mid-loop. Do not wait to see if it
recovers. Reason goes in the run log (§9).

- **Click spam:** more than 2 attacks dispatched in <1 game tick (600 ms),
  or any visibly rapid double/triple click.
- **Walking stuck:** player has not moved and walkTo has been dispatched
  >3 times in ~10 seconds with no progress.
- **Stop unresponsive:** Stop button does not return status to idle/disabled
  within 2 seconds.
- **Exception storm:** >3 stacktraces in client.log within ~10 seconds.
- **UI frozen:** Recorder panel status label not updating, or buttons not
  clickable.
- **Navigator retry storm:** repeated NO_ROUTE or pathing failures.
- **Wrong target:** attacks anything other than an NPC literally named "Cow"
  (no Goblins, no Cow calf, no players).
- **Consistent click miss:** dispatched clicks repeatedly land off the intended
  cow — e.g., 3+ attempted attacks with no cow taking damage and no combat
  starting. This is a basic-operation blocker, not a click-quality refinement
  (Phase 3 is out of scope — see §8).
- **Leaves cow-field area:** player drifts outside the 220-tile
  LUMBRIDGE_COW_FIELD zone and does not return within ~30 seconds.
- **Wrong script activates:** any non-Cow-Killer recorder script starts
  (ChickenFarmV3, Cooking, etc.).
- **Behavior diverges from planned Step tree:** any observable action not
  explained by the Phase 2C plan (e.g., bank widget opens, GE widget opens,
  prayer toggled, food eaten without prompt).

---

## 7. Success criteria — Tier 1 pass

Tier 1 passes if ALL of these are true after the run:

- [ ] UI Start launched the pilot.
- [ ] Pilot walked to LUMBRIDGE_COW_FIELD (or was already inside).
- [ ] Pilot found a target NPC literally named "Cow".
- [ ] Pilot dispatched at least one attack on a Cow. (The "via `Artemis.click`
  only" property is enforced by Phase 1B grep gate + Phase 2C structural
  tests — not by live runtime check; see §4.)
- [ ] Busy gate prevented visibly rapid attack spam during a kill.
- [ ] No-cow situation (if encountered) resulted in idle/retry, not logout,
  not crash, not zone-exit.
- [ ] UI Stop halted the engine and returned status to idle/disabled.
- [ ] Operator was able to manually log out in-game after Stop.
- [ ] No grep-gate changes required.
- [ ] No runtime crash, no exception storm in client.log.
- [ ] Continuous observed run of **at least 10 minutes**, target **30 minutes**
  if stability holds.

If any item fails, see §10 decision tree.

---

## 8. Known limitations — operator must accept before running

These are not bugs. They are intentional Phase 2D scope limits.

- **No natural session stop.** `SessionShape.fromTickCount(client.getTickCount())`
  returns `Long.MAX_VALUE`. Pilot runs until operator hits Stop. Real budget
  lands in Phase 0B.
- **Stop does NOT log out.** Phase 2B.1.a `RecorderLogoutAction` is
  observation-only. Manual logout required.
- **No loot pickup.** Cow hide / raw beef stay on the ground. Operator can
  manually pick if desired; not the bot's job in Tier 1.
- **No inventory-full termination.** With loot disabled this doesn't fire,
  but it's also not implemented in case loot were enabled later.
- **No bank flow.** No deposit, no withdraw.
- **No GE flow.** No setup, no collect.
- **Click quality not evaluated in Tier 1.** Phase 3 PixelResolver improvements
  are not in scope. Do not evaluate click distribution / heatmap quality from
  this run. If clicks consistently miss the intended target, record it as a
  basic-operation blocker (§6), not a Phase 3 quality metric.
- **Phase 0B budget not yet in place.** No 2-hour run, no v1.0 lock signoff
  from this Tier 1.
- **Contested cow field.** If other players are killing every cow, the
  no-cow branch will idle/retry indefinitely. Pick a quiet world or accept
  the idle behavior.
- **Config gates Start only.** Toggling config OFF mid-run does NOT stop
  the pilot. Use the Stop button.

---

## 9. Artifacts to collect

Operator captures during/after the run. Output goes to
`docs/learnings/2026-05-24-artemis-tier1-run-NN.md` (NN = sequential run number)
or attached to this protocol doc as an appendix.

**Run-log template:**

```
## Tier 1 Run NN — YYYY-MM-DD HH:MM

Commit under test:       <hash>
Branch:                  <branch>
Account:                 <test account label, NOT username>
World:                   <number>, <pop label>
Start location:          <Lumbridge / Cow field / Other>
Inventory at start:      <empty / food / other>
Gear at start:           <brief>
Recording session:       <enabled — session JSON path: ... | disabled>

Start time:              HH:MM:SS
Stop time:               HH:MM:SS
Duration:                MM:SS
Stop trigger:            <operator-normal / failure-trigger §6.X / fallback §5.X>

UI state transitions observed:
- Start clicked at HH:MM:SS → status "running" at HH:MM:SS (Δ ms)
- Stop clicked at HH:MM:SS  → status "idle/disabled" at HH:MM:SS (Δ ms)

Step tree behavior (source: session JSON post-run if recording was enabled;
otherwise operator-observed estimates — mark which):
- walkTo dispatches: <count>, success: <count>, failure: <count>
- Cow targets selected: <count>
- Attacks dispatched: <count>
- Busy-gate skip count (observable live as paced single-click cadence per
  kill — qualitative if no recording): <count or "qualitative only">
- No-cow idle/retry occurrences: <count>

Exceptions / log anomalies:
- <stacktraces, timestamps, brief tag>

Failures observed:
- <failure trigger §6.X at HH:MM:SS, what operator saw>

Stop worked cleanly? <yes / no — describe>
Manual logout completed? <yes / no>

Screenshots (optional, attach to docs/learnings/2026-05-24-artemis-tier1-run-NN/):
- ui-idle.png
- ui-running.png
- ui-after-stop.png
- canvas-attack.png

client.log excerpt path: <relative or absolute>

Operator notes:
- <free-form>

Verdict: <Tier 1 PASS / FAIL — see §10>
```

---

## 10. Post-run decision tree

After the run, route to the correct next step:

- **Clean pass** (all §7 met, no §6 triggers): Mark Tier 1 passed in the
  run log. Proceed to plan the next Artemis slice. Likely **Phase 0B**
  (real SessionShape budget) before any longer run, or **Phase 2C.x**
  (loot + inventory-full termination) if operator wants a self-terminating
  loop sooner.
- **UI issue** (Start/Stop not responding, status label wrong, panel
  freeze): patch in **Phase 2C.1.x** (UI-layer slice on top of 2C.1).
- **Script loop issue** (Step tree behaves wrong, busy gate skips,
  no-cow branch loops, dispatch mistargets): patch in **Phase 2C.x**
  (Step tree slice).
- **Navigation issue** (walkTo fails, wrong zone, drifts outside cow
  field): patch in **Phase 2A.x** (zone tile list) or **Phase 2B.x**
  (Artemis.walkTo wiring), depending on root cause.
- **Click-quality issue only** (clicks land but cluster on centroid, or
  look noticeably non-uniform): **defer to Phase 3** PixelResolver work
  UNLESS it blocks basic operation (e.g., click misses the cow entirely
  every attempt — that is §6 "Consistent click miss", not a Phase 3
  refinement).
- **Session/break issue** (operator wants a natural stop or fatigue-shaped
  cadence): **Phase 0B** (real budget + break shape).
- **Wanted loot/inventory stop** (operator decides Tier 1 should pick up
  hides): optional **Phase 2C.x** after `Artemis.take()` is independently
  verified.

Cross-cutting: any apparent **discipline violation** (suspicion that a
dispatch did not originate from Artemis) → audit the Phase 1B grep gate
output and Phase 2C structural tests at the commit under test BEFORE
planning the next slice. Artemis being the only input source is
load-bearing for everything downstream.

---

## 11. Output summary (this doc)

- **Doc path:** `docs/superpowers/plans/2026-05-24-artemis-phase-2d-operator-session-protocol.md`
- **Sections:** 1–10 above.
- **Pass/fail criteria:** §7.
- **Run-log template:** §9.
- **Decision tree:** §10.
- **No code changes.** No file modifications in this slice except the doc itself.
- **Grep gate unchanged.** No Java touched.
- **Compile not re-run** — no source modifications.
- **Next action after doc-commit:** operator runs the supervised pilot per
  this protocol; results captured per §9; routed per §10.
