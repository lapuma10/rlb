# Collision-aware Dijkstra (V2 nav planner)

**Status:** approved-with-changes (architect review 2026-05-17; changes integrated below)
**Author:** mantas
**Date:** 2026-05-17
**Targets:** `runelite-client/src/main/java/net/runelite/client/plugins/recorder/nav/v2/...`

---

## Problem

V2's two-tier planner (`LinkGraphDijkstra` → `WaypointPlanner` → `SkretzoBfsKernel`) systematically fails on routes that REQUIRE a transport to cross a static collision boundary (fence / wall) when a "more direct" abstract route exists.

Concrete reproducer (Lumbridge bank → chicken pen):

1. Player at `(3209, 3220, p=2)` (Lumbridge bank).
2. `findRouteSkeleton` returns: 3 stair transports → walk leg from `(3205, 3228, p=0)` directly to `(3235, 3295, p=0)`.
3. BFS validates the walk leg → `UNREACHABLE` (pen fence blocks every approach; only the south gate transport at `(3236, 3296, p=0) ↔ (3236, 3295, p=0)` can cross it).
4. `WaypointPlanner` returns `TARGET_UNREACHABLE`. Script aborts.

The gate transport IS in `transports-overrides.tsv` and IS in Dijkstra's graph. Dijkstra simply prefers the abstract-cheaper direct walk:

- Direct walk: `chebyshev((3205, 3228), (3235, 3295)) = max(30, 67) = 67`
- Via gate: `chebyshev(start, gate-N) + 1 + chebyshev(gate-S, target) = 68 + 1 + 1 = 70`

**Root cause:** `LinkGraphDijkstra` is collision-blind. Walk edges between same-plane nodes use Chebyshev cost with no awareness of fences/walls. Dijkstra commits to a route; BFS then proves it impossible; the plan fails entirely. There is no retry, no alternative-skeleton enumeration, no edge invalidation.

This is not a bug in any one component — it's an architectural gap between Dijkstra (oblivious to topology) and BFS (oblivious to alternatives).

## Solution

Make Dijkstra's walk-edge construction **topology-aware** by consulting a precomputed **connectivity-component map** built once from `GlobalCollisionSnapshot` (Skretzo's bundled collision data).

```
For each plane:
  flood-fill every walkable tile, label every tile with a component id.
  Two tiles in different components ⇒ no walkable path exists between them
  in static collision space ⇒ no walk edge in Dijkstra.
  Two tiles in the same component ⇒ MAY be walkable (BFS still verifies).

Dijkstra walk edge (a, b) added iff:
  a.plane == b.plane AND componentOf(a) == componentOf(b)
```

Transport edges are unaffected — they explicitly bridge endpoints, so they cross components by design. The pen-gate transport becomes the **only** edge that connects the outside-pen and inside-pen components; Dijkstra is forced to use it.

### Why this is the right design

1. **Eliminates the failure class.** If two tiles are in different components in static collision space, no walking gets you between them. That's topology, not heuristic. Dijkstra cannot pick a route it can't walk.

2. **No retry loops.** No top-K. No magic cost multipliers. One filter at edge construction.

3. **Defense-in-depth preserved.** BFS still validates each walk leg. If components ever go stale (e.g., a live overlay differs from the snapshot), BFS catches it and the planner returns `TARGET_UNREACHABLE` as before — no regression in error handling.

4. **Aligned with Skretzo's data model.** Their collision-map.zip is meant for collision-aware planning; we just haven't been consulting it at the Dijkstra layer.

5. **Cheap per-query.** O(1) component lookup per edge consideration. The O(N²) walk-edge construction in Dijkstra is unchanged — we just skip edges that cross components.

### Tradeoffs accepted

- **Precompute cost.** One-time flood-fill across all bundled regions (~640 regions × 4 planes × 64×64 = ~10.5M tiles). Expected ~1-2s on first use; cached in memory for the session.
- **Memory.** Sparse per-region `int[]` for component ids. ~16KB per loaded region per plane × 600 regions × 4 planes ≈ 38MB worst case (we can pack to 16-bit or use compressed scheme if it bites).
- **Static topology only.** Live runtime door-state changes (closed/open transitions) are NOT reflected in components. This is fine because runtime-toggleable doors are already modelled as transport entries; the static snapshot represents the "doors closed" world, which is the conservative case.

## API

### New class: `ConnectivityComponents`

Package: `net.runelite.client.plugins.recorder.nav.v2.collision`

```java
public final class ConnectivityComponents {
    /** Build components from a global collision snapshot. */
    public static ConnectivityComponents fromSnapshot(GlobalCollisionSnapshot snap);

    /** Returns the component id for a tile; -1 if the tile is in a
     *  region not present in the source snapshot, or if the tile is
     *  blocked (not walkable). Different ids ⇒ no walkable path
     *  exists between the two tiles in static collision space. */
    public int componentOf(WorldPoint p);
    public int componentOf(int x, int y, int plane);

    /** True iff componentOf(a) >= 0 && componentOf(a) == componentOf(b). */
    public boolean sameComponent(WorldPoint a, WorldPoint b);

    /** Total number of distinct components across all planes. Diagnostic. */
    public int componentCount();
}
```

Connectivity rule: a tile is walkable iff `(flagsAt(p) & BLOCK_MOVEMENT_FULL) == 0`. Two adjacent walkable tiles are in the same component iff `SkretzoBfsKernel.canMove(...)` permits a step between them.

**The same `canMove` logic the BFS kernel uses** — load-bearing for consistency. We achieve this by feeding `ConnectivityComponents` a `CollisionView` constructed with the global snapshot AND an empty `LiveSceneCollisionOverlay` — i.e., a view that strictly reflects static collision data. `SkretzoBfsKernel.canMove(view, ...)` is then called identically to its production path. To keep the kernel's package-private `canMove` reachable from the components package, we either (a) widen its visibility to package-private + same-package re-export, or (b) expose a thin public helper `SkretzoBfsKernel.canMove(view, x, y, plane, dx, dy)` on the kernel itself. Option (b) preferred — single source of truth.

**8-direction flood-fill including the pillar rule** (per architect review). Rationale: OSRS allows corner-touching tiles connected only by a diagonal step under the pillar rule. Cardinal-only flood-fill would over-partition those into different components, refusing to add a walk edge BFS would happily traverse — re-introducing the exact failure class this spec fixes, in reverse. 8-direction with the pillar rule means component connectivity is **identical** to BFS's view of static reachability. No drift, by construction.

### Integration: `LinkGraphDijkstra`

`findRouteSkeleton` gains an optional `ConnectivityComponents` parameter:

```java
public static SkeletonResult findRouteSkeleton(
    NavigationContext ctx,
    TransportTable table,
    WorldPoint from,
    WorldPoint to,
    @Nullable ConnectivityComponents components);  // NEW
```

When `components` is non-null, the walk-edge construction loop in step 3 filters:

```java
if (a.getPlane() != b.getPlane()) continue;
if (components != null && !components.sameComponent(a, b)) continue;  // NEW
int cost = chebyshev(a, b);
adj.get(a).add(Edge.walk(b, cost));
```

When `components` is null, behaviour is unchanged (backwards-compatible).

### Integration: `WaypointPlanner`

`WaypointPlanner.plan` already has the `WorldSnapshot` in scope (used to get the `CollisionView` for BFS). The snapshot carries the components map alongside its collision view:

```java
// In WorldSnapshot:
@Nullable
default ConnectivityComponents components() { return null; }
```

`WaypointPlanner` passes `snap.components()` through to `findRouteSkeleton`. If null (precompute not done yet), Dijkstra runs in current "collision-blind" mode — preserves old behaviour during the precompute window.

### Lifecycle (pinned — architect review)

**Holder:** a single `volatile ConnectivityComponents components` field on `RecorderPlugin`. Single writer (the precompute thread, exactly once), many readers (`WorldSnapshotBuilder.captureOnClientThread`). `volatile` is sufficient — there is no CAS and no compound update. `AtomicReference` adds nothing.

**Precompute trigger:** `RecorderPlugin.startUp` spawns a dedicated daemon thread `nav-v2-components-precompute` that calls `ConnectivityComponents.fromSnapshot(globalSnap)` and writes the result to the holder. Eager precompute — known cost upfront beats first-plan latency spike.

**Resolution at snapshot mint:** `WorldSnapshotBuilder.captureOnClientThread` reads the volatile holder **exactly once at mint time** and stores the reference (possibly null) on the `WorldSnapshotImpl`. The snapshot's `components()` returns that stored reference; it does NOT re-read the holder. This pin prevents an in-flight plan from seeing null on one read and non-null on a later read of the "same" snapshot.

```
Plugin start (RecorderPlugin.startUp)
  └─ thread "nav-v2-components-precompute" (daemon):
     ConnectivityComponents.fromSnapshot(globalSnap) → writes volatile holder

Snapshot mint (WorldSnapshotBuilder.captureOnClientThread, runs on client thread)
  └─ reads volatile holder ONCE, stores reference (possibly null) on WorldSnapshotImpl

Plan request (WaypointPlanner.plan)
  └─ snap.components() → returns the stored reference, no re-read
     ├─ null  → Dijkstra runs without component filter (status quo)
     └─ non-null → Dijkstra runs with component filter (the fix)
```

The background-precompute window is short (~1-2s). During that window, any snapshot minted sees null and the planner behaves as today. After: every newly-minted snapshot carries the non-null reference and the filter engages. Snapshots already in flight are unaffected by the holder update (correct).

No "wait for precompute" blocking on the first plan call — keeps the V2 plan latency the same on cold start.

### Persisting the component map (deferred)

Out of scope for this design. If precompute time becomes a problem (e.g., users notice the 1-2s spike) we can serialise the components to a sidecar file and rebuild only on snapshot version change. The hot path is the in-memory lookup; the precompute is amortised over a session.

## Test plan

1. **Unit: `ConnectivityComponents` — basic partitioning.**
   - Synthetic 5×5 grid with a wall row; assert two halves are different components.
   - Edge cases: out-of-snapshot tile → -1; blocked tile → -1; same tile → equal.

2. **Unit: `ConnectivityComponents` — diagonal corner-touch (architect-review addition).**
   - Fixture: two walkable tiles connected only via a NE diagonal step that is legal under the pillar rule (the connecting cardinal tiles are walkable). Assert: **same component.**
   - This guards against cardinal-only flood-fill regressing the design.

3. **Unit: `ConnectivityComponents` — `canMove` parity with BFS kernel.**
   - For a small synthetic fixture, enumerate every adjacent walkable-tile pair and assert: BFS kernel `canMove(view, src, dst)` returns true iff `components.sameComponent(src, dst)` is true (modulo the long-range nature of components — within one step, they MUST agree).
   - This is the load-bearing consistency check that prevents drift.

4. **Integration: `LinkGraphDijkstra`.**
   - Build a fake `TransportTable` with one transport bridging two components.
   - Confirm `findRouteSkeleton` picks the transport when components are supplied;
     confirm it picks the direct walk when components are null (regression guard).

5. **Regression: pen→bank route.**
   - The actual failing route from this session: `(3209, 3220, p=2) → (3235, 3295, p=0)`.
   - Load real `GlobalCollisionSnapshot` + real `TransportTable` + override TSV.
   - Build components. Plan.
   - Assert: status OK, skeleton contains a transport with `object id == 1560` (the pen south gate), all BFS legs PATH_FOUND.

6. **Regression: live-overlay-passable, static-blocked (architect-review addition).**
   - Fixture: a snapshot where the global snapshot blocks a tile transition (wall) but the live overlay has no block AND no transport bridges it (a dynamically-open door not in `transports.tsv`).
   - Expected behaviour with this design: Dijkstra refuses to walk there (components say different), and the planner returns `TARGET_UNREACHABLE` even though BFS-against-live would have succeeded. This is a **known and accepted regression** for un-modelled live-open doors. The test exists to make the regression explicit and to fail loudly if anyone later widens the live-overlay's role at the Dijkstra layer.
   - Mitigation outside the spec: if a real live-open door surfaces a route failure, the right fix is to add it to `transports-overrides.tsv`, not to weaken the component filter.

7. **Manual gate (deferred — user holding the client).**
   - When the user is ready to relaunch: at Lumbridge bank, V2_STRICT mode, run chicken-farm-v3 OUTBOUND. Expected: V2 plans bank → NW stairs → walk → gate transport → pen tile. No NO_ROUTE. No BFS UNREACHABLE.

## Out of scope

- Live collision changes (closed/open door state). Tracked via existing `TransportObserver` + override TSV.
- Persisting components to disk.
- Cross-plane connectivity (transports already cross planes; the component map is per-plane by design).
- Component merging/splitting at runtime if a door's transport becomes unavailable due to requirement gating — Dijkstra already filters those transports out at graph-construction time, which is the right layer.
- Touching BFS / executor / overlays / debug panel — none of those change.

## Open questions

1. **~~Diagonal connectivity in flood-fill.~~** Resolved per architect review: flood-fill is **8-direction with the same pillar rule as `SkretzoBfsKernel.canMove`**. Reuse the kernel's `canMove` against a synthetic `CollisionView` wrapping only the global snapshot. No over-partition risk by construction.

2. **Memory for full snapshot.** 38MB worst case feels high. If we measure and it's actually bad we can pack to short[] or run-length-encode per region. Mitigation deferred until measured.

3. **NavigationContext interaction.** Component map is independent of `NavigationContext` (requirement gating). Transports are filtered by requirement before Dijkstra runs; walk-edge filtering is independent. Composition: requirement-filtered transports + component-filtered walk edges → Dijkstra picks the cheapest valid route. Confirmed correct.

4. **Live-overlay-passable / static-blocked doors not in `transports.tsv`.** Architect-flagged regression vector. Accepted: see test plan #6. If we ever hit a real production case, the fix is to add the door to `transports-overrides.tsv` (its existing purpose), not to weaken the Dijkstra-layer filter.

## Out-of-scope (explicit non-goals)

- Replacing Dijkstra with a single flat A* over the full grid (Skretzo's plugin design). Larger surgery, defer.
- Top-K skeletons / retry-on-BFS-fail. The component filter eliminates the need.
- Live-overlay-aware components. Static collision is enough for the failing case.
