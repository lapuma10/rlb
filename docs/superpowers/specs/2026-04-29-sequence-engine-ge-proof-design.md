# Sequence-engine adoption proof: Grand Exchange transactions

**Date:** 2026-04-29
**Status:** Design draft (revised — GE-independent foundation); awaiting user review before plan execution
**Owner:** mantas
**Worktree:** to be created (`worktree-sequence-ge-proof`) — see §18

---

## 0. Revision history

**2026-04-29 #2:** Revised to make GE proof independent of banking branch progress.
GE branch now builds its own engine foundation subset (DiagnosticReason, engine fixes,
WorldSnapshot view scaffolding for Inventory/Interaction/GE). Banking rebases on GE's
foundation later. Bank-prep composition (OpenBank → WithdrawItem → CloseBank before GE
flow) deferred to Phase B. Initial GE Core assumes user is already at the GE with
coins/items in inventory.

**2026-04-29 #1:** Initial design. Sister spec to the banking proof. Banking lands first,
GE rebases onto it.

---

## 1. Problem

The sequence-engine adoption strategy is to prove the engine on real gameplay verticals
before migrating the rest of the recorder scripts. The banking proof and the GE proof are
two independent verticals on the same engine. **Neither needs to wait for the other to
land** — both build subsets of a shared engine foundation, and whoever lands first owns
the squashed foundation. The other branch rebases onto it and adds its domain-specific
layer.

The GE proof is scoped to build its own engine foundation subset (§5) plus GE Core types
and step library in Phase A. Phase B adds bank-prep composition once banking's step
library is available.

**GE Core Phase A assumptions** (no banking dependency):
- User is already at the Grand Exchange.
- For BUY: user already has enough coins in inventory.
- For SELL: user already has the sell item quantity in inventory.
- No bank sub-flow in this phase.

## 2. Goal

Build a narrow, **manual-UI-triggered** Grand Exchange transaction proof on the existing
sequence engine.

Two operations, button-driven from the recorder panel:

1. **Buy** X quantity of item Y at explicit price P each.
2. **Sell** X quantity of item Y at explicit price P each.

This is for vertical-slice validation, not autonomous trading. The engine executes the
requested transaction deterministically, verifies each click via `WorldSnapshot`, surfaces
typed failure reasons, and emits telemetry the user can read in the panel.

## 3. Scope

### In scope (Phase A — now)

- **Engine foundation subset** (§5) — GE branch builds the pieces it needs; banking
  rebases later.
- **`GrandExchangeView`** WorldSnapshot view + `GrandExchangeOfferView` per-slot record
  (§6).
- **`BlockReason`** sealed sub-interface of `DiagnosticReason` with the records GE Core
  needs: `PinKeypadUp`, `WorldInteractionBlocked(BlockingInterface)`,
  `NotAtLocation(WorldArea)`. Banking will add bank-domain records (`BankNotOpen`,
  `BankMissingItem`, etc.) to this type when it lands.
- **`GeBlockReason`** sealed sub-interface of `DiagnosticReason` carrying GE-domain typed
  reasons (§7).
- **Domain types** (§8): `BuyItemIntent`, `SellItemIntent`, sealed `PricePolicy` (only
  `Exact(coinsEach)` implemented), `OfferSide`, `OfferWaitPolicy`.
- **GE step library** (§9): `EnsureAtGrandExchangeStep`, `EnsureNoBlockingInterfaceStep`
  (in `sequence/activities/`, shared), `OpenGrandExchangeStep`,
  `EnsureNoConflictingOfferStep`, `CreateBuyOfferStep`, `CreateSellOfferStep`,
  `WaitForOfferStep`, `CollectOfferStep`. Post-collect result verification (slot=EMPTY +
  inventory delta) is built into `CollectOfferStep.check`; no separate Ensure*Result step
  is needed.
- **`GeActions`** interface + concrete `GeInteraction` (§10).
- **`GrandExchangeSequenceFactory.buyCore(BuyItemIntent, ...)`** and
  **`.sellCore(SellItemIntent, ...)`** — implemented now (§11).
- **`GrandExchangeScript`** (§12) — engine-only single-task controller.
- **`RecorderPanel` GE Core test tab** (§12) — implemented now (no longer Phase B).
- **Tests** (§13).

### Out of scope (Phase A)

- Bank-prep sub-flow (OpenBank → WaitForBankReady → WithdrawItem → CloseBank) before
  the GE sequence. **GE Core does not depend on bank-prep.** Phase B.
- `buyWithBankPrep(...)` / `sellWithBankPrep(...)` factory methods. Phase B.
- The full banking step library (`OpenBankStep`, `WaitForBankReadyStep`,
  `WithdrawItemStep`, `CloseBankStep`, `DepositItemStep`). Banking branch owns these.
- `BankView`, `EventFacts`, `WidgetView` — deferred to banking.
- Bank-domain `BlockReason` records: `BankNotOpen`, `BankMissingItem`, `WithdrawNoOp`,
  `BankContentsUnknown`. Banking adds these to `BlockReason.java`.
- Autonomous flipping / repeated trading / market loops.
- Price strategy (CurrentGuidePrice, percent-of-guide, market spread). `PricePolicy.Exact`
  only.
- Combat / cooking / chicken-farm / Lumbridge-walker migration.
- Walking inside the engine. `EnsureAtGrandExchangeStep` is a guard, not a walker.
- Anti-detection / evasion work.
- Item-name → item-id resolution at the step layer.
- `GameTick` / `ItemContainerChanged` event push into the engine. Verification is
  snapshot-based; `Step.onEvent` is a no-op for GE steps in this proof.
- Auto-aborting in-flight offers on timeout.
- Bank-collect mode.
- Edits to `recorder/scripts/CookingScript.java`.
- Edits to anything under `sequence/activities/banking/` (does not exist; banking creates
  this package).

## 4. Branch & merge plan

```
master
  └── worktree-sequence-ge-proof   (self-sufficient; builds foundation subset)
        └── (after banking lands: rebase to pick up banking's domain additions)
```

### Phase A — now

Implement everything: engine foundation subset, GE Core types, step library, factory,
script, UI. All in one phase; ordered tasks in the plan.

### Phase B — after banking lands

Add `buyWithBankPrep(...)` / `sellWithBankPrep(...)` factory methods that prepend banking
steps. Add a UI checkbox "Prepare from bank first" that selects the bank-prep variants.
See §17 for full Phase B scope.

### Touch list (Phase A)

Files the GE branch creates or modifies. The foundation files are new — banking rebases
onto GE's versions of them.

| Category | File | Action |
|---|---|---|
| Foundation — engine plumbing | `sequence/Completion.java` | Modify: add `Failed.diagnostic` field + `failed(DiagnosticReason)` factory |
| Foundation — engine plumbing | `sequence/Failure.java` | Modify: add `diagnostic` field + `fromDiagnostic` factory |
| Foundation — engine plumbing | `sequence/WorldSnapshot.java` | Modify: add default views for `inventory()`, `interaction()`, `grandExchange()` |
| Foundation — engine plumbing | `sequence/SequenceEngine.java` | Modify: `clearReactives()`, `registerReactive(Step,int)` |
| Foundation — engine plumbing | `sequence/SequenceManager.java` | Modify: scheduler-marshalled passthroughs, `setInputOwnership` |
| Foundation — engine plumbing | `sequence/internal/StateDrivenEngine.java` | Modify: `canStart` gate, `Retry` cumulativity, diagnostic passthrough, STEP-scope ordering |
| Foundation — engine plumbing | `sequence/internal/PriorityPlanner.java` | Modify: telemetry-on-reject |
| Foundation — engine plumbing | `sequence/internal/ClientObserver.java` | Modify: compose InventoryObserver, InteractionObserver, GrandExchangeObserver |
| Foundation — new files | `sequence/dispatch/InputOwnership.java` | New |
| Foundation — new files | `sequence/blackboard/SequenceBlackboardKeys.java` | New: `LAST_BLOCK_REASON` |
| Foundation — new files | `sequence/affordance/DiagnosticReason.java` | New: engine-generic sealed parent |
| Foundation — new files | `sequence/affordance/BlockReason.java` | New: shared sealed sub-interface; GE adds `PinKeypadUp`, `WorldInteractionBlocked`, `NotAtLocation`. Banking adds bank-domain records later. |
| Foundation — new files | `sequence/affordance/BlockingInterface.java` | New |
| Foundation — new views | `sequence/views/InventoryView.java` | New |
| Foundation — new views | `sequence/views/InteractionView.java` | New |
| Foundation — new views | `sequence/views/InteractionMode.java` | New (enum) |
| Foundation — new views | `sequence/views/ItemStack.java` | New |
| Foundation — new views | `sequence/views/ImmutableWorldSnapshot.java` | New (or confirm existing; inspect first) |
| Foundation — new internal | `sequence/internal/InventoryObserver.java` | New |
| Foundation — new internal | `sequence/internal/InteractionObserver.java` | New |
| GE views | `sequence/views/GrandExchangeView.java` | New |
| GE views | `sequence/views/GrandExchangeOfferView.java` | New |
| GE views | `sequence/views/OfferSide.java` | New (enum) |
| GE views | `sequence/views/OfferStatus.java` | New (enum) |
| GE internal | `sequence/internal/GrandExchangeObserver.java` | New |
| GE affordance | `sequence/affordance/GeBlockReason.java` | New: GE-domain sealed sub-interface |
| GE activities | `sequence/activities/EnsureNoBlockingInterfaceStep.java` | New (shared, not under `banking/`) |
| GE activities | `sequence/activities/ge/GeActions.java` | New |
| GE activities | `sequence/activities/ge/GeInteraction.java` | New |
| GE activities | `sequence/activities/ge/BuyItemIntent.java` | New |
| GE activities | `sequence/activities/ge/SellItemIntent.java` | New |
| GE activities | `sequence/activities/ge/PricePolicy.java` | New |
| GE activities | `sequence/activities/ge/OfferWaitPolicy.java` | New |
| GE activities | `sequence/activities/ge/GeBlackboardKeys.java` | New |
| GE activities | `sequence/activities/ge/EnsureAtGrandExchangeStep.java` | New |
| GE activities | `sequence/activities/ge/OpenGrandExchangeStep.java` | New |
| GE activities | `sequence/activities/ge/EnsureNoConflictingOfferStep.java` | New |
| GE activities | `sequence/activities/ge/CreateBuyOfferStep.java` | New (factory → LinearSequence) |
| GE activities | `sequence/activities/ge/CreateSellOfferStep.java` | New (factory → LinearSequence) |
| GE activities | `sequence/activities/ge/StartOfferStep.java` | New |
| GE activities | `sequence/activities/ge/SelectItemStep.java` | New |
| GE activities | `sequence/activities/ge/SetQuantityStep.java` | New |
| GE activities | `sequence/activities/ge/SetPriceStep.java` | New |
| GE activities | `sequence/activities/ge/ConfirmOfferStep.java` | New |
| GE activities | `sequence/activities/ge/WaitForOfferStep.java` | New |
| GE activities | `sequence/activities/ge/CollectOfferStep.java` | New |
| GE activities | `sequence/activities/ge/GrandExchangeSequenceFactory.java` | New |
| GE activities | `sequence/activities/ge/GrandExchangeSequencePlan.java` | New |
| Recorder | `recorder/scripts/GrandExchangeScript.java` | New |
| Recorder | `recorder/RecorderPanel.java` | Modify (minimal: add GE Core tab) |
| Recorder | `recorder/RecorderPlugin.java` | Modify (minimal: inject GrandExchangeScript) |

### No-touch list (Phase A)

| File | Why |
|---|---|
| `recorder/scripts/CookingScript.java` | Never touch |
| `sequence/activities/banking/**` | Does not exist; banking creates it |
| `recorder/RecorderConfig.java` | No flag for first proof (dev-only panel) |

## 5. Engine foundation subset

GE branch builds the minimum foundation it needs. Banking's spec lists some of these files
as "banking-owned" but banking has not touched them yet — GE owning them now is fine.
Banking rebases on top and adds its domain layer.

### 5.1 What GE branch builds

**Engine plumbing** (modifications to existing files):
- `Completion.java` — `Failed.diagnostic: @Nullable DiagnosticReason` field +
  `Completion.failed(DiagnosticReason)` factory.
- `Failure.java` — `diagnostic: @Nullable DiagnosticReason` field +
  `Failure.fromDiagnostic(DiagnosticReason, int)` factory.
- `SequenceEngine.java` — `clearReactives()`, `registerReactive(Step, int)`.
- `SequenceManager.java` — scheduler-marshalled passthroughs for clearReactives /
  registerReactive; `setInputOwnership(InputOwnership, String)`.
- `StateDrivenEngine.java` — `canStart` gate (block step from starting if
  `canStart=false`), `Retry` cumulativity (bounded at N+1 total attempts),
  STEP-scope clearing after terminal transition (not between onStart and same-tick check),
  diagnostic passthrough into `Failure`.
- `PriorityPlanner.java` — telemetry-on-reject: record `(stepName, reason)` when
  canStart gate fires.
- `ClientObserver.java` — compose `InventoryObserver`, `InteractionObserver`,
  `GrandExchangeObserver`; extend snapshot builder to supply those views.
- `WorldSnapshot.java` — add `default InventoryView inventory()`,
  `default InteractionView interaction()`, `default GrandExchangeView grandExchange()`,
  each returning the corresponding empty null-object. Existing `WorldSnapshot` impls
  stay compiling with no changes.

**New engine-layer files**:
- `dispatch/InputOwnership.java` — lease / tryAcquire / release.
- `blackboard/SequenceBlackboardKeys.java` — `LAST_BLOCK_REASON`.
- `affordance/DiagnosticReason.java` — engine-generic sealed parent type. Initial
  `permits` clause: `BlockReason`, `GeBlockReason`, plus engine-generic cases
  (`DiagnosticReason.Loading`, `DiagnosticReason.ActionTimedOut`,
  `DiagnosticReason.Unknown`).
- `affordance/BlockReason.java` — sealed sub-interface of `DiagnosticReason`.
  GE adds only the records it needs: `PinKeypadUp`, `WorldInteractionBlocked(BlockingInterface)`,
  `NotAtLocation(WorldArea)`. Banking's rebase adds `BankNotOpen`, `BankMissingItem`,
  `WithdrawNoOp`, `BankContentsUnknown`, `PinKeypadUp` (already present, no conflict).
- `affordance/BlockingInterface.java` — record with `rootWidgetId`, `canBeClosed`.
- `views/InventoryView.java` — interface: `count(itemId)`, `freeSlots()`, `items()`.
- `views/InteractionView.java` — interface: `mode()`, `blockingInterface()`.
- `views/InteractionMode.java` — enum: `FREE`, `BLOCKED_CLOSABLE`, `BLOCKED_NON_CLOSABLE`, `PIN_KEYPAD`.
- `views/ItemStack.java` — record: `itemId`, `quantity`.
- `views/ImmutableWorldSnapshot.java` — inspect existing engine; create if absent.
- `internal/InventoryObserver.java` — reads `ItemContainer(InventoryID.INVENTORY)` on client thread.
- `internal/InteractionObserver.java` — reads blocking-interface state from widget tree.

### 5.2 What banking branch adds later (defer)

- `BankView` — interface for bank container state.
- `EventFacts` — snapshot of event-push data (ItemContainerChanged, etc.).
- `WidgetView` — generic widget presence/visibility accessor.
- `internal/BankObserver.java`, `internal/WidgetObserver.java`.
- Bank-domain `BlockReason` records: `BankNotOpen`, `BankMissingItem`, `WithdrawNoOp`,
  `BankContentsUnknown`, `InventoryFull`.
- Banking step library (`OpenBankStep`, `WaitForBankReadyStep`, `WithdrawItemStep`,
  `DepositItemStep`, `CloseBankStep`, `BankActions`, `WithdrawQuantity`).

### 5.3 Banking conflict surface

When banking rebases onto GE's foundation:
- `BlockReason.java` — banking adds `permits` clause records. Low conflict: additive.
- `DiagnosticReason.java` — banking confirms `GeBlockReason` is already listed in permits.
- `ClientObserver.java` — banking adds BankObserver and WidgetObserver composition.
- `WorldSnapshot.java` — banking adds `default BankView bank()` and
  `default WidgetView widgets()` default methods. Additive.

### 5.4 Step contract invariants GE inherits

These invariants come from the engine design; GE tests assert GE-specific equivalents:

- One mutating action per `onStart` (no `tick()` mutation).
- `STEP` scope cleared after terminal transition (after `onFailure`), not between
  `onStart` and same-tick `check`.
- `Retry(N)` cumulative: bounded at N+1 total attempts regardless of what `onFailure`
  returns.
- `canStart=false` only for blockers a reactive can clear within bounded ticks. All other
  bad states are fatal preconditions in `onStart` → first `check` returns
  `Failed(reason)`.
- Snapshot is per-tick immutable; passed to all `canStart`/`check` calls in that tick.

## 6. WorldSnapshot expansion: `GrandExchangeView`

One new view; one production observer; one new test fixture builder.

```java
package net.runelite.client.sequence.views;

public interface GrandExchangeView {
    /** True when the main GE offers interface (GeOffers) is visible. */
    boolean open();

    /** True when the per-slot offer-setup interface (GeOfferSetup) is visible. */
    boolean offerSetupOpen();

    /** True when the collect interface (GeCollect) is visible (post-completion). */
    boolean collectOpen();

    /** All eight offer slots, ordered by slot index 0–7. */
    List<GrandExchangeOfferView> offers();

    /** First slot whose state is EMPTY, or empty Optional if none. */
    OptionalInt firstEmptySlot();

    /** Number of slots whose state is EMPTY. */
    int emptySlotCount();

    /** Slots currently holding any offer for (itemId, side), in slot order. */
    List<GrandExchangeOfferView> offersFor(int itemId, OfferSide side);

    static GrandExchangeView empty() { /* null-object: not open, all 8 slots EMPTY */ }
}

public record GrandExchangeOfferView(
    int slot,                                  // 0..7
    OfferSide side,                            // BUY, SELL, NONE (when state == EMPTY)
    OfferStatus status,                        // mapped from GrandExchangeOfferState (§6.1)
    int itemId,                                // 0 when EMPTY
    int requestedQuantity,                     // total quantity offered
    int completedQuantity,                     // quantitySold from the API
    int priceEach,                             // coins per item
    int spent                                  // running coin total moved by the offer
) {
    public boolean isEmpty()    { return status == OfferStatus.EMPTY; }
    public boolean isComplete() { return status == OfferStatus.COMPLETE; }
    public boolean isActive()   { return status == OfferStatus.ACTIVE || status == OfferStatus.PARTIALLY_COMPLETE; }
    public boolean isCancelled(){ return status == OfferStatus.CANCELLED; }
}

public enum OfferSide { BUY, SELL, NONE }

public enum OfferStatus {
    EMPTY,                  // GrandExchangeOfferState.EMPTY
    ACTIVE,                 // BUYING / SELLING with completedQuantity == 0
    PARTIALLY_COMPLETE,     // BUYING / SELLING with completedQuantity > 0 && < requestedQuantity
    COMPLETE,               // BOUGHT / SOLD
    CANCELLED               // CANCELLED_BUY / CANCELLED_SELL
}
```

### 6.1 `GrandExchangeOfferState` → `OfferStatus` mapping

Source: `runelite-api/src/main/java/net/runelite/api/GrandExchangeOfferState.java`.

| RuneLite API | OfferStatus |
|---|---|
| `EMPTY` | `EMPTY` |
| `BUYING` (qty=0) | `ACTIVE` |
| `BUYING` (0<qty<total) | `PARTIALLY_COMPLETE` |
| `SELLING` (qty=0) | `ACTIVE` |
| `SELLING` (0<qty<total) | `PARTIALLY_COMPLETE` |
| `BOUGHT` | `COMPLETE` |
| `SOLD` | `COMPLETE` |
| `CANCELLED_BUY` | `CANCELLED` |
| `CANCELLED_SELL` | `CANCELLED` |

The `OfferSide` field is derived: `BUYING`/`BOUGHT`/`CANCELLED_BUY` →
`BUY`; `SELLING`/`SOLD`/`CANCELLED_SELL` → `SELL`; `EMPTY` → `NONE`.

### 6.2 Observer

```java
package net.runelite.client.sequence.internal;

public final class GrandExchangeObserver {
    private final Client client;
    public GrandExchangeObserver(Client client) { this.client = client; }

    /** Reads on the client thread. Caller marshals. */
    public GrandExchangeView read(int tick) {
        // 1. Read InterfaceID.GeOffers.UNIVERSE / .GeOfferSetup.UNIVERSE / .GeCollect.UNIVERSE
        //    → open() / offerSetupOpen() / collectOpen() flags. (Inspect exact field names first.)
        // 2. client.getGrandExchangeOffers() returns GrandExchangeOffer[8].
        // 3. For each slot, build GrandExchangeOfferView via the §6.1 mapping.
    }
}
```

`ClientObserver` is modified in Phase A to compose this observer alongside
`InventoryObserver` and `InteractionObserver`. `WorldSnapshot.grandExchange()` returns its
output as a default method.

## 7. Affordance layer: `GeBlockReason`

GE-domain typed reasons live in a sealed sub-interface of `DiagnosticReason`.

```java
package net.runelite.client.sequence.affordance;

public sealed interface GeBlockReason extends DiagnosticReason {
    record NotAtGrandExchange(WorldArea required)                        implements GeBlockReason {}
    record GeNotOpen()                                                   implements GeBlockReason {}
    record GeOfferSetupNotOpen()                                         implements GeBlockReason {}
    record GeCollectNotOpen()                                            implements GeBlockReason {}
    record GeSlotsFull()                                                 implements GeBlockReason {}
    record GeExistingOfferConflict(int slot, OfferSide side, int itemId, OfferStatus status) implements GeBlockReason {}
    record GeOfferRejected(String reasonText)                            implements GeBlockReason {}
    record GeOfferTimeout(int slot, int ticks)                           implements GeBlockReason {}
    record GeOfferIncomplete(int slot, int completed, int requested)     implements GeBlockReason {}
    record GeOfferQuantityMismatch(int slot, int expected, int actual)   implements GeBlockReason {}
    record GeOfferItemMismatch(int slot, int expectedItemId, int actualItemId) implements GeBlockReason {}
    record GeOfferPriceMismatch(int slot, int expectedPriceEach, int actualPriceEach) implements GeBlockReason {}
    record GeCollectFailed(int slot, int expectedDeltaItemId, int observedDelta) implements GeBlockReason {}
    record InsufficientCoins(int needed, int have)                       implements GeBlockReason {}
    record InsufficientSellItems(int itemId, int needed, int have)       implements GeBlockReason {}
}
```

`InsufficientCoins` and `InsufficientSellItems` are checked in `CreateBuyOfferStep` and
`CreateSellOfferStep` respectively, at `onStart` time (inventory view pre-flight). These
are the primary guards in GE Core — without bank-prep there is no upstream
`WithdrawItemStep` to catch them first.

## 8. Domain types

### 8.1 Intents

```java
public record BuyItemIntent(
    int itemId,
    String displayName,        // for telemetry / panel; not load-bearing
    int quantity,              // total to buy
    PricePolicy pricePolicy,
    OfferWaitPolicy waitPolicy
) {
    public BuyItemIntent { /* compact ctor: validate quantity > 0 */ }
}

public record SellItemIntent(
    int itemId,
    String displayName,
    int quantity,
    PricePolicy pricePolicy,
    OfferWaitPolicy waitPolicy
) {
    public SellItemIntent { /* compact ctor: validate quantity > 0 */ }
}
```

### 8.2 `PricePolicy`

```java
public sealed interface PricePolicy {
    /** Use this exact coin price per item. */
    record Exact(int coinsEach) implements PricePolicy {
        public Exact { if (coinsEach <= 0) throw new IllegalArgumentException(); }
    }

    /*
     * Future variants — documented as contract, NOT implemented in this proof.
     * Each requires a price-lookup source the proof refuses to introduce.
     */
    // record CurrentGuidePrice() implements PricePolicy {}
    // record PercentOfGuidePrice(int percent) implements PricePolicy {}
    // record MarketSpread(int spreadPercent) implements PricePolicy {}
}
```

`PricePolicy` is a sealed marker — no method on the interface. The factory resolves price
via pattern matching:

```java
int priceEach = switch (intent.pricePolicy()) {
    case PricePolicy.Exact e -> e.coinsEach();
};
```

### 8.3 `OfferWaitPolicy`

```java
public record OfferWaitPolicy(
    int timeoutTicks,                  // hard cap on wait duration; 0 means do-not-wait
    boolean acceptPartialOnTimeout
) {
    public static OfferWaitPolicy until(int ticks)              { return new OfferWaitPolicy(ticks, false); }
    public static OfferWaitPolicy untilOrPartial(int ticks)     { return new OfferWaitPolicy(ticks, true); }
    public static OfferWaitPolicy noWait()                      { return new OfferWaitPolicy(0, false); }
}
```

### 8.4 `OfferSide`

Defined in §6 alongside `GrandExchangeView`. Used by intents implicitly (Buy → BUY;
Sell → SELL).

## 9. GE step library

All steps live in
`runelite-client/src/main/java/net/runelite/client/sequence/activities/ge/`.
`EnsureNoBlockingInterfaceStep` lives one level up at
`sequence/activities/EnsureNoBlockingInterfaceStep.java` so banking can reuse it without
a circular dependency.

All steps are stateless (working memory in `BlackboardScope.STEP`). All steps follow the
three-outcome contract: fresh work / already satisfied / fatal precondition.

### 9.1 Sequences

**BuyItemAtGECore** (`GrandExchangeSequenceFactory.buyCore(...)`):

```
LinearSequence("BuyItemAtGECore"):
  EnsureAtGrandExchange(geArea)
  EnsureNoBlockingInterface(allowList = [GE_ROOTS])
  OpenGrandExchange(geArea)
  EnsureNoConflictingOffer(itemId, BUY)
  CreateBuyOffer(itemId, qty, priceEach)    // sub-LinearSequence; writes K_GE_OFFER_SLOT
                                             // onStart: checks inv.count(COINS) >= qty*priceEach
                                             //          → fails InsufficientCoins if not
  WaitForOffer(waitPolicy)                  // reads K_GE_OFFER_SLOT
  CollectOffer                              // reads K_GE_OFFER_SLOT; verifies slot=EMPTY + inventory delta
```

**SellItemAtGECore** (`GrandExchangeSequenceFactory.sellCore(...)`):

```
LinearSequence("SellItemAtGECore"):
  EnsureAtGrandExchange(geArea)
  EnsureNoBlockingInterface(allowList = [GE_ROOTS])
  OpenGrandExchange(geArea)
  EnsureNoConflictingOffer(itemId, SELL)
  CreateSellOffer(itemId, qty, priceEach)   // onStart: checks inv.count(itemId) >= qty
                                             //          → fails InsufficientSellItems if not
  WaitForOffer(waitPolicy)
  CollectOffer                              // verifies slot=EMPTY + inventory delta of COINS
```

`CollectOfferStep` is identical between buy and sell; it reads the final offer state's
`OfferSide` from the slot view before dispatching collect, then verifies the inventory
delta of the expected item (itemId for BUY, ItemID.COINS for SELL).

`slotKey` is a `BlackboardScope.SEQUENCE`-scoped key written by the
`ConfirmOfferStep` (inside the `Create*` sub-sequence). `WaitForOffer` and `CollectOffer`
read the slot from this key.

Reactive steps:

```
[ EnsureNoBlockingInterfaceStep(allowList = [GE_ROOTS]) ]
```

Only non-GE blocking interfaces (level-up dialog, world-hop nag, account warnings) need
preempting. GE widget being open is expected.

### 9.2 Step contracts

| Step | Already-satisfied | `onStart` action / fatal preconditions | `check` outcomes | `onFailure` |
|---|---|---|---|---|
| **EnsureAtGrandExchange**(geArea) | player in `geArea` | fatal: `NotAtGrandExchange(area)` | `Failed(NotAtGrandExchange)` from `K_PRECONDITION_FAILURE`, else `Succeeded` | `Abort` |
| **OpenGrandExchange**(geArea) | `ge.open()` already true | fatal: non-closable `WorldInteractionBlocked`, `PinKeypadUp`; else dispatch GE-open click | `Succeeded` when `ge.open()`; `Failed(GeNotOpen)` after timeout | `Retry(3)` then `Abort` |
| **EnsureNoConflictingOffer**(itemId, side) | no slot has `(itemId, side)` non-EMPTY | fatal: `GeExistingOfferConflict(slot, side, itemId, status)` if any slot does | `Failed(GeExistingOfferConflict)` from precondition, else `Succeeded` | `Abort` |
| **CreateBuyOffer**(itemId, qty, priceEach) | (never satisfied) | fatal: `GeNotOpen`, `GeSlotsFull`, **`InsufficientCoins(needed, have)`** if `inv.count(COINS) < qty*priceEach`; else dispatch sub-steps | `Succeeded` when matching offer appears + `slotKey` recorded | `Retry(2)` for `GeOfferRejected`; `Abort` for mismatch |
| **CreateSellOffer**(itemId, qty, priceEach) | (never satisfied) | fatal: `GeNotOpen`, `GeSlotsFull`, **`InsufficientSellItems(itemId, needed, have)`** if `inv.count(itemId) < qty`; else dispatch sub-steps | `Succeeded` when matching offer appears + `slotKey` recorded | `Retry(2)` for `GeOfferRejected`; `Abort` for mismatch |
| **WaitForOffer**(waitPolicy) | offer in slot already `COMPLETE` | none — pure wait. records `K_START_TICK` | `Succeeded` when COMPLETE; `Failed(GeOfferIncomplete)` or `Failed(GeOfferTimeout)` at timeout | `Abort` |
| **CollectOffer** | slot already EMPTY AND last observed inventory delta matched | onStart reads slot, side, persists delta-start; dispatches `geActions.collect(slot)`; fatal: `GeNotOpen` | `Succeeded` when slot EMPTY AND inv delta observed; `Failed(GeCollectFailed)` at timeout if either unmet | `Retry(2)` then `Abort` |

### 9.3 `Create*OfferStep` — the multi-click sub-sequence

Per Option A (spec §9.3 original): `CreateBuyOfferStep` and `CreateSellOfferStep` are
factory functions returning a `LinearSequence` of five sub-steps:

```
LinearSequence("CreateBuyOffer"):
  StartOfferStep(BUY)               // click "BUY" button; check waits for ge.offerSetupOpen()
  SelectItemStep(itemId)            // type/select item; check verifies selected itemId
  SetQuantityStep(qty)              // click *X + type qty; check verifies shown qty
  SetPriceStep(priceEach)           // click *X + type price; check verifies shown price
  ConfirmOfferStep(itemId, BUY,     // click "Confirm"; check verifies offer surfaced in ge.offers();
                    qty, priceEach) //   writes K_GE_OFFER_SLOT to SEQUENCE scope
```

Each sub-step does one mutating dispatch in `onStart` and verifies via snapshot in
`check`. Single-action-per-tick preserved.

`SelectItemStep` is the most complex sub-step. Inspect at implementation time:
`InterfaceID.GeOfferSetup` search widget, result-row widget id,
`GrandExchangeInputListener` for the typing/varc path.

### 9.4 `slotKey` blackboard wiring

```java
// In GeBlackboardKeys:
public static final BlackboardKey<Integer> K_GE_OFFER_SLOT =
    BlackboardKey.of("ge.offerSlot", Integer.class);
```

SEQUENCE-scoped; written by `ConfirmOfferStep`, read by `WaitForOfferStep` and
`CollectOfferStep`.

## 10. `GeActions`

```java
public interface GeActions {
    void openGrandExchange();
    void clickOfferSlotButton(int slot, OfferSide side);
    void selectItem(int itemId, String displayName);
    void setQuantity(int qty);
    void setPrice(int priceEach);
    void confirmOffer();
    void collect(int slot);
    void closeGrandExchange();
}
```

### 10.1 `GeInteraction` (production impl)

Resolves widgets via `PixelResolver`, dispatches via `HumanizedInputDispatcher`. Each
method is fail-silent if the target widget is hidden — verification is the step's job.

`selectItem(itemId, displayName)`: choose between typing-then-click-result and the direct
varc-set shortcut at implementation time; document the chosen path in class javadoc.

### 10.2 Test-only `RecordingGeActions`

In-memory list of every call serialized as strings (e.g., `"openGrandExchange()"`,
`"setQuantity(50)"`). Each test asserts expected call sequence + count.

## 11. `GrandExchangeSequenceFactory`

```java
public record GrandExchangeSequencePlan(Step root, List<Step> reactiveSteps) {}

public final class GrandExchangeSequenceFactory {
    private GrandExchangeSequenceFactory() {}

    /**
     * GE Core buy — Phase A. User must already be at GE with coins in inventory.
     */
    public static GrandExchangeSequencePlan buyCore(
            BuyItemIntent intent,
            WorldArea geArea,
            GeActions ge) {

        List<Step> linear = new ArrayList<>(7);
        linear.add(new EnsureAtGrandExchangeStep(geArea));
        linear.add(new EnsureNoBlockingInterfaceStep(allowList(GE_ROOTS)));
        linear.add(new OpenGrandExchangeStep(geArea, ge));
        linear.add(new EnsureNoConflictingOfferStep(intent.itemId(), OfferSide.BUY));
        linear.add(buildCreateBuyOfferStep(intent, ge));   // sub-LinearSequence per §9.3
        linear.add(new WaitForOfferStep(intent.waitPolicy()));
        linear.add(new CollectOfferStep(ge));

        Step root = new LinearSequence("BuyItemAtGECore", linear);
        List<Step> reactives = List.of(new EnsureNoBlockingInterfaceStep(allowList(GE_ROOTS)));
        return new GrandExchangeSequencePlan(root, reactives);
    }

    /**
     * GE Core sell — Phase A. User must already be at GE with items in inventory.
     */
    public static GrandExchangeSequencePlan sellCore(
            SellItemIntent intent,
            WorldArea geArea,
            GeActions ge) {
        // mirror of buyCore(...) — creates SELL offer.
        // CollectOfferStep reads OfferSide.SELL from the slot view and verifies
        // a coin-count delta in inventory inline.
    }
}
```

Note: no `bankBoothIds` or `BankActions` parameters — GE Core does not use bank-prep.

`GE_ROOTS` is defined in the GE package:

```java
public static final Set<Integer> GE_ROOTS = Set.of(
    InterfaceID.GeOffers.UNIVERSE,
    InterfaceID.GeOfferSetup.UNIVERSE,
    InterfaceID.GeCollect.UNIVERSE
);
```

(Inspect exact field names — `GeOffers`, `GeOfferSetup`, `GeCollect` nested classes exist
in `runelite-api/.../gameval/InterfaceID.java`; `UNIVERSE` is the root convention per
banking's `Bankmain.UNIVERSE`.)

### 11.1 Phase B — `buyWithBankPrep` / `sellWithBankPrep` (deferred)

Once the banking step library lands, two additional factory methods will be added:

- `buyWithBankPrep(BuyItemIntent, WorldArea geArea, List<Integer> bankBoothIds, BankActions bank, GeActions ge)` — prepends `OpenBank → WaitForBankReady → WithdrawItem(COINS, AtLeast(totalCost)) → CloseBank` before the GE Core sequence. The `InsufficientCoins` check in `CreateBuyOfferStep` remains as a defensive post-withdraw guard.
- `sellWithBankPrep(SellItemIntent, ...)` — prepends `OpenBank → WaitForBankReady → WithdrawItem(itemId, AtLeast(qty)) → CloseBank`.

These methods are NOT implemented now. The UI's "Prepare from bank first" checkbox
(currently disabled, §12) will select them when Phase B lands.

## 12. `GrandExchangeScript` + `RecorderPanel` GE tab

### 12.1 `GrandExchangeScript`

Engine-only single-task controller. No daemon thread, no enum FSM.

Constructor takes `Client`, `ClientThread`, `HumanizedInputDispatcher`,
`GeInteraction`, `InputOwnership`, `WorldArea geArea`.

Note: no `BankInteraction` or `bankBoothIds` parameters in Phase A — GE Core does not
use bank-prep. Phase B adds those parameters when `startBuyWithPrep` /
`startSellWithPrep` methods are added.

```java
private static final String OWNER_TOKEN = "ge-script";

public boolean startBuy(BuyItemIntent intent)  {
    return start(GrandExchangeSequenceFactory.buyCore(intent, geArea, geActions));
}
public boolean startSell(SellItemIntent intent){
    return start(GrandExchangeSequenceFactory.sellCore(intent, geArea, geActions));
}
public boolean stop() { /* engine.stop(); release lease */ }
public SequenceState state()  { ... }
public String       status()  { ... }

/** RecorderPlugin @Subscribe forwards GameTick to here. */
public void onGameTick() {
    if (geManager == null) return;
    switch (geManager.state()) {
        case IDLE        -> { /* nothing in flight */ }
        case RUNNING     -> { scheduleEngineTick(); status.set(readLastTelemetry(geManager)); }
        case COMPLETED   -> { inputOwnership.release(OWNER_TOKEN); status.set("done"); }
        case FAILED      -> { inputOwnership.release(OWNER_TOKEN); status.set("failed: " + lastFailureSummary()); }
        case PAUSED      -> { /* unused */ }
    }
}
```

Race-guarded via `intentStartRequested` (AtomicBoolean); `geManager.run(...)` is
try/catch-guarded (release lease + reset flag on exception). `tickInFlight` AtomicBoolean
prevents queue-stacking.

`OWNER_TOKEN = "ge-script"` does not collide with banking's `"cooking-banking"`.

### 12.2 RecorderPanel GE tab

A new tab inside `RecorderPanel`'s existing `JTabbedPane`. Tab label: **"GE Core Mode"**.

```
┌─ GE Core Mode ────────────────────────────────────┐
│ ⚠ Requires you to already be at GE and have       │
│   coins/items in inventory.                        │
│                                                    │
│ Item:    [_______________________] (id: ____  )  │
│ Quantity:[_______]                                 │
│ Price:   [_______] coins each                      │
│ Wait:    [until complete (300t)] [partial OK]      │
│                                                    │
│  [  Buy  ]  [  Sell  ]   [  Stop  ]               │
│                                                    │
│ [☐ Prepare from bank first]  ← disabled           │
│   (tooltip: "Available after sequence banking      │
│    proof lands")                                    │
│                                                    │
│ Status: idle                                       │
│ ▸ Last 8 telemetry records (scrollable)           │
└────────────────────────────────────────────────────┘
```

The "Prepare from bank first" checkbox is **disabled** (not hidden) with tooltip text:
"Available after sequence banking proof lands". It will be enabled in Phase B to select
`buyWithBankPrep` / `sellWithBankPrep`.

Item-id field is the load-bearing one; name is decorative.
Buy/Sell buttons disable while a task is in flight. Stop calls `script.stop()`.
Telemetry polled from `script.status()` via Swing `Timer` once per second.

### 12.3 No `RecorderConfig` flag

First proof is dev-only via the panel. No config flag needed.

## 13. Tests (20 scenarios)

All new tests live under
`runelite-client/src/test/java/net/runelite/client/sequence/activities/ge/`.
Foundation tests live under
`runelite-client/src/test/java/net/runelite/client/sequence/`.

| # | Scenario | Verifies |
|---|---|---|
| 1 | Already at GE, GE already open, no conflicts → buy happy path | End-to-end: `RecordingGeActions` sees `clickOfferSlotButton(0,BUY)` → `selectItem` → `setQuantity(qty)` → `setPrice(p)` → `confirmOffer` → wait → `collect(0)`. No bank actions. Telemetry records started/succeeded per step. `CollectOfferStep` returns `Succeeded` with `inv.count(itemId) >= 1` post-collect. |
| 2 | Sell happy path mirror of #1 | `CollectOfferStep` returns `Succeeded` after observing `inv.count(COINS)` delta ≥ `qty * priceEach`. No bank actions. |
| 3 | Player not at GE | `EnsureAtGrandExchangeStep.onStart` records `K_PRECONDITION_FAILURE = NotAtGrandExchange(area)`; first `check` returns `Failed(NotAtGrandExchange)`; sequence aborts; `MockInputDispatcher.dispatchCount() == 0`. |
| 4 | Insufficient coins for buy | `CreateBuyOfferStep.onStart` reads `inv.count(COINS)` and records `K_PRECONDITION_FAILURE = InsufficientCoins(needed, have)`; first `check` returns `Failed(InsufficientCoins)`; abort. (In GE Core there is no upstream WithdrawItem, so this is the primary guard.) |
| 5 | Insufficient sell items | Mirror of #4: `InsufficientSellItems(itemId, needed, have)`. |
| 6 | Existing active BUY offer for same item | `EnsureNoConflictingOfferStep.onStart` records `K_PRECONDITION_FAILURE = GeExistingOfferConflict(slot, BUY, itemId, ACTIVE)`; abort. |
| 7 | All 8 GE slots full | `StartOfferStep.onStart` records `GeSlotsFull`; abort. |
| 8 | GE not open when CreateOffer dispatched | `StartOfferStep.onStart` records `GeNotOpen`; abort. |
| 9 | Offer creation click sent but no offer surfaces within timeout | `ConfirmOfferStep.check` returns `Failed(GeOfferRejected)`; `Retry(2)` then abort. |
| 10 | Offer created with wrong quantity | `Failed(GeOfferQuantityMismatch)`; abort. |
| 11 | Offer created for wrong item | `Failed(GeOfferItemMismatch)`; abort. |
| 12 | Offer created at wrong price | `Failed(GeOfferPriceMismatch)`; abort. |
| 13 | Buy completes partially within wait window | `WaitForOfferStep` with `acceptPartialOnTimeout=true`. `Succeeded` at timeout with partial. `CollectOfferStep` collects partial; verifies `inv.count(itemId)` delta ≥ completedQty. |
| 14 | Buy does not complete before timeout (no partial accepted) | `acceptPartialOnTimeout=false`. `Failed(GeOfferIncomplete(slot, 0, 10))`. No auto-abort dispatch asserted. |
| 15 | Sell completes partially | Mirror of #13 for SELL side. |
| 16 | Sell does not complete before timeout | Mirror of #14 for SELL side. |
| 17 | Collect dispatched but inventory delta not observed | `CollectOfferStep.check` sees slot EMPTY but inv delta missing. `Failed(GeCollectFailed(slot, expectedItemId, 0))`. |
| 18 | Blocking dialog appears mid-flow during WaitForOffer | Reactive `EnsureNoBlockingInterfaceStep` preempts; dispatch verified via `MockInputDispatcher`; after dismiss the linear frame resumes. |
| 19 | GE widget open during CreateBuyOffer does NOT trigger reactive | Allow-list = `[GE_ROOTS]`. Reactive `EnsureNoBlockingInterfaceStep.canStart=false` because GE is allowed; sequence proceeds. |
| F1 | Foundation: `Completion.failed(reason)` carries `diagnostic` field | Unit test on `Completion` + `Failure` plumbing. |
| F2 | Foundation: `Retry(N)` is cumulative (N+1 total attempts) | Engine unit test — `onFailure` returning `Retry(1)` endlessly still bounds at 2 total attempts. |
| F3 | Foundation: STEP scope clears after terminal, not mid-tick | Engine unit test — STEP scope survives between `onStart` and same-tick `check`. |
| F4 | Foundation: `canStart=false` gate blocks step without accruing ticks | Engine unit test. |
| F5 | Foundation: `WorldSnapshot` default methods return empty views | Unit test: an impl not overriding `grandExchange()` returns `GrandExchangeView.empty()`. |

Plus regression: all existing engine tests (LoginRunner, etc.) must still pass.

### 13.1 TDD discipline

Implementation follows `superpowers:test-driven-development`: failing test → minimal impl
→ green → commit. Step tests first; integration (`GrandExchangeSequenceFactoryTest`) last.

## 14. Documentation

### 14.1 `sequence/ARCHITECTURE.md`

Created by this branch (not banking). Includes:
- Engine overview + invariant list (§5.4).
- Case study: `GrandExchangeSequenceFactory.buyCore`.
- Sub-LinearSequence pattern (`CreateBuyOfferStep`).
- Slot-key blackboard pattern.
- "How to write a new step" checklist.

Banking adds a case study section when it lands.

### 14.2 No `CLAUDE.md` changes

No changes needed.

### 14.3 Inline javadoc

Every new step has a class-level doc listing pre-conditions, the single `onStart` action,
success criteria, possible `GeBlockReason`s / `BlockReason`s, and recovery policy.

## 15. Acceptance criteria

A reviewer should verify:

- [ ] `./gradlew :client:compileJava` passes from a fresh checkout of this branch against
      `master`. No banking foundation is required. GE branch is self-sufficient.
- [ ] No file in the §4 no-touch list has been edited.
- [ ] `GrandExchangeView` is on `WorldSnapshot` as a `default` method returning
      `GrandExchangeView.empty()`. `InventoryView` and `InteractionView` likewise have
      default methods.
- [ ] `GrandExchangeOfferView` records carry slot, side, status, itemId, quantities,
      price, spent fields per §6.
- [ ] `GeBlockReason` is a sealed sub-interface of `DiagnosticReason`. All GE typed
      reasons are records of `GeBlockReason`.
- [ ] `BlockReason` is a sealed sub-interface of `DiagnosticReason` with at minimum
      `PinKeypadUp`, `WorldInteractionBlocked(BlockingInterface)`,
      `NotAtLocation(WorldArea)`.
- [ ] No GE step holds mutable instance state. Working memory in `BlackboardScope.STEP`.
- [ ] `PricePolicy` is sealed and only `Exact` is implemented.
- [ ] `BuyItemIntent` and `SellItemIntent` are records with the fields in §8.1.
- [ ] `GrandExchangeSequenceFactory.buyCore(...)` and `.sellCore(...)` return
      `GrandExchangeSequencePlan(root, reactiveSteps)`. No `bankBoothIds` or
      `BankActions` parameters. Reactives include `EnsureNoBlockingInterfaceStep`
      with allow-list = `[GE_ROOTS]` only (no `BANK_ROOTS`).
- [ ] `CreateBuyOfferStep` and `CreateSellOfferStep` are `LinearSequence`s of five
      sub-steps (§9.3). Each sub-step does at most one mutating dispatch.
- [ ] `CreateBuyOfferStep.onStart` checks `inv.count(COINS) >= qty*priceEach`; fatal
      `InsufficientCoins` if not.
- [ ] `CreateSellOfferStep.onStart` checks `inv.count(itemId) >= qty`; fatal
      `InsufficientSellItems` if not.
- [ ] `WaitForOfferStep` does NOT auto-abort the offer. `MockInputDispatcher` shows no
      cancel-offer dispatch. Test #14 asserts.
- [ ] `CollectOfferStep` verifies BOTH slot → EMPTY AND inventory delta.
      `Failed(GeCollectFailed)` if either unmet.
- [ ] All 20 GE scenario tests + F1–F5 foundation tests pass; all existing engine tests
      green.
- [ ] `GrandExchangeScript` is engine-only, no daemon thread, no enum FSM.
      `onGameTick` is the entry point.
- [ ] `RecorderPanel` tab is labeled "GE Core Mode" with the pre-flight note displayed.
      Buy/Sell buttons disable while task in flight. "Prepare from bank first" checkbox
      visible but disabled, tooltip: "Available after sequence banking proof lands".
- [ ] `GrandExchangeScript` has no `BankInteraction` or `bankBoothIds` constructor params.
- [ ] `geManager.run(plan.root())` is try/catch-guarded.
- [ ] No GE step imports `net.runelite.api.widgets.Widget`.
- [ ] No GE step calls `dispatcher.dispatch(...)` directly.

## 16. Non-goals (re-emphasized)

- No combat / cooking / chicken-farm / Lumbridge-walker migration.
- No transport-aware walking inside the engine.
- No RuneLite event push into the GE engine. Verification snapshot-based.
- No bank-prep in GE Core (Phase A).
- No deletion or modification of `CookingScript.java` in this branch.
- No new dispatcher.
- No automated price source. `PricePolicy.Exact` only.
- No automated offer-cancel on timeout.
- No GE world-hop logic.
- No item-name lookup at the step layer.

## 17. Open questions / next-after-proof

### Phase B: bank-prep composition

Once the banking step library lands, two factory methods are added to
`GrandExchangeSequenceFactory`:

**`buyWithBankPrep(BuyItemIntent, WorldArea geArea, List<Integer> bankBoothIds, BankActions bank, GeActions ge)`**

Composes: `EnsureAtGrandExchange → EnsureNoBlockingInterface(BANK_ROOTS + GE_ROOTS) → OpenBank → WaitForBankReady → WithdrawItem(COINS, AtLeast(totalCost)) → CloseBank → OpenGrandExchange → EnsureNoConflictingOffer → CreateBuyOffer → WaitForOffer → CollectOffer`.

`CreateBuyOfferStep.onStart`'s `InsufficientCoins` check becomes a defensive post-withdraw guard.

**`sellWithBankPrep(SellItemIntent, WorldArea geArea, List<Integer> bankBoothIds, BankActions bank, GeActions ge)`**

Mirror: `WithdrawItem(itemId, AtLeast(qty))` instead of coins.

The `RecorderPanel`'s disabled "Prepare from bank first" checkbox will be enabled in Phase
B to select these variants. `GrandExchangeScript` gains `startBuyWithPrep` /
`startSellWithPrep` methods that call the bank-prep factory variants, and constructor
params for `BankInteraction` / `bankBoothIds`.

### Other deferred items

| # | Question | Next step |
|---|---|---|
| 1 | `PricePolicy.CurrentGuidePrice` source | RuneLite `ItemManager.getItemPrice(itemId)`; defer. |
| 2 | Bank-collect mode | Add `CollectMode { INVENTORY, BANK }`; defer. |
| 3 | Auto-cancel on timeout | `AbortOfferStep` cleanup path; out of scope per §3. |
| 4 | Item search robustness | Pass `itemId` directly; later proof: structured `selectItemByExactName`. |
| 5 | Multi-task queue | Future panel could queue tasks. |
| 6 | RuneLite event push | `@Subscribe GrandExchangeOfferChanged` push. Cross-vertical. |
| 7 | Telemetry surfacing | `GeTelemetryPanel` paralleling banking's open question #6. |
| 8 | World-hop / GE limit awareness | `WaitForBuyLimitResetStep` reading `getLimitResetTime(itemId)`. |

## 18. Worktree + commit hygiene

### 18.1 Worktree setup

```bash
git worktree add ../worktree-sequence-ge-proof -b worktree-sequence-ge-proof master
```

GE branch is self-sufficient from `master`. No rebase onto a banking ref needed for Phase A.

When banking lands, rebase GE onto the banking commit so the foundation overlap merges
cleanly. Banking only adds to the foundation (new `BlockReason` records, `BankView`,
`EventFacts`, `WidgetView`) — GE's additions are strictly additive so the rebase is
low-conflict.

### 18.2 Commit cadence

Phase A small, reviewable commits:
- `sequence(foundation): DiagnosticReason + BlockReason + GeBlockReason sealed types`
- `sequence(foundation): Completion/Failure diagnostic plumbing`
- `sequence(foundation): engine lifecycle fixes (canStart, Retry, STEP-scope, passthrough)`
- `sequence(foundation): SequenceManager extensions + InputOwnership + blackboard keys`
- `sequence(foundation): WorldSnapshot view scaffolding (Inventory, Interaction, GE)`
- `sequence(ge): GrandExchangeView + observer`
- `sequence(ge): GE step library — guards`
- `sequence(ge): GE step library — create offer sub-steps`
- `sequence(ge): GE step library — wait + collect`
- `sequence(ge): GrandExchangeSequenceFactory (buyCore / sellCore)`
- `recorder(ge): GrandExchangeScript`
- `recorder(ge): GE Core panel tab + RecorderPlugin wiring`

### 18.3 No BSD copyright headers on new files

Private fork; skip the header on new files.

### 18.4 Build verification

`./gradlew :client:compileJava` after every commit. Do not pipe to `tail` without
`set -o pipefail`.

## 19. Appendix — type quick reference

```java
// Foundation — GE branch creates / modifies (banking rebases onto these)
sequence/Completion.java                    +Failed.diagnostic + failed(DiagnosticReason) factory
sequence/Failure.java                       +diagnostic field + fromDiagnostic factory
sequence/WorldSnapshot.java                 +default inventory(), interaction(), grandExchange()
sequence/SequenceEngine.java                +clearReactives, registerReactive
sequence/SequenceManager.java               +clearReactives, registerReactive, setInputOwnership
sequence/internal/StateDrivenEngine.java    canStart gate, Retry cumulativity, STEP-scope, diagnostic passthrough
sequence/internal/PriorityPlanner.java      telemetry-on-reject
sequence/internal/ClientObserver.java       +InventoryObserver, InteractionObserver, GrandExchangeObserver
sequence/dispatch/InputOwnership.java                        (NEW)
sequence/blackboard/SequenceBlackboardKeys.java              (NEW)
sequence/affordance/DiagnosticReason.java                    (NEW — engine-generic sealed parent)
sequence/affordance/BlockReason.java                         (NEW — shared; GE adds PinKeypadUp, WorldInteractionBlocked, NotAtLocation)
sequence/affordance/BlockingInterface.java                   (NEW)
sequence/views/InventoryView.java                            (NEW)
sequence/views/InteractionView.java                          (NEW)
sequence/views/InteractionMode.java                          (NEW — enum)
sequence/views/ItemStack.java                                (NEW)
sequence/views/ImmutableWorldSnapshot.java                   (NEW — inspect existing engine first)
sequence/internal/InventoryObserver.java                     (NEW)
sequence/internal/InteractionObserver.java                   (NEW)

// GE-specific — GE branch creates
sequence/views/GrandExchangeView.java                        (NEW — interface)
sequence/views/GrandExchangeOfferView.java                   (NEW — record)
sequence/views/OfferSide.java                                (NEW — enum)
sequence/views/OfferStatus.java                              (NEW — enum)
sequence/internal/GrandExchangeObserver.java                 (NEW)
sequence/affordance/GeBlockReason.java                       (NEW — GE-domain sealed sub-interface)
sequence/activities/EnsureNoBlockingInterfaceStep.java       (NEW — shared, NOT under banking/)
sequence/activities/ge/GeActions.java                        (NEW — interface)
sequence/activities/ge/GeInteraction.java                    (NEW — production impl)
sequence/activities/ge/BuyItemIntent.java                    (NEW — record)
sequence/activities/ge/SellItemIntent.java                   (NEW — record)
sequence/activities/ge/PricePolicy.java                      (NEW — sealed, only Exact)
sequence/activities/ge/OfferWaitPolicy.java                  (NEW — record)
sequence/activities/ge/GeBlackboardKeys.java                 (NEW — K_GE_OFFER_SLOT)
sequence/activities/ge/EnsureAtGrandExchangeStep.java        (NEW)
sequence/activities/ge/OpenGrandExchangeStep.java            (NEW)
sequence/activities/ge/EnsureNoConflictingOfferStep.java     (NEW)
sequence/activities/ge/CreateBuyOfferStep.java               (NEW — factory → LinearSequence)
sequence/activities/ge/CreateSellOfferStep.java              (NEW — factory → LinearSequence)
sequence/activities/ge/StartOfferStep.java                   (NEW — sub-step)
sequence/activities/ge/SelectItemStep.java                   (NEW — sub-step)
sequence/activities/ge/SetQuantityStep.java                  (NEW — sub-step)
sequence/activities/ge/SetPriceStep.java                     (NEW — sub-step)
sequence/activities/ge/ConfirmOfferStep.java                 (NEW — sub-step; writes K_GE_OFFER_SLOT)
sequence/activities/ge/WaitForOfferStep.java                 (NEW)
sequence/activities/ge/CollectOfferStep.java                 (NEW — verifies slot=EMPTY + inventory delta inline)
sequence/activities/ge/GrandExchangeSequenceFactory.java     (NEW — buyCore / sellCore; buyWithBankPrep / sellWithBankPrep: Phase B)
sequence/activities/ge/GrandExchangeSequencePlan.java        (NEW — record(root, reactiveSteps))
recorder/scripts/GrandExchangeScript.java                    (NEW)
recorder/RecorderPanel.java                                  +GE Core tab
recorder/RecorderPlugin.java                                 +GrandExchangeScript injection

// Deferred to banking branch
sequence/views/BankView.java                                 banking adds
sequence/views/EventFacts.java                               banking adds
sequence/views/WidgetView.java                               banking adds
sequence/internal/BankObserver.java                          banking adds
sequence/internal/WidgetObserver.java                        banking adds
sequence/activities/banking/**                               banking creates
// BlockReason additions banking makes:
BlockReason.BankNotOpen, .BankMissingItem, .WithdrawNoOp, .BankContentsUnknown, .InventoryFull

// Tests (Phase A; mirror tree)
runelite-client/src/test/java/.../sequence/
  FoundationCompletionFailureTest.java           (F1)
  FoundationRetryBoundTest.java                  (F2)
  FoundationStepScopeOrderTest.java              (F3)
  FoundationCanStartGateTest.java                (F4)
  WorldSnapshotDefaultViewsTest.java             (F5)

runelite-client/src/test/java/.../sequence/activities/ge/
  GeSnapBuilder.java                       (test fixture builder)
  RecordingGeActions.java                   (mirrors RecordingBankActions)
  GeEngineHarness.java
  EnsureAtGrandExchangeStepTest.java
  OpenGrandExchangeStepTest.java
  EnsureNoConflictingOfferStepTest.java
  CreateBuyOfferStepTest.java               (covers sub-LinearSequence)
  CreateSellOfferStepTest.java
  WaitForOfferStepTest.java
  CollectOfferStepTest.java
  GrandExchangeSequenceFactoryTest.java     (buyCore + sellCore happy path + reactive preemption)
  GrandExchangeOfferStateMappingTest.java   (covers §6.1 mapping)
  GrandExchangeViewTest.java
```
