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

### Oak logs (5 options)

| Slot | Key | Item | Level | XP | Stackable | Item ID | Can string |
|---|---|---|---|---|---|---|---|
| 1 | `VK_1` | Arrow shafts | 15 | 10 | yes (30/log) | 52 | no |
| 2 | `VK_2` | Oak shortbow (u) | 20 | 16.5 | no | 54 | yes |
| 3 | `VK_SPACE` | Oak longbow (u) | 25 | 25 | no | 56 | yes |
| 4 | `VK_4` | Oak shield | 27 | 50 | no | 22251 | no — needs **2 logs** |
| 5 | `VK_5` | Oak stock | 24 | 16 | no | 9442 | no |

### Willow logs (5 options)

| Slot | Key | Item | Level | XP | Stackable | Item ID | Can string |
|---|---|---|---|---|---|---|---|
| 1 | `VK_1` | Arrow shafts | 30 | 15 | yes (45/log) | 52 | no |
| 2 | `VK_2` | Willow shortbow (u) | 35 | 33.3 | no | 60 | yes |
| 3 | `VK_SPACE` | Willow longbow (u) | 40 | 41.5 | no | 58 | yes |
| 4 | `VK_4` | Willow stock | 39 | 22 | no | 9444 | no |
| 5 | `VK_5` | Willow shield | 42 | 83 | no | 22254 | no — needs **2 logs** |

### Teak logs (1 option — single-item dialog)

| Slot | Key | Item | Level | XP | Stackable | Item ID | Can string |
|---|---|---|---|---|---|---|---|
| 1 | `VK_SPACE` | Teak stock | 46 | 27 | no | 9446 | no |

### Maple logs (5 options)

| Slot | Key | Item | Level | XP | Stackable | Item ID | Can string |
|---|---|---|---|---|---|---|---|
| 1 | `VK_1` | Arrow shafts | 45 | 20 | yes (60/log) | 52 | no |
| 2 | `VK_2` | Maple shortbow (u) | 50 | 50 | no | 64 | yes |
| 3 | `VK_SPACE` | Maple longbow (u) | 55 | 58.3 | no | 62 | yes |
| 4 | `VK_4` | Maple stock | 54 | 32 | no | 9448 | no |
| 5 | `VK_5` | Maple shield | 57 | 116.5 | no | 22257 | no — needs **2 logs** |

### Mahogany logs (1 option — single-item dialog)

| Slot | Key | Item | Level | XP | Stackable | Item ID | Can string |
|---|---|---|---|---|---|---|---|
| 1 | `VK_SPACE` | Mahogany stock | 61 | 41 | no | 9450 | no |

### Yew logs (5 options)

| Slot | Key | Item | Level | XP | Stackable | Item ID | Can string |
|---|---|---|---|---|---|---|---|
| 1 | `VK_1` | Arrow shafts | 60 | 25 | yes (75/log) | 52 | no |
| 2 | `VK_2` | Yew shortbow (u) | 65 | 67.5 | no | 68 | yes |
| 3 | `VK_SPACE` | Yew longbow (u) | 70 | 75 | no | 66 | yes |
| 4 | `VK_4` | Yew stock | 69 | 50 | no | 9452 | no |
| 5 | `VK_5` | Yew shield | 72 | 150 | no | 22260 | no — needs **2 logs** |

### Magic logs (5 options)

| Slot | Key | Item | Level | XP | Stackable | Item ID | Can string |
|---|---|---|---|---|---|---|---|
| 1 | `VK_1` | Arrow shafts | 75 | 30 | yes (90/log) | 52 | no |
| 2 | `VK_2` | Magic shortbow (u) | 80 | 83.3 | no | 72 | yes |
| 3 | `VK_SPACE` | Magic longbow (u) | 85 | 91.5 | no | 70 | yes |
| 4 | `VK_4` | Magic stock | 85 | 70 | no | 21952 | no |
| 5 | `VK_5` | Magic shield | 87 | 183 | no | 22263 | no — needs **2 logs** |

### Redwood logs (3 options)

| Slot | Key | Item | Level | XP | Stackable | Item ID | Can string |
|---|---|---|---|---|---|---|---|
| 1 | `VK_1` | Arrow shafts | 90 | 35 | yes (105/log) | 52 | no |
| 2 | `VK_2` | Redwood hiking staff | 90 | 10.5 | no | 31049 | no |
| 3 | `VK_SPACE` | Redwood shield | 92 | 216 | no | 22266 | no — needs **2 logs** |

### Shield implementation note

Shields consume **2 logs per shield**, so a 27-log inventory yields 13 shields with 1 log left over.
Banking: withdraw **26 logs** (not 27) when the selected item is a shield. The leftover from
odd-lot situations (bank has an odd number) gets deposited on the next trip as waste.

### Normal logs correction

The wiki confirms wooden stock (level 9) appears as slot 5 even though shortbow (level 5) and
longbow (level 10) bracket it — the game groups bows first, then stocks. Item IDs confirmed:
wooden stock = 9440, shortbow (u) = 50, longbow (u) = 48.

### Stringing bows

All stringing uses bowstring (item ID 1777) on unstrung bow → single-option skillmulti → `VK_SPACE`.
Batch size: 14 bowstrings + 14 unstrung bows = 28 slots.

| Level | Unstrung | Unstrung ID | Strung | Strung ID | XP |
|---|---|---|---|---|---|
| 5 | Shortbow (u) | 50 | Shortbow | 841 | 5 |
| 10 | Longbow (u) | 48 | Longbow | 839 | 10 |
| 20 | Oak shortbow (u) | 54 | Oak shortbow | 843 | 16.5 |
| 25 | Oak longbow (u) | 56 | Oak longbow | 845 | 25 |
| 35 | Willow shortbow (u) | 60 | Willow shortbow | 849 | 33.3 |
| 40 | Willow longbow (u) | 58 | Willow longbow | 847 | 41.5 |
| 50 | Maple shortbow (u) | 64 | Maple shortbow | 853 | 50 |
| 55 | Maple longbow (u) | 62 | Maple longbow | 851 | 58.3 |
| 65 | Yew shortbow (u) | 68 | Yew shortbow | 857 | 67.5 |
| 70 | Yew longbow (u) | 66 | Yew longbow | 855 | 75 |
| 80 | Magic shortbow (u) | 72 | Magic shortbow | 861 | 83.3 |
| 85 | Magic longbow (u) | 70 | Magic longbow | 859 | 91.5 |

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

1. **Skillmulti key positions** — the wiki does not publish dialog slot order directly. The tables
   above follow ascending-level ordering which matches wiki data, but **verify in-game** for any
   log type where you intend to use a non-Space key before trusting a wrong key would silently
   fletch the wrong item. Normal logs are confirmed from screenshot; others need checking.
2. **Shield item IDs** — wiki-sourced IDs (22251, 22254, 22257, 22260, 22263, 22266) should be
   cross-checked via click-inspector on the actual items.
3. **Stringing skillmulti** — confirm it is always a single option (Space) for all bow types in-game.
4. **Teak/Mahogany single-option dialog** — confirm whether a single-item dialog auto-confirms
   on open or still requires a keypress / click.

---

## Out of Scope

- Arrow tips / feathering / dart tips / bolt tips — not covered
- Walking to a specific location
- GE supply buying
- Logout / re-login plumbing
