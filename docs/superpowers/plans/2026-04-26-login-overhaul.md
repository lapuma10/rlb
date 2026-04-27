# Login Overhaul Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the procedural `LoginAssistant.login()` with an explicit state machine that verifies preconditions before every action, classifies errors, retries once on recoverable errors, dismisses the post-login welcome screen, and exposes a multi-account UI in the panel.

**Architecture:** A `LoginRunner` drives a 15-state FSM (`LoginState` enum) with a retry-on-recoverable wrapper. Each state explicitly re-reads client state via `dispatcher.runOnClient(...)` before acting (the "right field at right place" guarantee). Errors are classified into `LoginError` enum values tagged recoverable/terminal, with recovery actions per error class. A new panel UI replaces the JOptionPane username prompt with a `JList` of saved usernames + Add/Delete/Log-in buttons. WorldSwitcher is stubbed pending user investigation of canvas-pixel vs engine-setter approaches.

**Tech Stack:** Java 11+, JUnit 4, Mockito, RuneLite client APIs, Slf4j (Lombok `@Slf4j`), Swing.

**Spec reference:** `docs/superpowers/specs/2026-04-26-login-overhaul-design.md` (committed at 5d1803719). Read it first.

**Conventions:**
- Every Java file starts with the standard RuneLite 2026 BSD-2 license header (copy from any existing file in `runelite-client/src/main/java/net/runelite/client/sequence/login/`).
- All new files live under `runelite-client/src/main/java/net/runelite/client/sequence/login/` unless otherwise noted.
- All new tests live under `runelite-client/src/test/java/net/runelite/client/sequence/login/`.
- Test class names: `<ClassUnderTest>Test`. Test method names: snake_case describing behavior.
- Build / test command: from repo root, `./mvnw -pl runelite-client -am test -Dtest=<TestClassName>` for a single class; `./mvnw -pl runelite-client -am test` for all client tests. (Maven wrapper is `mvnw` per repo standard.)
- Commit format: `login: <imperative summary>` (matches existing `recorder:` prefix style — see `git log --oneline -10`).

---

## File Structure

**New files (8):**
| File | Responsibility |
|---|---|
| `LoginState.java` | Enum of all FSM states |
| `LoginContext.java` | Mutable context shared across transitions: target creds, retry count, last error, dispatcher refs, RNG, statusSink |
| `StateResult.java` | Sealed interface — `Continue(LoginState)`, `Done()`, `Failure(LoginError)` |
| `LoginError.java` | Enum of error classes; each value has `recoverable: boolean`, `message: String`, `applyRecovery(LoginContext)` (may throw `InterruptedException`) |
| `LoginErrorClassifier.java` | Maps red-banner text → `LoginError` via ordered substring patterns |
| `LoginRunner.java` | Drives FSM via switch over `LoginState`; owns retry loop |
| `WelcomeScreenDetector.java` | Helper: `isVisible(Client)`, `clickTarget(Client)` for `InterfaceID.WELCOME_SCREEN` |
| `WorldSwitcher.java` | Stub for now — `switchTo(int)` throws until B1/B2 picked |
| `runelite-client/src/main/java/net/runelite/client/plugins/recorder/debug/WidgetDumper.java` | Walks `client.getWidgetRoots()`, logs full widget tree |

**Modified files (7):**
| File | Change |
|---|---|
| `LoginAssistant.java` | Slimmed: constructor unchanged, `login(...)` delegates to `new LoginRunner(...).run()` |
| `WorldPicker.java` | Add `pickF2PNonPvP(int currentWorldId)` excluding PVP / DEADMAN / BOUNTY / PVP_ARENA / LMS / HIGH_RISK / TOURNAMENT / FRESH_START / SEASONAL / BETA / SKILL_TOTAL / QUEST_SPEEDRUNNING / MEMBERS |
| `HumanizedTyping.java` | Add `holdBackspaceUntilEmpty(...)` and `holdBackspaceForDuration(...)` |
| `CredentialStore.java` | Add `Set<String> list() throws CredentialStoreException` |
| `EncryptedFileCredentialStore.java` | Implement `list()` via `readMap().keySet()` |
| `KeychainCredentialStore.java` | Implement `list()` via sidecar JSON; sidecar = `<RUNELITE_DIR>/recorder/login-state.json` |
| `RecorderPanel.java` | Replace `buildLogin()` with JList-based UI; add `Debug: dump open widgets` button; statusSink wraps `SwingUtilities.invokeLater` |

---

## Task 1: LoginState enum

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/login/LoginState.java`
- Test: (no test — pure enum)

- [ ] **Step 1: Create the enum**

```java
package net.runelite.client.sequence.login;

/** All states in the login FSM. See docs/superpowers/specs/2026-04-26-login-overhaul-design.md §5.1. */
public enum LoginState
{
    PRECHECK,
    WAIT_FOR_LOGIN_SCREEN,
    NUDGE_INTRO,
    RESOLVE_USERNAME,
    CLEAR_USERNAME,
    TYPE_USERNAME,
    FOCUS_PASSWORD,
    CLEAR_PASSWORD,
    PASTE_PASSWORD,
    CLICK_LOGIN,
    AWAIT_LOGGED_IN,
    AWAIT_WELCOME,
    DISMISS_WELCOME,
    DONE,
    FAILED
}
```

- [ ] **Step 2: Verify it compiles**

```bash
./mvnw -pl runelite-client -am compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/login/LoginState.java
git commit -m "login: add LoginState enum for FSM"
```

---

## Task 2: StateResult sealed interface

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/login/StateResult.java`

- [ ] **Step 1: Create the sealed interface with three records**

```java
package net.runelite.client.sequence.login;

/** Outcome of running a single state. See spec §5.2. */
public sealed interface StateResult
    permits StateResult.Continue, StateResult.Done, StateResult.Failure
{
    /** Move to next state. */
    record Continue(LoginState next) implements StateResult {}

    /** Login fully succeeded. */
    record Done() implements StateResult {}

    /** Failed with classified error. */
    record Failure(LoginError error) implements StateResult {}
}
```

- [ ] **Step 2: Verify it compiles**

```bash
./mvnw -pl runelite-client -am compile
```

Expected: this will FAIL (LoginError not yet defined). That's expected — we'll add it in Task 4. Continue.

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/login/StateResult.java
git commit -m "login: add StateResult sealed interface"
```

---

## Task 3: LoginContext class

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/login/LoginContext.java`
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/login/LoginContextTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.runelite.client.sequence.login;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import static org.junit.Assert.*;

public class LoginContextTest
{
    @Test
    public void initialState_retryCountZero_lastErrorNull()
    {
        Consumer<String> sink = s -> {};
        LoginContext ctx = new LoginContext(null, null, null, null, new Random(0), sink, 308);
        assertEquals(0, ctx.getRetryCount());
        assertNull(ctx.getLastError());
        assertEquals(308, ctx.getCurrentWorldId());
    }

    @Test
    public void incrementRetry_increments()
    {
        LoginContext ctx = new LoginContext(null, null, null, null, new Random(0), s -> {}, 308);
        ctx.incrementRetry();
        assertEquals(1, ctx.getRetryCount());
    }

    @Test
    public void setLastError_persists()
    {
        LoginContext ctx = new LoginContext(null, null, null, null, new Random(0), s -> {}, 308);
        ctx.setLastError(LoginError.BAD_CREDS);
        assertEquals(LoginError.BAD_CREDS, ctx.getLastError());
    }

    @Test
    public void status_invokesSink()
    {
        List<String> received = new ArrayList<>();
        LoginContext ctx = new LoginContext(null, null, null, null, new Random(0), received::add, 308);
        ctx.status("hello");
        assertEquals(1, received.size());
        assertEquals("hello", received.get(0));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw -pl runelite-client -am test -Dtest=LoginContextTest
```

Expected: COMPILE FAIL ("cannot find symbol LoginContext").

- [ ] **Step 3: Implement LoginContext**

```java
package net.runelite.client.sequence.login;

import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import javax.annotation.Nullable;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Mutable state shared across LoginRunner transitions. All access is from the
 * single LoginRunner daemon thread; no synchronization needed.
 *
 * See docs/superpowers/specs/2026-04-26-login-overhaul-design.md §4.
 */
public final class LoginContext
{
    private final LoginCredentials credentials;
    private final HumanizedInputDispatcher dispatcher;
    private final Client client;
    @Nullable private final ClientThread clientThread;
    private final Random rng;
    private final Consumer<String> statusSink;

    private int currentWorldId;
    private int retryCount = 0;
    @Nullable private LoginError lastError;

    public LoginContext(LoginCredentials credentials,
                        HumanizedInputDispatcher dispatcher,
                        Client client,
                        @Nullable ClientThread clientThread,
                        Random rng,
                        Consumer<String> statusSink,
                        int currentWorldId)
    {
        this.credentials = credentials;
        this.dispatcher = dispatcher;
        this.client = client;
        this.clientThread = clientThread;
        this.rng = rng;
        this.statusSink = statusSink;
        this.currentWorldId = currentWorldId;
    }

    public LoginCredentials getCredentials() { return credentials; }
    public HumanizedInputDispatcher getDispatcher() { return dispatcher; }
    public Client getClient() { return client; }
    @Nullable public ClientThread getClientThread() { return clientThread; }
    public Random getRng() { return rng; }
    public int getCurrentWorldId() { return currentWorldId; }
    public void setCurrentWorldId(int id) { this.currentWorldId = id; }
    public int getRetryCount() { return retryCount; }
    public void incrementRetry() { this.retryCount++; }
    @Nullable public LoginError getLastError() { return lastError; }
    public void setLastError(LoginError e) { this.lastError = e; }
    public void status(String msg) { if (statusSink != null) statusSink.accept(msg); }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./mvnw -pl runelite-client -am test -Dtest=LoginContextTest
```

Expected: still FAILS to compile because `LoginError` doesn't exist yet. We'll fix in Task 4. Continue.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/login/LoginContext.java \
        runelite-client/src/test/java/net/runelite/client/sequence/login/LoginContextTest.java
git commit -m "login: add LoginContext class with retry/error state"
```

---

## Task 4: LoginError enum + LoginErrorClassifier

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/login/LoginError.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/login/LoginErrorClassifier.java`
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/login/LoginErrorClassifierTest.java`

- [ ] **Step 1: Write the failing classifier test**

```java
package net.runelite.client.sequence.login;

import org.junit.Test;
import static org.junit.Assert.*;

public class LoginErrorClassifierTest
{
    @Test
    public void classify_invalidCreds_returnsBadCreds()
    {
        assertEquals(LoginError.BAD_CREDS,
            LoginErrorClassifier.classify("Invalid username or password."));
    }

    @Test
    public void classify_caseInsensitive()
    {
        assertEquals(LoginError.BAD_CREDS,
            LoginErrorClassifier.classify("INVALID USERNAME OR PASSWORD"));
    }

    @Test
    public void classify_accountDisabled()
    {
        assertEquals(LoginError.BANNED,
            LoginErrorClassifier.classify("Your account has been disabled."));
    }

    @Test
    public void classify_tooManyIncorrect_winsOverLoginLimit()
    {
        // both contain "login" — TOO_MANY_INCORRECT_LOGINS is more specific
        assertEquals(LoginError.TOO_MANY_INCORRECT_LOGINS,
            LoginErrorClassifier.classify("Too many incorrect logins from this address."));
    }

    @Test
    public void classify_loginLimit()
    {
        assertEquals(LoginError.LOGIN_LIMIT,
            LoginErrorClassifier.classify("Login limit exceeded."));
    }

    @Test
    public void classify_worldFull()
    {
        assertEquals(LoginError.WORLD_FULL,
            LoginErrorClassifier.classify("This world is full."));
    }

    @Test
    public void classify_thisWorldNotAccepting_returnsWorldFull()
    {
        assertEquals(LoginError.WORLD_FULL,
            LoginErrorClassifier.classify("This world is not accepting new connections."));
    }

    @Test
    public void classify_membersWorld()
    {
        assertEquals(LoginError.MEMBER_WORLD,
            LoginErrorClassifier.classify("You need a members account to use this world."));
    }

    @Test
    public void classify_justLeftAnotherWorld()
    {
        assertEquals(LoginError.JUST_LEFT_OTHER_WORLD,
            LoginErrorClassifier.classify("You have only just left another world."));
    }

    @Test
    public void classify_serverOffline()
    {
        assertEquals(LoginError.SERVER_OFFLINE,
            LoginErrorClassifier.classify("Login server offline."));
    }

    @Test
    public void classify_connectionRefused()
    {
        assertEquals(LoginError.CONNECTION_TIMEOUT,
            LoginErrorClassifier.classify("Error connecting to server. Connection refused."));
    }

    @Test
    public void classify_unknownText_returnsUnknown()
    {
        LoginError result = LoginErrorClassifier.classify("Some new error nobody has seen.");
        assertEquals(LoginError.UNKNOWN_LOGIN_ERROR, result);
    }

    @Test
    public void classify_badCredsBeforeMembersArea()
    {
        // a string containing both BAD_CREDS pattern and "members area" should classify as BAD_CREDS
        assertEquals(LoginError.BAD_CREDS,
            LoginErrorClassifier.classify("Invalid username or password. Visit members area for help."));
    }

    @Test
    public void classify_emptyOrNull_returnsUnknown()
    {
        assertEquals(LoginError.UNKNOWN_LOGIN_ERROR, LoginErrorClassifier.classify(""));
        assertEquals(LoginError.UNKNOWN_LOGIN_ERROR, LoginErrorClassifier.classify(null));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw -pl runelite-client -am test -Dtest=LoginErrorClassifierTest
```

Expected: COMPILE FAIL ("cannot find symbol LoginError" / "LoginErrorClassifier").

- [ ] **Step 3: Implement LoginError enum**

```java
package net.runelite.client.sequence.login;

/**
 * Classified login failure modes. See spec §6.1.
 *
 * Each error is either RECOVERABLE (single retry) or terminal (hard stop).
 * Recovery actions live in applyRecovery() and may sleep / switch worlds.
 */
public enum LoginError
{
    BAD_CREDS              (false, "invalid credentials"),
    BANNED                 (false, "account disabled"),
    LOGIN_LIMIT            (false, "login limit exceeded — wait several minutes"),
    TOO_MANY_INCORRECT_LOGINS(false, "too many incorrect logins from this IP — wait, do not retry"),
    WORLD_FULL             (true,  "world full"),
    MEMBER_WORLD           (true,  "member-only world"),
    JUST_LEFT_OTHER_WORLD  (true,  "just-left-other-world"),
    CONNECTION_TIMEOUT     (true,  "connection lost"),
    TIMEOUT_NO_RESPONSE    (true,  "login timed out"),
    SERVER_OFFLINE         (true,  "login server offline"),
    UNKNOWN_LOGIN_ERROR    (true,  "unknown login error"),
    CLIENT_THREAD_STUCK    (true,  "client thread stuck"),
    UNEXPECTED_GAMESTATE   (false, "not on login screen"),
    WRONG_ACCOUNT_LOGGED_IN(false, "already logged in as different account — log out first"),
    FIELD_NOT_CLEARED      (false, "field state diverged from expected (input dispatch issue)"),
    WELCOME_STUCK          (false, "welcome screen click ignored after 3 attempts"),
    WORLD_SWITCH_FAILED    (false, "world switch failed; aborting"),
    INTERRUPTED            (false, "login cancelled");

    private final boolean recoverable;
    private final String message;

    LoginError(boolean recoverable, String message)
    {
        this.recoverable = recoverable;
        this.message = message;
    }

    public boolean recoverable() { return recoverable; }
    public String message() { return message; }

    /**
     * Apply the recovery action for this error. Caller invokes only if
     * recoverable() returns true. Sleep durations honor InterruptedException.
     *
     * Recovery actions live OUTSIDE the enum (in LoginErrorRecovery) to keep
     * this enum side-effect-free. The runner calls
     * LoginErrorRecovery.apply(this, ctx).
     */
}
```

- [ ] **Step 4: Implement LoginErrorClassifier**

```java
package net.runelite.client.sequence.login;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

/**
 * Maps red-banner text from the login screen to a LoginError. Patterns are
 * checked in order — first match wins. See spec §6.4.
 *
 * Order is most-specific first (e.g., "too many incorrect logins" before
 * "login limit"; "invalid creds" before "members area").
 */
public final class LoginErrorClassifier
{
    private LoginErrorClassifier() {}

    /** (substring, error) — order-sensitive; first match wins. */
    private record Pattern(String substring, LoginError error) {}

    private static final List<Pattern> PATTERNS = Arrays.asList(
        new Pattern("invalid username or password", LoginError.BAD_CREDS),
        new Pattern("account has been disabled",    LoginError.BANNED),
        new Pattern("account has been involved",    LoginError.BANNED),
        new Pattern("too many incorrect logins",    LoginError.TOO_MANY_INCORRECT_LOGINS),
        new Pattern("login limit exceeded",         LoginError.LOGIN_LIMIT),
        new Pattern("this world is not accepting",  LoginError.WORLD_FULL),
        new Pattern("world is full",                LoginError.WORLD_FULL),
        new Pattern("need a members account",       LoginError.MEMBER_WORLD),
        new Pattern("members area",                 LoginError.MEMBER_WORLD),
        new Pattern("only just left another world", LoginError.JUST_LEFT_OTHER_WORLD),
        new Pattern("login server offline",         LoginError.SERVER_OFFLINE),
        new Pattern("server is currently offline",  LoginError.SERVER_OFFLINE),
        new Pattern("error connecting to server",   LoginError.CONNECTION_TIMEOUT),
        new Pattern("connection refused",           LoginError.CONNECTION_TIMEOUT)
    );

    public static LoginError classify(@Nullable String redBannerText)
    {
        if (redBannerText == null || redBannerText.isEmpty()) return LoginError.UNKNOWN_LOGIN_ERROR;
        String lower = redBannerText.toLowerCase();
        for (Pattern p : PATTERNS)
        {
            if (lower.contains(p.substring())) return p.error();
        }
        return LoginError.UNKNOWN_LOGIN_ERROR;
    }
}
```

- [ ] **Step 5: Run tests to verify all pass**

```bash
./mvnw -pl runelite-client -am test -Dtest=LoginErrorClassifierTest,LoginContextTest
```

Expected: 14 tests in `LoginErrorClassifierTest` PASS; 4 tests in `LoginContextTest` PASS.

- [ ] **Step 6: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/login/LoginError.java \
        runelite-client/src/main/java/net/runelite/client/sequence/login/LoginErrorClassifier.java \
        runelite-client/src/test/java/net/runelite/client/sequence/login/LoginErrorClassifierTest.java
git commit -m "login: add LoginError enum and LoginErrorClassifier"
```

---

## Task 5: HumanizedTyping backspace-hold primitives

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/sequence/login/HumanizedTyping.java`
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/login/BackspaceHoldTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.runelite.client.sequence.login;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Random;
import java.util.function.Supplier;
import java.util.function.Predicate;
import static org.junit.Assert.*;

public class BackspaceHoldTest
{
    @Test
    public void holdUntilEmpty_stopsWhenFieldEmpty() throws InterruptedException
    {
        AtomicInteger remaining = new AtomicInteger(10);
        Supplier<String> read = () -> {
            int n = remaining.get();
            return n > 0 ? "x".repeat(n) : "";
        };
        AtomicInteger eventsFired = new AtomicInteger(0);
        Runnable onBackspace = () -> remaining.decrementAndGet();
        int fired = HumanizedTyping.holdBackspaceUntilEmpty(
            read, onBackspace, eventsFired::incrementAndGet, 3000, new Random(0));
        assertEquals("field should be empty", 0, remaining.get());
        assertTrue("at least 10 events fired", fired >= 10);
        assertTrue("at most some grace events fired", fired <= 15);
    }

    @Test
    public void holdUntilEmpty_capsAtMaxMs() throws InterruptedException
    {
        AtomicInteger remaining = new AtomicInteger(1000); // never drains
        Supplier<String> read = () -> "x".repeat(remaining.get());
        Runnable onBackspace = () -> {}; // doesn't drain
        long start = System.currentTimeMillis();
        HumanizedTyping.holdBackspaceUntilEmpty(read, onBackspace, () -> {}, 500, new Random(0));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("respects 500ms cap", elapsed < 800);
    }

    @Test
    public void holdForDuration_runsAtLeastBaseMs() throws InterruptedException
    {
        AtomicInteger fired = new AtomicInteger(0);
        long start = System.currentTimeMillis();
        HumanizedTyping.holdBackspaceForDuration(
            () -> fired.incrementAndGet(), null, 300, 0, new Random(0));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("ran at least 280ms (allow 20ms slop)", elapsed >= 280);
        assertTrue("fired some events", fired.get() > 3);
    }

    @Test
    public void holdForDuration_abortsWhenGuardTrue() throws InterruptedException
    {
        AtomicBoolean abort = new AtomicBoolean(false);
        Predicate<Void> guard = v -> abort.get();
        // schedule abort after 200ms
        new Thread(() -> {
            try { Thread.sleep(200); } catch (InterruptedException ie) { return; }
            abort.set(true);
        }).start();
        long start = System.currentTimeMillis();
        HumanizedTyping.holdBackspaceForDuration(() -> {}, guard, 2000, 0, new Random(0));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("aborted before full duration", elapsed < 700);
    }

    @Test(expected = InterruptedException.class)
    public void holdUntilEmpty_propagatesInterruption() throws InterruptedException
    {
        Thread.currentThread().interrupt();
        HumanizedTyping.holdBackspaceUntilEmpty(
            () -> "xxxxx", () -> {}, () -> {}, 3000, new Random(0));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw -pl runelite-client -am test -Dtest=BackspaceHoldTest
```

Expected: COMPILE FAIL.

- [ ] **Step 3: Add the methods to HumanizedTyping**

Read the current `HumanizedTyping.java` to see existing structure, then add these two methods at the end of the class (before the closing `}`):

```java
/**
 * Hold backspace until the polled field reads empty, or maxMs elapses.
 *
 * Inter-event delay 33-55ms with ±10% jitter (matches OS key-repeat).
 * Snap-stops when readField returns empty.
 *
 * @param readField     supplier that reads the current field text (must be safe to call from caller thread)
 * @param onBackspace   invoked once per simulated backspace event (typically dispatches a KEY_PRESSED+KEY_TYPED)
 * @param tickCallback  invoked once per inter-event sleep cycle (for test-counting)
 * @param maxMs         hard cap on total hold duration
 * @param rng           random source for jitter
 * @return number of backspace events fired
 * @throws InterruptedException if the calling thread is interrupted
 */
public static int holdBackspaceUntilEmpty(java.util.function.Supplier<String> readField,
                                          Runnable onBackspace,
                                          Runnable tickCallback,
                                          long maxMs,
                                          Random rng) throws InterruptedException
{
    long deadline = System.currentTimeMillis() + maxMs;
    int events = 0;
    while (System.currentTimeMillis() < deadline)
    {
        if (Thread.interrupted()) throw new InterruptedException();
        String current = readField.get();
        if (current == null || current.isEmpty()) return events;
        onBackspace.run();
        events++;
        long delay = nextBackspaceDelayMs(rng);
        Thread.sleep(delay);
        tickCallback.run();
    }
    return events;
}

/**
 * Hold backspace for a fixed-but-jittered duration. Used when we cannot
 * poll the field text (e.g., password). An optional abortGuard is checked
 * every ~200ms — if it returns true, we stop early.
 *
 * @param onBackspace  invoked once per simulated backspace event
 * @param abortGuard   optional; checked every ~200ms; null = no abort
 * @param baseMs       minimum total hold duration
 * @param varianceMs   added 0..varianceMs random ms to baseMs
 * @param rng          random source
 * @throws InterruptedException if the calling thread is interrupted
 */
public static void holdBackspaceForDuration(Runnable onBackspace,
                                            @Nullable java.util.function.Predicate<Void> abortGuard,
                                            long baseMs,
                                            int varianceMs,
                                            Random rng) throws InterruptedException
{
    long total = baseMs + (varianceMs > 0 ? rng.nextInt(varianceMs) : 0);
    long deadline = System.currentTimeMillis() + total;
    long nextGuardCheck = System.currentTimeMillis() + 200;
    while (System.currentTimeMillis() < deadline)
    {
        if (Thread.interrupted()) throw new InterruptedException();
        if (abortGuard != null && System.currentTimeMillis() >= nextGuardCheck)
        {
            if (abortGuard.test(null)) return;
            nextGuardCheck = System.currentTimeMillis() + 200;
        }
        onBackspace.run();
        Thread.sleep(nextBackspaceDelayMs(rng));
    }
}

/** 33-55ms with ±10% jitter. */
private static long nextBackspaceDelayMs(Random rng)
{
    long base = 33 + rng.nextInt(23);
    double jitter = 1.0 + (rng.nextDouble() - 0.5) * 0.20;
    return Math.max(20, (long) (base * jitter));
}
```

You will need these imports added at the top of `HumanizedTyping.java` if not already present:
```java
import javax.annotation.Nullable;
```

- [ ] **Step 4: Run tests**

```bash
./mvnw -pl runelite-client -am test -Dtest=BackspaceHoldTest,HumanizedTypingTest
```

Expected: all 5 new tests + existing HumanizedTypingTest tests PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/login/HumanizedTyping.java \
        runelite-client/src/test/java/net/runelite/client/sequence/login/BackspaceHoldTest.java
git commit -m "login: add holdBackspaceUntilEmpty and holdBackspaceForDuration"
```

---

## Task 6: WelcomeScreenDetector

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/login/WelcomeScreenDetector.java`
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/login/WelcomeScreenDetectorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.runelite.client.sequence.login;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import java.awt.Point;
import java.awt.Rectangle;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class WelcomeScreenDetectorTest
{
    @Test
    public void isVisible_returnsFalse_whenWidgetNull()
    {
        Client client = mock(Client.class);
        when(client.getWidget(anyInt())).thenReturn(null);
        assertFalse(WelcomeScreenDetector.isVisible(client));
    }

    @Test
    public void isVisible_returnsFalse_whenWidgetHidden()
    {
        Client client = mock(Client.class);
        Widget w = mock(Widget.class);
        when(w.isHidden()).thenReturn(true);
        when(client.getWidget(anyInt())).thenReturn(w);
        assertFalse(WelcomeScreenDetector.isVisible(client));
    }

    @Test
    public void isVisible_returnsTrue_whenWidgetVisible()
    {
        Client client = mock(Client.class);
        Widget w = mock(Widget.class);
        when(w.isHidden()).thenReturn(false);
        when(client.getWidget(anyInt())).thenReturn(w);
        assertTrue(WelcomeScreenDetector.isVisible(client));
    }

    @Test
    public void clickTarget_returnsCenterOfWidgetBounds()
    {
        Client client = mock(Client.class);
        Widget w = mock(Widget.class);
        when(w.isHidden()).thenReturn(false);
        when(w.getBounds()).thenReturn(new Rectangle(100, 200, 50, 30));
        when(client.getWidget(anyInt())).thenReturn(w);
        Point p = WelcomeScreenDetector.clickTarget(client);
        assertNotNull(p);
        assertEquals(125, p.x); // 100 + 50/2
        assertEquals(215, p.y); // 200 + 30/2
    }

    @Test
    public void clickTarget_returnsNull_whenNotVisible()
    {
        Client client = mock(Client.class);
        when(client.getWidget(anyInt())).thenReturn(null);
        assertNull(WelcomeScreenDetector.clickTarget(client));
    }
}
```

- [ ] **Step 2: Run test — expect compile fail**

```bash
./mvnw -pl runelite-client -am test -Dtest=WelcomeScreenDetectorTest
```

Expected: COMPILE FAIL.

- [ ] **Step 3: Implement WelcomeScreenDetector**

```java
package net.runelite.client.sequence.login;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import javax.annotation.Nullable;
import java.awt.Point;
import java.awt.Rectangle;

/**
 * Detects the post-login "Welcome screen" (the red Click-Here-to-Play screen
 * that appears after a successful login on some accounts/worlds) and provides
 * the click target.
 *
 * Uses InterfaceID.WelcomeScreen.CONTENT (0x017a_0002) which is the main
 * clickable surface. See spec §9 — confirm this is the right sub-component
 * via WidgetDumper before wiring the dismiss state in production.
 */
public final class WelcomeScreenDetector
{
    private WelcomeScreenDetector() {}

    public static boolean isVisible(Client client)
    {
        Widget w = client.getWidget(InterfaceID.WelcomeScreen.CONTENT);
        return w != null && !w.isHidden();
    }

    @Nullable
    public static Point clickTarget(Client client)
    {
        Widget w = client.getWidget(InterfaceID.WelcomeScreen.CONTENT);
        if (w == null || w.isHidden()) return null;
        Rectangle b = w.getBounds();
        if (b == null) return null;
        return new Point(b.x + b.width / 2, b.y + b.height / 2);
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./mvnw -pl runelite-client -am test -Dtest=WelcomeScreenDetectorTest
```

Expected: 5/5 PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/login/WelcomeScreenDetector.java \
        runelite-client/src/test/java/net/runelite/client/sequence/login/WelcomeScreenDetectorTest.java
git commit -m "login: add WelcomeScreenDetector for post-login screen"
```

---

## Task 7: WorldPicker PvP/danger filter

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/sequence/login/WorldPicker.java`
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/login/WorldPickerTest.java` (extend existing)

- [ ] **Step 1: Read the existing WorldPicker**

```bash
cat runelite-client/src/main/java/net/runelite/client/sequence/login/WorldPicker.java
```

Note its current structure. You'll add a new method, not rewrite.

- [ ] **Step 2: Write the failing test**

Append to existing `WorldPickerTest.java`:

```java
@Test
public void pickF2PNonPvP_excludesPvpWorlds()
{
    World pvpWorld = mockWorld(323, EnumSet.of(WorldType.PVP), 100);
    World cleanWorld = mockWorld(308, EnumSet.noneOf(WorldType.class), 100);
    World[] all = { pvpWorld, cleanWorld };
    WorldPicker picker = new WorldPicker(new Random(0));
    Integer chosen = picker.pickF2PNonPvP(all, 326);
    assertEquals(Integer.valueOf(308), chosen);
}

@Test
public void pickF2PNonPvP_excludesMembersWorlds()
{
    World members = mockWorld(330, EnumSet.of(WorldType.MEMBERS), 100);
    World cleanWorld = mockWorld(308, EnumSet.noneOf(WorldType.class), 100);
    World[] all = { members, cleanWorld };
    WorldPicker picker = new WorldPicker(new Random(0));
    Integer chosen = picker.pickF2PNonPvP(all, 326);
    assertEquals(Integer.valueOf(308), chosen);
}

@Test
public void pickF2PNonPvP_excludesCurrentWorld()
{
    World w308 = mockWorld(308, EnumSet.noneOf(WorldType.class), 100);
    World w316 = mockWorld(316, EnumSet.noneOf(WorldType.class), 100);
    World[] all = { w308, w316 };
    WorldPicker picker = new WorldPicker(new Random(0));
    Integer chosen = picker.pickF2PNonPvP(all, 308);
    assertEquals(Integer.valueOf(316), chosen);
}

@Test
public void pickF2PNonPvP_excludesOfflineWorlds()
{
    World offline = mockWorld(308, EnumSet.noneOf(WorldType.class), -1);
    World online  = mockWorld(316, EnumSet.noneOf(WorldType.class), 100);
    World[] all = { offline, online };
    WorldPicker picker = new WorldPicker(new Random(0));
    Integer chosen = picker.pickF2PNonPvP(all, 326);
    assertEquals(Integer.valueOf(316), chosen);
}

@Test
public void pickF2PNonPvP_excludesSkillTotalWorlds()
{
    World skill = mockWorld(308, EnumSet.of(WorldType.SKILL_TOTAL), 100);
    World clean = mockWorld(316, EnumSet.noneOf(WorldType.class), 100);
    World[] all = { skill, clean };
    WorldPicker picker = new WorldPicker(new Random(0));
    Integer chosen = picker.pickF2PNonPvP(all, 326);
    assertEquals(Integer.valueOf(316), chosen);
}

@Test
public void pickF2PNonPvP_returnsNull_whenNoCandidates()
{
    World[] all = { mockWorld(308, EnumSet.of(WorldType.PVP), 100) };
    WorldPicker picker = new WorldPicker(new Random(0));
    Integer chosen = picker.pickF2PNonPvP(all, 326);
    assertNull(chosen);
}

private static World mockWorld(int id, EnumSet<WorldType> types, int playerCount)
{
    World w = mock(World.class);
    when(w.getId()).thenReturn(id);
    when(w.getTypes()).thenReturn(types);
    when(w.getPlayerCount()).thenReturn(playerCount);
    return w;
}
```

Add these imports if missing:
```java
import net.runelite.api.World;
import net.runelite.api.WorldType;
import java.util.EnumSet;
import static org.mockito.Mockito.*;
```

- [ ] **Step 3: Run test — expect fail**

```bash
./mvnw -pl runelite-client -am test -Dtest=WorldPickerTest
```

Expected: COMPILE FAIL on `pickF2PNonPvP`.

- [ ] **Step 4: Add the method to WorldPicker**

Open `WorldPicker.java`. Add at the top of the class:

```java
private static final EnumSet<WorldType> EXCLUDED = EnumSet.of(
    WorldType.MEMBERS,
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
    WorldType.SKILL_TOTAL,
    WorldType.QUEST_SPEEDRUNNING
);
```

Add the method:

```java
/**
 * Pick a random F2P, non-PvP, non-skill-restricted world that is online and
 * not the current world. Returns null if no valid candidate exists.
 *
 * See spec §6.3.
 *
 * @param worlds          list from client.getWorldList()
 * @param currentWorldId  the world we want to switch FROM (excluded)
 */
@Nullable
public Integer pickF2PNonPvP(net.runelite.api.World[] worlds, int currentWorldId)
{
    java.util.List<net.runelite.api.World> candidates = new java.util.ArrayList<>();
    for (net.runelite.api.World w : worlds)
    {
        if (w.getId() == currentWorldId) continue;
        if (w.getPlayerCount() < 0) continue;
        if (!java.util.Collections.disjoint(w.getTypes(), EXCLUDED)) continue;
        candidates.add(w);
    }
    if (candidates.isEmpty()) return null;
    return candidates.get(rng.nextInt(candidates.size())).getId();
}
```

You will also need to ensure `WorldPicker` has a `Random` field accessible to this method — examine the constructor. If `rng` doesn't exist as a field, add it:

```java
private final Random rng;
public WorldPicker() { this(new Random()); }
public WorldPicker(Random rng) { this.rng = rng; }
```

Imports to add: `import net.runelite.api.WorldType;`, `import java.util.EnumSet;`, `import javax.annotation.Nullable;`.

- [ ] **Step 5: Run all WorldPicker tests**

```bash
./mvnw -pl runelite-client -am test -Dtest=WorldPickerTest
```

Expected: existing tests PASS + 6 new tests PASS.

- [ ] **Step 6: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/login/WorldPicker.java \
        runelite-client/src/test/java/net/runelite/client/sequence/login/WorldPickerTest.java
git commit -m "login: add WorldPicker.pickF2PNonPvP with PvP/skill filter"
```

---

## Task 8: CredentialStore.list() interface + EncryptedFileCredentialStore impl

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/sequence/login/CredentialStore.java`
- Modify: `runelite-client/src/main/java/net/runelite/client/sequence/login/EncryptedFileCredentialStore.java`
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/login/EncryptedFileCredentialStoreTest.java` (extend)

- [ ] **Step 1: Add the failing test to EncryptedFileCredentialStoreTest**

```java
@Test
public void list_returnsAllStoredUsernames() throws Exception
{
    Path file = tmp.newFolder().toPath().resolve("creds.json");
    EncryptedFileCredentialStore store = new EncryptedFileCredentialStore(file, () -> "passphrase".toCharArray());
    store.write("alt1@example.com", "p1");
    store.write("alt2@example.com", "p2");
    store.write("main@example.com", "p3");
    Set<String> all = store.list();
    assertEquals(3, all.size());
    assertTrue(all.contains("alt1@example.com"));
    assertTrue(all.contains("alt2@example.com"));
    assertTrue(all.contains("main@example.com"));
}

@Test
public void list_empty_returnsEmptySet() throws Exception
{
    Path file = tmp.newFolder().toPath().resolve("empty.json");
    EncryptedFileCredentialStore store = new EncryptedFileCredentialStore(file, () -> "passphrase".toCharArray());
    Set<String> all = store.list();
    assertNotNull(all);
    assertTrue(all.isEmpty());
}

@Test
public void list_reflectsDelete() throws Exception
{
    Path file = tmp.newFolder().toPath().resolve("creds.json");
    EncryptedFileCredentialStore store = new EncryptedFileCredentialStore(file, () -> "passphrase".toCharArray());
    store.write("a", "1");
    store.write("b", "2");
    store.delete("a");
    Set<String> all = store.list();
    assertEquals(1, all.size());
    assertTrue(all.contains("b"));
}
```

Add `import java.util.Set;` if not present.

- [ ] **Step 2: Run test — expect compile fail**

```bash
./mvnw -pl runelite-client -am test -Dtest=EncryptedFileCredentialStoreTest
```

Expected: COMPILE FAIL ("cannot find method list").

- [ ] **Step 3: Add list() to interface**

In `CredentialStore.java`, add:

```java
/**
 * Returns the set of all stored usernames. Implementations may return an
 * empty set if no credentials are stored. Throws if the underlying store
 * is unreachable.
 */
java.util.Set<String> list() throws CredentialStoreException;
```

- [ ] **Step 4: Implement in EncryptedFileCredentialStore**

```java
@Override
public synchronized Set<String> list() throws CredentialStoreException
{
    return new HashSet<>(readMap().keySet());
}
```

Add imports: `import java.util.HashSet;`, `import java.util.Set;`.

- [ ] **Step 5: Run tests**

```bash
./mvnw -pl runelite-client -am test -Dtest=EncryptedFileCredentialStoreTest
```

Expected: existing tests PASS + 3 new tests PASS.

- [ ] **Step 6: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/login/CredentialStore.java \
        runelite-client/src/main/java/net/runelite/client/sequence/login/EncryptedFileCredentialStore.java \
        runelite-client/src/test/java/net/runelite/client/sequence/login/EncryptedFileCredentialStoreTest.java
git commit -m "login: add CredentialStore.list() and impl in EncryptedFileCredentialStore"
```

---

## Task 9: KeychainCredentialStore.list() with sidecar JSON

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/sequence/login/KeychainCredentialStore.java`
- Test: (KeychainCredentialStore tests are not run on non-mac CI, so add a unit-style test that mocks the sidecar path)

The keychain itself does not enumerate generic-password entries by service in a portable way. We track usernames in a sidecar JSON file. Sidecar lives at `<RUNELITE_DIR>/recorder/login-state.json`. The same file is used by the panel for `lastSelected` (Task 23).

- [ ] **Step 1: Write the test**

Create `runelite-client/src/test/java/net/runelite/client/sequence/login/KeychainSidecarTest.java`:

```java
package net.runelite.client.sequence.login;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

public class KeychainSidecarTest
{
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void readKnownUsers_returnsEmpty_whenSidecarMissing() throws Exception
    {
        Path sidecar = tmp.newFolder().toPath().resolve("login-state.json");
        Set<String> users = KeychainCredentialStore.readKnownUsers(sidecar);
        assertTrue(users.isEmpty());
    }

    @Test
    public void writeAndReadKnownUsers_roundtrips() throws Exception
    {
        Path sidecar = tmp.newFolder().toPath().resolve("login-state.json");
        Set<String> in = new HashSet<>();
        in.add("a@b.com");
        in.add("c@d.com");
        KeychainCredentialStore.writeKnownUsers(sidecar, in, null);
        Set<String> out = KeychainCredentialStore.readKnownUsers(sidecar);
        assertEquals(in, out);
    }

    @Test
    public void writeKnownUsers_preservesLastSelected() throws Exception
    {
        Path sidecar = tmp.newFolder().toPath().resolve("login-state.json");
        Set<String> in = new HashSet<>();
        in.add("a");
        KeychainCredentialStore.writeKnownUsers(sidecar, in, "a");
        // Re-read raw JSON
        JsonObject obj = new Gson().fromJson(Files.readString(sidecar), JsonObject.class);
        assertEquals("a", obj.get("lastSelected").getAsString());
    }
}
```

- [ ] **Step 2: Run test — expect compile fail**

```bash
./mvnw -pl runelite-client -am test -Dtest=KeychainSidecarTest
```

Expected: COMPILE FAIL on the static helpers.

- [ ] **Step 3: Add the static helpers and `list()` to KeychainCredentialStore**

Add these static methods to `KeychainCredentialStore.java`:

```java
private static Path sidecarPath()
{
    Path dir = net.runelite.client.RuneLite.RUNELITE_DIR.toPath().resolve("recorder");
    try { Files.createDirectories(dir); }
    catch (IOException ignored) {}
    return dir.resolve("login-state.json");
}

/** Read the set of known usernames from the sidecar; empty if missing. */
static Set<String> readKnownUsers(Path sidecar) throws CredentialStoreException
{
    if (!Files.exists(sidecar)) return new HashSet<>();
    try
    {
        String json = Files.readString(sidecar);
        if (json.isBlank()) return new HashSet<>();
        com.google.gson.JsonObject obj = new com.google.gson.Gson().fromJson(json, com.google.gson.JsonObject.class);
        if (obj == null || !obj.has("knownUsers")) return new HashSet<>();
        Set<String> out = new HashSet<>();
        obj.getAsJsonArray("knownUsers").forEach(e -> out.add(e.getAsString()));
        return out;
    }
    catch (Exception ex)
    {
        throw new CredentialStoreException("sidecar read failed", ex);
    }
}

/** Write the sidecar atomically. lastSelected may be null. */
static void writeKnownUsers(Path sidecar, Set<String> users, @Nullable String lastSelected) throws CredentialStoreException
{
    try
    {
        Files.createDirectories(sidecar.getParent());
        com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
        if (lastSelected != null) obj.addProperty("lastSelected", lastSelected);
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        users.forEach(arr::add);
        obj.add("knownUsers", arr);
        Path tmp = sidecar.resolveSibling(sidecar.getFileName() + ".tmp");
        Files.writeString(tmp, new com.google.gson.Gson().toJson(obj));
        Files.move(tmp, sidecar, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
    catch (IOException ioe)
    {
        throw new CredentialStoreException("sidecar write failed", ioe);
    }
}
```

Required imports:
```java
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;
```

Override existing methods to maintain the sidecar:

```java
@Override
public synchronized void write(String username, String password) throws CredentialStoreException
{
    requireAvailable();
    // existing keychain write logic stays — keep whatever is there
    // ...
    // After successful keychain write, update sidecar:
    Set<String> users = readKnownUsers(sidecarPath());
    users.add(username);
    writeKnownUsers(sidecarPath(), users, null);
}

@Override
public synchronized void delete(String username) throws CredentialStoreException
{
    requireAvailable();
    // existing keychain delete logic stays
    // ...
    Set<String> users = readKnownUsers(sidecarPath());
    users.remove(username);
    writeKnownUsers(sidecarPath(), users, null);
}

@Override
public synchronized Set<String> list() throws CredentialStoreException
{
    return readKnownUsers(sidecarPath());
}
```

(Read the existing `KeychainCredentialStore.java` first; preserve its existing `write` / `delete` keychain-call logic when adding the sidecar update.)

- [ ] **Step 4: Run tests**

```bash
./mvnw -pl runelite-client -am test -Dtest=KeychainSidecarTest
```

Expected: 3/3 PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/login/KeychainCredentialStore.java \
        runelite-client/src/test/java/net/runelite/client/sequence/login/KeychainSidecarTest.java
git commit -m "login: add KeychainCredentialStore.list() with sidecar JSON"
```

---

## Task 10: WorldSwitcher stub

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/login/WorldSwitcher.java`

WorldSwitcher's mechanism is TBD (B1 canvas-pixel vs B2 engine-setter) per spec §7. We stub it for now so the FSM compiles; recovery from `WORLD_FULL` / `MEMBER_WORLD` will throw `UnsupportedOperationException` until the user picks an approach.

- [ ] **Step 1: Create the stub**

```java
package net.runelite.client.sequence.login;

import net.runelite.api.Client;

/**
 * Switches the OSRS world. Mechanism TBD per spec §7 — see
 * docs/superpowers/specs/2026-04-26-login-overhaul-design.md §7.
 *
 * Currently stubbed: throws UnsupportedOperationException. Recovery from
 * WORLD_FULL / MEMBER_WORLD will surface this as WORLD_SWITCH_FAILED via
 * the runner's Exception catch in §6.2.
 *
 * To complete: pick B1 (canvas-pixel humanized) or B2 (client.changeWorld
 * engine-setter carve-out) and implement switchTo() accordingly.
 */
public final class WorldSwitcher
{
    private final Client client;

    public WorldSwitcher(Client client)
    {
        this.client = client;
    }

    /**
     * Switch to the given world. Blocks until the switch is observed via
     * client.getWorld() or fails with an exception.
     */
    public void switchTo(int targetWorldId) throws InterruptedException
    {
        throw new UnsupportedOperationException(
            "WorldSwitcher mechanism TBD — see spec §7 (B1 canvas-pixel vs B2 engine-setter). "
            + "Pick one and implement before relying on world-switch recovery.");
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./mvnw -pl runelite-client -am compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/login/WorldSwitcher.java
git commit -m "login: stub WorldSwitcher pending B1/B2 decision"
```

---

## Task 11: LoginRunner skeleton (retry loop, no states)

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/login/LoginRunner.java`
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/login/LoginErrorRecovery.java`
- Test: `runelite-client/src/test/java/net/runelite/client/sequence/login/LoginRunnerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.runelite.client.sequence.login;

import net.runelite.api.Client;
import org.junit.Test;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Random;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class LoginRunnerTest
{
    @Test
    public void run_donesImmediately_whenInitialStateReturnsDone()
    {
        EnumMap<LoginState, java.util.function.Function<LoginContext, StateResult>> handlers = new EnumMap<>(LoginState.class);
        handlers.put(LoginState.PRECHECK, ctx -> new StateResult.Done());
        LoginContext ctx = mockContext();
        boolean ok = LoginRunner.runWithHandlers(handlers, LoginState.PRECHECK, ctx);
        assertTrue(ok);
    }

    @Test
    public void run_failsImmediately_onTerminalError()
    {
        EnumMap<LoginState, java.util.function.Function<LoginContext, StateResult>> handlers = new EnumMap<>(LoginState.class);
        handlers.put(LoginState.PRECHECK, ctx -> new StateResult.Failure(LoginError.BAD_CREDS));
        LoginContext ctx = mockContext();
        boolean ok = LoginRunner.runWithHandlers(handlers, LoginState.PRECHECK, ctx);
        assertFalse(ok);
        assertEquals(0, ctx.getRetryCount());
    }

    @Test
    public void run_retriesOnce_onRecoverableError()
    {
        EnumMap<LoginState, java.util.function.Function<LoginContext, StateResult>> handlers = new EnumMap<>(LoginState.class);
        int[] callCount = {0};
        handlers.put(LoginState.PRECHECK, ctx -> {
            callCount[0]++;
            return callCount[0] == 1
                ? new StateResult.Failure(LoginError.WORLD_FULL)
                : new StateResult.Done();
        });
        // WAIT_FOR_LOGIN_SCREEN is the post-recovery target
        handlers.put(LoginState.WAIT_FOR_LOGIN_SCREEN, ctx -> new StateResult.Continue(LoginState.PRECHECK));
        LoginContext ctx = mockContext();
        boolean ok = LoginRunner.runWithHandlers(handlers, LoginState.PRECHECK, ctx);
        assertTrue(ok);
        assertEquals(1, ctx.getRetryCount());
    }

    @Test
    public void run_doesNotRetryTwice()
    {
        EnumMap<LoginState, java.util.function.Function<LoginContext, StateResult>> handlers = new EnumMap<>(LoginState.class);
        handlers.put(LoginState.PRECHECK, ctx -> new StateResult.Failure(LoginError.WORLD_FULL));
        handlers.put(LoginState.WAIT_FOR_LOGIN_SCREEN, ctx -> new StateResult.Continue(LoginState.PRECHECK));
        LoginContext ctx = mockContext();
        boolean ok = LoginRunner.runWithHandlers(handlers, LoginState.PRECHECK, ctx);
        assertFalse(ok);
        assertEquals(1, ctx.getRetryCount());
    }

    private LoginContext mockContext()
    {
        return new LoginContext(null, null, mock(Client.class), null, new Random(0), s -> {}, 308);
    }
}
```

- [ ] **Step 2: Run test — expect compile fail**

```bash
./mvnw -pl runelite-client -am test -Dtest=LoginRunnerTest
```

Expected: COMPILE FAIL.

- [ ] **Step 3: Implement `LoginErrorRecovery` (recovery-action dispatch)**

Side-effect-bearing recovery actions live here, not in the LoginError enum, so the enum stays pure.

```java
package net.runelite.client.sequence.login;

import lombok.extern.slf4j.Slf4j;

/**
 * Applies the recovery action for a recoverable LoginError. Caller invokes
 * only if error.recoverable() is true.
 *
 * Sleep durations are interruptible — InterruptedException propagates.
 *
 * See spec §6.1 (per-error recovery durations).
 */
@Slf4j
public final class LoginErrorRecovery
{
    private LoginErrorRecovery() {}

    public static void apply(LoginError error, LoginContext ctx) throws InterruptedException
    {
        switch (error)
        {
            case WORLD_FULL:
            case MEMBER_WORLD:
                doWorldSwitch(ctx);
                sleepRange(ctx, 2_000, 5_000);
                return;
            case JUST_LEFT_OTHER_WORLD:
                sleepRange(ctx, 6_000, 12_000);
                return;
            case CONNECTION_TIMEOUT:
            case TIMEOUT_NO_RESPONSE:
                sleepRange(ctx, 5_000, 30_000);
                return;
            case SERVER_OFFLINE:
                sleepRange(ctx, 30_000, 60_000);
                return;
            case UNKNOWN_LOGIN_ERROR:
                sleepRange(ctx, 8_000, 15_000);
                return;
            case CLIENT_THREAD_STUCK:
                sleepRange(ctx, 3_000, 6_000);
                return;
            default:
                throw new IllegalStateException("recovery requested for non-recoverable error: " + error);
        }
    }

    private static void doWorldSwitch(LoginContext ctx) throws InterruptedException
    {
        Integer target = new WorldPicker(ctx.getRng()).pickF2PNonPvP(
            ctx.getClient().getWorldList(), ctx.getCurrentWorldId());
        if (target == null) throw new IllegalStateException("no candidate worlds for switch");
        log.info("[login] switching world: {} -> {}", ctx.getCurrentWorldId(), target);
        new WorldSwitcher(ctx.getClient()).switchTo(target);
        ctx.setCurrentWorldId(target);
    }

    private static void sleepRange(LoginContext ctx, int minMs, int maxMs) throws InterruptedException
    {
        long ms = minMs + ctx.getRng().nextInt(maxMs - minMs);
        log.info("[login] recovery sleep {}ms", ms);
        Thread.sleep(ms);
    }
}
```

(Note: `Client.getWorldList()` in this codebase returns `World[]`. If your IDE flags it differently, adjust accordingly.)

- [ ] **Step 4: Implement LoginRunner**

```java
package net.runelite.client.sequence.login;

import lombok.extern.slf4j.Slf4j;
import java.util.EnumMap;
import java.util.function.Function;

/**
 * Drives the login FSM. See spec §6.2 for the retry loop.
 *
 * The runner is testable via runWithHandlers(...) which takes a
 * caller-supplied dispatch table — production code uses run(ctx) which
 * builds the real handler table.
 */
@Slf4j
public final class LoginRunner
{
    private LoginRunner() {}

    /** Production entrypoint — uses the real state implementations. */
    public static boolean run(LoginContext ctx)
    {
        EnumMap<LoginState, Function<LoginContext, StateResult>> handlers = new EnumMap<>(LoginState.class);
        // Handlers wired in subsequent tasks (12-17). For now: only the runner-loop logic.
        // Engineer: add handlers here as Tasks 12-17 land:
        // handlers.put(LoginState.PRECHECK, LoginStates::precheck);
        // handlers.put(LoginState.WAIT_FOR_LOGIN_SCREEN, LoginStates::waitForLoginScreen);
        // ... etc
        if (handlers.isEmpty())
        {
            log.warn("[login] no state handlers registered; FSM is incomplete");
            return false;
        }
        return runWithHandlers(handlers, LoginState.PRECHECK, ctx);
    }

    /** Test-friendly entrypoint — caller supplies the handler table. */
    public static boolean runWithHandlers(
        EnumMap<LoginState, Function<LoginContext, StateResult>> handlers,
        LoginState initial,
        LoginContext ctx)
    {
        LoginState state = initial;
        while (true)
        {
            try
            {
                Function<LoginContext, StateResult> h = handlers.get(state);
                if (h == null)
                {
                    log.error("[login] no handler for state {}", state);
                    ctx.status("internal error: no handler for " + state);
                    return false;
                }
                log.info("[login] state {} entering", state);
                StateResult result = h.apply(ctx);

                if (result instanceof StateResult.Done)
                {
                    ctx.status("finished — logged in as " + (ctx.getCredentials() != null ? ctx.getCredentials().getUsername() : "?"));
                    return true;
                }
                if (result instanceof StateResult.Continue c)
                {
                    log.info("[login] state {} → {}", state, c.next());
                    state = c.next();
                    continue;
                }
                if (result instanceof StateResult.Failure f)
                {
                    LoginError err = f.error();
                    ctx.setLastError(err);
                    log.warn("[login] state {} failed: {} ({})", state, err, err.message());

                    if (err == LoginError.INTERRUPTED)
                    {
                        ctx.status("login cancelled");
                        return false;
                    }
                    if (err.recoverable() && ctx.getRetryCount() == 0)
                    {
                        ctx.status("retrying after " + err.message());
                        try
                        {
                            LoginErrorRecovery.apply(err, ctx);
                        }
                        catch (InterruptedException ie)
                        {
                            Thread.currentThread().interrupt();
                            ctx.status("login cancelled");
                            return false;
                        }
                        catch (Exception ex)
                        {
                            log.warn("[login] recovery action failed", ex);
                            ctx.setLastError(LoginError.WORLD_SWITCH_FAILED);
                            ctx.status(LoginError.WORLD_SWITCH_FAILED.message());
                            return false;
                        }
                        ctx.incrementRetry();
                        state = LoginState.WAIT_FOR_LOGIN_SCREEN;
                        continue;
                    }
                    ctx.status(err.message());
                    return false;
                }
                throw new IllegalStateException("unknown StateResult: " + result);
            }
            catch (RuntimeException re)
            {
                log.error("[login] runner exception in state {}", state, re);
                ctx.status("internal error: " + re.getMessage());
                return false;
            }
        }
    }
}
```

- [ ] **Step 5: Run tests**

```bash
./mvnw -pl runelite-client -am test -Dtest=LoginRunnerTest
```

Expected: 4/4 PASS.

- [ ] **Step 6: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/login/LoginRunner.java \
        runelite-client/src/main/java/net/runelite/client/sequence/login/LoginErrorRecovery.java \
        runelite-client/src/test/java/net/runelite/client/sequence/login/LoginRunnerTest.java
git commit -m "login: add LoginRunner skeleton with retry loop + LoginErrorRecovery"
```

---

## Task 12: PRECHECK + WAIT_FOR_LOGIN_SCREEN states

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/sequence/login/LoginStates.java` (companion class holding all state-impl methods)
- Modify: `runelite-client/src/test/java/net/runelite/client/sequence/login/LoginRunnerTest.java` (add tests)

`LoginStates` is a single companion class with one static method per state. This keeps state logic colocated and out of `LoginRunner`'s loop.

- [ ] **Step 1: Write the failing test**

Add to `LoginRunnerTest.java`:

```java
@Test
public void precheck_loginScreen_continuesToNudgeIntro() throws Exception
{
    Client client = mock(Client.class);
    when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGIN_SCREEN);
    HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
    when(dispatcher.runOnClient(any())).thenAnswer(inv -> {
        java.util.function.Supplier<?> s = inv.getArgument(0);
        return s.get();
    });
    LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
    StateResult r = LoginStates.precheck(ctx);
    assertTrue(r instanceof StateResult.Continue);
    assertEquals(LoginState.NUDGE_INTRO, ((StateResult.Continue) r).next());
}

@Test
public void precheck_loggedInWithSameUsername_returnsDone() throws Exception
{
    Client client = mock(Client.class);
    when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
    when(client.getUsername()).thenReturn("test@example.com");
    HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
    when(dispatcher.runOnClient(any())).thenAnswer(inv -> {
        java.util.function.Supplier<?> s = inv.getArgument(0);
        return s.get();
    });
    CredentialStore store = mock(CredentialStore.class);
    LoginCredentials creds = new LoginCredentials("test@example.com", store);
    LoginContext ctx = new LoginContext(creds, dispatcher, client, null, new Random(0), s -> {}, 308);
    StateResult r = LoginStates.precheck(ctx);
    assertTrue(r instanceof StateResult.Done);
}

@Test
public void precheck_loggedInWithDifferentUsername_failsWrongAccount() throws Exception
{
    Client client = mock(Client.class);
    when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
    when(client.getUsername()).thenReturn("other@example.com");
    HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
    when(dispatcher.runOnClient(any())).thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
    CredentialStore store = mock(CredentialStore.class);
    LoginCredentials creds = new LoginCredentials("test@example.com", store);
    LoginContext ctx = new LoginContext(creds, dispatcher, client, null, new Random(0), s -> {}, 308);
    StateResult r = LoginStates.precheck(ctx);
    assertTrue(r instanceof StateResult.Failure);
    assertEquals(LoginError.WRONG_ACCOUNT_LOGGED_IN, ((StateResult.Failure) r).error());
}
```

Add imports as needed: `import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;`, etc.

- [ ] **Step 2: Run — expect compile fail**

```bash
./mvnw -pl runelite-client -am test -Dtest=LoginRunnerTest
```

- [ ] **Step 3: Create LoginStates companion with PRECHECK and WAIT_FOR_LOGIN_SCREEN**

```java
package net.runelite.client.sequence.login;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import lombok.extern.slf4j.Slf4j;

/**
 * State implementations for the login FSM. One static method per state.
 * Each method takes the LoginContext, performs precondition reads via
 * runOnClient, acts, verifies post-conditions, and returns a StateResult.
 *
 * See spec §5.1.
 */
@Slf4j
public final class LoginStates
{
    private LoginStates() {}

    static final int LOGIN_FORM_INDEX = 2;
    static final long PRECHECK_RUN_ON_CLIENT_TIMEOUT_MS = 2_000L;
    static final long WAIT_FOR_LOGIN_SCREEN_TIMEOUT_MS = 8_000L;
    static final long POLL_INNER_SLEEP_MS = 200L;

    public static StateResult precheck(LoginContext ctx)
    {
        try
        {
            GameState gs = ctx.getDispatcher().runOnClient(ctx.getClient()::getGameState);
            if (gs == null) return new StateResult.Failure(LoginError.UNEXPECTED_GAMESTATE);
            switch (gs)
            {
                case LOGGED_IN:
                {
                    String currentUser = ctx.getDispatcher().runOnClient(ctx.getClient()::getUsername);
                    String target = ctx.getCredentials() != null ? ctx.getCredentials().getUsername() : null;
                    if (target == null) return new StateResult.Failure(LoginError.UNEXPECTED_GAMESTATE);
                    if (currentUser != null && currentUser.equalsIgnoreCase(target)) return new StateResult.Done();
                    return new StateResult.Failure(LoginError.WRONG_ACCOUNT_LOGGED_IN);
                }
                case LOGGING_IN:
                case LOADING:
                    return new StateResult.Continue(LoginState.WAIT_FOR_LOGIN_SCREEN);
                case LOGIN_SCREEN:
                    return new StateResult.Continue(LoginState.NUDGE_INTRO);
                default:
                    return new StateResult.Failure(LoginError.UNEXPECTED_GAMESTATE);
            }
        }
        catch (Exception ex)
        {
            log.warn("[login] precheck runOnClient failed", ex);
            return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
        }
    }

    public static StateResult waitForLoginScreen(LoginContext ctx)
    {
        long deadline = System.currentTimeMillis() + WAIT_FOR_LOGIN_SCREEN_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            if (Thread.interrupted()) return new StateResult.Failure(LoginError.INTERRUPTED);
            try
            {
                GameState gs = ctx.getDispatcher().runOnClient(ctx.getClient()::getGameState);
                if (gs == GameState.LOGIN_SCREEN) return new StateResult.Continue(LoginState.PRECHECK);
            }
            catch (Exception ex)
            {
                return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
            }
            try { Thread.sleep(POLL_INNER_SLEEP_MS); }
            catch (InterruptedException ie) { return new StateResult.Failure(LoginError.INTERRUPTED); }
        }
        return new StateResult.Failure(LoginError.UNEXPECTED_GAMESTATE);
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./mvnw -pl runelite-client -am test -Dtest=LoginRunnerTest
```

Expected: 7/7 PASS (4 from Task 11 + 3 new).

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/login/LoginStates.java \
        runelite-client/src/test/java/net/runelite/client/sequence/login/LoginRunnerTest.java
git commit -m "login: add PRECHECK and WAIT_FOR_LOGIN_SCREEN states"
```

---

## Task 13: NUDGE_INTRO state (with re-click)

**Files:**
- Modify: `LoginStates.java`
- Modify: `LoginRunnerTest.java`

- [ ] **Step 1: Add failing tests for NUDGE_INTRO**

```java
@Test
public void nudgeIntro_returnsResolveUsername_whenAlreadyOnForm() throws Exception
{
    Client client = mock(Client.class);
    HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
    when(client.getLoginIndex()).thenReturn(2);
    when(dispatcher.runOnClient(any())).thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
    LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
    StateResult r = LoginStates.nudgeIntro(ctx);
    assertTrue(r instanceof StateResult.Continue);
    assertEquals(LoginState.RESOLVE_USERNAME, ((StateResult.Continue) r).next());
    verify(dispatcher, never()).clickCanvas(anyDouble(), anyDouble());
}

@Test
public void nudgeIntro_clicksAndAdvances_whenOnIntro() throws Exception
{
    Client client = mock(Client.class);
    HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
    int[] callIdx = {0};
    when(client.getLoginIndex()).thenAnswer(inv -> callIdx[0]++ == 0 ? 0 : 2); // intro then form
    when(dispatcher.runOnClient(any())).thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
    LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
    StateResult r = LoginStates.nudgeIntro(ctx);
    assertTrue(r instanceof StateResult.Continue);
    assertEquals(LoginState.RESOLVE_USERNAME, ((StateResult.Continue) r).next());
    verify(dispatcher, atLeastOnce()).clickCanvas(anyDouble(), anyDouble());
}

@Test
public void nudgeIntro_failsAfterTwoFailedClicks() throws Exception
{
    Client client = mock(Client.class);
    HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
    when(client.getLoginIndex()).thenReturn(0); // never advances
    when(dispatcher.runOnClient(any())).thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
    LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
    StateResult r = LoginStates.nudgeIntro(ctx);
    assertTrue(r instanceof StateResult.Failure);
    assertEquals(LoginError.UNEXPECTED_GAMESTATE, ((StateResult.Failure) r).error());
    verify(dispatcher, times(2)).clickCanvas(anyDouble(), anyDouble());
}
```

- [ ] **Step 2: Run — expect fail**

```bash
./mvnw -pl runelite-client -am test -Dtest=LoginRunnerTest
```

- [ ] **Step 3: Add nudgeIntro to LoginStates**

```java
static final long NUDGE_INTRO_TIMEOUT_MS = 3_000L;
static final double EXISTING_USER_X = 0.50;
static final double EXISTING_USER_Y = 0.62;

public static StateResult nudgeIntro(LoginContext ctx)
{
    try
    {
        Integer idx = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
        if (idx != null && idx == LOGIN_FORM_INDEX) return new StateResult.Continue(LoginState.RESOLVE_USERNAME);

        // Click "Existing User" intro card; allow up to 2 attempts
        for (int attempt = 0; attempt < 2; attempt++)
        {
            ctx.getDispatcher().clickCanvas(EXISTING_USER_X, EXISTING_USER_Y);
            if (waitForLoginIndex(ctx, LOGIN_FORM_INDEX, NUDGE_INTRO_TIMEOUT_MS))
            {
                return new StateResult.Continue(LoginState.RESOLVE_USERNAME);
            }
            log.info("[login] nudgeIntro attempt {} did not transition; will re-click", attempt + 1);
        }
        return new StateResult.Failure(LoginError.UNEXPECTED_GAMESTATE);
    }
    catch (Exception ex)
    {
        log.warn("[login] nudgeIntro failed", ex);
        return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
    }
}

private static boolean waitForLoginIndex(LoginContext ctx, int targetIdx, long timeoutMs)
{
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline)
    {
        if (Thread.interrupted()) return false;
        try
        {
            Integer idx = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
            if (idx != null && idx == targetIdx) return true;
        }
        catch (Exception ignored) {}
        try { Thread.sleep(POLL_INNER_SLEEP_MS); }
        catch (InterruptedException ie) { return false; }
    }
    return false;
}
```

`HumanizedInputDispatcher.clickCanvas(double, double)` should already accept proportional coordinates — verify by reading the existing class. If the signature is `clickCanvas(int x, int y)`, you'll need to read canvas size and multiply: `int w = client.getCanvasWidth(); int h = client.getCanvasHeight(); dispatcher.clickCanvas((int)(w*X), (int)(h*Y));`. Match the pattern used by the current `LoginAssistant.java` code.

- [ ] **Step 4: Run tests**

```bash
./mvnw -pl runelite-client -am test -Dtest=LoginRunnerTest
```

Expected: 10/10 PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/login/LoginStates.java \
        runelite-client/src/test/java/net/runelite/client/sequence/login/LoginRunnerTest.java
git commit -m "login: add NUDGE_INTRO state with re-click on miss"
```

---

## Task 14: RESOLVE_USERNAME, CLEAR_USERNAME, TYPE_USERNAME states

**Files:**
- Modify: `LoginStates.java`
- Modify: `LoginRunnerTest.java`

- [ ] **Step 1: Add failing tests**

```java
@Test
public void resolveUsername_skipsClearTypeWhenMatch() throws Exception
{
    Client client = mock(Client.class);
    when(client.getUsername()).thenReturn("Test@Example.com");
    HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
    when(dispatcher.runOnClient(any())).thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
    CredentialStore store = mock(CredentialStore.class);
    LoginCredentials creds = new LoginCredentials("test@example.com", store);
    LoginContext ctx = new LoginContext(creds, dispatcher, client, null, new Random(0), s -> {}, 308);
    StateResult r = LoginStates.resolveUsername(ctx);
    assertTrue(r instanceof StateResult.Continue);
    assertEquals(LoginState.FOCUS_PASSWORD, ((StateResult.Continue) r).next());
}

@Test
public void resolveUsername_clearsWhenMismatch() throws Exception
{
    Client client = mock(Client.class);
    when(client.getUsername()).thenReturn("wrong@example.com");
    HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
    when(dispatcher.runOnClient(any())).thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
    LoginCredentials creds = new LoginCredentials("right@example.com", mock(CredentialStore.class));
    LoginContext ctx = new LoginContext(creds, dispatcher, client, null, new Random(0), s -> {}, 308);
    StateResult r = LoginStates.resolveUsername(ctx);
    assertTrue(r instanceof StateResult.Continue);
    assertEquals(LoginState.CLEAR_USERNAME, ((StateResult.Continue) r).next());
}

@Test
public void resolveUsername_typesWhenEmpty() throws Exception
{
    Client client = mock(Client.class);
    when(client.getUsername()).thenReturn("");
    HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
    when(dispatcher.runOnClient(any())).thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
    LoginCredentials creds = new LoginCredentials("right@example.com", mock(CredentialStore.class));
    LoginContext ctx = new LoginContext(creds, dispatcher, client, null, new Random(0), s -> {}, 308);
    StateResult r = LoginStates.resolveUsername(ctx);
    assertEquals(LoginState.TYPE_USERNAME, ((StateResult.Continue) r).next());
}
```

- [ ] **Step 2: Run — expect compile fail**

- [ ] **Step 3: Add the three states to LoginStates**

```java
public static StateResult resolveUsername(LoginContext ctx)
{
    try
    {
        String current = ctx.getDispatcher().runOnClient(ctx.getClient()::getUsername);
        String target = ctx.getCredentials().getUsername();
        if (current != null && !current.isEmpty() && current.equalsIgnoreCase(target))
            return new StateResult.Continue(LoginState.FOCUS_PASSWORD);
        if (current == null || current.isEmpty())
            return new StateResult.Continue(LoginState.TYPE_USERNAME);
        return new StateResult.Continue(LoginState.CLEAR_USERNAME);
    }
    catch (Exception ex)
    {
        log.warn("[login] resolveUsername failed", ex);
        return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
    }
}

static final double USERNAME_FIELD_X = 0.50;
static final double USERNAME_FIELD_Y = 0.46;
static final long FIELD_CLEAR_HARD_CAP_MS = 3_000L;

public static StateResult clearUsername(LoginContext ctx)
{
    try
    {
        Integer idx = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
        if (idx == null || idx != LOGIN_FORM_INDEX) return new StateResult.Failure(LoginError.FIELD_NOT_CLEARED);

        // Click username field to ensure focus
        ctx.getDispatcher().clickCanvas(USERNAME_FIELD_X, USERNAME_FIELD_Y);
        Thread.sleep(120 + ctx.getRng().nextInt(220));

        HumanizedTyping.holdBackspaceUntilEmpty(
            () -> {
                try { return ctx.getDispatcher().runOnClient(ctx.getClient()::getUsername); }
                catch (Exception e) { return null; }
            },
            () -> ctx.getDispatcher().tapKey(java.awt.event.KeyEvent.VK_BACK_SPACE),
            () -> {},
            FIELD_CLEAR_HARD_CAP_MS,
            ctx.getRng()
        );

        // Verify empty
        String after = ctx.getDispatcher().runOnClient(ctx.getClient()::getUsername);
        if (after == null || after.isEmpty()) return new StateResult.Continue(LoginState.TYPE_USERNAME);
        return new StateResult.Failure(LoginError.FIELD_NOT_CLEARED);
    }
    catch (InterruptedException ie)
    {
        Thread.currentThread().interrupt();
        return new StateResult.Failure(LoginError.INTERRUPTED);
    }
    catch (Exception ex)
    {
        log.warn("[login] clearUsername failed", ex);
        return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
    }
}

public static StateResult typeUsername(LoginContext ctx)
{
    try
    {
        // Re-verify preconditions
        Integer idx = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
        String current = ctx.getDispatcher().runOnClient(ctx.getClient()::getUsername);
        if (idx == null || idx != LOGIN_FORM_INDEX) return new StateResult.Failure(LoginError.FIELD_NOT_CLEARED);
        if (current != null && !current.isEmpty()) return new StateResult.Failure(LoginError.FIELD_NOT_CLEARED);

        String target = ctx.getCredentials().getUsername();
        for (char c : target.toCharArray())
        {
            ctx.getDispatcher().typeChar(c);
            // delay handled inside dispatcher / HumanizedTyping
        }

        // Post-condition: verify typed
        String after = ctx.getDispatcher().runOnClient(ctx.getClient()::getUsername);
        if (after != null && after.equalsIgnoreCase(target)) return new StateResult.Continue(LoginState.FOCUS_PASSWORD);
        return new StateResult.Failure(LoginError.FIELD_NOT_CLEARED);
    }
    catch (Exception ex)
    {
        log.warn("[login] typeUsername failed", ex);
        return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
    }
}
```

(If `HumanizedInputDispatcher` lacks `tapKey(int)`, use the existing key-dispatch method — read the dispatcher's existing API. Same for `typeChar(char)`. Match what current `LoginAssistant.java` calls.)

- [ ] **Step 4: Run tests**

```bash
./mvnw -pl runelite-client -am test -Dtest=LoginRunnerTest
```

Expected: 13/13 PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/login/LoginStates.java \
        runelite-client/src/test/java/net/runelite/client/sequence/login/LoginRunnerTest.java
git commit -m "login: add RESOLVE_USERNAME, CLEAR_USERNAME, TYPE_USERNAME states"
```

---

## Task 15: FOCUS_PASSWORD, CLEAR_PASSWORD, PASTE_PASSWORD states

**Files:**
- Modify: `LoginStates.java`
- Modify: `LoginRunnerTest.java`

- [ ] **Step 1: Add failing tests**

```java
@Test
public void focusPassword_advances() throws Exception
{
    Client client = mock(Client.class);
    HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
    when(client.getLoginIndex()).thenReturn(2);
    when(dispatcher.runOnClient(any())).thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
    LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
    StateResult r = LoginStates.focusPassword(ctx);
    assertTrue(r instanceof StateResult.Continue);
    assertEquals(LoginState.CLEAR_PASSWORD, ((StateResult.Continue) r).next());
}

@Test
public void clearPassword_advances() throws Exception
{
    Client client = mock(Client.class);
    HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
    when(client.getLoginIndex()).thenReturn(2);
    when(dispatcher.runOnClient(any())).thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
    LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
    long start = System.currentTimeMillis();
    StateResult r = LoginStates.clearPassword(ctx);
    long elapsed = System.currentTimeMillis() - start;
    assertTrue(r instanceof StateResult.Continue);
    assertEquals(LoginState.PASTE_PASSWORD, ((StateResult.Continue) r).next());
    assertTrue("ran at least 3000ms (the base duration)", elapsed >= 3_000);
}

@Test
public void clearPassword_abortsWhenLoginIndexChanges() throws Exception
{
    Client client = mock(Client.class);
    HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
    int[] callCount = {0};
    when(client.getLoginIndex()).thenAnswer(inv -> callCount[0]++ < 2 ? 2 : 0); // changes mid-wipe
    when(dispatcher.runOnClient(any())).thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
    LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
    StateResult r = LoginStates.clearPassword(ctx);
    assertTrue(r instanceof StateResult.Failure);
    assertEquals(LoginError.FIELD_NOT_CLEARED, ((StateResult.Failure) r).error());
}
```

- [ ] **Step 2: Run — expect fail**

- [ ] **Step 3: Add the three states**

```java
static final double PASSWORD_FIELD_X = 0.50;
static final double PASSWORD_FIELD_Y = 0.54;
static final double TAB_TO_PASSWORD_PROBABILITY = 0.7;
static final long PASSWORD_CLEAR_BASE_MS = 3_500L;
static final int  PASSWORD_CLEAR_VARIANCE_MS = 800;

public static StateResult focusPassword(LoginContext ctx)
{
    try
    {
        Integer idx = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
        if (idx == null || idx != LOGIN_FORM_INDEX) return new StateResult.Failure(LoginError.FIELD_NOT_CLEARED);

        if (ctx.getRng().nextDouble() < TAB_TO_PASSWORD_PROBABILITY)
        {
            ctx.getDispatcher().tapKey(java.awt.event.KeyEvent.VK_TAB);
        }
        else
        {
            ctx.getDispatcher().clickCanvas(PASSWORD_FIELD_X, PASSWORD_FIELD_Y);
        }
        Thread.sleep(120 + ctx.getRng().nextInt(220));
        return new StateResult.Continue(LoginState.CLEAR_PASSWORD);
    }
    catch (InterruptedException ie)
    {
        Thread.currentThread().interrupt();
        return new StateResult.Failure(LoginError.INTERRUPTED);
    }
    catch (Exception ex)
    {
        log.warn("[login] focusPassword failed", ex);
        return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
    }
}

public static StateResult clearPassword(LoginContext ctx)
{
    try
    {
        Integer idx0 = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
        if (idx0 == null || idx0 != LOGIN_FORM_INDEX) return new StateResult.Failure(LoginError.FIELD_NOT_CLEARED);

        boolean[] aborted = {false};
        java.util.function.Predicate<Void> abortGuard = v -> {
            try
            {
                Integer i = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
                if (i == null || i != LOGIN_FORM_INDEX) { aborted[0] = true; return true; }
                return false;
            }
            catch (Exception e) { aborted[0] = true; return true; }
        };

        HumanizedTyping.holdBackspaceForDuration(
            () -> ctx.getDispatcher().tapKey(java.awt.event.KeyEvent.VK_BACK_SPACE),
            abortGuard,
            PASSWORD_CLEAR_BASE_MS,
            PASSWORD_CLEAR_VARIANCE_MS,
            ctx.getRng()
        );

        if (aborted[0]) return new StateResult.Failure(LoginError.FIELD_NOT_CLEARED);
        return new StateResult.Continue(LoginState.PASTE_PASSWORD);
    }
    catch (InterruptedException ie)
    {
        Thread.currentThread().interrupt();
        return new StateResult.Failure(LoginError.INTERRUPTED);
    }
    catch (Exception ex)
    {
        log.warn("[login] clearPassword failed", ex);
        return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
    }
}

public static StateResult pastePassword(LoginContext ctx)
{
    java.awt.datatransfer.Clipboard cb = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
    java.awt.datatransfer.Transferable previous = null;
    try
    {
        Integer idx = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
        if (idx == null || idx != LOGIN_FORM_INDEX) return new StateResult.Failure(LoginError.FIELD_NOT_CLEARED);

        // Save existing clipboard
        try { previous = cb.getContents(null); } catch (Exception ignored) {}

        String pw = ctx.getCredentials().getPassword();
        cb.setContents(new java.awt.datatransfer.StringSelection(pw), null);

        int modifierMask = isMac()
            ? java.awt.event.KeyEvent.META_DOWN_MASK
            : java.awt.event.KeyEvent.CTRL_DOWN_MASK;
        int modifierKey = isMac() ? java.awt.event.KeyEvent.VK_META : java.awt.event.KeyEvent.VK_CONTROL;
        ctx.getDispatcher().tapKeyWithModifier(modifierKey, modifierMask, java.awt.event.KeyEvent.VK_V);

        Thread.sleep(80 + ctx.getRng().nextInt(120));
        return new StateResult.Continue(LoginState.CLICK_LOGIN);
    }
    catch (InterruptedException ie)
    {
        Thread.currentThread().interrupt();
        return new StateResult.Failure(LoginError.INTERRUPTED);
    }
    catch (Exception ex)
    {
        log.warn("[login] pastePassword failed", ex);
        return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
    }
    finally
    {
        try
        {
            if (previous != null)
            {
                cb.setContents(previous, null);
            }
            else
            {
                cb.setContents(new java.awt.datatransfer.StringSelection(" "), null);
            }
        }
        catch (Exception ignored) {}
    }
}

private static boolean isMac()
{
    return System.getProperty("os.name", "").toLowerCase().contains("mac");
}
```

(Verify `tapKey`/`tapKeyWithModifier` signatures against the existing `HumanizedInputDispatcher` — adjust call sites if names differ.)

- [ ] **Step 4: Run tests**

```bash
./mvnw -pl runelite-client -am test -Dtest=LoginRunnerTest
```

Expected: 16/16 PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/login/LoginStates.java \
        runelite-client/src/test/java/net/runelite/client/sequence/login/LoginRunnerTest.java
git commit -m "login: add FOCUS_PASSWORD, CLEAR_PASSWORD, PASTE_PASSWORD states"
```

---

## Task 16: CLICK_LOGIN state with early-response gate

**Files:**
- Modify: `LoginStates.java`
- Modify: `LoginRunnerTest.java`

- [ ] **Step 1: Add failing tests**

```java
@Test
public void clickLogin_advancesImmediately_whenStateChanges() throws Exception
{
    Client client = mock(Client.class);
    HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
    when(client.getLoginIndex()).thenReturn(2);
    int[] gsCalls = {0};
    when(client.getGameState()).thenAnswer(inv ->
        gsCalls[0]++ == 0 ? net.runelite.api.GameState.LOGIN_SCREEN : net.runelite.api.GameState.LOGGING_IN);
    when(dispatcher.runOnClient(any())).thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
    LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
    long start = System.currentTimeMillis();
    StateResult r = LoginStates.clickLogin(ctx);
    long elapsed = System.currentTimeMillis() - start;
    assertTrue(r instanceof StateResult.Continue);
    assertEquals(LoginState.AWAIT_LOGGED_IN, ((StateResult.Continue) r).next());
    assertTrue("returned within early-gate window", elapsed < 1_500); // not 2.5s waste
    verify(dispatcher, times(1)).clickCanvas(anyDouble(), anyDouble());
}

@Test
public void clickLogin_reclicksOnMissedClick() throws Exception
{
    Client client = mock(Client.class);
    HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
    when(client.getLoginIndex()).thenReturn(2);
    when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGIN_SCREEN); // never changes
    when(dispatcher.runOnClient(any())).thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
    LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
    StateResult r = LoginStates.clickLogin(ctx);
    assertTrue(r instanceof StateResult.Continue); // unconditionally → AWAIT_LOGGED_IN
    verify(dispatcher, times(2)).clickCanvas(anyDouble(), anyDouble()); // re-click fires
}
```

- [ ] **Step 2: Run — expect fail**

- [ ] **Step 3: Add CLICK_LOGIN to LoginStates**

```java
static final double LOGIN_BUTTON_X = 0.41;
static final double LOGIN_BUTTON_Y = 0.62;
static final long CLICK_LOGIN_EARLY_GATE_MS = 2_500L;

public static StateResult clickLogin(LoginContext ctx)
{
    try
    {
        Integer idx = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
        if (idx == null || idx != LOGIN_FORM_INDEX) return new StateResult.Failure(LoginError.FIELD_NOT_CLEARED);

        Thread.sleep(200 + ctx.getRng().nextInt(500));
        ctx.getDispatcher().clickCanvas(LOGIN_BUTTON_X, LOGIN_BUTTON_Y);

        if (waitForClickLanded(ctx)) return new StateResult.Continue(LoginState.AWAIT_LOGGED_IN);

        log.info("[login] CLICK_LOGIN gate timed out — re-clicking once");
        ctx.getDispatcher().clickCanvas(LOGIN_BUTTON_X, LOGIN_BUTTON_Y);
        return new StateResult.Continue(LoginState.AWAIT_LOGGED_IN);
    }
    catch (InterruptedException ie)
    {
        Thread.currentThread().interrupt();
        return new StateResult.Failure(LoginError.INTERRUPTED);
    }
    catch (Exception ex)
    {
        log.warn("[login] clickLogin failed", ex);
        return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
    }
}

/** Returns true if the click registered (gameState change, loginIndex change, or error widget). */
private static boolean waitForClickLanded(LoginContext ctx)
{
    long deadline = System.currentTimeMillis() + CLICK_LOGIN_EARLY_GATE_MS;
    while (System.currentTimeMillis() < deadline)
    {
        if (Thread.interrupted()) return false;
        try
        {
            net.runelite.api.GameState gs = ctx.getDispatcher().runOnClient(ctx.getClient()::getGameState);
            Integer idx = ctx.getDispatcher().runOnClient(ctx.getClient()::getLoginIndex);
            if (gs != null && gs != net.runelite.api.GameState.LOGIN_SCREEN) return true;
            if (idx != null && idx != LOGIN_FORM_INDEX) return true;
            // TODO: check for error banner widget once WidgetDumper has identified its id (spec §13)
        }
        catch (Exception ignored) {}
        try { Thread.sleep(POLL_INNER_SLEEP_MS); }
        catch (InterruptedException ie) { return false; }
    }
    return false;
}
```

- [ ] **Step 4: Run tests**

```bash
./mvnw -pl runelite-client -am test -Dtest=LoginRunnerTest
```

Expected: 18/18 PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/login/LoginStates.java \
        runelite-client/src/test/java/net/runelite/client/sequence/login/LoginRunnerTest.java
git commit -m "login: add CLICK_LOGIN with 2.5s early-response gate + re-click"
```

---

## Task 17: AWAIT_LOGGED_IN, AWAIT_WELCOME, DISMISS_WELCOME, DONE states

**Files:**
- Modify: `LoginStates.java`
- Modify: `LoginRunnerTest.java`

- [ ] **Step 1: Add failing tests**

```java
@Test
public void awaitLoggedIn_returnsAwaitWelcome_onLoggedIn() throws Exception
{
    Client client = mock(Client.class);
    HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
    when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
    when(dispatcher.runOnClient(any())).thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
    LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
    StateResult r = LoginStates.awaitLoggedIn(ctx);
    assertTrue(r instanceof StateResult.Continue);
    assertEquals(LoginState.AWAIT_WELCOME, ((StateResult.Continue) r).next());
}

@Test
public void awaitLoggedIn_failsConnectionTimeout_onConnectionLost() throws Exception
{
    Client client = mock(Client.class);
    HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
    when(client.getGameState()).thenReturn(net.runelite.api.GameState.CONNECTION_LOST);
    when(dispatcher.runOnClient(any())).thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
    LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
    StateResult r = LoginStates.awaitLoggedIn(ctx);
    assertTrue(r instanceof StateResult.Failure);
    assertEquals(LoginError.CONNECTION_TIMEOUT, ((StateResult.Failure) r).error());
}

@Test
public void awaitWelcome_returnsDone_whenScreenNotPresent() throws Exception
{
    Client client = mock(Client.class);
    HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
    when(client.getWidget(anyInt())).thenReturn(null);
    when(client.getGameState()).thenReturn(net.runelite.api.GameState.LOGGED_IN);
    when(dispatcher.runOnClient(any())).thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
    LoginContext ctx = new LoginContext(null, dispatcher, client, null, new Random(0), s -> {}, 308);
    long start = System.currentTimeMillis();
    StateResult r = LoginStates.awaitWelcome(ctx);
    long elapsed = System.currentTimeMillis() - start;
    assertTrue(r instanceof StateResult.Done || r instanceof StateResult.Continue);
    if (r instanceof StateResult.Continue) assertEquals(LoginState.DONE, ((StateResult.Continue) r).next());
    assertTrue("waited at least the welcome timeout", elapsed >= 4_500); // 5s spec, allow slop
}
```

- [ ] **Step 2: Run — expect compile fail**

- [ ] **Step 3: Add the four states**

```java
static final long AWAIT_LOGGED_IN_TIMEOUT_MS = 30_000L;
static final long AWAIT_WELCOME_TIMEOUT_MS = 5_000L;
static final long WELCOME_DISMISS_POLL_MS = 3_000L;
static final long WELCOME_CLICK_DELAY_MIN_MS = 4_000L;
static final long WELCOME_CLICK_DELAY_MAX_MS = 45_000L;

public static StateResult awaitLoggedIn(LoginContext ctx)
{
    long deadline = System.currentTimeMillis() + AWAIT_LOGGED_IN_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline)
    {
        if (Thread.interrupted()) return new StateResult.Failure(LoginError.INTERRUPTED);
        try
        {
            net.runelite.api.GameState gs = ctx.getDispatcher().runOnClient(ctx.getClient()::getGameState);
            if (gs == net.runelite.api.GameState.LOGGED_IN) return new StateResult.Continue(LoginState.AWAIT_WELCOME);
            if (gs == net.runelite.api.GameState.CONNECTION_LOST) return new StateResult.Failure(LoginError.CONNECTION_TIMEOUT);

            // Poll for error banner — TBD: actual widget id from WidgetDumper run.
            // For now, no-op until widget id known. See spec §13.
        }
        catch (Exception ex)
        {
            return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
        }
        try { Thread.sleep(POLL_INNER_SLEEP_MS); }
        catch (InterruptedException ie) { return new StateResult.Failure(LoginError.INTERRUPTED); }
    }
    return new StateResult.Failure(LoginError.TIMEOUT_NO_RESPONSE);
}

public static StateResult awaitWelcome(LoginContext ctx)
{
    long deadline = System.currentTimeMillis() + AWAIT_WELCOME_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline)
    {
        if (Thread.interrupted()) return new StateResult.Failure(LoginError.INTERRUPTED);
        try
        {
            boolean visible = ctx.getDispatcher().runOnClient(() -> WelcomeScreenDetector.isVisible(ctx.getClient()));
            if (visible) return new StateResult.Continue(LoginState.DISMISS_WELCOME);
        }
        catch (Exception ex)
        {
            return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
        }
        try { Thread.sleep(POLL_INNER_SLEEP_MS); }
        catch (InterruptedException ie) { return new StateResult.Failure(LoginError.INTERRUPTED); }
    }
    // Welcome screen never appeared — that's OK on some accounts/worlds
    return new StateResult.Continue(LoginState.DONE);
}

public static StateResult dismissWelcome(LoginContext ctx)
{
    try
    {
        long delay = WELCOME_CLICK_DELAY_MIN_MS + (long)(ctx.getRng().nextDouble() * (WELCOME_CLICK_DELAY_MAX_MS - WELCOME_CLICK_DELAY_MIN_MS));
        log.info("[login] welcome screen visible — waiting {}ms before click", delay);
        Thread.sleep(delay);

        for (int attempt = 0; attempt < 3; attempt++)
        {
            java.awt.Point target = ctx.getDispatcher().runOnClient(() -> WelcomeScreenDetector.clickTarget(ctx.getClient()));
            if (target == null) return new StateResult.Continue(LoginState.DONE); // already gone
            ctx.getDispatcher().clickCanvasPx(target.x, target.y);

            // Poll for dismissal up to WELCOME_DISMISS_POLL_MS
            long until = System.currentTimeMillis() + WELCOME_DISMISS_POLL_MS;
            while (System.currentTimeMillis() < until)
            {
                if (Thread.interrupted()) return new StateResult.Failure(LoginError.INTERRUPTED);
                boolean stillVisible = ctx.getDispatcher().runOnClient(() -> WelcomeScreenDetector.isVisible(ctx.getClient()));
                if (!stillVisible) return new StateResult.Continue(LoginState.DONE);
                Thread.sleep(POLL_INNER_SLEEP_MS);
            }
            log.info("[login] welcome dismiss attempt {} did not register; retrying", attempt + 1);
            Thread.sleep(1000);
        }
        return new StateResult.Failure(LoginError.WELCOME_STUCK);
    }
    catch (InterruptedException ie)
    {
        Thread.currentThread().interrupt();
        return new StateResult.Failure(LoginError.INTERRUPTED);
    }
    catch (Exception ex)
    {
        log.warn("[login] dismissWelcome failed", ex);
        return new StateResult.Failure(LoginError.CLIENT_THREAD_STUCK);
    }
}

public static StateResult done(LoginContext ctx)
{
    try { ctx.getDispatcher().parkCursor(); } catch (Exception ignored) {}
    return new StateResult.Done();
}
```

`clickCanvasPx(int, int)` — if the dispatcher only has proportional clickCanvas, add a px overload OR convert pixel → proportional via canvas size (read from `client.getCanvasWidth/getCanvasHeight`).

- [ ] **Step 4: Run tests**

```bash
./mvnw -pl runelite-client -am test -Dtest=LoginRunnerTest
```

Expected: 21/21 PASS.

- [ ] **Step 5: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/login/LoginStates.java \
        runelite-client/src/test/java/net/runelite/client/sequence/login/LoginRunnerTest.java
git commit -m "login: add AWAIT_LOGGED_IN, AWAIT_WELCOME, DISMISS_WELCOME, DONE states"
```

---

## Task 18: Wire all states into LoginRunner

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/sequence/login/LoginRunner.java`

- [ ] **Step 1: Replace the empty handlers map in `run(ctx)` with the full table**

In `LoginRunner.run(ctx)`, replace the placeholder handlers section with:

```java
EnumMap<LoginState, Function<LoginContext, StateResult>> handlers = new EnumMap<>(LoginState.class);
handlers.put(LoginState.PRECHECK,             LoginStates::precheck);
handlers.put(LoginState.WAIT_FOR_LOGIN_SCREEN,LoginStates::waitForLoginScreen);
handlers.put(LoginState.NUDGE_INTRO,          LoginStates::nudgeIntro);
handlers.put(LoginState.RESOLVE_USERNAME,     LoginStates::resolveUsername);
handlers.put(LoginState.CLEAR_USERNAME,       LoginStates::clearUsername);
handlers.put(LoginState.TYPE_USERNAME,        LoginStates::typeUsername);
handlers.put(LoginState.FOCUS_PASSWORD,       LoginStates::focusPassword);
handlers.put(LoginState.CLEAR_PASSWORD,       LoginStates::clearPassword);
handlers.put(LoginState.PASTE_PASSWORD,       LoginStates::pastePassword);
handlers.put(LoginState.CLICK_LOGIN,          LoginStates::clickLogin);
handlers.put(LoginState.AWAIT_LOGGED_IN,      LoginStates::awaitLoggedIn);
handlers.put(LoginState.AWAIT_WELCOME,        LoginStates::awaitWelcome);
handlers.put(LoginState.DISMISS_WELCOME,      LoginStates::dismissWelcome);
handlers.put(LoginState.DONE,                 LoginStates::done);
return runWithHandlers(handlers, LoginState.PRECHECK, ctx);
```

- [ ] **Step 2: Verify compilation + all existing tests still pass**

```bash
./mvnw -pl runelite-client -am test -Dtest=LoginRunnerTest
```

Expected: 21/21 PASS.

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/login/LoginRunner.java
git commit -m "login: wire all 14 state handlers into LoginRunner.run()"
```

---

## Task 19: Slim LoginAssistant to delegate to LoginRunner

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/sequence/login/LoginAssistant.java`

- [ ] **Step 1: Replace the body of `login(...)` with a delegation call**

Read the existing `login(LoginCredentials, Consumer<String>)` method in `LoginAssistant.java`. Replace its body with:

```java
public boolean login(LoginCredentials creds, Consumer<String> status) throws InterruptedException
{
    if (creds == null) throw new IllegalArgumentException("credentials required");
    Consumer<String> sink = status != null ? status : s -> {};
    int currentWorld = -1;
    try { currentWorld = client.getWorld(); } catch (Exception ignored) {}
    LoginContext ctx = new LoginContext(creds, dispatcher, client, clientThread, rng, sink, currentWorld);
    return LoginRunner.run(ctx);
}
```

Remove the procedural step methods that are no longer used (`nudgeIntroIfNeeded`, `typeUsernameIfNeeded`, `awaitLoggedIn`, etc.) — the FSM owns these now. Keep the constructor signature unchanged for backward compat with `RecorderPlugin`.

- [ ] **Step 2: Run all login package tests**

```bash
./mvnw -pl runelite-client -am test -Dtest='*Login*,*Backspace*,*Welcome*,*World*,*Credential*'
```

Expected: all PASS. The original `LoginAssistantTest` may have surface-level tests that still compile and pass (constructor + early-return paths).

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/sequence/login/LoginAssistant.java
git commit -m "login: slim LoginAssistant to delegate to LoginRunner"
```

---

## Task 20: WidgetDumper

**Files:**
- Create: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/debug/WidgetDumper.java`
- (No unit test — this is operator tooling that requires a running client)

- [ ] **Step 1: Create the dumper**

```java
package net.runelite.client.plugins.recorder.debug;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.RuneLite;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Dumps every currently-visible widget to log + a sidecar txt file.
 * Use to identify widget IDs for the welcome screen sub-components,
 * login error banner, and in-game world picker (spec §9).
 */
@Slf4j
public final class WidgetDumper
{
    private WidgetDumper() {}

    /**
     * Walk client.getWidgetRoots(), log each, and write a sidecar.
     * Caller must invoke from the client thread (use dispatcher.runOnClient).
     *
     * @return path written
     */
    public static Path dump(Client client) throws IOException
    {
        Path dir = RuneLite.RUNELITE_DIR.toPath().resolve("recorder");
        Files.createDirectories(dir);
        Path out = dir.resolve("widget-dump-" + Instant.now().getEpochSecond() + ".txt");

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out)))
        {
            pw.println("# Widget dump @ " + Instant.now());
            Widget[] roots = client.getWidgetRoots();
            if (roots == null) { pw.println("(no roots)"); log.info("widget dump: no roots"); return out; }
            for (Widget r : roots) walk(r, 0, pw);
        }
        log.info("widget dump written to {}", out);
        return out;
    }

    private static void walk(Widget w, int depth, PrintWriter pw)
    {
        if (w == null) return;
        String pad = " ".repeat(depth * 2);
        int id = w.getId();
        int group = id >>> 16;
        int child = id & 0xFFFF;
        String text = w.getText();
        int spriteId = w.getSpriteId();
        java.awt.Rectangle b = null;
        try { b = w.getBounds(); } catch (Exception ignored) {}
        pw.printf("%s0x%04x_%04x hidden=%s text=%s sprite=%d bounds=%s%n",
            pad, group, child, w.isHidden(),
            text != null && !text.isEmpty() ? "\"" + text.replace('\n', ' ') + "\"" : "-",
            spriteId, b != null ? b.toString() : "?");
        Widget[] children = w.getChildren();
        if (children != null) for (Widget c : children) walk(c, depth + 1, pw);
        Widget[] dynamic = w.getDynamicChildren();
        if (dynamic != null) for (Widget c : dynamic) walk(c, depth + 1, pw);
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./mvnw -pl runelite-client -am compile
```

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/debug/WidgetDumper.java
git commit -m "recorder: add WidgetDumper for widget-id discovery"
```

---

## Task 21: Replace RecorderPanel.buildLogin() with JList-based UI

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java`

This task replaces the existing `buildLogin()` and `onLogin()` / `onSetCredentials()` flow. Read the file first to understand the existing layout.

- [ ] **Step 1: Replace `buildLogin()` and remove `onSetCredentials()`**

Replace the `buildLogin()` method body and the `setCredsBtn` field references with:

```java
// Field (replace existing setCredsBtn / loginBtn declarations):
private final DefaultListModel<String> credModel = new DefaultListModel<>();
private final JList<String> credList = new JList<>(credModel);
private final JButton addBtn = new JButton("Add…");
private final JButton deleteBtn = new JButton("Delete");
private final JButton loginBtn = new JButton("Log in");
private final JButton dumpBtn = new JButton("Debug: dump open widgets");
private final JLabel loginStatus = new JLabel("idle");
private final java.util.concurrent.atomic.AtomicBoolean loginInFlight = new java.util.concurrent.atomic.AtomicBoolean(false);

// Method (replaces existing buildLogin):
private JComponent buildLogin()
{
    JPanel panel = new JPanel(new BorderLayout(4, 4));
    panel.setBorder(BorderFactory.createTitledBorder("Login"));

    credList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    credList.setVisibleRowCount(4);
    JScrollPane scroll = new JScrollPane(credList);

    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    buttons.add(addBtn);
    buttons.add(deleteBtn);
    buttons.add(loginBtn);

    JPanel south = new JPanel(new BorderLayout(4, 4));
    south.add(buttons, BorderLayout.NORTH);
    south.add(loginStatus, BorderLayout.CENTER);
    south.add(dumpBtn, BorderLayout.SOUTH);

    panel.add(new JLabel("Saved characters:"), BorderLayout.NORTH);
    panel.add(scroll, BorderLayout.CENTER);
    panel.add(south, BorderLayout.SOUTH);

    addBtn.addActionListener(e -> onAddCredential());
    deleteBtn.addActionListener(e -> onDeleteCredential());
    loginBtn.addActionListener(e -> onLogin());
    dumpBtn.addActionListener(e -> onDumpWidgets());

    credList.addListSelectionListener(e -> updateButtons());
    updateButtons();
    return panel;
}

private void updateButtons()
{
    boolean hasSel = credList.getSelectedIndex() >= 0;
    boolean inFlight = loginInFlight.get();
    deleteBtn.setEnabled(hasSel && !inFlight);
    loginBtn.setEnabled(hasSel && !inFlight);
    addBtn.setEnabled(!inFlight);
}

private void refreshList()
{
    SwingUtilities.invokeLater(() -> {
        try
        {
            credModel.clear();
            credentialStore.list().forEach(credModel::addElement);
            String last = readLastSelected();
            if (last != null && credModel.contains(last)) credList.setSelectedValue(last, true);
            else if (!credModel.isEmpty()) credList.setSelectedIndex(0);
        }
        catch (CredentialStoreException cse) { loginStatus.setText("list failed: " + cse.getMessage()); }
        updateButtons();
    });
}
```

(Required imports: `javax.swing.*`, `java.awt.*`, etc. Match what the existing file uses.)

- [ ] **Step 2: Add the panel-shown hook to refresh on display**

Find the panel's `addNotify()` or similar lifecycle method (or the constructor where layout is built); call `refreshList()` once after `setCredentialStore` is wired. Simplest: in the existing `setCredentialStore(CredentialStore store)` setter, after the assignment add `refreshList();`.

- [ ] **Step 3: Verify compilation**

```bash
./mvnw -pl runelite-client -am compile
```

(There will be unresolved references to `onAddCredential`, `onDeleteCredential`, `onDumpWidgets`, `readLastSelected`. We'll add them in Tasks 22-23.)

If compile FAILS on those — that's expected.

- [ ] **Step 4: Commit (compile-broken state OK; subsequent tasks complete)**

Don't commit yet — wait until Task 23 completes the panel.

---

## Task 22: Add/Delete/Login button behaviors

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java`

- [ ] **Step 1: Add the `onAddCredential` method**

```java
private void onAddCredential()
{
    JTextField userField = new JTextField(20);
    JPasswordField pwField = new JPasswordField(20);
    Object[] form = { "Username:", userField, "Password:", pwField };
    int result = JOptionPane.showConfirmDialog(this, form,
        "Add credential", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
    if (result != JOptionPane.OK_OPTION) return; // clean cancel

    String user = userField.getText() == null ? "" : userField.getText().trim();
    char[] pw = pwField.getPassword();
    if (user.isEmpty())
    {
        JOptionPane.showMessageDialog(this, "Username cannot be empty.", "Add credential", JOptionPane.WARNING_MESSAGE);
        return;
    }
    if (pw.length == 0)
    {
        int conf = JOptionPane.showConfirmDialog(this, "Save with empty password?",
            "Add credential", JOptionPane.YES_NO_OPTION);
        if (conf != JOptionPane.YES_OPTION) return;
    }
    try
    {
        if (credentialStore.list().contains(user))
        {
            int over = JOptionPane.showConfirmDialog(this,
                "Username '" + user + "' already saved. Overwrite the stored password?",
                "Overwrite credential", JOptionPane.YES_NO_OPTION);
            if (over != JOptionPane.YES_OPTION) return;
        }
        credentialStore.write(user, new String(pw));
        loginStatus.setText("saved " + user);
        refreshList();
        SwingUtilities.invokeLater(() -> credList.setSelectedValue(user, true));
        persistLastSelected(user);
    }
    catch (CredentialStoreException cse)
    {
        log.warn("credential write failed", cse);
        loginStatus.setText("save failed: " + cse.getMessage());
    }
    finally
    {
        java.util.Arrays.fill(pw, '\0'); // wipe password chars from heap
    }
}
```

- [ ] **Step 2: Add `onDeleteCredential`**

```java
private void onDeleteCredential()
{
    String sel = credList.getSelectedValue();
    if (sel == null) return;
    int conf = JOptionPane.showConfirmDialog(this,
        "Delete credentials for '" + sel + "'?",
        "Delete credential", JOptionPane.YES_NO_OPTION);
    if (conf != JOptionPane.YES_OPTION) return;
    try
    {
        int idx = credList.getSelectedIndex();
        credentialStore.delete(sel);
        loginStatus.setText("deleted " + sel);
        refreshList();
        SwingUtilities.invokeLater(() -> {
            int newSize = credModel.size();
            if (newSize == 0) { credList.clearSelection(); persistLastSelected(null); }
            else credList.setSelectedIndex(Math.min(idx, newSize - 1));
        });
    }
    catch (CredentialStoreException cse)
    {
        log.warn("credential delete failed", cse);
        loginStatus.setText("delete failed: " + cse.getMessage());
    }
}
```

- [ ] **Step 3: Update `onLogin` to use the JList selection + disable button**

Replace existing `onLogin()`:

```java
private void onLogin()
{
    if (loginAssistant == null) { setStatus("login unavailable"); return; }
    String user = credList.getSelectedValue();
    if (user == null) { setStatus("no character selected"); return; }
    if (!loginInFlight.compareAndSet(false, true)) return;
    updateButtons();
    persistLastSelected(user);

    LoginCredentials creds = new LoginCredentials(user, credentialStore);
    Thread t = new Thread(() -> {
        try
        {
            loginAssistant.login(creds, this::setStatus);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            setStatus("login cancelled");
        }
        finally
        {
            loginInFlight.set(false);
            SwingUtilities.invokeLater(this::updateButtons);
        }
    }, "login-assistant");
    t.setDaemon(true);
    t.start();
}

private void setStatus(String msg)
{
    SwingUtilities.invokeLater(() -> loginStatus.setText(msg));
}
```

- [ ] **Step 4: Add the `persistLastSelected` and `readLastSelected` helpers**

```java
private static java.nio.file.Path loginStatePath()
{
    java.nio.file.Path dir = net.runelite.client.RuneLite.RUNELITE_DIR.toPath().resolve("recorder");
    try { java.nio.file.Files.createDirectories(dir); } catch (java.io.IOException ignored) {}
    return dir.resolve("login-state.json");
}

private void persistLastSelected(@Nullable String username)
{
    try
    {
        // Re-use the same sidecar that KeychainCredentialStore writes
        java.util.Set<String> users;
        try { users = credentialStore.list(); } catch (Exception e) { users = new java.util.HashSet<>(); }
        net.runelite.client.sequence.login.KeychainCredentialStore.writeKnownUsers(loginStatePath(), users, username);
    }
    catch (Exception e) { log.warn("persist last-selected failed", e); }
}

@Nullable
private String readLastSelected()
{
    try
    {
        java.nio.file.Path p = loginStatePath();
        if (!java.nio.file.Files.exists(p)) return null;
        com.google.gson.JsonObject obj = new com.google.gson.Gson().fromJson(java.nio.file.Files.readString(p), com.google.gson.JsonObject.class);
        if (obj == null || !obj.has("lastSelected")) return null;
        return obj.get("lastSelected").getAsString();
    }
    catch (Exception e) { return null; }
}
```

(Note: `KeychainCredentialStore.writeKnownUsers` is package-private — RecorderPanel is in a different package. Either expose it as `public`, or copy the JSON write logic into a new shared helper class. Simplest: change `writeKnownUsers`/`readKnownUsers` to `public` in `KeychainCredentialStore`.)

- [ ] **Step 5: Add `onDumpWidgets`**

```java
private void onDumpWidgets()
{
    if (loginAssistant == null) { setStatus("dispatcher unavailable"); return; }
    Thread t = new Thread(() -> {
        try
        {
            // Use the dispatcher to schedule the dump on the client thread
            java.nio.file.Path out = dispatcher.runOnClient(() -> {
                try { return net.runelite.client.plugins.recorder.debug.WidgetDumper.dump(client); }
                catch (java.io.IOException ioe) { throw new RuntimeException(ioe); }
            });
            setStatus("widget dump written to " + out);
        }
        catch (Exception e)
        {
            log.warn("widget dump failed", e);
            setStatus("dump failed: " + e.getMessage());
        }
    }, "widget-dump");
    t.setDaemon(true);
    t.start();
}
```

(`dispatcher` and `client` may need to be wired from the panel constructor — verify the existing field references.)

- [ ] **Step 6: Run a compile**

```bash
./mvnw -pl runelite-client -am compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 7: Run all client tests**

```bash
./mvnw -pl runelite-client -am test
```

Expected: all PASS.

- [ ] **Step 8: Commit**

```bash
git add runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPanel.java \
        runelite-client/src/main/java/net/runelite/client/sequence/login/KeychainCredentialStore.java
git commit -m "recorder: replace login UI with JList of saved characters + Add/Delete/Login + WidgetDumper button"
```

---

## Task 23: Wire WidgetDumper button reference + final smoke build

**Files:**
- Modify: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java` (verify the panel still gets the dispatcher reference it needs for `onDumpWidgets`)

- [ ] **Step 1: Read RecorderPlugin to verify panel wiring is intact**

```bash
grep -n 'panel\.\|panel ' runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java | head -20
```

Confirm the panel receives `dispatcher` and `client` references in its constructor or via setters. If `dispatcher` was previously not exposed to the panel, add a setter `panel.setLoginDispatcher(loginDispatcher)` and a corresponding field on the panel.

- [ ] **Step 2: Run the full test suite**

```bash
./mvnw -pl runelite-client -am test
```

Expected: all PASS.

- [ ] **Step 3: Commit if any wiring tweak was needed**

```bash
git add -p runelite-client/src/main/java/net/runelite/client/plugins/recorder/RecorderPlugin.java
git commit -m "recorder: wire login dispatcher into panel for WidgetDumper"
```

(Skip if no changes were needed.)

---

## Task 24: End-to-end regression test for the captured bug

**Files:**
- Modify: `runelite-client/src/test/java/net/runelite/client/sequence/login/LoginAssistantTest.java`

- [ ] **Step 1: Add the regression test**

```java
@Test
public void regression_wrongUsernamePreFilled_clearsAndRetypes() throws Exception
{
    Client client = mock(Client.class);
    when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
    AtomicReference<String> currentUsername = new AtomicReference<>("wrong@example.com");
    when(client.getUsername()).thenAnswer(inv -> currentUsername.get());
    when(client.getLoginIndex()).thenReturn(2);
    when(client.getCanvasWidth()).thenReturn(800);
    when(client.getCanvasHeight()).thenReturn(600);
    HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
    when(dispatcher.runOnClient(any())).thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get());

    // Simulate the field draining as backspace is sent
    doAnswer(inv -> {
        int code = inv.getArgument(0);
        if (code == java.awt.event.KeyEvent.VK_BACK_SPACE)
        {
            String s = currentUsername.get();
            if (s != null && !s.isEmpty()) currentUsername.set(s.substring(0, s.length() - 1));
        }
        return null;
    }).when(dispatcher).tapKey(anyInt());

    // Simulate typing accumulating into the field
    doAnswer(inv -> {
        char c = inv.getArgument(0);
        currentUsername.set((currentUsername.get() == null ? "" : currentUsername.get()) + c);
        return null;
    }).when(dispatcher).typeChar(anyChar());

    CredentialStore store = mock(CredentialStore.class);
    when(store.read(anyString())).thenReturn("password");
    LoginCredentials creds = new LoginCredentials("right@example.com", store);

    LoginContext ctx = new LoginContext(creds, dispatcher, client, null, new Random(0), s -> {}, 308);

    // Test the FSM forward through CLEAR_USERNAME → TYPE_USERNAME
    StateResult resolveR = LoginStates.resolveUsername(ctx);
    assertEquals(LoginState.CLEAR_USERNAME, ((StateResult.Continue) resolveR).next());

    StateResult clearR = LoginStates.clearUsername(ctx);
    assertEquals("field cleared", "", currentUsername.get());
    assertEquals(LoginState.TYPE_USERNAME, ((StateResult.Continue) clearR).next());

    StateResult typeR = LoginStates.typeUsername(ctx);
    assertEquals("right@example.com", currentUsername.get());
    assertEquals(LoginState.FOCUS_PASSWORD, ((StateResult.Continue) typeR).next());
}
```

Required imports if not present:
```java
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.ArgumentMatchers.*;
```

- [ ] **Step 2: Run**

```bash
./mvnw -pl runelite-client -am test -Dtest=LoginAssistantTest
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add runelite-client/src/test/java/net/runelite/client/sequence/login/LoginAssistantTest.java
git commit -m "login: regression test for wrong-username-pre-filled bug"
```

---

## Task 25: Final smoke + manual verification checklist

This task documents the manual verification — no code changes, just running the binary and exercising the UI.

- [ ] **Step 1: Full test pass**

```bash
./mvnw -pl runelite-client -am test
```

Expected: all PASS.

- [ ] **Step 2: Build the client and launch**

```bash
./mvnw clean install -DskipTests
java -jar runelite-client/target/client-*-shaded.jar
```

- [ ] **Step 3: Manual checklist (per spec §11.3)**

- [ ] Add 3 credentials via panel; verify `JList` shows them
- [ ] Close panel, reopen — verify last-selected is pre-selected
- [ ] Delete one credential — verify list updates and selection moves to next entry
- [ ] Log in with a saved character from clean login screen — verify enters game
- [ ] Log in with wrong username pre-typed by hand — verify backspace-clears + retypes correctly
- [ ] Log in with deliberately wrong password — verify BAD_CREDS terminal message, no retry
- [ ] Mid-login, click panel Stop (if exposed) — verify INTERRUPTED status, daemon thread exits cleanly
- [ ] Run `Debug: dump open widgets` button at: world list pane (in-game), login error widget visible, welcome screen visible — confirm dump file is created at `<RUNELITE_DIR>/recorder/widget-dump-*.txt` and contains useful info
- [ ] Verify clipboard contents pre-login are restored after login completes

- [ ] **Step 4: Send the widget dumps back to the user** so they can identify:
  - The login error banner widget id (for `LoginErrorClassifier` real-world calibration)
  - The welcome screen click sub-component (verify `InterfaceID.WelcomeScreen.CONTENT` is correct)
  - Decision input for §7 WorldSwitcher B1 vs B2

- [ ] **Step 5: Commit any final docs/notes**

If new findings emerge from the dumps that should be documented:

```bash
git add docs/superpowers/specs/2026-04-26-login-overhaul-design.md  # if any spec updates from dumps
git commit -m "login: update spec with widget IDs from in-client dump"
```

---

## Deferred for follow-up (not in this plan)

These items are explicitly out of scope for this plan and tracked in spec §13:

1. **§7 WorldSwitcher mechanism (B1 canvas-pixel vs B2 engine-setter).** WorldSwitcher is currently a stub that throws. World-full / member-world recovery will fail with `WORLD_SWITCH_FAILED` until implemented. User to investigate and pick.

2. **Widget IDs from WidgetDumper run.** Login error banner widget id is currently TODO in `awaitLoggedIn` (no error-widget polling). Welcome screen click target is the parent `WelcomeScreen.CONTENT` — may need a sub-component once verified.

3. **`loginIndex == 2` form-type uniqueness verification.** NUDGE_INTRO trusts that index 2 means existing-user form. Verify with WidgetDumper that other panes (Create Account, Forgot Password) don't also use index 2.

4. **2FA / authenticator support.** Out of scope per spec.
