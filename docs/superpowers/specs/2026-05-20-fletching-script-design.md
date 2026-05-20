# Fletching Script Design

**Date:** 2026-05-20  
**Revised:** 2026-05-20 (post-review pass)

---

## Goal

A bank-standing fletching script covering the main 1–99 grind: cutting logs into bows/shafts
and stringing unstrung bows, with scheduled AFK breaks.

---

## V1 Scope

**Included:**

- Modes: Fletch, String, Cut+String
- Items: arrow shafts + shortbow/longbow for normal, oak, willow, maple, yew, magic logs
- Nearest bank — no location picker, no walking
- Break scheduler (on by default), same `BreakScheduler`/`BreakConfig` as PizzaScript
- One file: `scripts/FletchingScript.java` — inline everything, no new subpackage

**Deferred to v2:**

- Auto-advance on level-up (present in UI but not implemented — adds edge cases; ship core loop first)
- Stocks, shields, teak, mahogany, redwood (unverified key mappings; wrong key = silent wrong item)
- Javelin shafts, crossbow stocks (unverified; also not main training items)

---

## Files

| Path | Action |
|---|---|
| `recorder/scripts/FletchingScript.java` | New |
| `recorder/RecorderPanel.java` | Wire UI controls |
| `recorder/RecorderPlugin.java` | Instantiate + register |

---

## Item Catalog (inner enum `FletchItem`)

### Fields

```java
final int    logId;           // ItemID of input log (-1 for STRING-only entries)
final int    unstrungId;      // ItemID of unstrung bow output (-1 for shafts/stocks/shields)
final int    strungId;        // ItemID of strung bow (-1 for non-stringable items)
final int    levelReq;        // fletching level required
final int    fletchKey;       // KeyEvent.VK_* pressed after skillmulti opens (VK_SPACE = default)
final int    logsPerAction;   // 1 normally, 2 for shields
final int    outputPerLog;    // 1 for bows/stocks, 15/30/45/etc for shafts
final double xp;              // XP per action (for panel display)
final boolean canString;      // true if a bowstring can be applied
final boolean verified;       // false = hidden unless dev checkbox is on
final String label;           // panel dropdown text
```

`logWithdrawCount()` helper on the enum:

```java
int logWithdrawCount() {
    return logsPerAction == 2 ? 26 : 27;
}
```

Banking uses `bank.tryWithdrawX(item.logId, item.logWithdrawCount())` — never `tryWithdrawAll`.

### V1 items (verified = true)

#### Normal logs

| Enum | Item | Level | Key | logsPerAction | outputPerLog | XP | canString |
|---|---|---|---|---|---|---|---|
| `ARROW_SHAFTS` | Arrow shafts | 1 | `VK_1` | 1 | 15 | 5 | false |
| `SHORTBOW_U` | Shortbow (u) | 5 | `VK_SPACE` | 1 | 1 | 5 | true |
| `LONGBOW_U` | Longbow (u) | 10 | `VK_4` | 1 | 1 | 10 | true |

#### Oak logs

| Enum | Item | Level | Key | logsPerAction | outputPerLog | XP | canString |
|---|---|---|---|---|---|---|---|
| `OAK_ARROW_SHAFTS` | Oak arrow shafts | 15 | `VK_1` | 1 | 30 | 10 | false |
| `OAK_SHORTBOW_U` | Oak shortbow (u) | 20 | `VK_2` | 1 | 1 | 16.5 | true |
| `OAK_LONGBOW_U` | Oak longbow (u) | 25 | `VK_SPACE` | 1 | 1 | 25 | true |

#### Willow logs

| Enum | Item | Level | Key | logsPerAction | outputPerLog | XP | canString |
|---|---|---|---|---|---|---|---|
| `WILLOW_ARROW_SHAFTS` | Willow arrow shafts | 30 | `VK_1` | 1 | 45 | 15 | false |
| `WILLOW_SHORTBOW_U` | Willow shortbow (u) | 35 | `VK_2` | 1 | 1 | 33.3 | true |
| `WILLOW_LONGBOW_U` | Willow longbow (u) | 40 | `VK_SPACE` | 1 | 1 | 41.5 | true |

#### Maple logs

| Enum | Item | Level | Key | logsPerAction | outputPerLog | XP | canString |
|---|---|---|---|---|---|---|---|
| `MAPLE_ARROW_SHAFTS` | Maple arrow shafts | 45 | `VK_1` | 1 | 60 | 20 | false |
| `MAPLE_SHORTBOW_U` | Maple shortbow (u) | 50 | `VK_2` | 1 | 1 | 50 | true |
| `MAPLE_LONGBOW_U` | Maple longbow (u) | 55 | `VK_SPACE` | 1 | 1 | 58.3 | true |

#### Yew logs

| Enum | Item | Level | Key | logsPerAction | outputPerLog | XP | canString |
|---|---|---|---|---|---|---|---|
| `YEW_ARROW_SHAFTS` | Yew arrow shafts | 60 | `VK_1` | 1 | 75 | 25 | false |
| `YEW_SHORTBOW_U` | Yew shortbow (u) | 65 | `VK_2` | 1 | 1 | 67.5 | true |
| `YEW_LONGBOW_U` | Yew longbow (u) | 70 | `VK_SPACE` | 1 | 1 | 75 | true |

#### Magic logs

| Enum | Item | Level | Key | logsPerAction | outputPerLog | XP | canString |
|---|---|---|---|---|---|---|---|
| `MAGIC_ARROW_SHAFTS` | Magic arrow shafts | 75 | `VK_1` | 1 | 90 | 30 | false |
| `MAGIC_SHORTBOW_U` | Magic shortbow (u) | 80 | `VK_2` | 1 | 1 | 83.3 | true |
| `MAGIC_LONGBOW_U` | Magic longbow (u) | 85 | `VK_SPACE` | 1 | 1 | 91.5 | true |

### Deferred items (verified = false — hidden unless dev mode)

Full data is in the reference tables below. These are excluded from v1 because wrong key = silent
wrong item. Add them after in-game key verification.

| Item group | Log | logsPerAction | Notes |
|---|---|---|---|
| Wooden stock | Normal | 1 | key `VK_5` [VERIFY] |
| Javelin shafts | Normal | 1 | key `VK_2`; same slot as oak shortbow on normal logs |
| Oak stock | Oak | 1 | key `VK_5` [VERIFY] |
| Oak shield | Oak | **2** | key `VK_4` [VERIFY] |
| Willow stock | Willow | 1 | key `VK_4` [VERIFY] |
| Willow shield | Willow | **2** | key `VK_5` [VERIFY] |
| Teak stock | Teak | 1 | `VK_SPACE`, single-option dialog |
| Maple stock | Maple | 1 | key `VK_4` [VERIFY] |
| Maple shield | Maple | **2** | key `VK_5` [VERIFY] |
| Mahogany stock | Mahogany | 1 | `VK_SPACE`, single-option dialog |
| Yew stock | Yew | 1 | key `VK_4` [VERIFY] |
| Yew shield | Yew | **2** | key `VK_5` [VERIFY] |
| Magic stock | Magic | 1 | key `VK_4` [VERIFY] |
| Magic shield | Magic | **2** | key `VK_5` [VERIFY] |
| Redwood (all) | Redwood | varies | different dialog shape, no bow path |

### Stringing bows (STRING mode)

Bowstring item ID = 1777. All stringing: single-option skillmulti → `VK_SPACE`.
Batch: 14 bowstrings + 14 unstrung = 28 slots.

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

Arrow shafts (`outputPerLog > 1`, `canString = false`) are automatically excluded from
Cut+String mode and String mode item dropdowns.

---

## Modes

```java
enum Mode { FLETCH, STRING, CUT_AND_STRING }
```

- **FLETCH:** withdraw knife + logs, cut, bank output, repeat.
- **STRING:** withdraw 14 bowstrings + 14 unstrung bows, string, bank output, repeat.
- **CUT_AND_STRING:** FLETCH phase → STRING phase → FLETCH phase → …
  - If bowstrings exhausted: fall back to FLETCH-only.
  - Panel filters item dropdown to `canString == true` when this mode is active.

---

## State Machine

```
States: IDLE, BANKING, PROCESSING, ABORTED
```

`setState()` resets all per-state flags (same pattern as PieDishScript).  
A `nextAction` field (`CUT | STRING`) drives what BANKING withdraws and what PROCESSING does.

### Preflight validation (on `start()`, before spawning worker)

```java
if (mode == STRING && !item.canString)           → reject "item cannot be strung"
if (mode == CUT_AND_STRING && !item.canString)   → reject "item cannot be strung"
if (level < item.levelReq)                       → reject "level too low (need X, have Y)"
```

### BANKING tick

1. Open nearest bank (`bank.tryClickBankBoothRandom()`).
2. Gate `bank.bankReady()`.
3. `bank.depositAllInventory()`.
4. **CUT path:**
   - If knife not in inv: `bank.tryWithdrawX(KNIFE, 1)`. No knife in bank → abort.
   - `bank.tryWithdrawX(item.logId, item.logWithdrawCount())` — 26 for shields, 27 otherwise.
5. **STRING path:**
   - `bank.tryWithdrawX(item.unstrungId, 14)`. None in bank:
     CUT_AND_STRING → flip `nextAction = CUT`, restart BANKING. STRING-only → abort.
   - `bank.tryWithdrawX(BOWSTRING, 14)`. None in bank:
     CUT_AND_STRING → flip `nextAction = CUT`, restart BANKING. STRING-only → abort.
6. **Inventory prepared check** (after all withdrawals, before close):
   - CUT: assert `knifePresentInInv && invLogCount >= item.logsPerAction`. Fail → abort.
   - STRING: assert `bowstringCount > 0 && unstrungCount > 0`. Fail → abort.
7. `bank.tryCloseBank()` → `setState(PROCESSING)`.

`bankFailures` counter; abort after 3 consecutive failures.

### PROCESSING tick

**CUT path:**

1. `ensureInventoryTabOpen()` + `dispatcher.isBusy()` guard.
2. `CLICK_INV_ITEM` verb="Use" on knife slot → `awaitIdle(3_000)`.
3. Sleep 100ms (lean settle).
4. Click log slot bounds (resolved fresh on client thread) → `awaitIdle(3_000)`.
5. Set `clicksDone = true`, `skillmultiWaitMs = now`.

Once `clicksDone`:
6. Poll `InterfaceID.Skillmulti.UNIVERSE` visible. Not open after 5s → `dismissMenu()` + reset clicks.
7. `tapKey(item.fletchKey)` — selects and confirms Make-All in one keypress.
8. Set `processingConfirmed = true`, `craftWaitMs = now`.

**Completion check** (replaces `stackable` polling):

```java
int logCount = inventoryCount(item.logId);
boolean done = logCount < item.logsPerAction;  // 0 for bows, <2 for shields, 0 for shafts
```

On done: random AFK pause 2–8s → `setState(BANKING)` with `nextAction` for next phase.  
Timeout after 90s → abort.

**STRING path:**

1. `ensureInventoryTabOpen()` + `dispatcher.isBusy()` guard.
2. `CLICK_INV_ITEM` verb="Use" on bowstring slot → `awaitIdle(3_000)`.
3. Sleep 100ms.
4. Click unstrung bow slot bounds → `awaitIdle(3_000)`.
5. Set `clicksDone = true`.
6. Skillmulti visible → `tapKey(VK_SPACE)` → `processingConfirmed = true`.
7. Completion: `inventoryCount(item.unstrungId) == 0`.
8. AFK pause 2–8s → `setState(BANKING)`.

---

## Break Scheduler

Exact PizzaScript wiring:

```java
private final AtomicBoolean afkBreaksEnabled = new AtomicBoolean(true);  // on by default
private BreakScheduler breaks;
```

- Constructed fresh on each `start()`.
- Safe boundary: entering BANKING (between batches, never mid-fletch).
- Tick loop gate at BANKING entry:

```
breaks.endBreakIfDue(now)
if breaks.isInBreak(now) → status = breaks.statusLine(now); continue
if breaks.isBreakDue(now, state == BANKING) → breaks.startBreak(now); continue
```

- `breaks.disable()` in `stop()`.

---

## Timing Constants

```java
static final long TICK_MS               = 600;
static final long BANK_PACE_MS          = 1_500;
static final long INTER_CLICK_SETTLE_MS = 100;    // lean (vs 350ms in PieDishScript)
static final long POST_BATCH_MIN_MS     = 2_000;
static final long POST_BATCH_MAX_MS     = 8_000;
static final long SKILLMULTI_TIMEOUT_MS = 5_000;
static final long CRAFT_TIMEOUT_MS      = 90_000;
static final int  MAX_BANK_FAILURES     = 3;
```

---

## Panel Controls

- **Mode** dropdown: Fletch / String / Cut+String
- **Item** dropdown: filtered by mode (`canString` filter for String/Cut+String); unverified items hidden unless dev checkbox ticked
- **Auto-advance** checkbox — present but greyed out / no-op in v1 (deferred)
- **Enable breaks** checkbox (on by default) + `breaks.statusLine()` label
- **Dev mode** checkbox — unhides unverified items for testing
- **Start / Stop** button

---

## Error Handling & Abort Conditions

- Preflight: wrong mode+item combo or level too low → reject before starting.
- No knife in bank (CUT mode) → abort.
- Bank supplies exhausted → abort (or CUT_AND_STRING fallback as described).
- Inventory prepared check fails after withdrawals → abort.
- 3× consecutive bank primitive failures → abort.
- 90s craft timeout → abort.
- Level-up popup: auto-dismiss after 3–34s random delay (same as CookingScriptV3).

---

## Auto-Advance (deferred — v2)

Spec placeholder only. Not implemented in v1.

When implemented:
- Trigger on `isLevelUpVisible()`.
- Read `client.getRealSkillLevel(Skill.FLETCHING)`.
- Advance only to `verified == true` items where bank has the required log/unstrung.
- Arrow shafts excluded from auto-advance.
- CUT_AND_STRING: both phases must have materials for the new tier.

---

## What Needs In-Game Verification Before Enabling Deferred Items

1. **Skillmulti key slot positions** for oak/willow/maple/yew/magic stocks and shields — verify
   each [VERIFY] row before setting `verified = true`. Wrong key = silent wrong item.
2. **Shield item IDs** (22251, 22254, 22257, 22260, 22263, 22266) — cross-check via click-inspector.
3. **Teak/Mahogany single-option dialog** — does it auto-confirm on open or require `VK_SPACE`?
4. **Stringing skillmulti** — confirm single option (Space) for all bow types.

---

## Out of Scope

- Arrow tips / feathering / dart tips / bolt tips
- Walking to a specific location
- GE supply buying
- Logout / re-login plumbing
- Redwood logs (different dialog shape, no bow path — separate ticket if needed)
