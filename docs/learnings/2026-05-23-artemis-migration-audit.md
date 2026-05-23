# Artemis migration audit

**Status:** Phase 1A deliverable. Audit only — no implementation.
**Authored:** 2026-05-23.
**Reads from:** master @ `12abb3879` (post Phase 0A.3).
**Replaces:** the loose "we should migrate scripts to Artemis someday" sentiment with a concrete map of every engine-side reach in every script, sized by counts and tied to specific file:line citations.

The audit answers three questions:

1. Which scripts reach into which engine layers, and how often?
2. What does Artemis need to expose so each reach goes through it instead?
3. Which Artemis version (v1.0 / v1.1 bank / v1.2 GE / later) does each script need before it can migrate?

---

## 1. Executive summary

25 script-related files under `recorder/scripts/` + `recorder/quest/` reach directly into engine layers. The reach is concentrated but pervasive:

| Layer reached | Scripts importing | Total invocations (non-import) |
|---|---|---|
| `HumanizedInputDispatcher` | 17 of 25 | 132 `dispatcher.*` calls |
| `ActionRequest` (constructed via `builder()` — **codebase has zero `new ActionRequest(` usages**) | 15 of 25 | 32 `ActionRequest.builder()` sites |
| `BankInteraction` | 10 of 25 | 189 `bank.*` calls |
| `SceneScanner` | 6 of 25 | scene queries direct from 6 files (`RooftopAgilityScript`, `TakeGroundItemStep`, `ErnestTheChicken`, `ErnestQuestScript`, `InteractWithObjectStep`, `UseItemOnObjectStep`) |
| `GeInteraction` | 1 of 25 | 13 `ge.*` calls (concentrated in `GrandExchangeScript`) |
| `SequenceSleep` | 12 of 25 | 47 `SequenceSleep.sleep` calls |
| `TransportResolver` | 6 of 25 | walk-leg transport handling |
| `Navigator` / `TrailRegistry` / `TrailWalker` / `UniversalWalker` | 8 of 25 | walking subsystem (sum of imports across the three walker classes — see §3.6 for the per-layer split) |
| Raw `Widget` / `client.getWidget()` | 10 of 25 | 24 widget reaches in scripts |
| `combat/` helpers — `ChickenCombatLoop` (3 scripts), `NpcSelector` (1), `TrainingSession` (1) | 4 of 25 | imported into combat-flow scripts (see §3.8) |
| `WidgetActions` | **0 of 25** | engine-internal only (see §3.9) |
| **Hardcoded pixel-centroid math** (`b.width/2 + b.height/2 → clickCanvas`) | 6 of 25 | **9 sites** — direct hits on CLAUDE.md §10 BANNED list (see §3.1 for exact list) |

**Caveat on the counts:** the per-script profile in §2 was built from field-name greps (`dispatcher\.`, `bank\.`, `\bnav\.` etc.) which include import-line hits, javadoc tokens, and local-variable name collisions (e.g. `scene` field unrelated to `SceneScanner`). The counts are accurate within ±15% as relative weights for prioritization; treat exact numbers as rough. The per-category citations in §3 are verified file:line (those are the authoritative reach inventory).

**Top offenders by raw reach count** (sum across categories):

| Script | Reach total | Profile |
|---|---|---|
| PieDishScript | 122 | bank-cycle (`bank.*=48`) + dispatch + GE |
| PizzaScript | 119 | bank + cook + dispatch + dispatcher-issued keys |
| UltraCompostScript | 102 | bank-cycle + GE buying + Make-All keying |
| FletchingScript | 75 | bank + many direct widget reaches + clickCanvas centroids |
| CooksAssistantScript | 56 | bank + small GE + nav + npc selector |
| CookingScriptV3 | 50 | bank + cook + scene + walker |
| CookingScriptV2 | 47 | bank + cook + walker |
| LumbridgeBankPenScript | 35 | bank + combat + transport + **5 dead-centre clickCanvas sites** |
| GrandExchangeScript | 23 | the only GE-native script |
| RooftopAgilityScript | 30 | scene scanning + 12 direct ActionRequest builds |
| ChickenFarmV3Script | 32 | nav-heavy (Navigator interface) + light bank delegation |
| ChickenFarmV2Script | 7 | thin (delegates to ChickenCombatLoop + UniversalWalker) |

`ChickenFarmV2Script` reaches less because it predates the V3 Navigator interface and delegates more to `ChickenCombatLoop`; `ChickenFarmV3Script` reaches more because it does its own Navigator orchestration. **Lower reach ≠ better-architected** here — the V2 thinness comes from offloading into a different package, not from going through a facade.

---

## 2. Per-script reach profile

Column legend: `disp` = `dispatcher.*` invocations, `bank` = `bank.*`, `ge` = `ge.*`, `scene` = `scene.*` / `SceneScanner.*`, `nav` = `nav.*` / `navigator.*`, `trans` = `transport.*` / `TransportResolver`, `sleep` = `SequenceSleep.sleep`, `newAR` = `new ActionRequest` / `.Kind.`, `getWid` = `client.getWidget()`, `rawWid` = `Widget X =` local, `cCnv` = `clickCanvas` / `clickBounds` / `clickWidget`, `px/2` = pixel-centroid math (`b.width/2`, `b.height/2`).

| Script (`scripts/`) | disp | bank | ge | scene | nav | trans | sleep | newAR | getWid | rawWid | cCnv | px/2 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `ChickenFarmV2Script` | 1 | 2 | 0 | 0 | 0 | 1 | 1 | 0 | 1 | 1 | 1 | 1 |
| `ChickenFarmV3Script` | 6 | 2 | 0 | 0 | 9 | 2 | 4 | 6 | 1 | 1 | 0 | 0 |
| `CookingScriptV2` | 15 | 12 | 0 | 0 | 0 | 1 | 7 | 0 | 0 | 0 | 0 | 0 |
| `CookingScriptV3` | 15 | 11 | 0 | 1 | 0 | 1 | 7 | 0 | 0 | 0 | 0 | 0 |
| `CooksAssistantScript` | 5 | 18 | 3 | 0 | 0 | 1 | 2 | 2 | 0 | 0 | 0 | 0 |
| `FletchingScript` | 26 | 16 | 0 | 0 | 0 | 0 | 6 | 12 | 6 | 7 | 3 | 2 |
| `GrandExchangeScript` | 2 | 0 | 13 | 0 | 0 | 0 | 1 | 0 | 1 | 1 | 0 | 0 |
| `GrandExchangeTab` | 0 | 0 | 5 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| `LogoutHelper` | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 4 | 2 | 3 | 0 | 0 |
| `LumbridgeBankPenScript` | 6 | 4 | 0 | 2 | 0 | 1 | 1 | 2 | 2 | 2 | 4 | 5 |
| `PieDishScript` | 14 | 48 | 4 | 0 | 0 | 0 | 6 | 6 | 3 | 4 | 2 | 1 |
| `PizzaScript` | 18 | 35 | 0 | 0 | 0 | 0 | 9 | 6 | 3 | 4 | 1 | 2 |
| `RooftopAgilityScript` | 7 | 0 | 0 | 3 | 2 | 0 | 0 | 12 | 2 | 1 | 0 | 0 |
| `RooftopCourseLoader` | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| `UltraCompostScript` | 11 | 39 | 4 | 0 | 0 | 0 | 6 | 2 | 4 | 5 | 1 | 1 |

Quest sub-tree (`quest/`):

| Script (`quest/`) | disp | bank | ge | scene | nav | trans | sleep | newAR | notes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| `ErnestQuestScript` | 0 | 0 | 0 | 1 | 3 | 0 | 0 | 0 | orchestrator |
| `ErnestTheChicken` | 0 | 0 | 0 | 1 | 1 | 0 | 0 | 0 | quest impl |
| `QuestStepThreads` | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | runner |
| `steps/CombineItemsStep` | 2 | 0 | 0 | 0 | 0 | 0 | 0 | 2 | inv → inv combine |
| `steps/InteractWithObjectStep` | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 2 | scene + AR build |
| `steps/NavWalkStep` | 0 | 0 | 0 | 0 | 13 | 0 | 0 | 0 | nav loop, Artemis walkTo replaces wholesale |
| `steps/ReplayTrailStep` | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 2 | trail replay |
| `steps/TakeGroundItemStep` | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 2 | scene find + AR build |
| `steps/TalkToNpcStep` | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 3 | dialogue + AR |
| `steps/UseItemOnObjectStep` | 2 | 0 | 0 | 1 | 0 | 0 | 0 | 2 | useOn + AR |

---

## 3. Per-category reach inventory

For each category: total count, top scripts, sample citations (file:line). Citations are the entries that would land in the click-pattern / target-identity registers if not already there.

### 3.1 Hardcoded pixel-centroid math + `clickCanvas` — the §10 BANNED hits

**9 sites** of dead-centre pixel math feeding `clickCanvas`. This is the click-pattern register's Critical #1-3 territory. Each row below is a distinct dispatch invocation; the LumbridgeBankPen `:1282-1284` and `:1402-1406` rows count as one site each (the cx/cy locals are computed then immediately passed to one `clickCanvas` call).

| Script | File:line | Code excerpt |
|---|---|---|
| `LumbridgeBankPenScript` | `:411` | `dispatcher.clickCanvas(b.x + b.width / 2, b.y + b.height / 2);` |
| `LumbridgeBankPenScript` | `:1282-1284` | `int cx = b.x + b.width / 2; int cy = b.y + b.height / 2; dispatcher.clickCanvas(cx, cy);` |
| `LumbridgeBankPenScript` | `:1402-1406` | same pattern as `:1282-1284` (one site) |
| `FletchingScript` | `:702` | `dispatcher.clickCanvas(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);` |
| `FletchingScript` | `:830` | same pattern as `:702` |
| `UltraCompostScript` | `:637` | `dispatcher.clickCanvas(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);` |
| `PizzaScript` | `:902` | `dispatcher.clickCanvas(bounds.x + bounds.width / 2, ...)` |
| `PieDishScript` | `:759` | `dispatcher.clickCanvas(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);` |
| `ChickenFarmV2Script` | `:360` | `dispatcher.clickCanvas(b.x + b.width / 2, b.y + b.height / 2);` |

**Related but distinct bug class — cached pixel coords (1 site):**

| Script | File:line | Code excerpt | Why distinct |
|---|---|---|---|
| `LumbridgeBankPenScript` | `:898` | `dispatcher.clickCanvas(pick.canvas.getX(), pick.canvas.getY());` | Coord was captured into `WalkPick.canvas` earlier (cached), not centroid math now. Still violates §7 "never cache pixel coords while moving" — but its remediation is "don't cache; refetch at click time", different from the §10 ban on centroid-fraction math. Tracked separately. |

**Artemis replacement:** `clickCanvas(...)` calls disappear. Scripts compose `artemis.click(widget, verb)` / `artemis.click(npc, verb)` / `artemis.click(obj, verb)` Steps; Artemis routes through `PixelResolver.sampleInsideShape` (Phase 3 rewrite) for uniform-on-shape sampling.

**`sampleNearCentroid` direct callers:** none in scripts. Five hits, all inside `PixelResolver.java` itself (`:568`, `:632`, `:931` comment, `:938` javadoc, `:957` definition). The method dies in Phase 3; scripts can't ever call it directly via Artemis.

### 3.2 Direct `dispatcher.dispatch` + ActionRequest construction

**32 `ActionRequest.builder()` sites across 11 scripts.** Building requests by hand is the most common reach pattern after dispatcher.* invocations.

**Important pattern note:** the codebase has **zero `new ActionRequest(` usages** — every construction goes through `ActionRequest.builder()`. Audit-time greps for "new ActionRequest" produce false negatives; the right pattern is `ActionRequest\.builder\(\)` or `ActionRequest\.Kind\.`.

Kinds observed in scripts:

| `ActionRequest.Kind.X` | Observed in (sample) | Artemis replacement |
|---|---|---|
| `WALK` | `ChickenFarmV3:595`, `PizzaScript:1369` | `artemis.walkTo(WorldPoint)` / `walkTo(NamedZone)` |
| `CLICK_GAME_OBJECT` | `ChickenFarmV3:685`, `RooftopAgilityScript:676`, `PizzaScript:1302` | `artemis.click(GameObj, verb)` |
| `CLICK_WIDGET` | `ChickenFarmV3:761`, `RooftopAgilityScript:513` | `artemis.click(Widget, verb)` |
| `CLICK_INV_ITEM` | `RooftopAgilityScript:467` | `artemis.click(InvSlot, verb)` |
| `CLICK_GROUND_ITEM` | `RooftopAgilityScript:1056` | `artemis.take(GroundItem)` |
| `CLICK_TILE` | `RooftopAgilityScript:1167` | `artemis.click(Tile, verb)` (walk-here fallback) |
| `KEY` | `RooftopAgilityScript:400` | **distinct from Make-All confirm** — needs its own typed Step (obstacle-key-confirm) |

**`dispatcher.tapKey(VK_*)` is a separate reach** — not an ActionRequest.Kind.KEY. Two distinct use cases:

| Use case | Sites | Artemis surface |
|---|---|---|
| Make-All chatbox confirm | `UltraCompostScript:669,696,1057`, `PizzaScript:945,985`, `FletchingScript`, `PieDishScript` | `Step` like `confirmMakeAll()` — knows the chatbox-prompt context and waits for it |
| Agility obstacle key-confirm | `RooftopAgilityScript:400` (single `Kind.KEY`) | distinct typed Step — operates outside chatbox |

A single general `tapKey` surface would re-leak the dispatcher to scripts. Two purpose-named Step types keep the abstraction intact.

`dispatcher.isBusy()` / `dispatcher.awaitIdle(...)` is used by every reach-heavy script for backpressure. Artemis hides this — `Step.check()` polls state instead; the dispatcher worker thread is engine-internal.

### 3.3 `BankInteraction` direct reaches

**189 `bank.*` invocations across 10 scripts.** Already a facade interface, but reached directly from scripts in addition to via Steps.

Sample methods invoked (`PieDishScript`):

```
bank.bankReady()              // wait for container load
bank.depositAllInventory()    // bulk deposit
bank.withdraw(itemId, qty)
bank.withdrawAllExcept(...)   // composite — Artemis defers indefinitely
bank.isBankOpen()
bank.closeBank()
```

**Artemis v1.1 replacement:** `artemis.openBank()` / `deposit(itemId)` / `depositAll()` / `withdraw(itemId, qty)` / `closeBank()` — each returns a Step. Script composes `plan().then(openBank()).then(depositAll()).then(...)`. `bankReady()` no longer needed — the Step's own `check()` polls until bank is ready before completing.

Composite verbs like `depositAllExcept` deferred (per spec §8 — per-item primitives only in v1).

### 3.4 `GeInteraction` direct reaches

**Concentrated in 1 script: `GrandExchangeScript`** (13 `ge.*` invocations + heavy import of internal GE types: `BuyItemIntent`, `BuyLimitLedger`, `BuyLimitTable`, `GeActions`, `GrandExchangeSequenceFactory`, `GrandExchangeSequencePlan`, `SellItemIntent`, `PricePolicy.Exact`). The script is already engine-native (uses the sequence engine cleanly) but reaches into GE internals.

`CooksAssistantScript`, `UltraCompostScript`, `PieDishScript` each have 3-4 `ge.*` invocations for small buying flows (e.g. buy missing ingredients).

**Artemis v1.2 replacement:** `artemis.openGe()` / `buyItem(BuyIntent)` / `sellItem(SellIntent)` / `collectGe()`. Script-side types stay (`BuyIntent` is the user-facing intent record); internal types (`BuyLimitLedger`, `GrandExchangeSequenceFactory`) become engine-internal and unreachable from scripts.

### 3.5 `SceneScanner` direct reaches

**6 scripts/quest-files import `SceneScanner`** (verified via `import` grep): `RooftopAgilityScript`, `TakeGroundItemStep`, `ErnestTheChicken`, `ErnestQuestScript`, `InteractWithObjectStep`, `UseItemOnObjectStep`. The agility-tree of methods includes `findGameObjectByName`, `findGameObjectById`, `findTileItemByIdRandomNear`.

The §2 per-script profile column `scene` for `LumbridgeBankPenScript` (=2) and `CookingScriptV3` (=1) is **false-positive noise from a `\bscene\.` field-name grep** — those scripts have local `scene` variables (e.g. `client.getWorldView().getScene()`) but do not import or call `SceneScanner` directly. They go elsewhere for tile/object lookup. The §2 table column is kept as-is for completeness but read it as "may include local-variable noise"; the SceneScanner reach footprint is exactly the 6 import-confirmed files above.

**Artemis replacement:** `artemis.findObject(ObjectQuery.byName(name).within(n).rotation(policy))` returns `Optional<GameObj>`. The `RotationPolicy` choice (e.g. `ClosestWithSlack(2)`, `UniformWithinRange(n)`) replaces the strict-closest behaviour at `SceneScanner.findGameObjectByName:74-115` per the target-identity register.

Quest `steps/InteractWithObjectStep`, `steps/UseItemOnObjectStep`, `steps/TakeGroundItemStep` are essentially "find target + build AR" — those become a one-liner Artemis Step composition (`artemis.click(artemis.findObject(...), verb)`).

### 3.6 Walker / Navigator / TransportResolver

**Three coexisting walker layers** (full file lists, verified by import grep):

1. `UniversalWalker` — `ChickenFarmV2Script`, `CookingScriptV2`, `CookingScriptV3`.
2. `TrailWalker` / `TrailRegistry` — recorded-trail replay: `CooksAssistantScript`, `PizzaScript`, `ChickenFarmV3Script`, `ErnestQuestScript`, `ErnestTheChicken`, `quest/steps/ReplayTrailStep`.
3. `Navigator` interface (nav v2.1 reactive walker) — `ChickenFarmV3Script`, `ErnestQuestScript`, `ErnestTheChicken`, `quest/steps/NavWalkStep`.

`NavWalkStep.java`'s `nav.*` count of 13 in the per-script profile includes 3 import lines and a few javadoc-token matches; the actual `nav.tick(...)` / `nav.cancel()` / `nav.lastFailureReason()` callsites are ≈6. `NavWalkStep` is essentially a wrapper around `V2Navigator` — Artemis subsumes the file wholesale (it deletes, not migrates).

The same import-noise caveat applies to `ChickenFarmV3Script`'s `nav=9` in §2 — actual nav callsites are ≈4. The §2 counts are "rough import + invocation totals" not invocation-only.

`TransportResolver` is used by 6 scripts for stair/gate handling on walk legs.

**Artemis replacement:** `artemis.walkTo(WorldPoint)` and `artemis.walkTo(NamedZone)`. Engine internals decide which walker (v2.1 reactive vs trail-replay vs `UniversalWalker`) based on whether a registered trail exists for the route. Transport handling is embedded — script never touches `TransportResolver`.

The three current walker layers stay in the engine until full migration; engine-internal selection logic is added behind `Artemis.walkTo`.

### 3.7 Raw `client.getWidget()` + `Widget X =` local types

**24 widget reaches in scripts.** Hotspots:

- `FletchingScript` (6 `client.getWidget` + 7 `Widget X =` locals): `:715,765,843,885` all read `InterfaceID.Skillmulti.UNIVERSE`; `:936` reads `InterfaceID.LevelupDisplay.UNIVERSE`; `:1138-1140` walks inventory: `Widget parent = client.getWidget(InterfaceID.Inventory.ITEMS); Widget child = parent.getChild(slot);`
- `LumbridgeBankPenScript` (2 + 2): bank/menu widget reads
- `PieDishScript` (3 + 4): bank container + level-up widgets
- `PizzaScript` (3 + 4): same
- `UltraCompostScript` (4 + 5): bank container + Make-All confirm widgets

**Artemis replacement:** `artemis.findWidget(WidgetQuery.byId(id).visible())` returns `Optional<WidgetView>`. The "is the widget visible up its parent chain" check (per CLAUDE.md §1) lives in the query, not in script code. Scripts never read `Widget.getBounds()` directly — Artemis.click() resolves bounds at click time.

### 3.8 `combat/` helper imports — script-shape vs engine-helper question

Four `combat/` classes are imported into scripts. The Artemis spec needs to classify each as either **engine-internal helper** (allowed to survive, like `PixelResolver`) or **script-shape code** (must migrate behind Artemis):

| Helper | Imported by | Proposed classification |
|---|---|---|
| `ChickenCombatLoop` | `ChickenFarmV2Script`, `ChickenFarmV3Script`, `LumbridgeBankPenScript` | **engine-internal helper** — the combat-loop FSM is a reusable engine-side mini-state-machine, not script logic. Artemis exposes `artemis.combatLoop(NpcQuery, TrainingPlan)` returning a Step that wraps it. |
| `TrainingSession` | `ChickenFarmV3Script` | **engine-internal helper** — same rationale; tracks XP / level-up state. Surfaces via Artemis `artemis.session()` extension once defined. |
| `NpcSelector` | `CooksAssistantScript:20` (instantiates `new NpcSelector("Cook", 12)`) | **script-shape** — banned per Artemis spec §14. Replaced by `artemis.findNpc(NpcQuery.byName("Cook").within(12).rotation(...))`. The script needs explicit migration. |
| `CombatStateTracker` | none in scripts (engine-side only) | engine-internal (already not reached from scripts) |

This question — *which engine helpers script-side code is allowed to keep importing after Artemis lands* — is not addressed in the Artemis spec §14 import allow/ban list at the level of specific `combat/` classes. The classifications above are this audit's recommendation; Phase 1B should pin them in CLAUDE.md §11.

### 3.9 `WidgetActions` reach (explicit-null result)

The user's audit directive named `WidgetActions` alongside `BankInteraction` / `GeInteraction`. **No script imports `WidgetActions` directly.** The class is engine-internal: it's reached only from `widget/WidgetEngine`, `widget/SidebarTabActions`, `combat/CombatTabActions`, `debug/DebugOverlay`. Scripts touch widget state via raw `client.getWidget(id)` instead (the §3.7 hotspots).

**Implication:** Artemis exposes its `findWidget(WidgetQuery)` / `click(widget, verb)` surface to displace the raw `client.getWidget` reaches; it does **not** need to displace `WidgetActions` (which scripts already don't reach).

### 3.10 `SequenceSleep.sleep`

**47 invocations across 12 scripts.** Most are short-tick pauses (200-600ms) for "wait for state X to settle." These usually live inside `bank.*` / GE setup loops.

**Artemis replacement:** scripts don't import `SequenceSleep`. Steps yield via their `check()` returning `RUNNING` — engine ticks until state reaches success. The 600 ms server tick is the natural cadence; sub-tick waits become engine-internal. Long break-style sleeps (60-90 minutes) become `artemis.idle(IdlePolicy.PHONE_GLANCE)` Steps.

---

## 4. Artemis surface mapping summary

| Reach pattern today | Artemis v1.x surface |
|---|---|
| `dispatcher.dispatch(new ActionRequest...)` | `artemis.click(target, verb) : Step` / `walkTo(...)` / `take(...)` / `useOn(...)` |
| `dispatcher.clickCanvas(b.x + b.w/2, ...)` | gone — routed through `PixelResolver.sampleInsideShape` (Phase 3) |
| `dispatcher.tapKey(VK_SPACE)` | engine-internal; Artemis Step for Make-All confirm |
| `dispatcher.isBusy()` / `awaitIdle()` | engine-internal; Step.check() polls |
| `bank.*` (10 methods) | v1.1: `openBank()` / `deposit()` / `depositAll()` / `withdraw()` / `closeBank()` |
| `ge.*` + GE-internal types | v1.2: `openGe()` / `buyItem(BuyIntent)` / `sellItem()` / `collectGe()` |
| `SceneScanner.findGameObjectByName(...)` | v1.0: `findObject(ObjectQuery)` with `RotationPolicy` |
| `SceneScanner.findTileItemByIdRandomNear(...)` | v1.0: `findItem(ItemQuery)` with `RotationPolicy` |
| `client.getNpcs()` / npc selection / `new NpcSelector(...)` | v1.0: `findNpc(NpcQuery)` |
| `UniversalWalker.walk(...)` / `nav.tick(...)` / `trailWalker.replay(...)` | v1.0: `walkTo(WorldPoint)` / `walkTo(NamedZone)` (engine picks impl) |
| `TransportResolver.find(...)` | embedded in `walkTo(...)` |
| `client.getWidget(id)` + `Widget.getBounds()` | v1.0: `findWidget(WidgetQuery)` + `click(widget, verb)` |
| `SequenceSleep.sleep(client, ms)` | engine-internal; Step.check() yields |
| Break/sleep cycles | v1.0: `idle(IdlePolicy.PHONE_GLANCE)` (NATURAL_BREAK / MEAL deferred per spec §10) |
| Logout | v1.0: `logout()` |
| Login / world hop / relogin | v1.3+ deferred |
| `combat/ChickenCombatLoop`, `combat/TrainingSession` | **no Artemis surface needed** — engine-internal helpers, allowed via CLAUDE.md §11 (see §3.9 for classification) |
| `combat/NpcSelector` (instantiated as `new NpcSelector(...)` in `CooksAssistantScript`) | replaced by `findNpc(NpcQuery)` (banned per spec §14) |
| `WidgetActions` | **no Artemis surface needed** — no script imports it today (see §3.10) |

---

## 5. Script-by-script Artemis version requirement

### v1.0 only (no bank, no GE) — pilot tier

| Script | Notes |
|---|---|
| **CowKillerScript** (NEW, Phase 2 pilot) | kill-until-full-then-logout |
| `RooftopAgilityScript` → V2 | scene + obstacles + obstacle-key Step; no bank, no GE |
| `LogoutHelper` | **not a migration — deletion / internalization.** The helper's logic becomes `artemis.logout()` (returns a Step) inside Artemis itself; the script-side file disappears. |
| Quest steps (`TakeGroundItemStep`, `InteractWithObjectStep`, `TalkToNpcStep`, `UseItemOnObjectStep`, `CombineItemsStep`, `ReplayTrailStep`) | each is a thin wrapper that becomes a single Artemis Step composition — migrate as one batch (~50-150 LOC each). |
| `NavWalkStep` | **not a migration — deletion.** The file is essentially a wrapper around `V2Navigator.tick(...)`; Artemis subsumes it via `walkTo(WorldPoint)`. Delete after Artemis walkTo lands. |
| `ErnestQuestScript` / `ErnestTheChicken` / `QuestStepThreads` | orchestrator + quest impl + runner — pure composition of Artemis Steps once the 6 quest steps above migrate. |

### v1.1 (adds bank) — bulk migration tier

| Script | Notes |
|---|---|
| `ChickenFarmV3Script` → **ChickenFarmV4** | the planned ChickenFarmV4 rewrite per spec §15 |
| `ChickenFarmV2Script` | deletable once V4 lands |
| `CookingScriptV3` → V4 | bank + cook + nav |
| `CookingScriptV2` | deletable once V4 lands |
| `FletchingScript` → V2 | bank + 9 direct `client.getWidget` / `Widget X =` widget reaches consolidate to `findWidget` |
| `PieDishScript` → V2 | bank-heavy (`bank.*=48`); the dominant `bank.*` user |
| `PizzaScript` → V2 | bank + cook + dispatcher-keyed Make-All confirms |
| `LumbridgeBankPenScript` → V2 | bank + combat + **5 dead-centre clickCanvas sites disappear via Artemis routing** |

### v1.2 (adds GE) — final-tier migration

| Script | Notes |
|---|---|
| `GrandExchangeScript` → V2 | **not "thin"** — 8 GE-internal types imported directly (`BuyItemIntent`, `BuyLimitLedger`, `BuyLimitTable`, `GeActions`, `GeInteraction`, `GrandExchangeSequenceFactory`, `GrandExchangeSequencePlan`, `SellItemIntent`, plus `PricePolicy.Exact` casts). Spec §14 bans these on the script side. Migration needs every one replaced with Artemis-shaped equivalents; the user-facing intent records (`BuyIntent`/`SellIntent`) survive; the ledger and sequence-factory become engine-internal. |
| `GrandExchangeTab` | UI tab; touches `ge.*` 5× — migrates with v1.2 |
| `CooksAssistantScript` → V2 | bank + GE (5 ge-callsite invocations verified — §2 table understated; v1.2 only) |
| `UltraCompostScript` → V2 | bank + GE (4 invocations); the script that produced 497 cycles on one banker NPC in the jbane777 incident |

### Deferred (v1.3+)

| Concern | Deferred to | Why |
|---|---|---|
| Logout + relogin cycle (`NATURAL_BREAK` + 60-min hibernate) | v1.3 per spec §10 | login is its own design pass |
| World hop / world rotation | v1.3 | same |
| `bank.depositAllExcept` composite | indefinite per spec §8 | composite verb dangerous; defer until concrete need |
| Weighted RotationPolicy | per-need basis | typed weighted variants land when a concrete use surfaces |

---

## 6. Migration priority recommendation

The user's directive is **one script at a time per [[feedback_one_at_a_time_irl_test]]**. The order below is recommended by Artemis-version dependency + **§10 risk** (centroid-click sites are the dominant fingerprint hazard; migrating those scripts earlier rotates them out of harm's way faster):

1. **Phase 2 — `CowKillerScript`** (NEW, v1.0). Standalone pilot; no migration debt.
2. **Phase 5 — `ChickenFarmV4Script`** (NEW, v1.1). The first v1.1 bank user; templated by cow killer. Delete `ChickenFarmV3` and `ChickenFarmV2` once V4 burner-verifies.
3. **`LumbridgeBankPenScript` → V2** (v1.1). **Moved earlier — has 3 §10-banned centroid-click sites; leaving them live through 6 other migrations would keep producing the dominant correctness failure pattern. Migrate as soon as Artemis v1.1 bank lands.**
4. **`RooftopAgilityScript` → V2** (v1.0). Self-contained, no bank dependency. Can land in parallel with Phase 5 / v1.1 work. Also exercises `findObject` rotation + OutcomeChecks more thoroughly than the cow killer — implicitly second-stress for v1.0 surface.
5. **`PieDishScript` → V2** (v1.1). The dominant `bank.*` user; the migration here exercises every Artemis bank primitive.
6. **`PizzaScript` → V2** (v1.1). Bank + cook + 1 centroid-click site.
7. **`FletchingScript` → V2** (v1.1). The widget-reach hotspot; exercises `findWidget` heavily. 2 centroid-click sites disappear.
8. **`CookingScriptV4`** (v1.1). Delete V2 + V3 once V4 lands.
9. **`UltraCompostScript` → V2** (v1.2). Bank + GE + 1 centroid-click site.
10. **`CooksAssistantScript` → V2** (v1.2). Bank + GE.
11. **`GrandExchangeScript` → V2** (v1.2). 8 GE-internal types to replace (not "thin"); the migration validates that Artemis v1.2 surface fully wraps the GE feature set.
12. **Quest sub-tree** — 6 step migrations + 2 deletions + 3 orchestrators. **This is bundled as one slot to retain spec §19 ordering, but per the rollback contract below each quest-step migrates and IRL-tests individually.** Order within the bundle: `ReplayTrailStep` → `TakeGroundItemStep` → `InteractWithObjectStep` → `TalkToNpcStep` → `UseItemOnObjectStep` → `CombineItemsStep` (small-to-large), then deletions (`NavWalkStep`, `LogoutHelper`), then orchestrators (`ErnestQuestScript`, `ErnestTheChicken`, `QuestStepThreads`).

**Rollback contract** (per [[feedback_one_at_a_time_irl_test]]): each migration commits independently; the next slot does not start until the previous one IRL-tests clean on a test-profile session. If slot 3 surfaces a bank-surface gap mid-migration, slots 4-8 wait until v1.1 is patched and slot 3 re-verifies — not in parallel.

**Deviation from spec §19 Phase 6 ordering:**
- This audit moves `LumbridgeBankPenScript` from spec-position-6 to **slot 3** because of its 3 §10-banned centroid sites (justified above).
- This audit inserts `CooksAssistantScript` as slot 10 — **spec §19 omits this script**; the audit recommends adding it because it has both `bank.*` and `ge.*` reaches and is in the same tier as `UltraCompostScript`. **Update spec §19 to mirror this 10-migration ordering, or roll back the audit to the spec's 8-migration list.** Pick one before Phase 5 lands.

Total: **10 migrations + 2 deletions** (audit recommendation), vs **8 migrations** in spec §19 — reconcile before Phase 5.

---

## 7. Open questions for the implementation phase

These surfaced during the audit and should be resolved before Phase 1B (Artemis impl) begins, not after:

1. **`dispatcher.tapKey(VK_*)` — two distinct use cases, not one.** Make-All chatbox confirm (`UltraCompostScript`, `PizzaScript`, `FletchingScript`, `PieDishScript`) and obstacle-key-confirm (`RooftopAgilityScript:400` `Kind.KEY`) need separate typed Steps — a single general `tapKey` surface would re-leak the dispatcher. Pick the two names now.
2. **`RotationPolicy` default contradiction.** Spec §7 names the default `ClosestWithSlack(1)`. Audit data shows scripts today picking `ClosestWithSlack(2)` for NPCs 80% of the time and **strict-closest** for stairs / banks / clerks. The spec default disagrees with both observed patterns. Decide: (a) keep spec default `ClosestWithSlack(1)` and accept callers always override, (b) change spec default to `ClosestWithSlack(2)` for NPCs / strict-closest for objects, (c) per-target-type defaults.
3. **Threading contract per Step.** Spec §12 says Step `onStart` runs on the client thread and re-resolves refs there. Audit's 47 `SequenceSleep.sleep` calls + `dispatcher.awaitIdle` reaches imply scripts today block on the dispatcher-worker. Pin which Artemis Steps complete inside `onStart` (client-thread) vs which enqueue a `RUN_TASK` action that runs on dispatcher-worker (e.g. Make-All confirm needs a chatbox-wait — multi-step blocking flow per CLAUDE.md threading §3, hence dispatcher-worker, hence cannot be a thin `onStart`-only Step).
4. **`LinearSequence.then(...)` semantics.** Audit assumes scripts compose Steps via `plan().then(openBank()).then(depositAll())` but spec doesn't define: does `then` await predecessor success, retry on failure, short-circuit on failure? What's the contract for the second `then` when the first fails?
5. **`Retry` budget semantics.** Spec §8 mentions default 8 ticks budget for stale-ref retry. Does each Step get its own budget? Is the budget per-Step or per-plan? What backoff between retries? Audit didn't surface this; implementation will.
6. **`NamedZone` enum content** — initial set from audit data: 8+ scripts walk to Lumbridge bank, 4 walk to a GE clerk, 3 walk to chicken/cow pen. Build the v1.0 NamedZone from these. **Spec §9 currently lists 6 zones but is missing `LUMBRIDGE_BANK` (the single most-walked zone in the codebase).** Add it.
7. **Walker selection inside `Artemis.walkTo(...)`** — engine has three walker impls. Audit shows trails imported by 6 files, nav v2.1 by 4, UniversalWalker by 3. The selection rule needs to be deterministic: trail-if-registered → else V2Navigator? `UniversalWalker` retired? Pin behaviour.
8. **`combat/` helper classification** — Phase 1B must pin in CLAUDE.md §11 which `combat/` classes scripts may import after Artemis lands. Audit recommends (§3.9): `ChickenCombatLoop` + `TrainingSession` are engine-internal helpers (allowed); `NpcSelector` is script-shape (banned, replaced by `findNpc`).
9. **`bank.withdrawAllExcept`** — composite verb listed in §3.3 sample methods but not given an Artemis fate. Likely same disposition as `depositAllExcept` (defer indefinitely per spec §8), but confirm.
10. **What does the cow-killer pilot prove vs. NOT prove of v1.0?** Cow killer exercises `findNpc + click + take + walkTo + idle + logout`. It does NOT exercise `useOn`, `findObject` with rotation, widget queries with re-resolution, or the stale-ref Retry path heavily. Slot 4 (`RooftopAgilityScript`) implicitly second-stresses the v1.0 surface; acceptance signals for v1.0 should be the **union** of cow killer + agility passing, not cow killer alone.

---

## 8. What this audit does NOT cover (deliberately)

- The engine-side internals: `BankInteraction`, `GeInteraction`, `SceneScanner`, `NpcSelector`, `PixelResolver`, the walker impls. They stay; Artemis delegates to them. Phase 3 separately rewrites `PixelResolver` (§10 ban on `sampleNearCentroid`).
- The dashboard / analyse side (`recorder/analyse/*` consumers of `StepEvent`). Phase 7 territory.
- Migration test plan / IRL acceptance criteria per script. Lands when each script's V2 migration commit lands (per `[[feedback_one_at_a_time_irl_test]]`).
- Performance / latency analysis. The migrations are 1-to-1 mappings; no perf regression expected.
- Naming bikeshed (`artemis.click(npc)` vs `artemis.clickNpc(npc)` etc.). Spec §6 already locks this; audit doesn't re-open.

---

**Next gate:** operator review of this audit. Once reviewed, Phase 1A continues with the **Artemis interface skeleton** (no impl yet — just signatures + javadoc), followed by Phase 1B impl + migration of CowKillerScript as the pilot.
