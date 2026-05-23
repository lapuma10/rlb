# Click-pattern fingerprints — tracking list

Per-pattern register of fingerprintable behaviours in the bot's click pipeline and scripts. Surfaced during the jbane777 ban post-mortem audit (see `2026-05-22-jbane777-ban-postmortem.md`).

Each entry: **Found** = the date the pattern was identified. **Fixed** = the date a verified remediation lands on master (leave empty until then; do not pre-fill).

Severity is relative to anti-cheat detectability: **Critical** = single-pattern smoking gun, **High** = strongly reinforcing, **Medium** = compounding signal, **Low** = minor.

---

## Critical

### 1. Bank close button clicked dead-centre, zero jitter
- **Where:** `runelite-client/src/main/java/net/runelite/client/plugins/recorder/farm/BankInteraction.java:829-852`
- **Pattern:** `findChildWithAction(root, "Close", …)` resolves the X button, then clicks `b.x + b.width/2, b.y + b.height/2`. No margin, no jitter, no random.
- **Evidence:** Log shows `bounds=x=727,y=8,w=26,h=23` 497 times across 48 h → exact click pixel `(740, 19)` every cycle.
- **Found:** 2026-05-22
- **Fixed:**

### 2. UltraCompost craft click — dead-centre of super-compost widget
- **Where:** `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/UltraCompostScript.java:637`
- **Pattern:** Use-ash → use-super dispatch uses `bounds.x + width/2, bounds.y + height/2`. Same widget layout, same pixel, every cycle.
- **Found:** 2026-05-22
- **Fixed:**

### 3. FletchingScript use-knife → use-log clicks — both dead-centre
- **Where:** `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/FletchingScript.java:702` (cut) and `:830` (string).
- **Pattern:** Same `bounds.x + width/2, bounds.y + height/2` for both halves of the use-item-on-item pair. Combined with `inventorySlotOf(...)` (`:1114-1117`) which scans left-to-right deterministically, the knife and the log/bowstring sit at the same inv slot for a given inventory state → same two pixels per cycle.
- **Found:** 2026-05-22
- **Fixed:**

---

## High

### 4. Inv-slot jitter envelope is a fixed 1/6 inset
- **Where:** `runelite-client/src/main/java/net/runelite/client/sequence/dispatch/HumanizedInputDispatcher.java:1877-1882`
- **Pattern:** `marginX = max(1, width/6)`, `marginY = max(1, height/6)`. For a 36×32 inv slot that's a fixed 6/5 px inset on each side → click constrained to a 24×22 px band inside a 36×32 rect, *every call*. Real players use the full rect.
- **Found:** 2026-05-22
- **Fixed:**

### 5. Widget click jitter — fixed 1/4 inset + centre rejection
- **Where:** `runelite-client/src/main/java/net/runelite/client/sequence/dispatch/PixelResolver.java:854-879`
- **Pattern:** `margin = max(1, width/4)` applied to every widget click, plus a hard rejection rule for the geometric centre (`abs(x-cx) <= 1 && abs(y-cy) <= 1`). Means 497 clicks on the same Deposit-All / Withdraw-All button cluster in the same ~20-30 px central band. Heat-mappable.
- **Found:** 2026-05-22
- **Fixed:**

### 6. Game-object centroid jitter is ±6 px around a deterministic centroid
- **Where:** `runelite-client/src/main/java/net/runelite/client/sequence/dispatch/PixelResolver.java:957-983` and constant `CENTROID_JITTER_PX = 6` at `:941`.
- **Pattern:** `sampleNearCentroid()` computes bbox centroid once, jitters ±6 px each axis, retries up to 12 times. For stationary objects (bank booth, banker NPC), the centroid is invariant → 497 clicks land inside a 12×12 px circle on the exact same point.
- **Found:** 2026-05-22
- **Fixed:**

### 7. Menu-row math is formula-derived → "Deposit-All" / "Withdraw-All" always at the same Y offset from menu top
- **Where:** `runelite-client/src/main/java/net/runelite/client/sequence/dispatch/HumanizedInputDispatcher.java:1829-1852`
- **Pattern:** `rowY = menuY + 19 + visualRow*15` with only ±6 px jitter in Y. Deposit-All / Withdraw-All are the last menu entries → identical visualRow → identical pixel offset *relative to wherever the menu opened*. The relative geometry inside the menu is invariant.
- **Found:** 2026-05-22
- **Fixed:**

### 8. UltraCompost has no inter-cycle break, no fatigue, no logout schedule
- **Where:** `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/UltraCompostScript.java:245-272`
- **Pattern:** FSM is `CHECKING_BANK ↔ MAKING_ULTRACOMPOST` with no randomised pause between cycles, no break scheduler (unlike `FletchingScript` which has `BreakScheduler`). Cycle 1 == cycle 497 in structure and timing. 497 cycles in 48 h with no breaks is the dominant volume signal in the ban.
- **Found:** 2026-05-22
- **Fixed:**

### 9. Rooftop agility — recovery always walks to the same tile
- **Where:** `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/RooftopAgilityScript.java:1424` (Draynor `validTiles` def) and `:1166-1170` (`walkToNearestStartTile()`).
- **Pattern:** For Draynor, `walkToNearestStartTile()` always returns `(3103, 3279, 0)`. Every fall, every off-route → walks back from/to the same spot. On a course Jagex monitors heavily, this is highly distinctive.
- **Found:** 2026-05-22
- **Fixed:**

### 10. Rooftop agility — stage detection is a perfect static lookup, ~100 % success
- **Where:** `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/RooftopAgilityScript.java:566` (uses `course.stageByTile.get(here)`); table built at `:1220-1232`.
- **Pattern:** Stage detection is a static HashMap lookup → the bot never misidentifies a stage, never stands on the wrong tile, never misclicks. Real players occasionally misfire; we never do. Compounds with the fixed recovery target above.
- **Found:** 2026-05-22
- **Fixed:**

---

## Medium

### 11. Mouse movement always Fitts-biased (peak velocity early)
- **Where:** `runelite-client/src/main/java/net/runelite/client/sequence/dispatch/WindMouse.java:76-225`, biasing at `:102`.
- **Pattern:** Cubic Bezier with asymmetric ease, `easeAsym` biased negative → every move peaks velocity early. Real human velocity profiles vary (some slow-then-fast, some flat-then-slow); ours don't.
- **Found:** 2026-05-22
- **Fixed:**

### 12. Click hold-duration always uniform in [40, 80] ms
- **Where:** `runelite-client/src/main/java/net/runelite/client/sequence/dispatch/PressTiming.java:26-47, 66-69`
- **Pattern:** `sampleHoldMs` returns a uniform random value in a fixed 40-point span. Real human button-hold times are broader (20–150 ms) and non-uniform.
- **Found:** 2026-05-22
- **Fixed:**

### 13. Bank flow has a rigid timing skeleton (`BANK_PACE_MS = 1500ms` floor)
- **Where:** `UltraCompostScript.java:91` (`BANK_PACE_MS`), `BankInteraction.java:82,89,93` (`DEPOSIT_VERIFY_TIMEOUT_MS=3000`, `WITHDRAW_VERIFY_TIMEOUT_MS=4000`, `POLL_INTERVAL_MS=80`).
- **Pattern:** Hard floor of 1500 ms between bank actions, fixed 80 ms poll, fixed 650 ms tick → bank-action timestamps quantize to predictable multiples. Cycle-to-cycle duration variance is tiny.
- **Found:** 2026-05-22
- **Fixed:**

### 14. Recent-click rejection ring is a fixed 6 px / 12-click buffer
- **Where:** `runelite-client/src/main/java/net/runelite/client/sequence/dispatch/PixelResolver.java:85-95` (`RECENT_CLICK_HISTORY=12`, `MIN_REPEAT_PX=6`) and `:1006-1015`.
- **Pattern:** Forces successive clicks on the same target to spread, but only by ~6 px. Net effect on a stationary target: tight 12-px-diameter cloud with std dev ~3-4 px. Humans show std dev ~8-12 px.
- **Found:** 2026-05-22
- **Fixed:**

### 15. Rooftop agility — eat / run thresholds fixed per session
- **Where:** `RooftopAgilityScript.java:124, 283, 411, 489, 497`
- **Pattern:** `eatAtHp` set once at start; `runOnAtLeast` rolled once at start and only re-rolled on the falling edge of the run bar (`:489`). Within a 15-lap run the thresholds never drift. Real player thresholds drift with attention/fatigue.
- **Found:** 2026-05-22
- **Fixed:**

### 16. FletchingScript — deterministic deposit/withdraw branch and fixed inter-click timing
- **Where:** `FletchingScript.java:514-528` (deposit branch), `:697` (`INTER_CLICK_SETTLE_MS=100`), `:640` (`400ms` after bank close), `:40` (`BANK_PACE_MS=2500`).
- **Pattern:** Boolean check `inventoryOnlyContains(KNIFE, log, unstrung)` produces the same branch result given the same inv state → every cycle does either "deposit all" or "deposit bows only" with no randomness in the choice. All timing constants are fixed except the level-up dismiss and post-batch pause (both genuinely random and OK).
- **Found:** 2026-05-22
- **Fixed:**

### 17. UltraCompost — ash slot persistence creates a fixed signature within a run
- **Where:** `UltraCompostScript.java:796-801` (re-withdraw only when ash < 54), `:1076-1109` (`inventorySlotOf` scans left-to-right).
- **Pattern:** Ash is withdrawn once and reused across multiple super batches until quantity drops below 54. Within a single bank trip, the ash sits in the same logical slot until depleted. Super is freshly withdrawn each cycle. Signature: "ash always in slot N, super in slot M+1" — predictable inv layout for the entire run.
- **Found:** 2026-05-22
- **Fixed:**

### 18. Rooftop agility — fall/off-route emits a deterministic, observable signature
- **Where:** `RooftopAgilityScript.java:847-855, 870-875`
- **Pattern:** Fall to ground → log line `"fell to ground at … — resetting progression"` → walk to fixed start tile. The recovery sequence is identical every time; only the 1200-2500 ms delay before resuming clicks is randomised.
- **Found:** 2026-05-22
- **Fixed:**

---

## Low

### 19. RooftopAgility — spam-guard retry uses fixed 300 ms (not random)
- **Where:** `RooftopAgilityScript.java:372-379`
- **Pattern:** When the same stage is re-clicked without progress, the next retry is paced by a fixed 300 ms — distinctive "retry pause" signature if click fails. The non-failing 600-1200 ms spacing at `:686-687` is random.
- **Found:** 2026-05-22
- **Fixed:**

### 20. Banker NPC selection is deterministic when one is adjacent
- **Where:** `BankInteraction.java:664-666, 724-726` (adjacent pick), `:745-750` (random pick when none adjacent).
- **Pattern:** If any banker is within 1 tile, *that* banker is always chosen (no rotation). For static script locations (Lumbridge / GE), the same NPC index gets clicked every cycle (logs confirm: 25085 for the whole 2026-05-21 session, 25092 for 2026-05-22 after spawn reset).
- **Found:** 2026-05-22
- **Fixed:**

---

## Already OK — don't break these while fixing the above

These were checked during the same audit and found to be genuinely random / non-fingerprintable. Listed here so future fixes don't accidentally regress them.

- `ThreadLocalRandom.current()` used throughout — no seeded determinism across the process.
- `PressTiming.java:59-76` — pre/post-click delays use real per-call randomness (the *range* is the issue, not the sampling).
- `WindMouse.java:129-134` — per-path Bezier control-point offsets are Gaussian-sampled, so consecutive moves to the same target use different curves.
- `PixelResolver.java:221-231, 294-305` — tile / ground-item sampling uses rejection sampling across the full bounding box, genuinely uniform.
- `FletchingScript.java:947-949` — level-up dismiss delay genuinely random in [3 s, 34 s]; logs confirm.
- `FletchingScript.java:903-909` — post-batch pause genuinely random in [2 s, 8 s].
- `FletchingScript.java:50-63, 403-442` — has a real `BreakScheduler` with idle-budget capping. Model for other scripts to follow.
- `BankInteraction.java:745-750` — non-adjacent banker pick is uniform random over candidates.
- `BankInteraction.java:1494-1517, 1651-1712` — bank/inv slot bounds are derived live from the widget tree, not hardcoded.
- `RooftopAgilityScript.java:472-473, 686-687, 814-815, 936` — per-event delays are random in fixed ranges (the range fixedness is medium severity, but the sampling itself is fine).
