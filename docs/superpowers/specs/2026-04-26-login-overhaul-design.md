# Login Overhaul — Design

**Date:** 2026-04-26
**Status:** Draft v3 (revised after second dual-reviewer pass; pending user review of this written spec)
**Author:** brainstormed with the user
**Scope:** Replace the procedural `LoginAssistant.login()` with an explicit state machine; add multi-account UI, humanized field clearing, post-login welcome dismissal, error-classified single-retry, world switching (mechanism TBD — see §7), and a debug widget dumper.

---

## 1. Problem summary

The current `LoginAssistant.login()` (procedural, ~463 lines, in `runelite-client/src/main/java/net/runelite/client/sequence/login/LoginAssistant.java`) is missing or wrong on the following:

1. Treats `LOGGED_IN` / `LOADING` / `LOGGING_IN` as success at precheck — wrong if the wrong account is already logged in.
2. Compares pre-filled username case-insensitively but, on mismatch, types the new value **without clearing the field**. Result: garbage characters concatenated.
3. No mechanism to clear the username or password field.
4. Panel asks for the username via a `JOptionPane` every login — saved usernames must be re-typed each attempt.
5. Does not detect or dismiss the post-login welcome screen ("Click here to play" red button).
6. No retry logic — any failure returns `false`; caller has to re-invoke.
7. Pre-login world hop uses `client.changeWorld(int)` engine setter; world filter doesn't exclude PvP/Bounty/Deadman/etc.
8. No way to identify required widget IDs for in-game widgets the new design needs (welcome screen sub-components, login error banner location).

## 2. Goals

- Replace the procedural flow with an explicit FSM where every state checks its preconditions before acting (the "right field at right place" guarantee).
- Add a multi-account panel UI: `JList` of saved usernames + `Add` / `Delete` / `Log in` buttons.
- Implement humanized field clearing via a true backspace hold (KEY_PRESSED → repeat → KEY_RELEASED) with inter-event jitter for variance.
- Detect missed clicks early (CLICK_LOGIN early-response gate, NUDGE_INTRO re-click) so a missed click costs ~3s, not 30s — fixes the bug captured in production logs.
- Detect and dismiss the welcome screen with a wide randomized 4–45s delay before clicking.
- Single-retry policy on recoverable errors only (world full, member-only, network blip, transient unknown errors); terminal failures stop immediately with classified messages and **do not** restart.
- Replace `client.changeWorld(int)` with a more humanized world switcher — **mechanism TBD pending user investigation; see §7**.
- Provide a debug widget dumper to identify unknown in-game widget IDs.
- Filter out PvP / Bounty / Deadman / PVP_ARENA / LMS / High-Risk / Tournament / Skill-Total / Quest-Speedrunning / Fresh-Start / Seasonal / Beta worlds.
- Persist the panel's last-selected username so the user does not re-pick on every panel re-open.

## 3. Non-goals

- No multi-character batch login or scheduled login flows.
- No 2FA / authenticator handling (out of scope; assumes OSRS account without authenticator).
- No multi-attempt retry beyond the single retry on recoverable errors (terminal errors hard-stop per user requirement).
- No support for tournament / beta / fresh-start / seasonal / skill-total / quest-speedrun worlds — they are excluded by the world filter.
- Mining / chicken loop are unaffected by this change.

## 4. Architecture

### 4.1 File layout

| File | Status | Role |
|---|---|---|
| `runelite-client/src/main/java/net/runelite/client/sequence/login/LoginAssistant.java` | modified (slimmed) | Public entrypoint. Delegates to `LoginRunner`. Same constructor signature for backward compat with `RecorderPlugin` wiring. |
| `runelite-client/src/main/java/net/runelite/client/sequence/login/LoginRunner.java` | new | Drives the FSM via a `switch` over `LoginState` enum. Owns the retry loop. |
| `runelite-client/src/main/java/net/runelite/client/sequence/login/LoginState.java` | new | Enum of all FSM states (see §5). |
| `runelite-client/src/main/java/net/runelite/client/sequence/login/LoginContext.java` | new | Mutable context shared across transitions: target `LoginCredentials`, current world id, retry count, last error, `HumanizedInputDispatcher`, `Client`, `ClientThread`, `Random`, `statusSink`. |
| `runelite-client/src/main/java/net/runelite/client/sequence/login/LoginError.java` | new | Enum of error classes. Each value carries `recoverable: boolean`, `message: String`, and an `applyRecovery(LoginContext)` method that may throw `InterruptedException`. |
| `runelite-client/src/main/java/net/runelite/client/sequence/login/LoginErrorClassifier.java` | new | Maps a raw red-banner string from the login screen to a `LoginError`. Centralized so the matching strings are testable. |
| `runelite-client/src/main/java/net/runelite/client/sequence/login/WelcomeScreenDetector.java` | new | Helper: `boolean isVisible(Client)`, `Point clickTarget(Client)`. Wraps `InterfaceID.WELCOME_SCREEN` lookup. |
| `runelite-client/src/main/java/net/runelite/client/sequence/login/WorldSwitcher.java` | new (mechanism TBD — §7) | Switches worlds when `WORLD_FULL` / `MEMBER_WORLD` recovery fires. Implementation depends on §7 decision. |
| `runelite-client/src/main/java/net/runelite/client/sequence/login/WorldPicker.java` | modified | Add `pickF2PNonPvP(int currentWorldId)`. Exclude all PvP/danger/non-standard world types. |
| `runelite-client/src/main/java/net/runelite/client/sequence/login/HumanizedTyping.java` | modified | Add `holdBackspaceUntilEmpty(...)` and `holdBackspaceForDuration(...)` for the field-clear primitives. |
| `runelite-client/src/main/java/net/runelite/client/sequence/login/CredentialStore.java` | modified | Add `Set<String> list() throws CredentialStoreException`. |
| `runelite-client/src/main/java/net/runelite/client/sequence/login/KeychainCredentialStore.java` | modified | Implement `list()` via a sidecar JSON of usernames (keychain itself stores passwords). |
| `runelite-client/src/main/java/net/runelite/client/sequence/login/EncryptedFileCredentialStore.java` | modified | Implement `list()` as `readMap().keySet()`. |
| `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java` | modified | Replace JOptionPane username prompt with `JList` + `Add` / `Delete` / `Log in` buttons. Add `Debug: dump open widgets` button. Status callback wraps `SwingUtilities.invokeLater`. |
| `runelite-client/src/main/java/net/runelite/client/plugins/recorder/debug/WidgetDumper.java` | new | Walks `client.getWidgetRoots()`, logs full widget tree to log + sidecar txt file under `RUNELITE_DIR`. |

### 4.2 Threading

`LoginRunner.run()` executes on a daemon thread spawned by the panel. All client reads go through `dispatcher.runOnClient(Supplier)` (2s timeout per the dispatcher contract). Per-state precondition checks all use `runOnClient`; if it times out, the state returns `Failure(CLIENT_THREAD_STUCK)` (recoverable). All `Thread.sleep` calls in `applyRecovery` and within states honor `InterruptedException` and propagate it; the runner catches `InterruptedException` at the top level and returns `Failure(INTERRUPTED)` (terminal — user explicitly stopped).

### 4.3 Status callback contract

`statusSink: Consumer<String>` is invoked from the runner's daemon thread. The panel-side implementation MUST wrap setText calls in `SwingUtilities.invokeLater`. (Existing `RecorderPanel.java:540` already does this; the rewrite preserves that pattern.)

### 4.5 Logging contract

Every state transition emits an SLF4J INFO log line via the runner's class logger, prefixed `[login]`, in the format `[login] state X → Y` (matches the existing `LoginAssistant.java` log style — see captured production log, e.g. `[login] clicking LOGIN`). Every error emits a WARN log line including the full `LoginError.message()` and (for `UNKNOWN_LOGIN_ERROR`) the raw banner text so future patterns can be added to `LoginErrorClassifier`. Cancellation (`INTERRUPTED`) emits INFO. Recovery sleeps emit INFO with the chosen sleep duration so timing is auditable. No password text is ever logged.

### 4.4 Dispatcher reuse

The login dispatcher (per `RecorderPlugin.startUp()` line 145) remains a per-feature `HumanizedInputDispatcher` instance. No cross-feature changes here.

## 5. State machine

### 5.1 States

Linear with two retry loops: one for the welcome-click sub-step internal to `DISMISS_WELCOME`, one outer for whole-flow retry-on-recoverable.

```
PRECHECK
  - GameState reachable? on LOGIN_SCREEN, or LOGGED_IN with right account?
  - LOGGED_IN + right account → DONE (early)
  - LOGGED_IN + wrong account → FAILED(WRONG_ACCOUNT_LOGGED_IN)
  - LOGGING_IN / LOADING (transitional) → WAIT_FOR_LOGIN_SCREEN
  - other state → FAILED(UNEXPECTED_GAMESTATE)
  - LOGIN_SCREEN → NUDGE_INTRO

WAIT_FOR_LOGIN_SCREEN
  - poll GameState for up to 8s waiting for it to settle to LOGIN_SCREEN
    (used when retrying after a world switch — game may still be in transition)
  - on settle → PRECHECK
  - on timeout → FAILED(UNEXPECTED_GAMESTATE)

NUDGE_INTRO
  - if loginIndex == LOGIN_FORM_INDEX (== 2): → RESOLVE_USERNAME
    (note: loginIndex == 2 is currently TRUSTED to mean the existing-user form;
     if other forms — Create Account, Forgot Password — also report index 2,
     this needs an additional form-type check. To be verified once WidgetDumper
     can dump the title-screen state.)
  - else: click "Existing User" intro card; wait up to 3s for loginIndex == 2
  - on first timeout: re-click intro card, wait another 3s
  - on second timeout → FAILED(UNEXPECTED_GAMESTATE)

RESOLVE_USERNAME
  - explicit re-read: client.getUsername() via dispatcher.runOnClient
  - matches target (case-insensitive exact) → FOCUS_PASSWORD
  - mismatch + non-empty → CLEAR_USERNAME
  - empty → TYPE_USERNAME

CLEAR_USERNAME
  - precondition: explicit re-read of loginIndex == 2 (still on form);
                  click username field to ensure focus
  - HumanizedTyping.holdBackspaceUntilEmpty(
        readField = () -> dispatcher.runOnClient(client::getUsername),
        maxMs = 3000)
  - 3s cap
  - on timeout → FAILED(FIELD_NOT_CLEARED)
  - on success → re-read getUsername; if empty → TYPE_USERNAME, else FAILED(FIELD_NOT_CLEARED)

TYPE_USERNAME
  - precondition: explicit re-read of getUsername == "" AND loginIndex == 2
  - if either fails → FAILED(FIELD_NOT_CLEARED)  (something diverged)
  - HumanizedTyping.typeString(target.username)
  - post-condition: re-read getUsername; assert equals target (case-insensitive)
  - on mismatch → FAILED(FIELD_NOT_CLEARED)  (typing didn't land cleanly)
  - → FOCUS_PASSWORD

FOCUS_PASSWORD
  - precondition: re-read loginIndex == 2
  - 70%: tap Tab. 30%: click password field at proportional 0.50 × 0.54
  - settle 120-340ms
  - → CLEAR_PASSWORD

CLEAR_PASSWORD
  - precondition: re-read loginIndex == 2
    (we cannot read password text — client.getPassword() does not exist;
     only client.setPassword() is exposed. So we use a duration-bounded clear
     and guard against firing into the wrong field.)
  - HumanizedTyping.holdBackspaceForDuration(baseMs = 3500, varianceMs = 800)
    - generous duration so any realistic password length is covered;
      OSRS does NOT have a known small max password length (the prior 20-char
      assumption was wrong) — at ~50ms per event, 3500-4300ms covers
      ~70-86 chars which is well above any realistic password length
    - mid-wipe guard: every 200ms, re-check loginIndex == 2 via runOnClient;
      if it changed (focus moved off form), abort + return FAILED(FIELD_NOT_CLEARED)
  - → PASTE_PASSWORD

PASTE_PASSWORD
  - precondition: re-read loginIndex == 2
  - save current clipboard contents (Toolkit.getDefaultToolkit clipboard.getContents)
  - clipboard set → Ctrl/Cmd+V → restore previous clipboard (in finally block)
  - → CLICK_LOGIN

CLICK_LOGIN
  - precondition: re-read loginIndex == 2
  - 200-700ms hesitation
  - click LOGIN button at proportional 0.41 × 0.62
  - early-response gate: poll for 2.5s at 200ms intervals checking
    GameState != LOGIN_SCREEN OR loginIndex != 2 OR error widget visible
    (any of these indicates the click registered)
  - if gate passes → AWAIT_LOGGED_IN
  - if gate fails (still LOGIN_SCREEN at index 2 with no error) → click missed,
    re-click LOGIN once, then unconditionally → AWAIT_LOGGED_IN
    (so the captured 30s-wasted-on-missed-click bug becomes ~3s + retry)

AWAIT_LOGGED_IN
  - poll GameState every 200ms for up to 30s
  - within each poll, also check for error widget visible (see §6.4 classifier)
    — first non-empty banner text seen exits immediately, no need to wait 30s
  - on LOGGED_IN → AWAIT_WELCOME
  - on CONNECTION_LOST → FAILED(CONNECTION_TIMEOUT) [recoverable]
  - on remained LOGIN_SCREEN with error widget visible → LoginErrorClassifier
    matches the red-banner text against known patterns (see §6.4)
  - on 30s elapsed with no transition and no error widget → FAILED(TIMEOUT_NO_RESPONSE) [recoverable]
  - poll loop honors Thread.interrupted() between 200ms inner sleeps

AWAIT_WELCOME
  - precondition: GameState == LOGGED_IN
  - poll WelcomeScreenDetector.isVisible() for up to 5s
  - if visible → DISMISS_WELCOME
  - if 5s elapsed without it appearing → DONE (welcome was skipped — happens sometimes)

DISMISS_WELCOME
  - randomized 4,000-45,000ms delay (interruptible — propagates InterruptedException)
  - click WelcomeScreenDetector.clickTarget()
  - poll: welcome screen widget hidden? (up to 3s)
  - if dismissed → DONE
  - if still visible: retry click up to 2 more times with 1s gaps
  - if still visible after 3 attempts → FAILED(WELCOME_STUCK)

DONE
  - park cursor (move off-canvas 6-55px past edge)
  - statusSink.accept("finished — logged in as " + target.username)
  - return SUCCESS
```

### 5.2 Per-state contract

Every state, when invoked by `LoginRunner`, returns a `StateResult`:

```java
sealed interface StateResult {
    record Continue(LoginState next) implements StateResult {}
    record Done() implements StateResult {}
    record Failure(LoginError error) implements StateResult {}
}
```

Each state internally has three phases:

1. **Precondition check** — explicit read via `dispatcher.runOnClient(...)` of the specific client property the state depends on. On mismatch or `runOnClient` timeout, return `Failure` with a typed error. **No state may act on assumed state — every state re-reads.** This is the "right field at right place" guarantee.
2. **Act** — perform the humanized action.
3. **Post-condition / transition** — verify the act produced the expected change (where observable), decide the next state.

### 5.3 Constants

```java
static final int LOGIN_FORM_INDEX = 2;        // client.getLoginIndex() value when username/password form is shown (intro card is 0)
static final long PRECHECK_RUN_ON_CLIENT_TIMEOUT_MS = 2_000L;   // matches HumanizedInputDispatcher onClient timeout
static final long FIELD_CLEAR_HARD_CAP_MS = 3_000L;
static final long PASSWORD_CLEAR_BASE_MS = 3_500L;
static final int  PASSWORD_CLEAR_VARIANCE_MS = 800;
static final long NUDGE_INTRO_TIMEOUT_MS = 3_000L;
static final long CLICK_LOGIN_EARLY_GATE_MS = 2_500L;
static final long POLL_INNER_SLEEP_MS = 200L;
static final long AWAIT_LOGGED_IN_TIMEOUT_MS = 30_000L;
static final long AWAIT_WELCOME_TIMEOUT_MS = 5_000L;
static final long WELCOME_DISMISS_POLL_MS = 3_000L;
static final long WAIT_FOR_LOGIN_SCREEN_TIMEOUT_MS = 8_000L;
```

## 6. Error policy & retry behavior

### 6.1 LoginError enum

| Error | Source state | Recoverable | Recovery action | Terminal message |
|---|---|---|---|---|
| `BAD_CREDS` | AWAIT_LOGGED_IN | No | — | `"invalid credentials"` |
| `BANNED` | AWAIT_LOGGED_IN | No | — | `"account disabled"` |
| `LOGIN_LIMIT` | AWAIT_LOGGED_IN | No | — | `"login limit exceeded — wait several minutes"` |
| `TOO_MANY_INCORRECT_LOGINS` | AWAIT_LOGGED_IN | No | — | `"too many incorrect logins from this IP — wait, do not retry"` |
| `WORLD_FULL` | AWAIT_LOGGED_IN | **Yes** | `WorldSwitcher.switchTo(WorldPicker.pickF2PNonPvP(currentWorldId))` then sleep 2,000-5,000ms | `"world full; retry on world N also failed"` |
| `MEMBER_WORLD` | AWAIT_LOGGED_IN | **Yes** | Same as WORLD_FULL | `"member-only world; retry on world N also failed"` |
| `JUST_LEFT_OTHER_WORLD` | AWAIT_LOGGED_IN | **Yes** | Sleep 6,000-12,000ms; same world | `"just-left-other-world; retry also failed"` |
| `CONNECTION_TIMEOUT` | AWAIT_LOGGED_IN | **Yes** | Sleep 5,000-30,000ms; same world | `"connection lost; retry also failed"` |
| `TIMEOUT_NO_RESPONSE` | AWAIT_LOGGED_IN | **Yes** | Sleep 5,000-30,000ms; same world | `"login timed out; retry also failed"` |
| `SERVER_OFFLINE` | AWAIT_LOGGED_IN | **Yes** | Sleep 30,000-60,000ms; same world | `"login server offline; retry after long wait also failed"` |
| `UNKNOWN_LOGIN_ERROR` | AWAIT_LOGGED_IN | **Yes** | Sleep 8,000-15,000ms; same world (loud log of raw text so we can add a known mapping later) | `"unknown login error: <raw text>; retry also failed"` |
| `CLIENT_THREAD_STUCK` | any (runOnClient timeout) | **Yes** | Sleep 3,000-6,000ms; same world | `"client thread stuck; retry also failed"` |
| `UNEXPECTED_GAMESTATE` | PRECHECK / NUDGE_INTRO / WAIT_FOR_LOGIN_SCREEN | No | — | `"not on login screen — current state: X"` |
| `WRONG_ACCOUNT_LOGGED_IN` | PRECHECK | No | — | `"already logged in as different account — log out first"` |
| `FIELD_NOT_CLEARED` | CLEAR_USERNAME / TYPE_USERNAME / CLEAR_PASSWORD | No | — | `"field state diverged from expected (input dispatch issue)"` |
| `WELCOME_STUCK` | DISMISS_WELCOME | No | — | `"welcome screen click ignored after 3 attempts"` |
| `WORLD_SWITCH_FAILED` | WorldSwitcher | No | — | `"world switch failed; aborting"` |
| `INTERRUPTED` | any | No | — | `"login cancelled"` |

**Per-user requirement:** terminal errors hard-stop. They do NOT trigger a fresh restart-from-scratch attempt. Only recoverable errors trigger the single retry. Unknown server messages are recoverable (retry once, log loudly so we can add a mapping); they are NOT terminal.

### 6.2 Retry loop

```
retryCount = 0
state = PRECHECK
loop {
    try {
        result = runState(state, context)
    } catch (InterruptedException ie) {
        Thread.currentThread().interrupt()
        statusSink.accept("login cancelled")
        return FAILURE(INTERRUPTED)
    }
    if result is Done:
        statusSink.accept("finished — logged in as " + ctx.username)
        return SUCCESS
    if result is Continue:
        state = result.next
        continue
    if result is Failure:
        if result.error == INTERRUPTED:
            statusSink.accept("login cancelled")
            return FAILURE(result.error)
        if result.error.recoverable() && retryCount == 0:
            statusSink.accept("retrying after " + result.error.message())
            try {
                result.error.applyRecovery(context)
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt()
                return FAILURE(INTERRUPTED)
            } catch (Exception ex) {
                // recovery itself failed (e.g. WorldSwitcher threw)
                log.warn("recovery action failed", ex)
                return FAILURE(WORLD_SWITCH_FAILED)
            }
            state = WAIT_FOR_LOGIN_SCREEN   // not PRECHECK directly — let game settle
            retryCount++
            continue
        statusSink.accept(result.error.message())
        return FAILURE(result.error)
}
```

Maximum total attempts: 2 (initial + 1 retry, only if first failure is recoverable). Cancellation honored at all sleep / poll points.

### 6.3 Worlds we exclude (`WorldPicker`)

```java
EnumSet<WorldType> EXCLUDED = EnumSet.of(
    WorldType.MEMBERS,            // F2P account only
    WorldType.PVP,
    WorldType.DEADMAN,
    WorldType.BOUNTY,
    WorldType.PVP_ARENA,
    WorldType.LAST_MAN_STANDING,
    WorldType.HIGH_RISK,
    WorldType.TOURNAMENT_WORLD,
    WorldType.FRESH_START_WORLD,
    WorldType.SEASONAL,
    WorldType.BETA_WORLD,
    WorldType.SKILL_TOTAL,        // rejects players below total threshold
    WorldType.QUEST_SPEEDRUNNING  // rejects players not in the speedrun quest
);

pickF2PNonPvP(int currentWorldId):
    World[] worlds = client.getWorldList()   // returns array, not stream
    candidates = Arrays.stream(worlds)
        .filter(w -> Collections.disjoint(w.getTypes(), EXCLUDED))
        .filter(w -> w.getId() != currentWorldId)
        .filter(w -> w.getPlayerCount() >= 0)   // negative count == offline world
        .toList()
    return rng-pick from candidates
```

(`World.isOnline()` does not exist on the API; offline worlds report `getPlayerCount() < 0`.)

### 6.4 Login error string classifier

`LoginErrorClassifier.classify(String redBannerText) → LoginError` matches text patterns against the known set. All matching is `String.contains` after `.toLowerCase()` for resilience to capitalization changes.

**Precedence: iteration is in table order, first match wins.** Patterns are intentionally ordered most-specific to most-general so overlapping patterns resolve correctly (e.g., `"too many incorrect logins"` is checked before `"login limit exceeded"`; `"members area"` is checked after `"invalid username or password"` so a string containing both classifies as BAD_CREDS not MEMBER_WORLD). New patterns added later must be inserted at the position that preserves precedence.

| # | Pattern (substring, lowercased) | LoginError |
|---|---|---|
| 1 | `"invalid username or password"` | `BAD_CREDS` |
| 2 | `"account has been disabled"` / `"account has been involved"` | `BANNED` |
| 3 | `"too many incorrect logins"` | `TOO_MANY_INCORRECT_LOGINS` |
| 4 | `"login limit exceeded"` | `LOGIN_LIMIT` |
| 5 | `"this world is not accepting"` | `WORLD_FULL` |
| 6 | `"world is full"` | `WORLD_FULL` |
| 7 | `"need a members account"` / `"members area"` | `MEMBER_WORLD` |
| 8 | `"only just left another world"` | `JUST_LEFT_OTHER_WORLD` |
| 9 | `"login server offline"` / `"server is currently offline"` | `SERVER_OFFLINE` |
| 10 | `"error connecting to server"` / `"connection refused"` | `CONNECTION_TIMEOUT` |
| — | (any other red banner text) | `UNKNOWN_LOGIN_ERROR` (carries raw text) |

The classifier is purely a string→enum mapper, fully unit-testable. New patterns are added here without touching the FSM.

## 7. WorldSwitcher (mechanism TBD)

**Status: pending user investigation** (decision pending; user said "need to look into this" on 2026-04-26).

Two candidate implementations, exactly one will be picked:

### Option B1 — Canvas-pixel humanized world switch

Stay fully humanized using canvas-pixel proportional clicks (same approach the existing code uses for "Existing User" / "LOGIN" buttons). The OSRS title screen has no widget hierarchy (per `LoginAssistant.java:50-77`), so no widget IDs are available — we'd use proportional pixel coordinates.

**Required:** identify pixel coordinates for (a) the "Switch worlds" button on the title screen, and (b) world list rows in the picker pane (likely scrolling). The widget dumper (§9) does NOT help here — there are no widgets pre-login. Identification path: in-client visual inspection or a "canvas click logger" debug aid (not yet specced).

**Sub-FSM (3 states):**
- `OPEN_SWITCHER` — click the "Switch worlds" pixel target on title screen; wait up to 2s for picker pane to render
- `PICK_WORLD` — locate target world's row (scroll if needed), click it
- `AWAIT_WORLD_SWITCHED` — poll `client.getWorld()` for up to 10s until equal to target

### Option B2 — Engine setter with documented carve-out

Use `client.changeWorld(int)` for world switching ONLY (not other inputs). This is the same approach the existing code currently takes (per `LoginAssistant.java:72-76`), with the rationale: *"the same setter used by every other RuneLite plugin (DefaultWorld) and is not the hot path the 'no menuAction' rule was written about."*

**Implementation:** `WorldSwitcher.switchTo(targetWorldId)` calls `client.changeWorld(targetWorldId)` on the client thread, then polls `client.getWorld()` until it matches (10s timeout). Pre-login random world hop in current code is removed regardless of this choice — we only switch reactively when `WORLD_FULL` / `MEMBER_WORLD` fires.

### Open question for user

Once decided:
- B1 → spec section 7 expanded to full sub-FSM detail; an additional "canvas click logger" debug aid likely needed
- B2 → spec section 7 reduced to ~10 lines; engine-setter carve-out documented inline alongside existing `LoginAssistant.java:72-76` rationale

Specific widget IDs for in-game widgets (login error banner location, welcome screen sub-component) are **independent** of this decision and will be identified via the dumper (§9).

## 8. Panel UI

Replace the existing `buildLogin()` section in `RecorderPanel.java`.

**Layout:**

```
┌─────────────────── Login ───────────────────┐
│  Saved characters:                           │
│  ┌──────────────────────────────────────┐    │
│  │  alt1@example.com         [highlighted]   │
│  │  alt2@example.com                    │    │
│  │  main@example.com                    │    │
│  └──────────────────────────────────────┘    │
│  [Add…]   [Delete]   [Log in]                │
│                                              │
│  status: idle                                │
└──────────────────────────────────────────────┘
```

**Components:**

- `JList<String>` (single-select) inside a `JScrollPane`. Backed by a `DefaultListModel<String>` populated from `credentialStore.list()`. Selection appearance is the standard list highlight (panel's existing look-and-feel — RuneLite uses Substance LAF). User asked for "list view (radio rows)"; standard `JList` selection visually communicates the current pick. If a literal radio-style row is wanted, a custom `ListCellRenderer` with `JRadioButton` painted per row can be added — flagged as a potential refinement, not blocking.
- `Add…` button: opens a `JOptionPane` form with username + password fields. Behavior:
  - Cancel mid-dialog → clean no-op, no state changes.
  - Empty username → reject with inline message; do not call `write`.
  - Empty password → allow but show a warning prompt ("Save with empty password?") with Yes/No.
  - Username already exists in `list()` → confirm-overwrite dialog ("Username 'X' already saved. Overwrite the stored password?") with Yes/No. Yes → call `write(u, p)` (overwrite); No → no-op.
  - On successful write → refresh list, select the newly added entry, persist to `login-state.json`.
- `Delete` button: confirm dialog → `credentialStore.delete(selectedUsername)` → refresh list. Disabled when nothing is selected. After deletion: if any rows remain, select the next entry (or the previous if the deleted was the last); if the list is now empty, clear selection. Persist new lastSelected (or null).
- `Log in` button: enabled only when something is selected AND no login is currently in flight. On click: disable immediately. Wrap the selected username in a `LoginCredentials` and hand it to `loginAssistant.login(creds, statusSink)` on a daemon thread (same threading as today). Re-enable when the login terminates (DONE or FAILURE) — done via the status callback.
- `status` label: bound to the status callback. Updates wrapped in `SwingUtilities.invokeLater`.
- **Last-selected persistence:** after every `Log in` click, write the selected username + the known users list to `<RUNELITE_DIR>/recorder/login-state.json` (single sidecar, format `{"lastSelected": "...", "knownUsers": [...]}`). On panel show, pre-select that entry if it still exists in `list()`. The keychain sidecar (used by `KeychainCredentialStore.list()`) is the same file — both pieces of state coexist in one JSON.

**Removed:** the `setCredsBtn` and its `onSetCredentials()` flow are replaced by the `Add…` button.

## 9. Debug widget dumper

New panel button: **`Debug: dump open widgets`**.

When clicked:

1. Calls `dispatcher.runOnClient(() -> client.getWidgetRoots())`.
2. Recursively walks each widget, emitting:
   - Combined widget id in hex (e.g., `0x017a_0002`) split into group/child
   - `text` if present
   - `spriteId` if non-zero
   - `bounds` (canvas pixel rect)
   - `isHidden`
   - `actions` array if present
3. Logs to standard SLF4J log (visible in client console) AND writes to `<RUNELITE_DIR>/recorder/widget-dump-<unix-timestamp>.txt` (using `RuneLite.RUNELITE_DIR`).
4. Status callback shows: `"widget dump written to <path>"`.

**Useful for identifying:**

- Welcome screen — confirm `InterfaceID.WelcomeScreen.CONTENT` is the right click target, or pick a sub-component.
- Login error banner — find the red-text component id and the message strings that flow through it (helps tune `LoginErrorClassifier` patterns).
- (If Option B1 is chosen for §7) does NOT help with pre-login title screen — title screen has no widget hierarchy. A separate debug aid would be needed (see §7).

## 10. Humanization details

### 10.1 Backspace hold

Synthesize as a stream of repeating events:
- KEY_PRESSED + KEY_TYPED for `'\b'`
- Inter-event delay: 33-55ms with per-event jitter (±10%)
  - Floor at 33ms matches macOS default key-repeat (Windows is similar; lower floors read as bot-like to sophisticated detectors)
- Final KEY_RELEASED on stop

Two API entry points:

**`holdBackspaceUntilEmpty(Supplier<String> readField, long maxMs)`** — used for username clear:
- Poll `readField.get()` between events; stop the moment it returns empty
- Hard cap `maxMs` (default 3000)
- Snap-stops on empty (no overshoot — variance lives in inter-event jitter)

**`holdBackspaceForDuration(long baseMs, int varianceMs, Predicate<Void> abortGuard)`** — used for password clear (no field-poll possible):
- Hold for `baseMs + rng.nextInt(varianceMs)` ms total (default 3500 + 0-800ms = 3500-4300ms, sized for any realistic password length at ~50ms per event)
- Every 200ms, invoke `abortGuard.test(null)` — if true, abort early (used to bail if `loginIndex` changes mid-wipe)

The 80/20 staged-vs-continuous split and the post-empty overshoot from earlier drafts are both dropped — reviewers flagged both as cargo-cult variance. The only meaningful variance is inter-event delay jitter.

### 10.2 Welcome click delay

Uniform random 4,000-45,000ms between welcome screen detection and the click. (User originally specified 2-45s, then reviewed and confirmed 4-45s after reviewer noted that 2s reads as bot-tell to detectors that profile click latency on welcome screens.)

### 10.3 Other timings

- Pre-LOGIN button hesitation: 200-700ms
- Tab vs click for password focus: 70/30 split
- World-switch inter-step pacing (B1 only): 800-1,800ms between "open switcher" and "click world row"
- Recovery sleep after CONNECTION_TIMEOUT / TIMEOUT_NO_RESPONSE: 5,000-30,000ms
- Recovery sleep after WORLD_FULL / MEMBER_WORLD switch: 2,000-5,000ms before re-entering WAIT_FOR_LOGIN_SCREEN
- Recovery sleep after SERVER_OFFLINE: 30,000-60,000ms
- Recovery sleep after UNKNOWN_LOGIN_ERROR: 8,000-15,000ms
- Recovery sleep after JUST_LEFT_OTHER_WORLD: 6,000-12,000ms
- All sleeps interruptible (propagate `InterruptedException`)

## 11. Testing strategy

### 11.1 Unit tests

| Test | Covers |
|---|---|
| `LoginRunnerTest` | Each state's behavior with a mocked `LoginContext`/dispatcher. Asserts correct transition output and emitted error class. Walks happy path end-to-end with mocked client. |
| `LoginErrorClassifierTest` | Feed sample error widget texts (every pattern in §6.4 + several un-mapped) → assert correct error class. Includes case variation + leading/trailing whitespace. |
| `WorldSwitcherTest` | Sub-FSM tests dependent on §7 decision. For B1: mock canvas clicks + world picker pane. For B2: mock `client.changeWorld` + verify `getWorld()` poll loop. |
| `WorldPickerTest` (extended) | Asserts excluded world types are filtered. Asserts current world id is excluded. Asserts offline worlds (`getPlayerCount() < 0`) are filtered. |
| `BackspaceHoldTest` | Drives both `holdBackspaceUntilEmpty` and `holdBackspaceForDuration` against a fake field that decrements per event. Asserts: termination on empty, hard cap on stuck field, abort-guard fires when loginIndex changes mid-wipe. |
| `CredentialStoreListTest` | Adds entries via `write`, asserts `list` returns them, deletes one, asserts `list` reflects removal. Per-impl. For Keychain impl: asserts the sidecar JSON is updated. |
| `WelcomeScreenDetectorTest` | Mocks `InterfaceID.WELCOME_SCREEN` widget visible / hidden. Asserts detection + click target resolution. |
| `LoginContextTest` | Asserts retry-count increment, error capture, statusSink invocation order. |

### 11.2 Integration tests

`LoginAssistantTest` (existing — extended):

- Happy path: PRECHECK → … → DONE with mocked `EmbeddedClient`.
- WORLD_FULL recovery: first attempt fails → world switch → WAIT_FOR_LOGIN_SCREEN → retry succeeds.
- BAD_CREDS terminal: first attempt fails → no retry → returns false with correct error.
- Wrong-account-already-logged-in: PRECHECK detects, fails immediately.
- **Regression test for the user's bug:** "wrong username pre-filled, type-over without clear" — assert that with `loginIndex == 2` (already on form) and pre-filled `getUsername()` value `"wrong@example.com"` and target `"right@example.com"`, the FSM bypasses NUDGE_INTRO entirely (`loginIndex == 2` → straight to RESOLVE_USERNAME), enters CLEAR_USERNAME, the field reaches empty, then TYPE_USERNAME runs, and the post-condition re-read confirms the correct username was typed. The test captures the full ordered list of visited states (`List<LoginState> visited`) and asserts the exact sequence: `PRECHECK → NUDGE_INTRO → RESOLVE_USERNAME → CLEAR_USERNAME → TYPE_USERNAME → FOCUS_PASSWORD → CLEAR_PASSWORD → PASTE_PASSWORD → CLICK_LOGIN → AWAIT_LOGGED_IN → AWAIT_WELCOME → DONE`.
- **Missed-click test:** simulate the captured production bug — CLICK_LOGIN fires, GameState stays `LOGIN_SCREEN`/`loginIndex == 2`/no error widget for the full 2.5s gate. Assert: re-click fires, then AWAIT_LOGGED_IN starts, then on the next poll cycle GameState transitions to LOGGED_IN. Total time from first click to LOGGED_IN should be < 4s, not 30s.
- Interruption: midway through CLEAR_USERNAME, daemon thread interrupted → returns `INTERRUPTED`, statusSink shows "login cancelled".

### 11.3 Manual verification checklist

Once implementation lands, verify in the running client:

1. Add 3 credentials via panel; verify `JList` shows them; verify last-selected persistence across panel re-open.
2. Delete one; verify `JList` updates.
3. Log in with a saved char from clean login screen → verify enters game.
4. Log in with wrong username pre-typed by hand → verify backspace-clears + retypes correctly, no leftover characters.
5. Log in on a member-only world → verify world switch happens (B1: humanized; B2: engine setter), retry succeeds.
6. Log in with deliberately wrong password → verify BAD_CREDS terminal message, no retry.
7. Mid-login, click panel Stop → verify INTERRUPTED status, daemon thread exits cleanly.
8. Run debug widget dumper at: world list pane (in-game), login error widget visible, welcome screen visible — confirm dumps are useful.
9. Verify clipboard contents pre-login are restored after login completes.

## 12. File impact summary

**New (8):**
- `LoginRunner.java`
- `LoginState.java`
- `LoginContext.java`
- `LoginError.java`
- `LoginErrorClassifier.java`
- `WelcomeScreenDetector.java`
- `WorldSwitcher.java` (size depends on §7 decision)
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/debug/WidgetDumper.java`

**Modified (7):**
- `LoginAssistant.java` (slimmed to delegate)
- `WorldPicker.java` (PvP/danger filter + `pickF2PNonPvP`)
- `HumanizedTyping.java` (add `holdBackspaceUntilEmpty` + `holdBackspaceForDuration`)
- `CredentialStore.java` (add `list`)
- `KeychainCredentialStore.java` (implement `list` via sidecar JSON shared with last-login state)
- `EncryptedFileCredentialStore.java` (implement `list`)
- `RecorderPanel.java` (replace login UI section, add debug button, EDT-safe statusSink)

**Removed behavior:**
- Pre-login random world hop via `client.changeWorld(int)` — replaced by reactive recovery via `WorldSwitcher` on `MEMBER_WORLD` failure.
- `setCredsBtn` and `onSetCredentials()` JOptionPane flow — replaced by `Add…` button.
- Username-typed-into-JOptionPane prompt at login time — replaced by `JList` selection.

## 13. Open items deferred for later

- §7 mechanism (B1 canvas-pixel vs B2 engine-setter carve-out) — pending user investigation.
- Specific widget IDs for the welcome screen click sub-component and login error banner — to be identified post-merge of `WidgetDumper`, before completing `WelcomeScreenDetector` and `LoginErrorClassifier` calibration.
- 2FA / authenticator support — out of scope; will need its own state + brainstorm if needed.
- Multi-attempt retry policy beyond N=1 — out of scope per user requirement; revisit only if recoverable errors prove flakier than expected.
- Per-account world preferences (e.g., "always log this alt into world 326") — out of scope.
- Custom `JList` cell renderer drawing literal `JRadioButton` per row (potential refinement for §8 if standard list highlight isn't sufficient).
- Verify whether `loginIndex == 2` uniquely identifies the existing-user form, or if other login-screen panes (Create Account, Forgot Password, Authenticator, etc.) also report index 2. If not unique, NUDGE_INTRO needs an additional form-type signal. Verifiable once WidgetDumper can dump title-screen state.

---

**End of design.**
