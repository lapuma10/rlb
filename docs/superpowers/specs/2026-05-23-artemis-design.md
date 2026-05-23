# Artemis v1 — Design

**Status:** REVISED 2026-05-23 (round 4 — reconciliation with `docs/learnings/2026-05-23-artemis-migration-audit.md` after Phase 0A landed in 3 commits). Three earlier review passes the same date: first surfaced 11 design contradictions (§2-§16), second surfaced 6 phasing/scoping issues (§10, §18, §19), third applied a neutral-engineering wording pass and split Phase 0A into three micro-commits (§19). Round 4 (this revision) absorbs nine reconciliation items from the migration audit: widget support promoted to v1.0 (§4-§6, §8, §11), per-query `RotationPolicy` defaults (§7), `LUMBRIDGE_BANK` zones added (§9), Step threading contract pinned per category (§12), `LinearSequence.then(...)` failure semantics pinned (§12.5), per-Step retry budget specified (§12.6), `tapKey` split into typed future Steps (§11, §18), `combat/` helper import allow/ban list (§14), Phase 6 migration order reconciled with audit (§19). Phase 0A.1/0A.2/0A.3 are already committed; Phase 1A starts after this revision is re-approved.

**Companion to:** [`/Users/lilbee/.claude/plans/that-sounds-fucking-great-polymorphic-wren.md`](../../../.claude/plans/that-sounds-fucking-great-polymorphic-wren.md) (the 7-phase plan), `docs/learnings/2026-05-23-retrospective-and-process-changes.md` (the why), `CLAUDE.md` §10 (click sampling rule), `docs/learnings/2026-05-22-click-pattern-fingerprints.md` + `2026-05-23-target-identity-rotation.md` (symptom registers Artemis subsumes).

## 1. Goal

A single script-facing interface — `Artemis` — that becomes the **only** way scripts interact with the OSRS client. Scripts cannot construct `ActionRequest`, cannot call `HumanizedInputDispatcher.dispatch()`, cannot read `Widget.getBounds()`, cannot reach into engine internals. Artemis owns: click sampling per CLAUDE.md §10, target rotation per `RotationPolicy`, UI occlusion mask, session shape (breaks/logout/budget), per-account RNG seeding, recording, stale-reference detection.

**Permanent rule:** Scripts express *what*. Engine owns *how*.

After Artemis lands, this becomes impossible:
```
script chooses pixel → script dispatches click
```
Only this remains:
```
script declares intent → Artemis resolves target → engine samples valid shape → dispatcher executes → recorder logs
```

## 2. Non-goals (v1)

- **No blocking imperative wrappers.** Action methods MAY return `Step` (which is composable, not blocking) — that is canonical, not convenience. What's banned is *blocking* calls like `void openBank()` that hide a sequence behind a synchronous interface. Returning `Step openBank()` is correct and required.
- **No backward-compat shim.** Legacy scripts keep calling engine pieces directly until their migration phase; the grep gate's allow-list shrinks per-script. No `Artemis.legacy.*` surface, ever.
- **No new low-level dispatch.** Artemis is a facade. If something is missing in the engine, fix the engine, don't reinvent it in Artemis.
- **No GUI / config surface.** Per-account config (RotationPolicy defaults, IdlePolicy windows) is set at construction by `RecorderPlugin`; v1 Artemis exposes no runtime mutators.
- **No async/Future/Promise primitives.** Returns Steps; sequence engine runs them.
- **No script-side ActionRequest construction.** Period.

## 3. Design principles

1. **One model.** Every action method returns `Step`. No exceptions, no overload that returns void.
2. **Engine reuse over reinvention.** Artemis delegates to existing interfaces (`BankActions`, `GeActions`) and utilities (`NpcSelector`, `SceneScanner`, `PixelResolver` — *the class* survives as the architectural home for click-pixel resolution; what dies in Phase 3 is the broken `sampleNearCentroid` *method* plus the rest of CLAUDE.md §10's BANNED list, replaced by `sampleInsideShape`). New primitives = new Step subclasses wrapping `ActionRequest`, not a new dispatch layer.
3. **Threading contract is the engine's contract.** Reads run on the client thread, return synchronously. Action Steps' lifecycle runs on the client thread per `sequence/Step.java:29-43`; blocking work goes to dispatcher worker via `ActionRequest.RUN_TASK` per `CloseBankStep.java` pattern.
4. **Identity rotation is first-class.** Every selection point takes a `RotationPolicy`. Defaults favour variety with stickiness — never "strict closest + index tiebreak."
5. **Session shape gates *gameplay* actions, not maintenance.** Gameplay Steps (click, take, useOn, walkTo for non-safe-zone, openBank/openGe when in v1.x) check `session.shouldContinue()`. **Maintenance Steps (`idle`, `logout`, `walkToSafeZone` once added) bypass the gate** — refusing to start `logout()` because the session is over would be a deadlock.
6. **Every Step records.** Lifecycle events (`onStart`, `check`-completion, `onFailure`) write to `RecorderManager`. Phase 7 diversity dashboard reads from these. Invisible to scripts.
7. **Per-account RNG seed is global.** Set once at construction in `ArtemisImpl` from `client.getAccountHash()`. Propagates to `RotationPolicy`, `PixelResolver`, `WindMouse`, `IdlePolicy`. One seed, one identity per account.
8. **Stale-reference safety.** View records carry `observedTick`; action Steps re-verify the live entity matches the ref (id, name, near-loc, still visible) before pressing. Fail-and-requery instead of clicking a despawned target or an index-reused NPC.

## 4. v1 scope (explicit)

**In v1** (the smallest slice that proves the architecture):

| Surface | Methods |
|---|---|
| Reads | `findNpc`, `findObject`, `findItem`, `findWidget`, `inventory`, `player`, `session` |
| Actions | `click(NpcRef, verb)`, `click(GameObjRef, verb)`, `click(WidgetRef, verb)`, `take(GroundItemRef)`, `useOn(InvSlot, InvSlot \| NpcRef \| GameObjRef \| WidgetRef)`, `walkTo(WorldPoint)`, `walkTo(NamedZone)`, `idle(IdlePolicy)`, `logout()` |
| Composition | `plan(name)` |
| Optional outcome variant | `click(target, verb, OutcomeCheck)` for callers that need state-change verification |

**Widget support is in v1.0** (was nearly deferred). Audit `docs/learnings/2026-05-23-artemis-migration-audit.md` §3.7 shows 10 of 25 scripts already do raw `client.getWidget(id)` reaches (24 callsites). Bank reaches are heavier in raw count (189), but bank is already roadmapped to v1.1 — deferring widget too would leave the heaviest *not-yet-roadmapped* category unaddressed when the first bank-tier migrations start. Widget surface is small (one `WidgetRef` view record + `WidgetQuery` + `findWidget` + `click(widget, verb)`) and clean to bolt onto v1.0.

**Deferred to v1.1**: bank methods (`openBank`, `deposit`, `depositAll`, `withdraw`, `closeBank`). Cow killer pilot adapts — v1 pilot kills until inventory full, then `logout()`. v1.1 adds bank → cycle becomes kill→bank→repeat.

**Deferred to v1.2**: GE methods (`openGe`, `buyItem`, `sellItem`, `collectGe`, `closeGe`).

**Deferred indefinitely**: `login(world)` — too large (credentials, launcher state, world hopping, failure recovery). When needed, gets its own spec and integration with `AccountLauncher`. `depositAllExcept` deferred even past v1.1 unless we land conservative per-item deposit only (no deposit-orb-plus-re-withdraw strategy).

## 5. The interface

`runelite-client/src/main/java/net/runelite/client/sequence/artemis/Artemis.java`:

```java
package net.runelite.client.sequence.artemis;

import java.util.Optional;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.composite.SequencePlanBuilder;
import net.runelite.client.sequence.artemis.query.NpcQuery;
import net.runelite.client.sequence.artemis.query.ObjectQuery;
import net.runelite.client.sequence.artemis.query.ItemQuery;
import net.runelite.client.sequence.artemis.query.WidgetQuery;
import net.runelite.client.sequence.artemis.view.NpcRef;
import net.runelite.client.sequence.artemis.view.GameObjRef;
import net.runelite.client.sequence.artemis.view.GroundItemRef;
import net.runelite.client.sequence.artemis.view.WidgetRef;
import net.runelite.client.sequence.artemis.view.InvSlot;
import net.runelite.client.sequence.artemis.view.InventoryView;
import net.runelite.client.sequence.artemis.view.PlayerState;
import net.runelite.client.plugins.recorder.session.SessionShape;
import net.runelite.client.sequence.artemis.session.IdlePolicy;
import net.runelite.client.sequence.artemis.zones.NamedZone;
import net.runelite.client.sequence.artemis.outcome.OutcomeCheck;

/**
 * Single script-facing API for automation scripts. Engine owns the rules; scripts
 * express intent. See {@code docs/superpowers/specs/2026-05-23-artemis-design.md}.
 *
 * <p>Reads return synchronously; Action methods return {@link Step}.
 * Scripts that depend on this interface MUST NOT also import the engine
 * internals listed in section 14 (Import policy). Enforced by grep gate.
 */
public interface Artemis {

    // ── READS — synchronous, return Optional<view-record> ─────────

    Optional<NpcRef> findNpc(NpcQuery query);
    Optional<GameObjRef> findObject(ObjectQuery query);
    Optional<GroundItemRef> findItem(ItemQuery query);
    Optional<WidgetRef> findWidget(WidgetQuery query);
    InventoryView inventory();
    PlayerState player();
    SessionShape session();

    // ── ACTIONS — return Step ─────────────────────────────────────

    Step walkTo(WorldPoint target);
    Step walkTo(NamedZone zone);

    /** Base click — succeeds when menu verb verified before press AND
     *  dispatcher reported completion. Does NOT assume animation change
     *  or state change. Use the OutcomeCheck overload for those. */
    Step click(NpcRef target, String verb);
    Step click(GameObjRef target, String verb);
    Step click(WidgetRef target, String verb);

    /** Outcome-verifying click — succeeds when base success criteria PLUS
     *  the OutcomeCheck.matches() returns true within its budget. Caller
     *  supplies the expected post-condition. Common OutcomeChecks:
     *  {@link OutcomeCheck#playerAnimChanged(int)},
     *  {@link OutcomeCheck#targetAnimChanged(int)},
     *  {@link OutcomeCheck#widgetVisible(int)},
     *  {@link OutcomeCheck#interactingWithMe()}. */
    Step click(NpcRef target, String verb, OutcomeCheck expected);
    Step click(GameObjRef target, String verb, OutcomeCheck expected);
    Step click(WidgetRef target, String verb, OutcomeCheck expected);

    Step take(GroundItemRef item);

    Step useOn(InvSlot source, InvSlot target);
    Step useOn(InvSlot source, GameObjRef target);
    Step useOn(InvSlot source, NpcRef target);
    Step useOn(InvSlot source, WidgetRef target);

    // ── Maintenance (bypass session.shouldContinue gate) ──────────

    Step idle(IdlePolicy policy);
    Step logout();

    // ── Composition ───────────────────────────────────────────────

    SequencePlanBuilder plan(String name);
}
```

`ArtemisImpl` lives in the same package. Constructor takes the engine pieces; scripts only see the interface.

## 6. Query types

Immutable records with fluent builders. `NpcQuery` example:

```java
public record NpcQuery(
    String name, Integer id, int rangeTiles, int plane,
    Set<Integer> excludeIndices, boolean unengagedOnly,
    RotationPolicy rotation
) {
    public static NpcQuery byName(String name) { ... }
    public static NpcQuery byId(int id) { ... }
    public NpcQuery within(int tiles) { ... }
    public NpcQuery onPlane(int plane) { ... }
    public NpcQuery exclude(int npcIndex) { ... }
    public NpcQuery unengagedOnly() { ... }
    public NpcQuery rotation(RotationPolicy policy) { ... }
}
```

`ObjectQuery` and `ItemQuery` follow the same shape. `ItemQuery` adds `minQuantity`.

`WidgetQuery` is shaped slightly differently — widgets are addressed by `int widgetId` (from `InterfaceID.*`) with optional child-slot selector and a "must be visible up the parent chain" gate. **No `RotationPolicy` field** — a widget id resolves to one specific widget (rotation has no meaning); multi-child selection inside a widget container is its own concern handled via `child(int)` or, for "any matching row", a Step-level loop:

```java
public record WidgetQuery(
    int widgetId, Integer childSlot,
    boolean requireVisible
) {
    public static WidgetQuery byId(int widgetId) { ... }
    public WidgetQuery child(int slotIdx) { ... }
    public WidgetQuery visible() { ... }              // default = required
}
```

The `requireVisible` flag walks `Widget.isHidden()` up the parent chain (CLAUDE.md §1 ban on dispatching to hidden widgets); `findWidget` returns `Optional.empty()` if any ancestor is hidden.

## 7. `RotationPolicy`

Sealed interface, four cases for v1 (generic `Weighted` deferred):

```java
public sealed interface RotationPolicy {
    /** Strict closest. Tiebreak: random among ties (NEVER NPC-index tiebreak). */
    record Closest() implements RotationPolicy {}

    /** Random pick from any target within {@code slack} tiles of the closest. */
    record ClosestWithSlack(int slack) implements RotationPolicy {}

    /** Uniform random over every match in range. */
    record UniformWithinRange() implements RotationPolicy {}

    /** Sticky pick — per-account-seeded RNG biases toward a specific instance
     *  for {@code stickyMs}, then drifts. Models "this account tends to use
     *  the SE booth most of the time but sometimes uses the others." */
    record SessionSticky(int stickyMs, String stickinessKey) implements RotationPolicy {}
}
```

**Per-query-type defaults** (replaces the earlier single global default; the audit observed that scripts today pick different defaults per target kind, and a single global default fights the observed pattern):

| Query type | Default `RotationPolicy` | Rationale |
|---|---|---|
| `NpcQuery` | `ClosestWithSlack(2)` | Audit data: scripts pick this ~80% of the time for NPCs today; 1-tile slack is often too tight (e.g. one cow standing slightly closer than three equally-good cows) |
| `ObjectQuery` | `ClosestWithSlack(1)` | Objects are sparser (one bank booth, one stair); strict-or-near-strict matches observed pattern |
| `ItemQuery` | `ClosestWithSlack(1)` | Ground items are typically on the player's tile or one tile away; tight default |
| `WidgetQuery` | n/a — no `RotationPolicy` field (see §6) | Widget id resolves to one widget; rotation has no semantic meaning |
| `NamedZone` (via `walkTo`) | `UniformWithinRange()` | Tile-set within a named area; vary the final standing tile per cycle to displace the "always lands on the same tile" pattern |

Typed weighted variants (`WeightedNpc`, `WeightedObject`) deferred to v1.1+ when a concrete need surfaces.

## 8. View records — stale-reference safe

Every reference-bearing record carries `observedTick` so Steps can detect stale refs and fail-and-requery instead of clicking the wrong entity.

```java
public record NpcRef(
    int index,             // engine index — may be reused after despawn
    int id,                // NPC definition id (chicken = X, cow = Y)
    String name,           // markup-stripped display name
    WorldPoint originalLoc,// where it was when findNpc returned
    int healthRatio,       // 0 = dying/dead, -1 = unknown
    long observedTick      // client.getTickCount() at find time
) {}

public record GameObjRef(
    int id, String name, WorldPoint originalLoc,
    ObjectKind kind, long observedTick
) {}

public record GroundItemRef(
    int itemId, String name, int quantity,
    WorldPoint originalLoc, long observedTick
) {}

public record WidgetRef(
    int widgetId,           // InterfaceID.* — e.g. Bankmain.UNIVERSE
    Integer childSlot,      // null for leaf widget; set for child-by-slot
    int itemId,             // -1 when not an item-bearing widget (e.g. inv slot widgets)
    long observedTick       // client.getTickCount() at findWidget time
) {}

public record InvSlot(int slotIdx, int itemId, int quantity, String name) {}

public record InventoryView(List<InvSlot> slots) {
    public int count(int itemId);
    public boolean has(int itemId);
    public boolean isFull();
    public Optional<InvSlot> firstSlotOf(int itemId);   // uses default RotationPolicy
    public List<InvSlot> allSlotsOf(int itemId);
}

public record PlayerState(
    WorldPoint loc, int plane, int animation,
    int hp, int prayer, int energy, boolean idle
) {}
```

**Re-resolution contract** (enforced inside every action Step's `onStart`):

```
1. Re-fetch live entity by ref's index/id (for WidgetRef: widget+childSlot).
2. Verify match:
   - same definition id (NPC id, object id, item id, widget id)
   - same name where applicable (markup-stripped, case-insensitive)
   - location within 2 tiles of originalLoc OR target has moved naturally
     (NPC patrolling, item still on same tile; not applicable to WidgetRef)
   - still on the same plane (not applicable to WidgetRef)
   - still visible — for WidgetRef this means Widget.isHidden() returns false
     up the full parent chain (CLAUDE.md §1)
3. Verify freshness:
   - now - observedTick ≤ STALE_REF_BUDGET_TICKS (default: 8 ticks ≈ 4.8 s)
4. If any check fails → Step.check returns Failed with DiagnosticReason.STALE_REF;
   `onFailure` returns `Recovery.Retry(...)` (re-query upstream on next attempt)
   or `Recovery.Abort(...)` per §12.6 retry budget.
```

## 9. `NamedZone`

Enum in `sequence/artemis/zones/NamedZone.java`. v1 seeded:

```java
public enum NamedZone {
    LUMBRIDGE_CASTLE_GROUND_FLOOR,
    LUMBRIDGE_CASTLE_P1,
    LUMBRIDGE_CASTLE_P2,
    LUMBRIDGE_BANK,           // most-walked zone per migration audit §7 Q6
    LUMBRIDGE_BANK_P2,        // bank booth area on castle top floor
    LUMBRIDGE_COW_FIELD,
    LUMBRIDGE_CHICKEN_PEN,    // for ChickenFarmV4 in Phase 5
    GRAND_EXCHANGE,
    ;
    public List<WorldPoint> tiles();   // walkable tile set
    public int plane();
}
```

`walkTo(NamedZone)` picks a tile inside `tiles()` per the zone's configured `RotationPolicy` (default `UniformWithinRange` — varies the final standing tile per cycle, fixes target-identity register entry #14).

## 10. `IdlePolicy`

```java
public record IdlePolicy(int minMs, int maxMs, boolean logoutPreferred) {
    // v1.0 ships in-game idle only — logoutPreferred is always false here.
    // Logout-then-re-login variants (NATURAL_BREAK, MEAL) need AccountLauncher
    // integration on wake and are deferred to v1.x. See §18 roadmap.
    public static final IdlePolicy PHONE_GLANCE = new IdlePolicy(30_000,  90_000,  false);
    public static final IdlePolicy BATHROOM     = new IdlePolicy(180_000, 270_000, false);
}
```

`idle(policy)` in v1.0: in-game idle for the sampled duration (minor off-canvas mouse movement, no clicks). No re-login.

For a one-way "stop the session" pattern in v1.0, scripts chain `idle(PHONE_GLANCE).then(logout())` — `logout()` is a one-way Step that succeeds at the login screen and ends the run. **A v1.3 extension** (per §18 row) adds `NATURAL_BREAK` / `MEAL` with `logoutPreferred=true` semantics (logout + sleep + AccountLauncher re-login + resume); not in v1.0 because `login(world)` is deferred indefinitely (see §4) and we don't want the contradiction.

## 11. Step return-type contracts

| Method | Step succeeds on | Step fails on |
|---|---|---|
| `walkTo(WorldPoint)` | Player loc ±1 tile of target for 2 consecutive ticks | No route, transport failure, 60-tick timeout |
| `walkTo(NamedZone)` | Player loc inside zone's tile set for 2 consecutive ticks | Same as above |
| `click(NpcRef \| GameObjRef, verb)` *(base, no OutcomeCheck)* | Re-resolution passed AND menu verb verified before press AND dispatcher reported completion | Stale ref (Retry per §12.6), verb not on menu top after 3 retries, dispatch error, 8-tick timeout |
| `click(WidgetRef, verb)` | Re-resolution passed (widget visible up parent chain) AND `dispatcher.clickWidget(widgetId)` (or `clickBounds(rect)` for child-slot) dispatched AND no dispatcher error | Widget hidden mid-flight, child-slot out of range, stale ref, 6-tick timeout |
| `click(target, verb, OutcomeCheck)` | Base success criteria AND OutcomeCheck.matches() returns true within its budget (default 4 ticks) | Base failure modes OR OutcomeCheck timeout |
| `take(GroundItemRef)` | Re-resolution passed AND inventory gained ≥1 of `item.itemId` within 4 ticks | Item despawned, inventory full, stale ref, 6-tick timeout |
| `useOn(...)` | Re-resolution passed AND use-on dispatched AND target widget/skillmulti visible OR target anim changed within 6 ticks | Slot unresolvable, no widget reaction, stale ref, 8-tick timeout |
| `idle(policy)` | Sampled duration elapsed (and logout/login completed if applicable) | LogoutHelper / AccountLauncher error |
| `logout()` | `client.getGameState() == LOGIN_SCREEN` within 8 ticks | World unreachable, timeout |

**Note: base click contract is deliberately verb-only.** Animation change, target's `getInteracting() == self`, widget visibility — these are all *outcomes*, not all of which apply to every click site. A banker's "Bank" verb produces a widget; a chicken's "Attack" produces interaction; a quest NPC's "Talk-to" produces dialogue. The caller selects the OutcomeCheck that fits — or accepts the verb-verified base contract when no further verification is needed.

`OutcomeCheck` is a sealed interface with concrete cases like `PlayerAnimChanged(int withinTicks)`, `TargetAnimChanged(int withinTicks)`, `WidgetVisible(int widgetId, int withinTicks)`, `InteractingWithMe(int withinTicks)`, `Custom(Predicate<WorldSnapshot> check, int withinTicks)`. New cases added in v1.x as patterns emerge.

**Future-Step planning — typed `tapKey` replacements (NOT in v1.0).** The audit found two distinct use cases for `dispatcher.tapKey(VK_*)`: chatbox Make-All confirms (5+ scripts) and obstacle-key confirms (1 script, agility). Artemis does NOT expose a generic `tapKey` Step — that would re-leak the dispatcher to scripts. Instead, typed Steps land later:

| Step | Use case | Lands in | Threading |
|---|---|---|---|
| `confirmMakeAll(OutcomeCheck quantityConfirmed)` | Cooking/Fletching Make-All chatbox confirm | v1.1 alongside bank (the scripts that need it are bank-tier) | dispatcher-worker (multi-step: wait for prompt → type → confirm) |
| `obstacleKeyConfirm(int vkKey)` | Agility obstacle-key confirm | v1.x (lands when `RooftopAgilityScript` migrates per §19 Phase 6 ordering) | dispatcher-worker (single keypress with surrounding wait) |

## 12. Threading

**Two threading roles, per CLAUDE.md "Threading model" §1-§3 (`read state on client thread, build/decide anywhere, multi-step blocking flows on dispatcher worker NEVER on client thread`):**

| Step category | `onStart` runs on | Multi-step blocking work | Notes |
|---|---|---|---|
| `walkTo(WorldPoint)` / `walkTo(NamedZone)` | client thread — but **only for trivial input (capture target + initial scene snapshot)**; the route-planning work itself runs on the dispatcher worker | dispatcher worker (route planning + execution: solve path, click minimap / click tile, await arrival, handle transports) | Trail and nav-v2.1 planning over long routes (e.g. Lumb→GE) is not free; moving planning to the worker keeps the client thread responsive. `Step.check()` polls arrival on the client thread (cheap; reads player loc only). |
| `click(target, verb)` *(base, no OutcomeCheck)* | client thread (re-resolve ref + check verb on menu) | dispatcher worker (cursor path + click + dispatcher.awaitIdle equivalent) | single-shot; no blocking inside `onStart` |
| `click(target, verb, OutcomeCheck)` | client thread (same as base) | dispatcher worker (click) + client thread (OutcomeCheck.matches polled in `check()`) | OutcomeCheck poll is client-thread-only (reads game state) |
| `take(GroundItemRef)` | client thread (re-resolve + inv-full check) | dispatcher worker (click ground item, await inv gain) | |
| `useOn(InvSlot, target)` | client thread (re-resolve target + inv slot lookup) | dispatcher worker (click source slot → click target) | classic two-step blocking flow — MUST be on dispatcher worker |
| `idle(IdlePolicy)` | client thread (sample duration from per-account RNG) | dispatcher worker (sleep + optional off-canvas mouse movement) | sleep on client thread would freeze cs2; must be worker |
| `logout()` | client thread (LogoutHelper.logout() — quick verb selection) | client thread (LogoutHelper handles the rest synchronously; succeeds when GameState == LOGIN_SCREEN) | one-shot; no multi-step flow |
| `confirmMakeAll(...)` *(future, v1.1)* | client thread (find chatbox prompt visible) | dispatcher worker (wait for cs2 prompt + type quantity + press Enter) | **must be dispatcher worker — the chatbox prompt is opened by cs2 running on the client thread; sleeping the client thread here is the bug class CLAUDE.md §1-§3 warns against** |
| `obstacleKeyConfirm(...)` *(future, v1.x)* | client thread | dispatcher worker (single keypress + brief settle wait) | minimal worker work, but worker nonetheless |

**Scripts never manage threads.** They construct `Artemis` once at startup, call READ methods + chain ACTION methods inside `start()` / sequence-engine-driven methods. The sequence engine ticks `Step.check()` on the client thread per `sequence/Step.java:29-43`; that's the only place script-callable work runs.

**Read methods** are synchronous and may be called from any thread; the impl internally marshals to the client thread via `ClientThread.invoke(...)` if needed and blocks the caller until the read completes. The marshal cost (≤1 frame) is acceptable because reads are infrequent compared to ticks.

## 12.5 Composition semantics (`LinearSequence.then(...)` and friends)

The sequence engine's existing `LinearSequence` (`runelite-client/src/main/java/net/runelite/client/sequence/composite/LinearSequence.java:36-80`) is the canonical composition primitive. Artemis's `plan(name).then(stepA).then(stepB)` builds a `LinearSequence` under the hood. Pinned semantics:

- **Sequential execution.** Each `then(...)` runs the next Step only after the previous one's `check()` returned `Completion.Succeeded` (the only non-`Failed` completion in the current `Completion` sealed type — `Running`, `Succeeded`, `Failed`; see `runelite-client/src/main/java/net/runelite/client/sequence/Completion.java`).
- **Failure short-circuits the plan.** If a Step's `check()` returns `Completion.Failed`, the surrounding `LinearSequence` immediately reports `FinishWithFailure` and any subsequent `then(...)` Steps do NOT run. `SequenceManager` invokes the plan's `onFailure` (if set), otherwise stops the run. Verified in `LinearSequence.onChildPopped(...)`:71-74.
- **No implicit retry inside `then(...)`.** A failed Step does not auto-retry by virtue of being in a sequence. Retries are explicit: the failing Step's `onFailure(...)` returns `Recovery.Retry(maxAttempts)` per §12.6 (the engine then re-invokes the Step), or callers wrap in `Selector(stepA, stepB, ...)` (`composite/Selector.java`, existing engine type) for try-this-then-fallback.
- **No magic recovery.** If a `click` fails because the ref was stale, the click does NOT silently re-find the target inside the same `then(...)` slot. The caller is expected to use the Step's `Recovery.Retry` budget (default 3 attempts re-resolve every attempt per §12.6) or to structure the plan to re-fetch upstream (typical pattern: a `RepeatStep` outer loop that re-finds + clicks each iteration; for cow killer, see §15).
- **Cancellation.** `SequenceManager.cancel()` aborts the running Step and the rest of the `LinearSequence` does not run.

These semantics match the existing engine; Artemis adds no new composition operators in v1.0. v1.x may add ergonomic wrappers (`tryEach`, `repeatUntil`) when concrete needs surface.

## 12.6 Retry budget

**Engine mechanism (existing, not new):** retry is implemented via the existing `Step.onFailure(Failure, WorldSnapshot, Blackboard) → Recovery` contract in `sequence/Step.java:42`. When a Step's `check()` returns `Completion.Failed`, the engine (`internal/StateDrivenEngine.java`, `internal/StepFrame.java`) calls `onFailure(...)` to get a `Recovery` decision. The `Recovery` sealed interface (`sequence/Recovery.java`) has four cases:

```java
public sealed interface Recovery {
    record Retry(int maxAttempts) implements Recovery {}
    record Skip(String reason) implements Recovery {}
    record Abort(String reason) implements Recovery {}
    record JumpToAnchor(String anchorName) implements Recovery {}
}
```

`Recovery.Retry(N)` tells the engine to re-invoke this Step (fresh `onStart`, fresh `check()` polling) up to `N` total attempts. This is the only retry mechanism; there is no separate composite wrapper.

**Artemis Step defaults** (each Artemis-returned action Step implements `onFailure` to return these unless overridden by an `OutcomeCheck` overload that wants different behaviour):

| Failure type | Default `Recovery` | Notes |
|---|---|---|
| `DiagnosticReason.STALE_REF` | `Recovery.Retry(3)` | Re-resolution per §8 happens on the next attempt's `onStart`; doesn't reuse the stale ref |
| Verb not on menu top after 3 internal verb-retries | `Recovery.Retry(2)` | Total attempts = 1 original + 2 retries; menu state may stabilize across ticks |
| Dispatcher reported error (transient) | `Recovery.Retry(2)` | E.g. dispatcher busy at exact dispatch instant |
| Inventory full (for `take`) | `Recovery.Abort("inventory full")` | Not retriable without script-level state change |
| Widget hidden mid-flight (for `click(WidgetRef, ...)`) | `Recovery.Retry(2)` | Widget may un-hide on next tick (interface animation) |
| Target despawned definitively (no entity with that id within 4 tiles for 4 ticks) | `Recovery.Abort("target gone")` | |
| Timeout with no progress | `Recovery.Abort("timeout")` | Per-Step tick timeouts in §11 |

**Backoff between retry attempts** is the existing engine's natural tick cadence — the engine re-invokes the failed Step on the next engine tick after `onFailure` returns `Recovery.Retry(N)`. No artificial backoff sleep. If a Step needs to wait longer between attempts (e.g. wait for a particular interface to animate), the Step's `check()` returns `Completion.Running` and only flips to `Completion.Failed` once truly stuck.

**Scope of retry:** per-Step. `Recovery.Retry(3)` resets nothing about the surrounding `LinearSequence`; the plan's other Steps are unaffected by retries inside one slot.

**Repeated stale-refs across all attempts** produce a final `Completion.Failed(reason, DiagnosticReason.STALE_REF)` after the budget is exhausted; the surrounding `LinearSequence` then short-circuits per §12.5.

**Retry storms** (≥3 consecutive same-target failures across `RepeatStep` iterations, e.g. a body that keeps failing on the same NPC) are flagged in the §19 acceptance rubric. They indicate a structural script bug, not something the per-Step `Recovery.Retry` budget should silently mask.

## 13. Recording

Every Artemis-returned Step writes lifecycle events to `RecorderManager` from `onStart`, `check` (success or failed), `onFailure`. Event payload: step name, target ref, verb, ticks elapsed, diagnostic reason, click pixel (post-resolution). Phase 7 diversity dashboard reads from these. Invisible to scripts.

## 14. Import policy (enforced by grep gate, Phase 4)

**Scripts in `recorder/scripts/` MAY import:**
- `net.runelite.client.sequence.artemis.*` (Artemis, queries, view records, NamedZone, IdlePolicy, OutcomeCheck)
- `net.runelite.client.sequence.Step`, `SequencePlanBuilder`, composite types when explicitly composing
- `net.runelite.api.coords.WorldPoint` (item-id and world-coord constants)
- `net.runelite.api.gameval.ItemID` / similar id constant classes
- `java.util.*`, `java.util.concurrent.*` basics

**Scripts MAY NOT import:**
- `net.runelite.client.sequence.dispatch.*` (HumanizedInputDispatcher, PixelResolver, ActionRequest, PressTiming, WindMouse, InputOwnership)
- `net.runelite.client.sequence.activities.*` (BankActions, GeActions, GeInteraction, all step subclasses — Artemis returns the Steps)
- `net.runelite.client.plugins.recorder.farm.*` (BankInteraction direct)
- `net.runelite.client.plugins.recorder.scene.*` (SceneScanner direct)
- `net.runelite.client.plugins.recorder.combat.NpcSelector` (use NpcQuery instead — banned)
- `net.runelite.client.plugins.recorder.walker.*`, `nav.*`, `trail.*`, `transport.*` (use walkTo)
- `net.runelite.client.plugins.recorder.widget.WidgetActions` (Artemis owns widget interactions — audit confirmed zero scripts currently import this; ban codifies that)
- `net.runelite.client.callback.ClientThread` (scripts don't marshal threads themselves)
- `net.runelite.api.NPC`, `net.runelite.api.GameObject`, `net.runelite.api.Widget` raw types (use NpcRef, GameObjRef, etc.)
- `java.awt.Robot`, `java.awt.event.MouseEvent` (raw input — never)

**`combat/` helper allow-list** (per migration audit §3.8 — distinct from the blanket `combat.NpcSelector` ban above):

| Class | Policy | Why |
|---|---|---|
| `combat.NpcSelector` | **banned** in scripts | Replaced by `Artemis.findNpc(NpcQuery)` (already covered above). |
| `combat.ChickenCombatLoop` | **allowed** as engine-internal helper | High-level combat-loop FSM (manages combat for a target type, doesn't expose low-level input to scripts). **Transitive coupling caveat: `ChickenCombatLoop.java` itself imports `NpcSelector` internally (12 usages). Scripts importing `ChickenCombatLoop` are transitively coupled to `NpcSelector` semantics via composition.** This is acceptable because (a) the coupling is hidden inside the helper, scripts don't call `NpcSelector` methods themselves, and (b) the Phase 6 `ChickenFarmV4` migration will replace `ChickenCombatLoop` callsites with an Artemis `artemis.combatLoop(NpcQuery, TrainingPlan)` wrapper, removing the transitive coupling. Until then, allow-list keeps the legacy scripts compiling. |
| `combat.TrainingSession` | **allowed** as engine-internal helper | XP/level-up state tracker. Surfaces eventually via `Artemis.session()` extension. |
| `combat.CombatStateTracker` | not script-reached today | engine-internal-only; no allow/ban entry needed |

**Engine packages are exempt** — `sequence/artemis/`, `sequence/dispatch/`, `sequence/activities/`, `plugins/recorder/farm/`, etc. internally cross-reference each other. The gate runs ONLY against `recorder/scripts/`.

Gate implementation: `scripts/check-no-direct-engine-reaches.sh` greps the banned-import set against `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/**/*.java`. Allow-list (one filename per legacy script not yet migrated) shrinks per Phase 6 milestone.

## 15. Pilot scope under v1

Cow killer pilot in v1 is **kill-until-full-then-logout**, not the full bank-cycle version:

```java
public final class CowKillerScript {
    private final Artemis artemis;
    private final SequenceManager manager;

    public void start() {
        manager.run(plan().root());
    }

    private SequencePlanBuilder plan() {
        return artemis.plan("cow-killer-v1")
            .then(artemis.walkTo(NamedZone.LUMBRIDGE_COW_FIELD))
            .then(combatLoop())
            .then(artemis.logout());
    }

    private Step combatLoop() {
        // Composite: until inventory.isFull() OR !session.shouldContinue():
        //   findNpc(NpcQuery.byName("Cow").within(14).rotation(ClosestWithSlack(2)))
        //   click(cow, "Attack", OutcomeCheck.interactingWithMe())
        //   wait for cow.healthRatio == 0 OR animation idle
        //   take(GroundItemRef for COWHIDE) - re-find each cycle to handle staleness
        //   take(GroundItemRef for BONES)
        // Implementation via RepeatStep + Selector over Artemis-returned Steps.
    }
}
```

Zero engine imports — only `Artemis`, `SequenceManager`, `SequencePlanBuilder`, the artemis-package types, and id constants.

v1.1 adds bank → pilot becomes bank → cow field → kill → bank → repeat. ChickenFarmV4 (Phase 5) layers gate handling on top of v1.1.

## 16. Migration audit (DONE — committed 2026-05-23)

Audit landed at `docs/learnings/2026-05-23-artemis-migration-audit.md` (commit `6ff46d47`). It enumerated, per-script, every direct engine reach in the 7 categories the user named (dispatcher.dispatch / ActionRequest, SceneScanner, BankInteraction, GeInteraction, WidgetActions, walker/nav/transport, raw Widget/NPC/GameObject, clickCanvas + hardcoded pixel math, sampleNearCentroid). It mapped each callsite to its Artemis replacement and grouped scripts by v1.x version needed.

The nine reconciliation items the audit surfaced (widget v1.0, per-query RotationPolicy defaults, LUMBRIDGE_BANK, threading per-Step, then-semantics, retry budget, tapKey split, combat helper policy, Phase 6 reorder) are absorbed into this revision of the spec. This section becomes a pointer; do not re-derive the audit.

## 17. Out-of-scope (deferred to v1.x+)

- **Reactive steps** for global handlers (level-up dismiss, random-event response, disconnect detection). Engine has `registerReactive`; Artemis v1 doesn't expose it — added in v1.x when a script needs it.
- **Per-account zone weights** for actor clicks (head/upper-body/lower-body/feet preference per §10 closing). Lands in Phase 3 as a `PixelResolver`-internal feature, transparent to Artemis.
- **Cross-script state sharing** (shared buy-limit ledger, etc.). Existing `BuyLimitLedger` stays; Artemis v1 adds no new shared-state primitive.
- **Script-side prompt for user input** (e.g. "what world?"). Configuration is set by `RecorderPanel` at start, not mid-run.
- **Hot-reload of Artemis config** while a script is running. Restart to change config.

## 18. v1.x roadmap

| Version | Scope | Trigger |
|---|---|---|
| v1.0 | This spec. Reads + click + take + useOn + walkTo + idle/logout + plan, **including widget surface (`findWidget`, `click(WidgetRef, verb)`, `useOn(InvSlot, WidgetRef)`)**. | Cow killer pilot passes 2-h test-profile session |
| v1.1 | Bank: `openBank`, `deposit(itemId)`, `withdraw(itemId, qty)`, `closeBank`. No `depositAllExcept`. Plus `confirmMakeAll(OutcomeCheck)` Step (typed chatbox-confirm; needed by Cooking / Fletching / Pizza / PieDish / UltraCompost migrations). | Cow killer needs to bank hides between fills |
| v1.2 | GE: `openGe`, `buyItem(BuyIntent)`, `sellItem(SellIntent)`, `collectGe`, `closeGe`. | First script needing GE post-migration |
| v1.x (alongside Phase 6 RooftopAgility migration) | `obstacleKeyConfirm(int vkKey)` Step — typed obstacle keypress. Lands when `RooftopAgilityScript` migrates. | RooftopAgility V2 migration |
| v1.3 | Reactive steps surface, typed `Weighted` RotationPolicy variants, `IdlePolicy.NATURAL_BREAK` / `MEAL` with `logoutPreferred=true` semantics (logout + sleep + AccountLauncher re-login + resume), `LogoutHelper`-into-`BreakScheduler` wiring. Depends on AccountLauncher integration landing alongside. | First script that benefits |
| Indefinite | `login(world)`, `depositAllExcept`. Need their own design pass + integration with AccountLauncher / bank state machine. | Real need surfaces |

Each version-bump gets its own spec doc revision; the change isn't a free-for-all extension of v1.

## 19. Full implementation roadmap (Phases 0–7)

v1.0 is the **foundation**, not the destination. Every subsequent phase *extends* Artemis; nothing bypasses it. This section embeds the full 7-phase build sequence directly so future readers don't have to chase the plan file — and so we can't drift into "treat v1.0 as the whole project."

**Load-bearing guarantee:** After Phase 1 lands, every new automation capability lives inside Artemis (new methods, new RotationPolicy cases, new OutcomeChecks, new NamedZones). The script-facing surface never extends in ways that bypass the centralized engine path.

### Phase 0A — Required prerequisites (must precede Phase 1)

Split into three micro-commits. Each lands separately, tests pass before moving on, no script behaviour changes in any of them.

#### Phase 0A.1 — Per-account RNG seed provider (smallest first commit)

- **Goal:** Centralize the seed source. Tiny commit. No surface area changes elsewhere.
- **Lands:** new `recorder/session/AccountRng.java` — a seed provider keyed off `client.getAccountHash()` exposed as a single method `Random forAccount()` (or `long seed()`). Wire it into `HumanizedInputDispatcher` constructor (replaces `new Random()` at `:81`) and `WindMouse` constructor (replaces `new Random()` at `:72`). No other touched files. No script behaviour changes. No migration.
- **Exit (observable signals):**
  - `git grep "new Random()" runelite-client/src/main/java/net/runelite/client/sequence/dispatch/` returns zero.
  - Unit test: same account hash → same seed across invocations; different hashes → different seeds.
  - Compile clean; no test regressions elsewhere.
- **Don't redesign later:** seed source (always `client.getAccountHash()`).

#### Phase 0A.2 — SessionShape scaffold

- **Goal:** Data view that `ArtemisImpl` will inject. Runtime-owned.
- **Lands:** new `recorder/session/SessionShape.java`. v1 surface: `shouldContinue()`, `budgetExhausted()`, `ticksSinceLastBreak()`. Implementation reads from a budget config + the existing tick clock. No `BreakScheduler` re-wiring in this commit (that's 0B's logout-related work). No script behaviour changes.
- **Exit (observable signals):**
  - Synthetic test: instantiate with budget=N ticks; advance tick counter; verify `shouldContinue()` flips to false at the right point and `budgetExhausted()` becomes true.
  - Compile clean.
- **Don't redesign later:** ownership (always runtime, never script).

#### Phase 0A.3 — Recorder StepEvent producer baseline

- **Goal:** Producer-side hook only. Consumer is Phase 7.
- **Lands:** new `StepEvent` record in `recorder/analyse/StepEvent.java` (fields: step name, target ref, verb, ticks elapsed, diagnostic reason, click pixel). New method `RecorderManager.recordStepEvent(StepEvent)` that appends to the existing session JSON. Nothing reads these events yet.
- **Exit (observable signals):**
  - Synthetic dispatch of a `StepEvent.start(...)` appends a record to the session JSON; format matches the record schema.
  - Compile clean.
- **Don't redesign later:** StepEvent record fields are the contract for Phase 7's dashboard.

**Phase 1 does not start until 0A.1, 0A.2, 0A.3 all compile, tests pass, and a one-paragraph touched-files summary is committed.**

### Phase 0B — Runtime polish (can land parallel with Phase 5+)

- **Goal:** Operational nice-to-haves that don't gate Phase 1.
- **Lands:**
  - Daily playtime budget in `RecorderPanel` (stop-by-clock-time).
  - World rotation per login via `AccountLauncher`.
  - Test-profile `.runelite-test/` config isolation.
  - `LogoutHelper` integration: v1.0 just needs `Artemis.logout()` to call `LogoutHelper.logout()` directly (one-way, ends the session). Wiring `LogoutHelper` into `BreakScheduler` for break-then-re-login is deferred to v1.x along with the `NATURAL_BREAK` / `MEAL` `IdlePolicy` re-introduction (see §18, §10).
- **Exit (observable signals):**
  - 24-h test-profile session run with daily budget: script stops at configured wall-clock; `client.log` shows the budget trigger.
  - 3+ sessions across 2 days: each picks a different world from the per-account pool.
  - Test-profile sessions write to `.runelite-test/`, real accounts to `.runelite/`; no cross-contamination.
- **Don't redesign later:** test-profile-vs-real dir choice (always envvar / config flag at startup), world-pool source (always per-account config).

### Phase 1A — Artemis core (this spec)

- **Goal:** Ship the interface, impl, all supporting types, and the audit table.
- **Lands:** `Artemis.java`, `ArtemisImpl.java`, query records (§6 — `NpcQuery`, `ObjectQuery`, `ItemQuery`, `WidgetQuery`), view/ref records with `observedTick` and re-resolution contract (§8 — `NpcRef`, `GameObjRef`, `GroundItemRef`, `WidgetRef`, `InvSlot`, `InventoryView`, `PlayerState`), `RotationPolicy` per-query defaults (§7), `OutcomeCheck` cases (§11), `NamedZone` v1 set (§9 — includes `LUMBRIDGE_BANK` / `LUMBRIDGE_BANK_P2`), `IdlePolicy` defaults (§10), Step-subclass impls under `sequence/activities/script/` (1:1 with the action methods in §5 — `ClickNpcStep`, `ClickGameObjStep`, `ClickWidgetStep`, `TakeGroundItemStep`, `UseOnStep`, `WalkToPointStep`, `WalkToZoneStep`, `IdleStep`, `LogoutStep`), §14 import policy text in CLAUDE.md §11, §16 migration audit (already committed `6ff46d47` — this section is now done).
- **Exit (observable signals):**
  - Compile clean on `:client:shadowJar`.
  - 3-step smoke plan (`walkTo(NamedZone.LUMBRIDGE_CASTLE_GROUND_FLOOR)` → `findNpc(...)` → `logout()`) runs end-to-end on a test-profile session; each Step emits start + check-completion `StepEvent` to the session JSON.
  - No gameplay behaviour changes outside the approved Artemis integration points (the new `sequence/artemis/` package, new `sequence/activities/script/` Step subclasses, `RecorderPlugin.java` wiring, plus docs/audit). Tests, scripts, doc edits are allowed; behaviour changes to existing scripts are not.
- **Don't redesign later:** any of §1–§14. v1.x extensions *add* methods; they never change the existing contract. The class `PixelResolver` is the click-resolution home forever (the broken *method* dies in Phase 3, not the class).

### Phase 1B — Minimal grep gate (lands at end of Phase 1A)

- **Goal:** Prevent the pilot from accidentally importing engine internals — even though Phase 4 will harden enforcement, the pilot needs *some* gate so it can't ship a regression while Phase 4 is in flight.
- **Lands:** `scripts/check-no-direct-engine-reaches.sh` minimal version: greps the §14 banned-import set against `recorder/scripts/**/*.java`. Run manually + on-demand (not yet pre-commit / CI; that's Phase 4). Allow-list initialized with every existing legacy script (so they keep compiling) plus an explicit `EXEMPT` block for engine packages.
- **Exit (observable signals):**
  - Gate runs against the empty placeholder `CowKillerScript.java` (or equivalent), exits 0.
  - Adding a deliberate `import HumanizedInputDispatcher` line to the pilot makes the gate exit non-zero.
- **Don't redesign later:** §14 allow/ban lists. Phase 4 hardens the runner (CI, pre-commit, automation), not the policy.

### Phase 2 — Pilot script (cow killer v1.0)

- **Goal:** Prove the API shape on one real script. Convince ourselves v1.0 is enough to do useful work.
- **Lands:** `recorder/scripts/CowKillerScript.java` per §15. Kill-until-full-then-logout. Zero direct engine imports. Uses `findNpc` / `click(NpcRef, "Attack", interactingWithMe())` / `take(GroundItemRef)` / `walkTo(NamedZone)` / `idle(IdlePolicy.PHONE_GLANCE)` / `logout()` only.
- **Exit — Tier 1 (structural; meet before declaring Phase 2 in-flight done):**
  - Grep gate (Phase 1B) passes against `CowKillerScript.java`: zero direct engine imports.
  - Compiles + runs end-to-end on a test-profile session for ≥30 min.
  - Every dispatched click emits start + check-completion `StepEvent` to session JSON.
  - `Artemis.findNpc` re-resolution rejects a deliberately-stale `NpcRef` (synthesised by mutating the ref after find): emits `DiagnosticReason.STALE_REF`, no click goes through.
  - No stuck state for ≥5 min during the run (Step state advances; if not, retry storm bumps).
  - No retry storms (≥3 consecutive same-target failures on one obstacle/NPC).
- **Exit — Tier 2 (centralised clicking actually centralised; requires Phase 3 done):**
  - Heatmap of dispatched click pixels on a single cow over 100+ cycles shows whole-model coverage. Standard deviation of click offsets from centroid ≥ 30 % of the model's larger dimension (vs. ~5 % under the pre-Phase-3 `sampleNearCentroid` 12-px window).
  - Cow identity histogram across the run: number of distinct NPC indices clicked ≈ field population over the session, not 1–2 dominant indices.
  - Bank-booth and NamedZone-tile rotation observable from session JSON (this becomes relevant once v1.1 bank is added in Phase 5; in v1.0 the equivalent is NamedZone tile rotation only).
- **Don't redesign later:** the import policy. The pilot proves it's workable as written. If the pilot needs a banned import to function, the policy is wrong — fix the policy or extend Artemis, never add an escape hatch.

**Note on phasing:** Tier 1 can land before Phase 3. Tier 2 cannot — without §10's `sampleInsideShape` replacement, the pilot's heatmap will still show the centroid-cluster pattern. Phase 2 is *complete* only when both tiers pass.

### Phase 3 — PixelResolver §10 rewrite (parallel with Phases 1–2)

- **Goal:** Execute the CLAUDE.md §10 rule in code. Kill `sampleNearCentroid`, ship `sampleInsideShape`, add UI occlusion mask.
- **Lands:** `sampleInsideShape(shape, ...)` modelled on existing `sampleInsidePolygon:215-245`. Migrate `resolveNpc` / `resolveGameObject` / `resolveWallObject` / `resolveDecorativeObject` callers to it. Prefer `getClickbox()` over `getConvexHull()` when both exist. 1–2 px edge inset (no fixed-fraction). `UiOcclusionMask` (world-map orb, chatbox, sidebar tabs, open dialogs) rejects samples in the loop. Delete `sampleNearCentroid` once all callers migrated. Agility-specific: extend Draynor `startTiles` with plane-1 fallback; drop `MAX_OBSTACLE_FAILS` from 6 to 2.
- **Exit:** Heatmap on a single NPC across 100+ cycles shows whole-model coverage. `git grep sampleNearCentroid` returns zero. Agility test-profile session: zero "no startTile" events, zero retry storms.
- **Don't redesign later:** the §10 rule itself. The rule is locked in CLAUDE.md; Phase 3 only executes it. Per-session zone weights (head/upper-body/lower-body/feet) are an in-resolver future feature, transparent to Artemis.

### Phase 4 — Enforcement gate (hardens Phase 1B)

- **Goal:** Make script-side engine reaches mechanically impossible to merge. Phase 1B brought the minimal grep script; this phase wraps it in automation and locks the policy doc.
- **Lands:** CLAUDE.md §11 (the rule, mirroring §10's format) — note the import policy was already in CLAUDE.md from Phase 1A; this phase formalises §11 as a numbered rule referencing §14 of this spec. Local pre-commit hook + CI workflow (`.github/workflows/check-engine-reaches.yml`) run the Phase 1B grep script automatically on every PR. Allow-list management: per-script entries removed as Phase 6 migrations land (one PR per script removes both the legacy file and its allow-list entry).
- **Exit (observable signals):**
  - Pre-commit hook installed and rejects a deliberately-introduced `dispatcher.dispatch(...)` call in `CowKillerScript.java` locally before commit.
  - CI workflow runs on a test PR with the same bad import → red. Passes when the import is removed → green.
  - Allow-list seeded with the exact set of unmigrated legacy scripts from the Phase 1A migration audit; count matches.
- **Don't redesign later:** §14's allow/ban lists. v1.x doesn't relax bans; new capabilities extend Artemis. Allow-list is a *migration tool*, not an exception mechanism — it only shrinks.

### Phase 5 — First real script upgrade (Artemis v1.1 → ChickenFarmV4)

- **Goal:** Land bank surface in Artemis v1.1; migrate a real existing script.
- **Lands:** Artemis v1.1 adds `openBank`, `deposit(itemId)`, `withdraw(itemId, qty)`, `closeBank`. New view types `BankView`. Cow killer pilot extended to bank-cycle shape ("Phase 2.5"). Then `recorder/scripts/ChickenFarmV4Script.java` written from cow-killer template + gate-handling additions (`findObject(ObjectQuery.byName("Gate"))` + `click(gate, "Open")`). Old `ChickenFarmV3Script.java` deleted after IRL test.
- **Exit:** 2-h test-profile session cow-killer-with-bank cycle clean. 2-h test-profile session ChickenFarmV4 clean with gate handling verified.
- **Don't redesign later:** Artemis v1.0 contract. Bank surface is *additive* — new methods, no changes to existing signatures, no changes to Step contracts.

### Phase 6 — Legacy migration

- **Goal:** Every script in `recorder/scripts/` uses only Artemis.
- **Lands:** V2 of each remaining legacy script. **Migration order reconciled with audit §6 — reorders the original priority list to (a) bring `LumbridgeBankPenScript` forward because it has 3 §10-banned centroid-click sites that would otherwise stay live through 5+ migrations, and (b) add `CooksAssistantScript` which was missing from the original list:**

  1. `LumbridgeBankPenScript` → V2 (v1.1; **moved earlier — §10 risk**)
  2. `RooftopAgilityScript` → V2 (v1.0; can land in parallel with v1.1 work; lands `obstacleKeyConfirm` Step alongside)
  3. `PieDishScript` → V2 (v1.1; dominant `bank.*` user — 48 callsites — stress-tests every bank primitive)
  4. `PizzaScript` → V2 (v1.1 + `confirmMakeAll`)
  5. `FletchingScript` → V2 (v1.1 + `confirmMakeAll` + widget-reach hotspot — stress-tests `findWidget`)
  6. `CookingScriptV4` (v1.1 + `confirmMakeAll`; delete CookingV2 + V3)
  7. `UltraCompostScript` → V2 (v1.2; the script that produced 497 cycles on one banker NPC in the jbane777 incident)
  8. `CooksAssistantScript` → V2 (v1.2; **added per audit — missing from original list**)
  9. `GrandExchangeScript` → V2 (v1.2; **not "thin" — imports 8 GE-internal types per audit §3.4 that must each be replaced with Artemis-shaped equivalents**)
  10. Quest sub-tree — bundled in the roadmap, but each step commits + IRL-tests individually per [[feedback_one_at_a_time_irl_test]]. Order within the bundle: `ReplayTrailStep` → `TakeGroundItemStep` → `InteractWithObjectStep` → `TalkToNpcStep` → `UseItemOnObjectStep` → `CombineItemsStep` (small-to-large), then deletions (`NavWalkStep`, `LogoutHelper`), then orchestrators (`ErnestQuestScript`, `ErnestTheChicken`, `QuestStepThreads`).

  Each migration: ≤1 day implementation, IRL-test on test-profile session, delete legacy, shrink grep allow-list by one.

- **Exit:** §14 allow-list empty. Every script imports only the Artemis-allowed set.
- **Don't redesign later:** Artemis itself. If a script needs a primitive Artemis lacks, *add the primitive to Artemis*, don't reach around it. If a primitive seems too narrow to be reusable, design it generally enough that the next script can use it too.

### Phase 7 — Recorder / diversity dashboard

- **Goal:** Self-monitoring infra so we never have to hand-grep `client.log` for ban-postmortem evidence again.
- **Lands:** Step lifecycle events writing to `RecorderManager` per §13 (already declared in the contract; Phase 7 wires the consumer). Per-session summary JSON: click pixels per target, target-identity histograms, action counts, time buckets. Live overlay: Shannon entropy of identity selection, retry-storm counter, top-N targets last 30 min. Cross-session aggregation script (`scripts/analyse_sessions.py`) for cross-account drift detection. Alerts surfaced in `RecorderPanel`.
- **Exit:** A deliberately-bad test script (degenerate `RotationPolicy.Closest()` on a single NPC) gets flagged red within 30 min by the live overlay. Aggregation script catches identity-domination across two test sessions.
- **Don't redesign later:** §13 recording contract. Step events are already the data model; Phase 7 only adds consumers.

### Acceptance signals across phases (the rubric)

Every phase's exit criteria draw from this list. "2-h test-profile session clean" is not by itself an acceptance signal — it's the platform on which these signals are observed. Pre-Phase-7 these are extracted by grep against `client.log` and the session JSON; post-Phase-7 the diversity dashboard surfaces them live.

- **Direct engine import count** in `recorder/scripts/**/*.java` (grep against §14 ban list). Must be 0 for any non-allow-listed script.
- **`StepEvent` emission rate** — every dispatched action emits start + check-completion events to the session JSON. Missing events = unwired Step.
- **Stale-ref failure count** — `DiagnosticReason.STALE_REF` occurrences. Non-zero is expected and good (ref hygiene is working); a sudden zero usually means re-resolution isn't running.
- **Unresolved-target failure count** — `findNpc / findObject / findItem` returning empty followed by a Step that depended on it. Non-trivial count = NamedZone or RotationPolicy misconfigured.
- **Dispatcher failure count** — `HumanizedInputDispatcher` reported error / timeout. Should be near 0; ≥1/min is a regression.
- **Retry-storm count** — ≥3 consecutive same-target failures on one entity. Was the dominant correctness failure observed in the jbane777 agility postmortem; any non-zero count indicates a structural script or resolver issue.
- **Stuck-state time** — engine ticks without a Step state transition for ≥5 min on a script that should be active. Was the "frozen on plane-1 rooftop" pattern. Any non-zero count is a script-correctness bug.
- **Click diversity (per-target heatmap)** — stdev of click pixels on a single entity over N cycles. Phase 3 target: ≥30 % of the model's larger dimension. Pre-Phase-3 the current centroid cluster (~5 %) is the baseline to improve from.
- **Identity diversity (per-target-class histogram)** — number of distinct NPC indices / object ids / NamedZone tiles clicked over a session vs. the population available. Skewed-to-one = target-identity register regression.
- **Session summary generated** — at script stop, a JSON file with the above signals lands in the recorder session dir.

### What this roadmap is NOT

- **Not a v2 of Artemis.** v2 doesn't exist in the foreseeable plan. v1.x is the entire arc through Phase 7. If v2 ever happens, it's because a *new architectural* problem has surfaced — not because we forgot to put something in v1.
- **Not a justification for reinventing.** Anything Phase 5+ wants that doesn't fit Artemis = a gap *in Artemis*, fixed by extending Artemis. Never by going around it.
- **Not a license to skip Phase 0.** Phase 1 implementation does not start until Phase 0 is on master and test-profile-verified. The per-account RNG seed alone is load-bearing for cross-account variance separation; the whole exercise loses leverage without it.

## 20. Acceptance criteria for v1 spec lock

- [x] v1 scope explicit (§4)
- [x] Non-goal #1 reworded (no *blocking* imperative; Step-returning is canonical)
- [x] Java package matches file path (`sequence.artemis`)
- [x] Click contracts deliberately verb-only at base; OutcomeCheck overload for state verification
- [x] Session gate exempts maintenance Steps
- [x] View records carry `observedTick`; re-resolution contract specified
- [x] RotationPolicy v1 has no generic `Weighted` (deferred typed variants)
- [x] `depositAllExcept` and `login` out of v1
- [x] Import policy explicit (§14) with both allow and ban lists
- [x] Migration audit committed before Phase 1A starts (§16) — landed at `docs/learnings/2026-05-23-artemis-migration-audit.md` (commit `6ff46d47`)
- [x] v1.x roadmap (§18) shows what comes next and when
- [x] Full 7-phase implementation roadmap (§19) embedded in this spec — not just referenced — so v1.0 is unmistakably the *foundation*, not the whole project
- [x] "Extends Artemis, never bypasses" guarantee stated explicitly (§19 header)
- [x] Phase 0 split into 0A (required prerequisites) and 0B (later polish that doesn't block Phase 1)
- [x] `idle()` / `login()` contradiction resolved (§10 ships in-game idle only in v1.0; logout-and-re-login deferred to v1.3 with AccountLauncher integration per §18)
- [x] Phase 1 exit criteria reworded to "no gameplay behaviour changes outside approved integration points" (§19 Phase 1A) and split into Phase 1A core + Phase 1B minimal grep gate
- [x] Phase 2 acceptance tiered: Tier 1 (structural, can land pre-Phase-3) vs Tier 2 (heatmap diversity, requires Phase 3 done)
- [x] Phase 4 reframed as "hardens Phase 1B" (CI/pre-commit automation, not the policy)
- [x] Observable signals rubric (§19 closing) — every phase's exit criteria draw from it; "test-profile session ran for 2h" is the platform, not the signal
- [x] Phase 0A split into three micro-commits (0A.1 / 0A.2 / 0A.3); Phase 1 gated on all three landing
- [x] Neutral-engineering wording pass complete — no "burner" / "fingerprint signal" / "smoking gun" / "kills" / "the bot" framings; all replaced with engineering vocabulary (test profile, runtime variance, observed pattern, etc.) per `feedback_no_evasion_framing` memory
- [x] Phase 0A.1, 0A.2, 0A.3 all committed on master (`a1886f79`, `9caf2c7e`, `12abb387`); migration audit committed (`6ff46d47`); Phase 1 gate satisfied
- [x] Widget surface promoted into v1.0 (§4, §5, §6, §8, §11) — `WidgetRef` view record, `WidgetQuery` builder, `findWidget`, `click(WidgetRef, verb)`, `useOn(InvSlot, WidgetRef)`
- [x] Per-query `RotationPolicy` defaults pinned (§7) instead of single global default
- [x] `LUMBRIDGE_BANK` + `LUMBRIDGE_BANK_P2` added to `NamedZone` (§9) — they were the single most-walked zones in the codebase but missing from v1 set
- [x] Step threading contract pinned per Step category (§12 table); blocking flows always on dispatcher worker per CLAUDE.md §1-§3
- [x] `LinearSequence.then(...)` failure semantics pinned (§12.5) — success continues, failure short-circuits, retries explicit via `Step.onFailure(...) → Recovery.Retry(N)` (engine mechanism per §12.6) or via `Selector` composite for try-this-then-fallback
- [x] Per-Step retry budget specified (§12.6) — default 3 attempts via `Recovery.Retry`, per-attempt re-resolution, no artificial backoff (re-invocation lands on the engine's next tick)
- [x] `dispatcher.tapKey(VK_*)` split into two typed future Steps (§11 + §18): `confirmMakeAll(OutcomeCheck)` in v1.1, `obstacleKeyConfirm(int vkKey)` in v1.x — no generic `tapKey` ever exposed
- [x] `combat/` helper allow/ban list pinned (§14): `NpcSelector` banned (replaced by `findNpc`), `ChickenCombatLoop` + `TrainingSession` allowed as engine-internal helpers
- [x] Phase 6 migration order reconciled with audit §6 (§19) — `LumbridgeBankPenScript` moved earlier for §10 risk, `CooksAssistantScript` added (missing from original list), `GrandExchangeScript` no longer described as "thin", quest sub-tree bundling clarified

Status: **REVISED 2026-05-23, awaiting re-approval after round-4 reconciliation.** Phase 0A is committed (`a1886f79` / `9caf2c7e` / `12abb387`). Migration audit committed (`6ff46d47`). Phase 1A starts after this revision is re-approved (do NOT start implementation until operator green-lights the round-4 changes).
