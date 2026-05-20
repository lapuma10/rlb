# Fletching Script Design

**Date:** 2026-05-20
**Branch:** v21-trail-guided-nav (feature branch to be cut from master)

---

## Goal

A bank-standing fletching script covering the full 1–99 grind: cutting logs into bows/shafts/stocks
and stringing unstrung bows, with optional auto-advance on level-up and scheduled AFK breaks.

---

## Scope

- Modes: **Fletch** (knife+logs → output), **String** (bowstring+unstrung → strung bow), **Cut+String** (both phases in loop)
- All common log types: normal, oak, willow, teak, maple, mahogany, yew, magic (redwood TBD)
- Nearest bank — no location picker, no walking
- Auto-advance checkbox (off by default)
- Break scheduler (on by default), same `BreakScheduler`/`BreakConfig` as PizzaScript
- One file: `scripts/FletchingScript.java` — inline everything, no new subpackage

---

## Files

| Path | Action |
|---|---|
| `recorder/scripts/FletchingScript.java` | New — FSM, item catalog, interaction logic |
| `recorder/RecorderPanel.java` | Wire UI controls |
| `recorder/RecorderPlugin.java` | Instantiate + register script |

---

## Item Catalog (inner enum `FletchItem`)

Each entry stores:
- `logId` — ItemID of the input log (for FLETCH mode)
- `unstrungId` — ItemID of the unstrung bow (for STRING mode; also the output of FLETCH for bows)
- `strungId` — ItemID of the strung bow (for STRING mode output; -1 for non-bow items)
- `levelReq` — fletching level required
- `fletchKey` — `KeyEvent.VK_*` pressed after skillmulti opens in FLETCH mode (`VK_SPACE` for default item)
- `stackable` — true for arrow shafts, javelin shafts (affects batch size math)
- `canString` — true if a bowstring can be applied (false for shafts/stocks/shields)
- `label` — display name for the panel dropdown

### Normal logs (knife+logs skillmulti — 5 options)

| Option | Item | Level | Key | Stackable | Can string |
|---|---|---|---|---|---|
| 1 | Arrow shafts | 1 | `VK_1` | yes (15/log) | no |
| 2 | Javelin shafts | 3 | `VK_2` | yes (15/log) | no |
| 3 | Shortbow (u) | 5 | `VK_SPACE` | no | yes |
| 4 | Longbow (u) | 10 | `VK_4` | no | yes |
| 5 | Crossbow stock | 9 | `VK_5` | no | no |

### Other log types — PLACEHOLDER

Exact dialog option order and key positions must be confirmed in-game or from OSRS wiki before
implementation. Rows marked **[VERIFY]** are estimates based on wiki level data; key mappings
are unknown until the dialog is seen in-game.

A background research task was dispatched at spec-write time to fill in this table from the
OSRS wiki. Update this section before implementation.

| Log | Item | Level | Key | Notes |
|---|---|---|---|---|
| Oak | Oak shortbow (u) | 20 | [VERIFY] | |
| Oak | Oak longbow (u) | 25 | [VERIFY] | |
| Oak | Oak stock | 27 | [VERIFY] | |
| Oak | Oak shield | 32 | [VERIFY] | user confirmed shields exist |
| Willow | Willow shortbow (u) | 35 | [VERIFY] | |
| Willow | Willow longbow (u) | 40 | [VERIFY] | |
| Willow | Willow stock | 42 | [VERIFY] | |
| Teak | Teak stock | 46 | [VERIFY] | crossbow stock only? |
| Maple | Maple shortbow (u) | 50 | [VERIFY] | |
| Maple | Maple longbow (u) | 55 | [VERIFY] | |
| Maple | Maple stock | 54 | [VERIFY] | |
| Mahogany | Mahogany stock | 61 | [VERIFY] | |
| Yew | Yew shortbow (u) | 65 | [VERIFY] | |
| Yew | Yew longbow (u) | 70 | [VERIFY] | |
| Magic | Magic shortbow (u) | 80 | [VERIFY] | |
| Magic | Magic longbow (u) | 85 | [VERIFY] | |
| Redwood | Redwood shield | 92 | [VERIFY] | TBD |

### Stringing bows

All stringing uses bowstring on unstrung bow → single-option skillmulti → `VK_SPACE`.
Batch size: 14 bowstrings + 14 unstrung bows = 28 slots.

| Unstrung | Strung | Level |
|---|---|---|
| Shortbow (u) | Shortbow | 5 |
| Longbow (u) | Longbow | 10 |
| Oak shortbow (u) | Oak shortbow | 20 |
| Oak longbow (u) | Oak longbow | 25 |
| Willow shortbow (u) | Willow shortbow | 35 |
| Willow longbow (u) | Willow longbow | 40 |
| Maple shortbow (u) | Maple shortbow | 50 |
| Maple longbow (u) | Maple longbow | 55 |
| Yew shortbow (u) | Yew shortbow | 65 |
| Yew longbow (u) | Yew longbow | 70 |
| Magic shortbow (u) | Magic shortbow | 80 |
| Magic longbow (u) | Magic longbow | 85 |

---

## Modes

```java
enum Mode { FLETCH, STRING, CUT_AND_STRING }
```

- **FLETCH:** withdraw knife + logs, cut, bank output, repeat.
- **STRING:** withdraw 14 bowstrings + 14 unstrung bows, string, bank output, repeat.
- **CUT_AND_STRING:** FLETCH phase → STRING phase → FLETCH phase → …
  - If bowstrings exhausted during STRING phase: fall back to FLETCH-only loop.
  - Arrow shafts and crossbow stocks cannot be selected in CUT_AND_STRING (no string counterpart); panel should filter these out of the item dropdown when this mode is active.

---

## State Machine

```
States: IDLE, BANKING, FLETCHING, ABORTED
```

A `nextAction` field (`CUT | STRING`) drives what BANKING withdraws and what FLETCHING does.
`setState()` resets all per-state flags (same pattern as PieDishScript).

### BANKING tick

1. Open nearest bank (`bank.tryClickBankBoothRandom()`).
2. Wait for `bank.bankReady()`.
3. `bank.depositAllInventory()`.
4. If `nextAction == CUT`:
   - If knife not in inv: `bank.tryWithdrawX(KNIFE, 1)`. If bank has no knife → abort.
   - `bank.tryWithdrawAll(logId)` — fills remaining 27 slots.
5. If `nextAction == STRING`:
   - `bank.tryWithdrawX(unstrungId, 14)`. If none in bank → check mode:
     - CUT_AND_STRING: flip `nextAction = CUT`, go back to BANKING.
     - STRING only: abort "no unstrung bows".
   - `bank.tryWithdrawX(BOWSTRING, 14)`. If none → abort "no bowstrings" OR (CUT_AND_STRING) flip to CUT.
6. `bank.tryCloseBank()` → `setState(FLETCHING)`.

Banking failures tracked with `bankFailures` counter; abort after 3 consecutive failures (same as PieDishScript).

### FLETCHING tick

**CUT path (`nextAction == CUT`):**

1. `ensureInventoryTabOpen()`.
2. `dispatcher.isBusy()` guard.
3. Step A — engage use-mode on knife: `CLICK_INV_ITEM` verb="Use", slot=knifeSlot.
4. `dispatcher.awaitIdle(3_000)`.
5. Sleep 100ms (lean settle, down from PieDishScript's 350ms).
6. Step B — click any log slot bounds (resolved fresh on client thread).
7. `dispatcher.awaitIdle(3_000)`.
8. Set `clicksDone = true`, `skillmultiWaitMs = now`.

Once `clicksDone`:
9. Poll `InterfaceID.Skillmulti.UNIVERSE` visible. If not open after 5s → `dismissMenu()` + retry clicks.
10. `dispatcher.tapKey(item.fletchKey)` — selects item and confirms Make-All in one keypress.
11. Set `skillmultiConfirmed = true`, `craftWaitMs = now`.

Once confirmed: poll inventory until logs gone (count drops to 0). On stackable items (shafts)
poll on knife being the only item left. Timeout 90s → abort.

When done: random AFK pause 2–8s → determine next state:
- CUT_ONLY: `setState(BANKING)` with `nextAction = CUT`.
- CUT_AND_STRING: if `item.canString` → `nextAction = STRING`, `setState(BANKING)`.
  Else → `nextAction = CUT`, `setState(BANKING)`.

**STRING path (`nextAction == STRING`):**

Exact PieDishScript use-on shape:
1. `ensureInventoryTabOpen()`.
2. Step A — `CLICK_INV_ITEM` verb="Use" on bowstring slot.
3. Await idle + 100ms settle.
4. Click unstrung bow slot bounds.
5. Await idle.
6. Wait for skillmulti → `tapKey(VK_SPACE)` (always single option).
7. Wait until unstrung bows gone.
8. AFK pause 2–8s → `setState(BANKING)` with `nextAction` depending on mode.

---

## Auto-Advance

Toggled by checkbox, **off by default**.

Trigger: `isLevelUpVisible()` (polled every tick, same as CookingScriptV3).

On level up:
1. Read `client.getRealSkillLevel(Skill.FLETCHING)` on client thread.
2. Scan `FletchItem` values upward from current `selectedItem`.
3. Pick first entry where `levelReq <= newLevel` AND bank contains `logId` (for FLETCH/CUT_AND_STRING)
   or `unstrungId` (for STRING).
4. Arrow shafts are excluded from auto-advance (they don't have a natural progression path).
5. If no higher-tier material found: stay on current item.

---

## Break Scheduler

Exact PizzaScript wiring:

```java
private final AtomicBoolean afkBreaksEnabled = new AtomicBoolean(true);
private BreakScheduler breaks;
```

- Constructed fresh on each `start()`.
- Safe boundary: entering BANKING state (between batches, never mid-fletch).
- Tick loop gate (at BANKING entry):
  ```
  breaks.endBreakIfDue(now)
  if breaks.isInBreak(now) → status = breaks.statusLine; continue
  if breaks.isBreakDue(now, atBankBoundary) → breaks.startBreak(now); continue
  ```
- `breaks.disable()` called in `stop()`.
- `BreakConfig` shared (no per-script overrides needed).

Panel exposes: checkbox "Enable breaks" + `breaks.statusLine()` label.

---

## Timing Constants

```java
static final long TICK_MS               = 600;
static final long BANK_PACE_MS          = 1_500;
static final long INTER_CLICK_SETTLE_MS = 100;   // fast (vs 350ms in PieDishScript)
static final long POST_BATCH_MIN_MS     = 2_000;  // AFK pause after each batch
static final long POST_BATCH_MAX_MS     = 8_000;
static final long SKILLMULTI_TIMEOUT_MS = 5_000;
static final long CRAFT_TIMEOUT_MS      = 90_000;
static final int  MAX_BANK_FAILURES     = 3;
```

---

## Panel Controls

- **Mode** dropdown: Fletch / String / Cut+String
- **Item** dropdown: filtered by mode (Cut+String hides non-stringable items)
- **Auto-advance** checkbox (off by default)
- **Enable breaks** checkbox (on by default) + status line
- **Start / Stop** button

The panel reads the current `FletchItem` selection and `Mode` before calling `script.start()`.
The `RecorderPlugin` instantiates `FletchingScript` the same way it instantiates `CookingScriptV3`.

---

## Error Handling & Abort Conditions

- No knife in bank (CUT mode) → abort with message.
- No logs in bank → abort.
- No unstrung bows in bank (STRING-only mode) → abort.
- No bowstrings in bank (STRING-only) → abort; (CUT_AND_STRING) → fall back to CUT-only.
- `MAX_BANK_FAILURES` consecutive bank primitive failures → abort.
- `CRAFT_TIMEOUT_MS` exceeded with items still in inventory → abort.
- Level up popup: auto-dismiss after 3–34s random delay (same as CookingScriptV3's `safeDismissLevelUp`).

---

## What Needs In-Game Verification Before Implementation

1. **Skillmulti key positions for all non-normal log types** — which key maps to which item per dialog. See the [VERIFY] rows in the item catalog. The wiki research subagent dispatched during spec write should fill most of these in; confirm in-game for any remaining gaps.
2. **Oak shield and Redwood shield** item IDs — confirm `ItemID.*` names via click-inspector.
3. **Teak and Mahogany** dialog layouts — these may only produce crossbow stocks; confirm.
4. **Stringing skillmulti** — confirm it is always a single option (Space) for all bow types.

---

## Out of Scope

- Arrow tips / feathering / dart tips / bolt tips — not covered
- Walking to a specific location
- GE supply buying
- Logout / re-login plumbing
