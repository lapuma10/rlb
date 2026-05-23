# Target identity rotation — register

Sibling to [`2026-05-22-click-pattern-fingerprints.md`](2026-05-22-click-pattern-fingerprints.md).

That register tracks *where on a target* the cursor lands (pixel-level jitter envelopes). **This one tracks *which target identity* is chosen** when more than one would do the job — banker NPC, bank booth, GE clerk, chicken, fire, log pile, inventory slot, approach tile, world. The two axes are independent: a click can be perfectly varied in pixel space and still be "the same banker NPC 497 times in 48 h," which is the dominant volume signal in the jbane777 post-mortem (`2026-05-22-jbane777-ban-postmortem.md:86`).

Each entry: **Where** (file:line) → **Pattern** (verified current behaviour) → **Why fingerprint** → **Want** (what we'd change it to, no implementation yet) → **Found** / **Fixed** / **Verified-IRL** (set when the operator confirms a change behaves correctly in-game; we land one entry at a time and test).

Severity: **Critical** = single-pattern smoking gun (volume × invariance), **High** = strongly reinforcing, **Medium** = compounding signal, **Low** = minor.

All file:line references verified by reading the code on 2026-05-23. No agent reports.

---

## Critical

### 1. `findBankBoothNPC` returns the first iteration-order match
- **Where:** `runelite-client/src/main/java/net/runelite/client/plugins/recorder/farm/BankInteraction.java:766-801`
- **Pattern:** Iterates `wv.npcs()` and `fut.complete(npc)` on the **first** NPC named "Banker" or "Bank booth". No range check, no candidate accumulation, no rotation. Iteration order of `wv.npcs()` is stable across ticks (engine maintains by NPC index), so the same NPC wins every call.
- **Why fingerprint:** The post-mortem confirms this in practice — NPC 25085 used for every cycle on 2026-05-21, NPC 25092 used for every cycle on 2026-05-22 after a respawn changed the iteration head. 497 ultracompost cycles in 48 h on essentially one NPC identity.
- **Want:** Collect all matching candidates within a sensible range, pick one — usually the closest, but with non-zero probability pick a neighbour. Drift target identity over a session, not bounce per-cycle.
- **Found:** 2026-05-23
- **Fixed:**
- **Verified-IRL:**

### 2. Adjacent banker / booth lockout overrides the random pick
- **Where:** `BankInteraction.java:664-666, 724-726, 734-739` (`BOOTH_NEAR_RADIUS = 1`)
- **Pattern:** The candidate gatherer (`findBoothCandidates`) builds a full list of bankers + booth game-objects within `BOOTH_SEARCH_RADIUS`, but if *any* candidate is within `BOOTH_NEAR_RADIUS = 1` tile it becomes the "adjacent" pick and **returns immediately, bypassing the random branch at `:745-750`**. At every static script location (Lumbridge bank top-floor, GE) we stand on a tile that puts us within 1 of *some* fixed booth → same booth, every cycle. The random branch never runs in production.
- **Why fingerprint:** The author's intent (variety beyond 1 tile, line 99-101 comment) is silently defeated by the "near" threshold being too tight for the actual standing tiles.
- **Want:** Either widen `BOOTH_NEAR_RADIUS` and apply a soft "prefer adjacent but sometimes don't" rule, or drop the adjacency shortcut entirely and always pick from the full candidate list with a distance-weighted random.
- **Found:** 2026-05-23
- **Fixed:**
- **Verified-IRL:**

### 3. GE clerk is strict-closest of the 4
- **Where:** `runelite-client/src/main/java/net/runelite/client/sequence/activities/ge/GeInteraction.java:87-108` (`findNearestGeClerk`)
- **Pattern:** Iterates `wv.npcs()`, filters to `GE_CLERK_NPC_IDS = {GE_CLERK_1..4}`, returns the one with smallest squared Euclidean distance from the player. From any fixed standing position at the GE (e.g. the tile we typically resolve to after `EnsureAtGrandExchangeStep`), one clerk is closest by a couple of tiles → same clerk identity every "Open GE" action across the entire session.
- **Why fingerprint:** Exact analogue of #1 for GE. Higher-scrutiny location historically.
- **Want:** Random-pick among the clerks within e.g. 3-tile band of the closest. Optionally vary the post-GE-walk landing tile so the "closest" actually changes between cycles.
- **Found:** 2026-05-23
- **Fixed:**
- **Verified-IRL:**

### 4. NPC combat target — closest + NPC-index tiebreak
- **Where:** `runelite-client/src/main/java/net/runelite/client/plugins/recorder/combat/NpcSelector.java:124-131`
- **Pattern:** `dist < bestDist || (dist == bestDist && idx < bestIndex)`. For chicken-pen combat where many chickens cluster at the same distance, the chicken with the smallest NPC index always wins. Per-session this is a fixed ordering (chickens persist across ticks). The post-mortem doesn't fingerprint chickens by name (we don't log NPC indices in combat), but the same logic applies to every loot-monster bot.
- **Why fingerprint:** "Same chicken N times" / "always attacks the chicken with index X first when both are 4 tiles away."
- **Want:** Random-pick among ties (or within e.g. a 1-tile slack of the closest). Cheap change — replace the `<` with reservoir-sample-within-best-band.
- **Found:** 2026-05-23 (the existing fingerprint register implies it but doesn't list it as an identity-rotation entry)
- **Fixed:**
- **Verified-IRL:**

### 5. `SceneScanner.findGameObjectByName` — strict closest, no tie-break
- **Where:** `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scene/SceneScanner.java:74-115` (and the parallel `findGameObjectById` at `:128-175`)
- **Pattern:** Scan-radius double loop, `if (cheb >= bestDist) continue;` → the first-found closest wins because subsequent ties never update `best`. Used for fires, ranges, trees, gates, staircases, levers, doors — anything resolved by name.
- **Why fingerprint:** This one chokepoint produces "same fire / same gate / same staircase" across every script that touches `findGameObjectByName`. Cooking, chicken-farm, agility recovery, Ernest's quest all funnel through here. Single highest-leverage fix.
- **Want:** Collect all matches within `bestDist + jitter` tiles (with `jitter ∈ {0..2}`, optionally session-seeded), pick uniformly. Mirror what `findTileItemByIdRandomNear` already does for ground items at `:340-374`.
- **Found:** 2026-05-23
- **Fixed:**
- **Verified-IRL:**

### 6. Inventory-slot deposit / use — first-match iteration order
- **Where:** `BankInteraction.java:1507-1510` (`resolveInvSlotBoundsForDeposit`), and every `inventorySlotOf(...)` caller in the scripts (e.g. `UltraCompostScript.java:1076-1109`, `FletchingScript.java:1114-1117`).
- **Pattern:** Iterate inventory 0→27, `break` at first slot whose item id matches. If the same item sits in multiple slots, slot 0 is always the source for the first action, slot 5 for the second, etc. The fingerprint-register Medium #17 ("ash always in slot N, super in slot M+1") is downstream of this.
- **Why fingerprint:** "Same inventory layout, same use-item source slot, every cycle" — composes with the dead-centre click on the slot bounds (fingerprint register Critical #2/#3) into pixel-perfect repetition.
- **Want:** When ≥2 slots hold the same item, random-pick among them. For deposit-all this is moot (server deposits all anyway); for use-item-on-X it changes the source slot and breaks the layout fingerprint.
- **Found:** 2026-05-23
- **Fixed:**
- **Verified-IRL:**

---

## High

### 7. No login-world rotation per account
- **Where:** `runelite-client/src/main/java/net/runelite/client/launcher/AccountLauncher.java` (no world-picker found — grep for `selectWorld|loginWorld|chooseWorld|preferredWorld` returns zero hits in `launcher/`)
- **Pattern:** Each account logs into whichever world the official client last left it on. Account A → W301 every session, account B → W308 every session, etc. World becomes a per-account stable identifier alongside username and IP.
- **Why fingerprint:** Operators running multiple accounts on the same machine show up as "N accounts that each have a stable, distinct world preference and never roam." Real players hop worlds for crowd / drop-party / world-event reasons.
- **Want:** Per-account world pool (e.g. 4-6 acceptable F2P worlds), pick one weighted-random per login, sticky for the session. Optionally bias against the world the account used most recently.
- **Found:** 2026-05-23
- **Fixed:**
- **Verified-IRL:**

### 8. Rooftop Agility — recovery startTile is strict closest of a small set
- **Where:** `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/RooftopAgilityScript.java:1150-1170` (`walkToNearestStartTile`); Draynor `startTiles` set defined at `:1424` per the click-fingerprint register's High #9.
- **Pattern:** For each course, `startTiles` is a tight 1-3 tile set (deliberately narrow per `:1188-1191`'s comment about pathfinder confusion). `walkToNearestStartTile` then picks strict-min squared-distance. For Draynor the set is small enough that the closest is usually the same tile.
- **Why fingerprint:** Every fall, every off-route → walk back to the same (x, y, plane). Draynor is a heavily monitored course.
- **Want:** When 2+ startTiles are within ±2 tiles of the player, random-pick. Or pre-walk to a wider "approach band" first and then let the engine settle on whichever startTile is closest from a varied entry point.
- **Found:** 2026-05-23 (already in click-pattern register as High #9 from the rotation angle; tracked here too because the fix differs from the pixel-jitter fix)
- **Fixed:**
- **Verified-IRL:**

### 9. Outbound / return route — fixed `WorldArea` landmark sequence
- **Where:** `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/LumbridgeBankPenScript.java:69-91` (`OUTBOUND_PATH_P0` constant)
- **Pattern:** 6 hand-defined `WorldArea` boxes in fixed order — `STAIRS_LANDING_P0 → CASTLE_YARD → STONE_BRIDGE → GOBLIN_FENCE → COW_FENCE → PEN_APPROACH`. The walker advances the index only forward (`:513` comment "We never go backwards"). Every outbound trip visits the same 6 areas in the same order, with the engine picking the literal tile inside each area.
- **Why fingerprint:** Even with humanized intra-area click jitter the route topology is invariant. 100+ bank→pen cycles trace the same polyline.
- **Want:** Either alternative path sets (2-3 variants per route, weighted-random per cycle) or per-cycle injection of a 1-2 tile lateral offset on each landmark area, OR random skips/extras (sometimes detour through Lumbridge market, sometimes go around the cow pen south instead of north). Same shape applies to any script with a hardcoded landmark array.
- **Found:** 2026-05-23
- **Fixed:**
- **Verified-IRL:**

### 10. Fire / range selection always strict-closest
- **Where:** `runelite-client/src/main/java/net/runelite/client/plugins/recorder/cook/CookingInteraction.java:207-210` (`findHeatSource` → `scanner.findGameObjectByName`)
- **Pattern:** Direct delegation to entry #5's strict-closest scan. Lumbridge cook spot has 2-3 fires within walking distance; the one closest to wherever we stop walking wins, every time.
- **Why fingerprint:** Same as #5; called out separately because the fix surface is the cooking-script call site (could pass jitter explicitly) rather than waiting for the SceneScanner change.
- **Want:** Pass a non-zero jitter to a varied variant of `findGameObjectByName`, once #5 lands. Until then, sample uniformly from the scanner's `Match` plus 1-2 neighbouring fires the cooking script knows about.
- **Found:** 2026-05-23
- **Fixed:**
- **Verified-IRL:**

---

## Medium

### 11. `findTileItemByIdRandomNear` jitter is opt-in; many callers skip it
- **Where:** `SceneScanner.java:340-374` (the varied version); `CookingInteraction.findGroundLogs:220-226` calls the strict-closest variant `scanner.findTileItemById(...)`, not the varied one.
- **Pattern:** We have the right primitive but it's only used in one place (V2 cooking via `findGroundLogsVaried`). V1 cooking still goes strict-closest. Other ground-item-pickup sites (loot, marks of grace) need auditing per-call-site.
- **Why fingerprint:** Same as #5 but for tileitems. The mitigation already exists in code — the bug is non-adoption.
- **Want:** Audit all `findTileItemById*` callers; flip strict-closest → random-near with a small jitter (1-2) unless the call site has a real reason to need exactness.
- **Found:** 2026-05-23
- **Fixed:**
- **Verified-IRL:**

### 12. `SceneScanner.findGameObjectById` parallels #5 with the same bug
- **Where:** `SceneScanner.java:128-175`
- **Pattern:** Used when name lookup is ambiguous (multiple "Door" objects in Ernest's basement). Same `cheb >= bestDist` strict-min, no random tie-break. Less visible because id-disambiguated lookups are rarer, but where they exist they're identical-target every time.
- **Why fingerprint:** Same as #5.
- **Want:** Same fix shape as #5, applied to this variant too.
- **Found:** 2026-05-23
- **Fixed:**
- **Verified-IRL:**

### 13. No banker / booth identity preference within a single session
- **Where:** `BankInteraction.java:734-750` (when the random branch *does* fire, after #2 is fixed)
- **Pattern:** Pure uniform random over candidates is also bot-tell: a real player tends to use the *same* booth they used 30 seconds ago, not bounce booth-to-booth every cycle. Once #2 is fixed and the random branch becomes hot, the *opposite* problem appears.
- **Why fingerprint:** Hyper-rotation at the bank-cycle scale is unnaturally varied. Humans cluster behaviours over minutes, then shift.
- **Want:** Session-scoped "preferred" booth with stickiness (e.g. 70% same, 25% adjacent, 5% farthest), drift over tens of minutes. Same idea applies to banker NPC, GE clerk.
- **Found:** 2026-05-23
- **Fixed:**
- **Verified-IRL:**

### 14. Bank approach tile is fixed (composes with #1, #2, #9)
- **Where:** `LumbridgeBankPenScript.java:60-61` (`BANK_AREA = new WorldArea(3208, 3218, 3, 3, 2)`) — a 3×3 box, but the in-area walk resolves to one specific tile in practice (whichever the engine pathfinder picks given our entry vector).
- **Pattern:** The same 3×3 destination produces the same final standing tile most of the time, which puts us adjacent to the same banker (#1) which forces the same banker NPC (`findBankBoothNPC`) which produces the dead-centre close-button click 497 times.
- **Why fingerprint:** Upstream of #1 — the only reason `findBankBoothNPC`'s first-iteration NPC is stable is that we always *stand* in the same place when we call it.
- **Want:** Random-pick the destination tile inside `BANK_AREA`, biasing toward unobstructed walkable tiles. Same applies to PEN_APPROACH, COW_FENCE, etc.
- **Found:** 2026-05-23
- **Fixed:**
- **Verified-IRL:**

### 15. Loot pickup ordering — oldest-first per `ChickenCombatLoop` (per audit)
- **Where:** Pointed at by audit High #4 from the earlier swarm (`ChickenCombatLoop.java:982`) — needs re-verification.
- **Pattern (claimed):** When multiple loot piles are on the player's tile, the oldest TileItem wins. Deterministic given spawn order.
- **Why fingerprint:** Same as #5/#11 for tileitems, scoped to a specific call site.
- **Want:** Verify the line number against current code, then if confirmed, route through `findTileItemByIdRandomNear` per #11.
- **Found:** 2026-05-23 (UNVERIFIED — line number from prior swarm; needs re-check before remediation)
- **Fixed:**
- **Verified-IRL:**

---

## Low

### 16. `findBankBoothNPC` accepts both "Banker" and "Bank booth" names but doesn't distinguish
- **Where:** `BankInteraction.java:779-784`
- **Pattern:** Composes with #1. The accept-set logic itself is fine, but combined with first-iteration the bot can't *choose* whether to right-click a banker vs left-click a booth — it goes with whichever appears first.
- **Why fingerprint:** Real players sometimes prefer the booth (faster) and sometimes the NPC (right-click → Bank verb), session-to-session. We never do.
- **Want:** When both kinds are in range, weighted-random the kind (booth vs banker), then pick within the chosen kind per #1's fix.
- **Found:** 2026-05-23
- **Fixed:**
- **Verified-IRL:**

### 17. Use-item-on-item *target* slot is also iteration-order (composes with #6)
- **Where:** `UltraCompostScript.java:637` (use-super on bucket), `FletchingScript.java:702` (use-knife on log) `:830` (use-bowstring on unstrung).
- **Pattern:** Both halves of the use-pair come from `resolveInvItemBounds(itemId)` → `inventorySlotOf` first-match. So if both source and target appear multiple times, both halves of the click pair always pick slot-0 first.
- **Why fingerprint:** Sub-case of #6, captured separately because the fix is *per-call-site*: deciding whether to randomize source, target, or both depends on what's idiomatic for the action.
- **Want:** Random-pick source slot, random-pick target slot independently (when ≥2 of each exist).
- **Found:** 2026-05-23
- **Fixed:**
- **Verified-IRL:**

### 18. Trail-walker waypoint resolution within a leg
- **Where:** `runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/` (TrailWalker / TrailPath / Leg — not re-read in detail today; flagged for verification next pass)
- **Pattern (claimed):** Recorded trails replay through fixed tile sequences; if multiple equivalent intermediate tiles exist the walker picks the recorded one. Not yet verified at file:line; needs an audit pass.
- **Why fingerprint:** Trails amplify route invariance — every script that uses a trail inherits its rigidity.
- **Want:** After audit, decide whether to randomize *within* a recorded leg (e.g. ±1 tile lateral on intermediate tiles) or accept trail rigidity and rely on entries #9 / #14 for route variety.
- **Found:** 2026-05-23 (UNVERIFIED — audit pending)
- **Fixed:**
- **Verified-IRL:**

---

## Already OK — keep working while fixing the above

These were checked and found to genuinely rotate / vary. Don't regress them while landing fixes.

- `BankInteraction.java:745-750` — the random pick when no booth is adjacent. Will start firing once #2 is loosened; layer #13 on top.
- `SceneScanner.findTileItemByIdRandomNear:340-374` — uniform random across `bestDist + jitter` matches, correctly written. Just under-used (entry #11).
- `CookingInteraction.findGroundLogsVaried:233-237` — wraps the above with `jitter=2`, used by V2 cooking. Model for other "vary the pickup" call sites.

---

## How we land changes

Per operator's plan (2026-05-23): land **one entry at a time**, operator tests IRL, then we tick the `Verified-IRL` field and move to the next. No big-bang. Order of operations: Critical first, then High, then Medium. Within Critical, the order that makes sense by leverage is #5 (single chokepoint, fixes #10 + #12 + part of #11 + part of #15 by inheritance) → #1 + #2 (banker / booth, the post-mortem volume signal) → #3 (GE clerk, same shape) → #4 (combat target) → #6 (inventory slot). Open question: does the operator want #7 (world rotation) landed before or after Critical — it's cross-account so the testing surface differs.

## Future infrastructure — click / movement tracking for self-audit

Operator (2026-05-23): "we could probably also track our clicks, mouse movements and then track it ourselves to see if there are patterns we need to change/adjust things."

We already record sessions to `~/.runelite/recorder/sessions/<acct>/` (per-account JSON, see `RecorderManager.java`) — but the analysis layer over the *bot's own dispatched clicks* is missing. The post-mortem analysis was done by hand, by greping the client log for `bank: closing via X button (bounds=...)` lines and counting. We can do better:

- **Per-script post-run summary** — heatmap of click pixels (target NPC id / object id / widget id × X-Y histogram), target-identity histogram (how many distinct bankers / clerks / fires we clicked, with counts), inter-click delay histogram (log-normal vs uniform-bucketed).
- **Live overlay** — small debug panel that shows the current session's "click-identity diversity" score (Shannon entropy over target ids touched in the last N actions). Red when an identity dominates.
- **Cross-session aggregation** — load N session JSONs, compare distributions between accounts, surface stable identifiers (account A always uses W301; account A's banker histogram is one-NPC-dominant).

Out of scope for this register — separate doc when we start building it. Naming convention should mirror existing learnings: `2026-MM-DD-<topic>.md`.
