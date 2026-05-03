# NPC Interaction Extraction — find / talkTo / walkUntil

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans
> when this is approved. Steps use checkbox (`- [ ]`) syntax for tracking.
> Per project memory: keep code work inline; subagents reserved for QC after
> each task. Per project memory: don't paste full Java sources — describe
> behavior; inspect APIs before specifying signatures.

**Goal:** Hoist the three reusable patterns currently inlined in
`CooksAssistantScript` into shared utilities so the next quest script
(Sheep Shearer, Witch's Potion, etc.) can be ~300 lines of state-machine
glue instead of re-implementing scene scans, dialogue lifecycles, and
walk-with-arrival-predicates.

**Architecture:** Extend the existing `recorder/npc/NpcInteraction`
(currently dialogue-only) with `findOnScene` and `talkTo`. Extend
`recorder/trail/TrailWalker` with one wrapper (`walkRouteUntil`) that
short-circuits when an arrival predicate fires. Migrate
`CooksAssistantScript` so the new APIs are exercised by a real caller
on day one — that's the QC gate.

**Tech stack:** Java 17, RuneLite API (`NPC`, `WorldView`, `NPCComposition`,
`Player`, `WorldPoint`, `Polygon`), engine internals (`ActionRequest`,
`HumanizedInputDispatcher`, `SequenceSleep`), JUnit 4 + Mockito for tests
(matches existing `npchighlight` and `recorder/trail` test conventions).

**Scope:** Three extractions in dependency order:
1. `NpcInteraction.findOnScene(...)` + `NpcScan` record (foundation).
2. `NpcInteraction.talkTo(...)` (consumes #1).
3. `TrailWalker.walkRouteUntil(...)` (orthogonal; lands last so we don't
   churn TrailWalker before the NPC work is settled).

Each task is self-contained: passes its own tests, ends in a green
build, and is independently committed. Migration of `CooksAssistantScript`
to each new API happens *inside* the task that introduces it — no
straggling cleanup tasks.

**Out of scope:**
- `GameObjectInteraction` — earn it on the second use.
- Renaming `NpcInteraction` (already named, already public — additive
  changes only).
- Generalising `walkRouteUntil` to cover transport waypoints / minimap
  walks. One wrapper, one caller pattern.
- Touching `tick(TrailPath)` semantics. `walkRouteUntil` wraps
  `walkRoute`, which already wraps `tick`.

---

## Threading contract — re-affirm before any code change

These extractions cross thread boundaries. Read the
**"Threading model"** section at top of `CLAUDE.md` before writing any
new method. Recap:

- **Pure scene scans** (`findOnScene`) — must run on the **client thread**.
  Caller marshals via `clientThread.invokeLater`; the API itself does
  NOT marshal. Reason: callers often already hold a client-thread
  context (e.g. inside another `onClient(...)`) and a second hop would
  deadlock or double-pay latency. The existing `scanForCook` is shaped
  this way; preserve it.
- **`talkTo`** — must run on the **dispatcher worker thread** (inside a
  `RUN_TASK`). It internally uses `npcClickOnWorker`, `SequenceSleep`,
  and `completeDialogue` — all of which assert worker thread or
  block. The method itself adds an `assertWorkerThread("talkTo")`
  guard mirroring `BankInteraction`'s pattern.
- **`walkRouteUntil`** — same thread as `walkRoute`. Today
  `CooksAssistantScript` calls `walkRoute` from its `tickLoop()`
  worker thread. The arrival predicate is invoked synchronously from
  the same thread; if it touches client state it must marshal itself.

If a step's "where does this run" answer is fuzzy, stop and re-derive
it from the three-question framework before writing code.

---

## File map

**New / modified files (full list):**

```
runelite-client/src/main/java/net/runelite/client/plugins/recorder/
  npc/NpcInteraction.java            (modify — add findOnScene + talkTo)
  npc/NpcScan.java                   (create — public record)
  trail/TrailWalker.java             (modify — add walkRouteUntil)
  scripts/CooksAssistantScript.java  (modify — migrate to new APIs;
                                      delete inlined scanForCook +
                                      CookScan + tickTalkToCook
                                      bookkeeping)

runelite-client/src/test/java/net/runelite/client/plugins/recorder/
  npc/NpcInteractionFindOnSceneTest.java   (create)
  npc/NpcInteractionTalkToTest.java        (create)
  trail/TrailWalkerWalkUntilTest.java      (create)
```

No new packages. No deprecation annotations on old methods (the only
"old" method is the private `scanForCook` which gets deleted, not
deprecated).

---

# Task 1: NpcInteraction.findOnScene + NpcScan record

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/npc/NpcScan.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/npc/NpcInteraction.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/CooksAssistantScript.java`
  (delete `CookScan` record + `scanForCook` method; replace callsites)
- Create: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/npc/NpcInteractionFindOnSceneTest.java`

**API shape:**

```java
public record NpcScan(int npcIndex, WorldPoint tile, boolean onCanvas, String diagnostic) {
    public boolean found() { return npcIndex >= 0; }
    public static NpcScan miss(String diagnostic) {
        return new NpcScan(-1, null, false, diagnostic);
    }
}
```

```java
// NpcInteraction.java — new public method, instance, must run on client thread.
//
// scanRadius: Chebyshev tile distance from local player. Cooks-assistant uses 24.
// preferredIds: if any NPC matches one of these ids, it wins (id check is invariant
//   against name markup and quest-completion id swaps).
// nameFallback: nullable. If non-null and no id match, the first NPC whose
//   COMPOSITION name (markup-stripped, case-insensitive) equals nameFallback wins.
// Returns NpcScan.miss(diag) when nothing found; populated NpcScan otherwise.
//
// Diagnostic always includes scanned-NPC count + plane + first 8 names/ids/dists,
// or "matched-by=id|name hullPoly=present|null" on hit. Format mirrors current
// scanForCook output so log-grep across versions stays viable.
public NpcScan findOnScene(int scanRadius, int[] preferredIds, @Nullable String nameFallback)
```

**Threading guard:** call `client.isClientThread()` at entry; throw
`IllegalStateException("findOnScene must run on client thread")` if false.
This is intentionally STRICTER than the existing `scanForCook` (which was
private and trusted callers). Callers must marshal — single source of
truth, no surprise hop.

**Implementation notes (do not paste source — these constrain the diff):**
- Inline the iteration logic from `CooksAssistantScript.scanForCook`
  lines 518-572. Key invariants to preserve:
  - Skip NPCs with null `WorldPoint` or wrong plane (the current code's
    `loc.getPlane() != here.getPlane()` filter).
  - First-id-match wins, then first-name-match, then no match.
  - Read NPC name from `getComposition().getName()` first, fall back to
    `npc.getName()`. Strip `<col=...>` markup with the existing helper
    pattern (`s.replaceAll("<[^>]+>", "").trim()`); inline as a private
    static helper `stripMarkup(String)` on `NpcInteraction` — DRY with
    the deleted copy in `CooksAssistantScript`.
  - Use `cook.getCanvasTilePoly() != null` for `onCanvas`. Same source
    of truth as the live caller.
  - When `preferredIds` is null or empty, name match is the sole path;
    when `nameFallback` is null and no id matches, return miss.
  - Diagnostic-buffering loop (first 8 NPCs) is part of the contract.
  Tests assert it.

**Steps:**

- [ ] **Step 1.1: Write NpcScan record + tests**

Create `npc/NpcScan.java` with the record above. Tests not needed for
the record itself (it's a value type), but the next step's tests will
exercise it.

- [ ] **Step 1.2: Write the failing tests for findOnScene**

Create `NpcInteractionFindOnSceneTest.java` with these cases (use
Mockito + `RuneLiteAPI` shape — pattern: see
`runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailWalkerHandoffTest.java`
for how the existing tests stub `Client`, `Player`, `WorldView`,
`NPC`, `NPCComposition`, `WorldPoint`).

**Threading-guard mocking convention for these tests:** every
happy-path test must `when(client.isClientThread()).thenReturn(true)`
so the strict guard in `findOnScene` allows the call. The single
guard test (case 9) does the inverse to verify the throw. Mockito's
default for unstubbed primitive booleans is `false`, so missing the
stub silently fails every other case — keep this in `@Before` to
avoid one-off bugs.

  1. **Empty scene** — `WorldView.npcs()` empty → returns
     `NpcScan.miss(...)` with diagnostic containing "scanned 0 NPCs".
  2. **Match by id (preferred)** — two NPCs in range, one with
     `getId() == 4626` (cook), other with id 1234. preferredIds=[4626] →
     returns the cook's index, `matched-by=id` in diagnostic.
  3. **Id miss → name fallback wins** — NPC has unmatched id but
     composition name "Cook"; preferredIds=[9999], nameFallback="Cook" →
     returns that NPC, `matched-by=name` in diagnostic.
  4. **Name with markup stripped** — NPC composition name
     `"<col=ffff00>Cook"`; nameFallback="cook" (lowercase) → matches
     case-insensitively after markup strip.
  5. **Plane filter** — NPC matching id but on a different plane than
     local player → not returned (must be miss).
  6. **Range filter** — NPC matching id but outside `scanRadius` → miss.
  7. **onCanvas reflects hull poly** — NPC with `getCanvasTilePoly()`
     returning non-null → `onCanvas=true`; null → `false`.
  8. **No local player** — `client.getLocalPlayer()` null →
     `NpcScan.miss("no local player")`. (Edge case from current code.)
  9. **Threading guard** — call from non-client-thread (mock
     `client.isClientThread()` → false) → throws `IllegalStateException`.
 10. **Diagnostic content** — pack 10 NPCs in range, none match;
     diagnostic must list 8 names with `id=` and `d=` annotations,
     prefixed by `"scanned 10 NPCs"`.

Run: `./gradlew :client:test --tests NpcInteractionFindOnSceneTest`

Expected: all FAIL (`findOnScene` doesn't exist).

- [ ] **Step 1.3: Implement findOnScene**

Add the method per the API shape above. Add a private static
`stripMarkup(String)` helper. Reuse the existing `onClient` helper (do
NOT marshal inside `findOnScene`; the threading guard is the contract).

- [ ] **Step 1.4: Run tests**

Run: `./gradlew :client:test --tests NpcInteractionFindOnSceneTest`

Expected: all PASS.

- [ ] **Step 1.5: Migrate CooksAssistantScript callers**

Two callsites in `CooksAssistantScript.java`:
- Line 484: `CookScan scan = onClient(this::scanForCook);`
- Line 950: `CookScan scan = onClient(this::scanForCook);`

Replace each with:

```java
NpcScan scan = onClient(() -> npcInteraction.findOnScene(
    COOK_SCENE_SCAN_RADIUS,
    new int[]{ COOK_NPC_ID },
    "Cook"));
```

Update field/local references (`scan.npcIndex()`, `scan.tile()`,
`scan.onCanvas()`, `scan.diagnostic()`) — record API is identical, so
this is a type rename only.

Delete:
- `private record CookScan(...)` (line 458)
- `private CookScan scanForCook()` (lines 518-572)
- `private static String stripMarkup(String s)` (lines 574-577) — now
  on `NpcInteraction` as private static.

Delete `import java.awt.Polygon;` and `import net.runelite.api.NPC;`,
`net.runelite.api.NPCComposition;` if no longer used elsewhere in the
file (they aren't; the iteration moved).

- [ ] **Step 1.6: Compile + run all recorder tests**

```bash
JAVA_HOME=$(dirname /opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home/bin/java)/.. \
  ./gradlew :client:compileJava :client:test --tests "net.runelite.client.plugins.recorder.*"
```

Expected: BUILD SUCCESSFUL. Pre-existing `recorder` tests should still
pass (none touch the deleted private members).

- [ ] **Step 1.7: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/npc/NpcScan.java \
        runelite-client/src/main/java/net/runelite/client/plugins/recorder/npc/NpcInteraction.java \
        runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/CooksAssistantScript.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/npc/NpcInteractionFindOnSceneTest.java
git commit -m "$(cat <<'EOF'
refactor(npc): hoist findOnScene + NpcScan into NpcInteraction

cooks-assistant's scanForCook + private CookScan record duplicate
infrastructure that every quest script will need. Move them onto
NpcInteraction with id-list + optional name fallback, strict
client-thread guard, and the existing diagnostic format preserved.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task 2: NpcInteraction.talkTo

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/npc/NpcInteraction.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/CooksAssistantScript.java`
  (replace `tickTalkToCook` with one `RUN_TASK` dispatch + done-predicate poll)
- Create: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/npc/NpcInteractionTalkToTest.java`

**API shape:**

```java
// NpcInteraction.java — new public method, must run on dispatcher worker.
//
// One full talk cycle: click NPC with verb → wait for dialogue widget →
// drive completeDialogue(options) until no dialogue visible. Returns
// when dialogue ends naturally OR fails to open within dialogueTimeoutMs.
//
// npcIndex: from a prior findOnScene; caller is responsible for
//   re-resolving if the scene shifted between scan and dispatch.
// verb: e.g. "Talk-to", "Trade with". Passed to npcClickOnWorker.
// dialogueOpenTimeoutMs: how long to wait for ChatLeft/ChatRight/Chatmenu
//   to appear after the click. Cooks-assistant currently uses 5_000ms
//   end-to-end (DIALOGUE_RETRY_MS); start with 5_000 as the default and
//   accept it as a parameter so quest scripts with snappy/laggy dialogues
//   can tune it.
// options: passed verbatim to completeDialogue. Empty array = advance
//   Continue prompts only.
//
// Returns: TalkResult.OPENED_AND_COMPLETED | TalkResult.NEVER_OPENED.
// Does NOT return success/failure of the *interaction* (e.g. quest
// progressed) — that's the caller's done-predicate concern.
public TalkResult talkTo(int npcIndex, String verb,
                         long dialogueOpenTimeoutMs, String... options)
        throws InterruptedException
```

```java
public enum TalkResult { OPENED_AND_COMPLETED, NEVER_OPENED }
```

**Threading guard:** `assertWorkerThread("talkTo")` at entry. Cooks-assistant
already dispatches `completeDialogue` inside a `RUN_TASK`; `talkTo`
inherits that contract and is itself dispatched the same way.

**Implementation notes:**
- Step A: `dispatcher.npcClickOnWorker(npcIndex, verb)` — already exists
  per `HumanizedInputDispatcher.java:1703`. This blocks until the click
  chain completes (success or failure).
- Step B: poll `inDialogue()` (already on `NpcInteraction`) at 250ms
  cadence with `SequenceSleep.sleep(client, 250L + jitter)`; abort with
  `NEVER_OPENED` once `dialogueOpenTimeoutMs` elapses without
  `inDialogue()` ever returning true. Mirror the
  `DIALOGUE_WAIT_MS`/`DIALOGUE_RETRY_MS` semantics from
  `tickTalkToCook` but collapse the "wait then retry click" outer
  retry — the *caller* retries (see migration in Step 2.5).
- Step C: once `inDialogue()` returns true, call
  `completeDialogue(options)`. Return `OPENED_AND_COMPLETED` after it
  returns.
- Logging: one `log.info("npc: talkTo idx={} verb={} → {}", ...)`
  on entry and exit. The existing per-step logs in `completeDialogue`
  give the dialogue-step trace.

**Why no built-in retry:** the cook needs TWO talks (start quest, hand
items in). The talk loop's "done" condition is script-specific (in
cooks-assistant: `inventoryCount(EGG) > 0`). Built-in retry would
either (a) need a `Supplier<Boolean> done` parameter, or (b) call once
and let the caller loop. We pick (b): smaller API, caller stays in
charge of state-transition and abort decisions, and the FSM tick
cadence already provides the retry rhythm.

**Steps:**

- [ ] **Step 2.1: Write the failing tests for talkTo**

Create `NpcInteractionTalkToTest.java` with these cases. The dispatcher
is mocked (don't try to drive a real `HumanizedInputDispatcher` from
unit tests — that path is covered by integration in
`tickTalkToCook` migration).

  1. **Happy path** — mock `dispatcher.npcClickOnWorker(...)` returns
     normally; `inDialogue()` returns true on first poll;
     `completeDialogue(options)` returns. Verify return value =
     `OPENED_AND_COMPLETED` and `npcClickOnWorker(idx, verb)` was
     invoked exactly once with the right args.
  2. **Dialogue never opens** — `inDialogue()` always returns false;
     test injects a fake clock so we can advance past
     `dialogueOpenTimeoutMs`. Result = `NEVER_OPENED`,
     `completeDialogue` NOT called.
  3. **Dialogue opens late but inside timeout** — `inDialogue()`
     returns false, false, true. Result = `OPENED_AND_COMPLETED`,
     `completeDialogue` called once.
  4. **Empty options** — Result = `OPENED_AND_COMPLETED`,
     `completeDialogue("")` reached as `completeDialogue(/*empty*/)`.
     Verify the caller can pass no options.
  5. **Worker-thread guard** — call from main test thread (which is
     not the dispatcher worker). Expect `IllegalStateException` from
     `assertWorkerThread`. (Achieve "running on worker" in the other
     tests by mocking dispatcher's worker-detection helper, OR by
     stubbing the assertion via package-private hook — see how
     `BankInteractionTest` does it; replicate that style.)

If `assertWorkerThread` isn't currently package-mockable in
`NpcInteraction` — add a package-private hook on this skill (a
`Predicate<Void> workerThreadCheck` field with a default that calls
`Thread.currentThread().getName().contains("dispatcher")` or whatever
`BankInteraction` uses). Match `BankInteraction`'s style — search for
`assertWorkerThread` in `BankInteraction.java` and lift the same
mechanism, do not invent a new one.

Run: `./gradlew :client:test --tests NpcInteractionTalkToTest`

Expected: all FAIL.

- [ ] **Step 2.2: Implement talkTo + TalkResult**

Add `TalkResult` enum and `talkTo` method per shape above. Use a fake
clock (`LongSupplier nowMs`) parameter or extract a private
`waitForDialogueOpen(long timeoutMs)` so tests can swap the clock.

- [ ] **Step 2.3: Run tests**

Expected: all PASS.

- [ ] **Step 2.4: Migrate tickTalkToCook**

Replace the `tickTalkToCook` body in `CooksAssistantScript.java`
(lines 945-1050) with the new shape:

```
private void tickTalkToCook() {
    if (!cookClickDispatched) {
        if (dispatcher.isBusy()) { status.set("cook: dispatcher busy"); return; }
        NpcScan scan = onClient(() -> npcInteraction.findOnScene(
            COOK_SCENE_SCAN_RADIUS, new int[]{COOK_NPC_ID}, "Cook"));
        if (scan == null || !scan.found()) {
            log.info("cooks-assistant: cook not on scene during talk — bouncing to WALK_TO_COOK");
            setState(State.WALK_TO_COOK);
            return;
        }
        int idx = scan.npcIndex();
        dispatcher.dispatch(ActionRequest.builder()
            .kind(ActionRequest.Kind.RUN_TASK)
            .channel(ActionRequest.Channel.MOUSE)
            .task(() -> {
                NpcInteraction.TalkResult r = npcInteraction.talkTo(
                    idx, VERB_TALK_TO, DIALOGUE_RETRY_MS, COOK_DIALOGUE_OPTIONS);
                log.info("cooks-assistant: talkTo cook idx={} → {}", idx, r);
            })
            .taskName("CooksAssistant.talkTo")
            .build());
        cookClickDispatched = true;
        return;
    }

    if (dispatcher.isBusy()) { status.set("cook: dialogue in progress"); return; }

    // Talk task finished. Inventory check is the truth source.
    talkAttempts++;
    boolean stillHasIngredients = ...; // unchanged
    if (!stillHasIngredients) { ... DONE ... return; }
    if (talkAttempts >= MAX_COOK_TALKS) { abortWith(...); return; }
    cookClickDispatched = false;
}
```

Delete the now-unused fields:
- `private boolean dialogueTaskDispatched;` (line 228)
- `DIALOGUE_WAIT_MS` constant (line 182) — `talkTo` owns this now via
  internal poll cadence; the caller only needs `DIALOGUE_RETRY_MS`.
- Reset of `dialogueTaskDispatched` in `setState` (line 1059).

Verify the only field still gating retries is `cookClickDispatched`.
The two-phase wait (DIALOGUE_WAIT_MS, then DIALOGUE_RETRY_MS) collapses
to one: dispatcher busy → wait, dispatcher idle → talk completed (or
NEVER_OPENED, in which case the next tick re-enters cookClickDispatched=false).

- [ ] **Step 2.5: Compile + run recorder tests + smoke-build the jar**

```bash
JAVA_HOME=$(dirname /opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home/bin/java)/.. \
  ./gradlew :client:compileJava :client:test --tests "net.runelite.client.plugins.recorder.*"

JAVA_HOME=$(dirname /opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home/bin/java)/.. \
  ./gradlew :client:shadowJar
```

Expected: BUILD SUCCESSFUL on both. Shadow jar built — pre-flight gate
before manual cooks-assistant smoke run, which the user will perform
out of band.

- [ ] **Step 2.6: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/npc/NpcInteraction.java \
        runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/CooksAssistantScript.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/npc/NpcInteractionTalkToTest.java
git commit -m "$(cat <<'EOF'
refactor(npc): add talkTo lifecycle to NpcInteraction; collapse cooks-assistant talk FSM

talkTo runs one full click→dialogue→complete cycle on the dispatcher
worker. Cooks-assistant drops dialogueTaskDispatched + DIALOGUE_WAIT_MS
and now just polls dispatcher.isBusy() between talk attempts; the
inventory-consumed check stays as the script-level done predicate.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Task 3: TrailWalker.walkRouteUntil

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailWalker.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/CooksAssistantScript.java`
  (replace `tickWalkToCook`'s manual scan-then-walk with one `walkRouteUntil` call)
- Create: `runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailWalkerWalkUntilTest.java`

**API shape:**

```java
// TrailWalker.java — new public method.
//
// Runs walkRoute(route) but checks shortCircuit BEFORE delegating each
// tick. If shortCircuit returns true: returns ARRIVED immediately and
// resets the active route trail (so a subsequent walkRoute starts fresh,
// matching the post-ARRIVED contract of the existing walkRoute).
//
// shortCircuit runs on the calling thread. If it needs client state,
// it must marshal itself (e.g. via clientThread.invokeLater).
//
// Caller's threading: same as walkRoute — cooks-assistant calls this
// from its tickLoop worker thread.
public Status walkRouteUntil(Route route, BooleanSupplier shortCircuit)
        throws InterruptedException
```

**Implementation notes:**
- Three lines of body. Don't over-engineer:
  ```
  if (shortCircuit.getAsBoolean()) {
      activeRoutePath = null;     // matches walkRoute's post-ARRIVED reset
      return Status.ARRIVED;
  }
  return walkRoute(route);
  ```
- Do NOT add a per-leg `shortCircuit` check inside `tick(TrailPath)`.
  That requires plumbing the predicate through internal state, and the
  current cadence (one outer-tick = one walker tick = ~650ms) already
  gives sub-second responsiveness. Keep the change non-invasive.
- `BooleanSupplier` cannot throw checked exceptions, so the predicate
  cannot propagate `InterruptedException`. The cooks-assistant predicate
  calls the script's `onClient(...)` helper which catches
  `InterruptedException` internally (sets the interrupt flag, returns
  null) — a fine fit. Other callers that need to await a checked
  failure must encode the failure as a boolean (return false +
  rely on the next tick) or wrap with `RuntimeException`.

**Steps:**

- [ ] **Step 3.1: Write the failing tests**

Create `TrailWalkerWalkUntilTest.java`. Build on the existing
`TrailWalkerHandoffTest.java` fixture pattern (it already mocks
`Client`, `WorldView`, `Player`, `Route`, etc.). Cases:

  1. **Predicate true on first tick** — `walkRouteUntil(route, () -> true)`
     → returns `ARRIVED`. `walkRoute` body never executed (verify by
     mocking dispatcher and asserting zero `dispatch(...)` calls).
  2. **Predicate false → delegates to walkRoute** — `walkRouteUntil(route,
     () -> false)` returns whatever `walkRoute(route)` returned. Verify
     by stubbing route.entries() with one trail and asserting the
     walker advances exactly as it does in `TrailWalkerHandoffTest`'s
     plain-`walkRoute` flow (one identical assertion).
  3. **Predicate flips between calls** — call 1 with predicate=false →
     IN_PROGRESS, call 2 with predicate=true → ARRIVED, call 3 with
     predicate=false on a new tick → starts a fresh trail pick (i.e.
     `walkRoute` called `lastPickedTrail` honors `noRepeat`). Asserts
     the `activeRoutePath = null` reset post-shortcircuit works.
  4. **Predicate throws RuntimeException** — predicate throws → exception
     propagates uncaught (no swallow). Document: caller is responsible
     for catching predicate failures.

Run: `./gradlew :client:test --tests TrailWalkerWalkUntilTest`

Expected: FAIL (method doesn't exist).

- [ ] **Step 3.2: Implement walkRouteUntil**

Add the 3-line method per shape above. Update class JavaDoc to mention
the new entry point alongside `walkRoute`.

- [ ] **Step 3.3: Run tests**

Expected: PASS. Also run the full `recorder/trail` test suite to
confirm no regression on the existing walker tests:

```bash
JAVA_HOME=$(dirname /opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home/bin/java)/.. \
  ./gradlew :client:test --tests "net.runelite.client.plugins.recorder.trail.*"
```

- [ ] **Step 3.4: Migrate tickWalkToCook**

In `CooksAssistantScript.java`, replace `tickWalkToCook` (lines 482-512)
with one call to `walkRouteUntil`:

```
private void tickWalkToCook() throws InterruptedException {
    Route route = routeFor(State.WALK_TO_COOK);
    if (route == null) return;
    String prefix = ROUTE_PREFIX.get(State.WALK_TO_COOK);
    TrailWalker.Status st = trailWalker.walkRouteUntil(route, () -> {
        NpcScan scan = onClient(() -> npcInteraction.findOnScene(
            COOK_SCENE_SCAN_RADIUS, new int[]{COOK_NPC_ID}, "Cook"));
        long now = System.currentTimeMillis();
        boolean onCanvas = scan != null && scan.found() && scan.onCanvas();
        if (now - lastCookVisibilityLogMs > COOK_VISIBILITY_LOG_PACE_MS) {
            lastCookVisibilityLogMs = now;
            logCookScan(scan);    // extracted helper for the existing diagnostic block
        }
        return onCanvas;
    });
    status.set("route[" + prefix + "]: " + st);
    switch (st) {
        case ARRIVED -> { walkerStuckCount = 0; setState(State.TALKING_TO_COOK); }
        case STUCK, ERROR -> {
            walkerStuckCount++;
            log.info("cooks-assistant: route stuck #{} on '{}'", walkerStuckCount, prefix);
            if (walkerStuckCount > WALKER_MAX_STUCK)
                abortWith("walker stuck " + walkerStuckCount + "× on '" + prefix + "'");
        }
        default -> {}
    }
}

private void logCookScan(NpcScan scan) {
    if (scan == null) {
        log.info("cooks-assistant: cook scan returned null (client-thread timeout?)");
    } else if (!scan.found()) {
        log.info("cooks-assistant: cook not on scene yet — {}", scan.diagnostic());
    } else if (!scan.onCanvas()) {
        log.info("cooks-assistant: cook on scene at {} but offscreen — walker will continue ({})",
            scan.tile(), scan.diagnostic());
    }
}
```

Note: this collapses `tickWalkToCook` from a custom shape into one
that mirrors `tickRouteWalk(curState, onArrival)` plus a predicate.
Keep `tickWalkToCook` as a separate method (don't try to fold it into
`tickRouteWalk` via a predicate parameter — that's a separate
refactor and not in scope).

- [ ] **Step 3.5: Compile + full recorder test suite + shadow jar**

```bash
JAVA_HOME=$(dirname /opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home/bin/java)/.. \
  ./gradlew :client:compileJava :client:test --tests "net.runelite.client.plugins.recorder.*"

JAVA_HOME=$(dirname /opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home/bin/java)/.. \
  ./gradlew :client:shadowJar
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3.6: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/trail/TrailWalker.java \
        runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/CooksAssistantScript.java \
        runelite-client/src/test/java/net/runelite/client/plugins/recorder/trail/TrailWalkerWalkUntilTest.java
git commit -m "$(cat <<'EOF'
refactor(trail): add walkRouteUntil(predicate) for early-arrival short-circuit

Three-line wrapper around walkRoute that returns ARRIVED if the predicate
fires before the trail finishes. Cooks-assistant's tickWalkToCook drops
its manual scan-then-walk shape and uses walkRouteUntil + a closure that
short-circuits on cook-on-canvas.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

# Self-review (writing-plans skill checklist)

**1. Spec coverage:**
- ✓ Task 1 covers extraction #1 (findOnScene + name fallback) and
  migrates both existing callsites.
- ✓ Task 2 covers extraction #2 (talkTo lifecycle), migrates
  `tickTalkToCook`, and removes the now-redundant `dialogueTaskDispatched`
  flag + `DIALOGUE_WAIT_MS` constant.
- ✓ Task 3 covers extraction #3 (walkRouteUntil) and migrates
  `tickWalkToCook`.

**2. Placeholder scan:** No "TBD"s. The few code blocks (e.g.
`tickWalkToCook` migration) are pseudocode-shaped because they
reference fields/methods we don't redefine in the plan; the engineer
must inline-merge with the live source. This is intentional given the
"don't paste full Java sources" rule from project memory.

**3. Type consistency:**
- `NpcScan.found()` (Task 1) → used in Tasks 2 and 3 migration
  examples. Consistent.
- `TalkResult.OPENED_AND_COMPLETED` / `NEVER_OPENED` defined in Task 2,
  used in same task only.
- `findOnScene(int, int[], String)` signature stable across all three
  tasks. No drift.
- `walkRouteUntil(Route, BooleanSupplier)` signature stable.

**4. Inspect-first compliance:** Every API I'm extending was read
during planning:
- `NpcInteraction.java` (full file, 184 lines)
- `CooksAssistantScript.java` (full file, 1126 lines)
- `TrailWalker.java` (header + walkRoute + tick — 537+)
- `HumanizedInputDispatcher.java` (verified `npcClickOnWorker` exists
  at line 1703, signature matches)
- `BankInteraction.java` (worker-thread guard pattern referenced for
  Task 2 — verified `assertWorkerThread("...")` is the existing
  convention)
- Existing test fixture `TrailWalkerHandoffTest.java` referenced as
  the prototype for new tests.

---

## Risks / things to watch during execution

- **Test fixture drift:** `NpcInteractionFindOnSceneTest` will need to
  mock `Client.getTopLevelWorldView().npcs()` — verify this returns
  an `Iterable<NPC>` in the live API before locking the mock shape.
- **Worker-thread guard mismatch:** if `BankInteraction.assertWorkerThread`
  uses a thread-name check (`"dispatcher"` substring) and the test
  harness names threads differently, the tests will leak the guard
  failure. Lift the *exact* mechanism from `BankInteraction`, don't
  re-invent — that's why Step 2.1 explicitly says "match the style".
- **CookScan diagnostic format:** if any log-grep tooling parses the
  exact diagnostic string, the match-by= prefix or "scanned N NPCs"
  format must stay identical. We preserve them by lifting the
  StringBuilder code verbatim, but flag for the user during QC.
- **Smoke test on the live client:** after Task 2 and Task 3, the user
  should run cooks-assistant end-to-end (start at Lumbridge bank,
  confirm: bank-prep → walk to cook → talk start → walk back? no,
  just: scan-handoff fires, talkTo completes, quest done). Unit
  tests don't cover the dispatcher round-trip.
