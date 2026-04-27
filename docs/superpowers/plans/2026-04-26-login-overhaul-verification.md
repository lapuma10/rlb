# Login Overhaul — Final Verification Checklist

Companion to `2026-04-26-login-overhaul.md` (Task 25).

## Automated test results

All login-package tests pass (85 total):

| Suite | Count | Time |
|---|---:|---:|
| BackspaceHoldTest | 5 | 1.6s |
| EncryptedFileCredentialStoreTest | 10 | 5.8s |
| HumanizedTypingTest | 3 | <0.1s |
| KeychainSidecarTest | 3 | <0.1s |
| LoginAssistantTest | 4 | 1.2s |
| LoginContextTest | 4 | <0.1s |
| LoginCredentialsTest | 5 | <0.1s |
| LoginErrorClassifierTest | 14 | <0.1s |
| LoginRunnerTest | 21 | 24.8s |
| WelcomeScreenDetectorTest | 5 | <0.1s |
| WorldPickerTest | 11 | <0.1s |

Run command (Gradle, from worktree root):
```bash
./gradlew :client:test --tests "net.runelite.client.sequence.login.*"
```

## Commit log (23 commits since plan)

Plan reference: `0c7aba58c login: add implementation plan for FSM-based overhaul`

```
0b16b5544 login: regression test for wrong-username-pre-filled bug
112764a89 recorder: replace login UI with JList of saved characters + Add/Delete/Login + WidgetDumper button
bb6c626d6 recorder: add WidgetDumper for widget-id discovery
37a93d5d5 login: slim LoginAssistant to delegate to LoginRunner
8a40010b9 login: wire all 14 state handlers into LoginRunner.run()
7367ee1e9 login: add AWAIT_LOGGED_IN, AWAIT_WELCOME, DISMISS_WELCOME, DONE states
241f8620b login: add CLICK_LOGIN with 2.5s early-response gate + re-click
f6638a65a login: add FOCUS_PASSWORD, CLEAR_PASSWORD, PASTE_PASSWORD states
a73a19a80 login: add RESOLVE_USERNAME, CLEAR_USERNAME, TYPE_USERNAME states
98357bc12 login: add NUDGE_INTRO state with re-click on miss
644c32e34 login: simplify Task 12 — drop ClientQueryRunner seam, mock dispatcher directly
a75c9ba83 login: add PRECHECK and WAIT_FOR_LOGIN_SCREEN states
1ac817693 login: add LoginRunner skeleton with retry loop + LoginErrorRecovery
d81626620 login: stub WorldSwitcher pending B1/B2 decision
d5bfc2066 login: add KeychainCredentialStore.list() with sidecar JSON
1e7acfc8f login: add CredentialStore.list() and impl in EncryptedFileCredentialStore
09f053da0 login: add WorldPicker.pickF2PNonPvP with PvP/skill filter
26def76dd login: add WelcomeScreenDetector for post-login screen
25e827fc4 login: add holdBackspaceUntilEmpty and holdBackspaceForDuration
6ac93b20d login: add LoginError enum and LoginErrorClassifier
e4f46a6de login: add LoginContext class with retry/error state
684a3ad7d login: add StateResult sealed interface
d2e404ee8 login: add LoginState enum for FSM
```

## Manual verification checklist (per spec §11.3)

These require a running OSRS client and cannot be automated. Mark each as you complete it:

- [ ] Add 3 credentials via panel → verify `JList` shows them
- [ ] Close panel, reopen → verify last-selected is pre-selected
- [ ] Delete one credential → verify list updates and selection moves to next entry
- [ ] Log in with a saved character from clean login screen → verify enters game
- [ ] Log in with wrong username pre-typed by hand → verify backspace-clears + retypes correctly
- [ ] Log in with deliberately wrong password → verify BAD_CREDS terminal message, no retry
- [ ] Mid-login, click panel Stop (if exposed) → verify INTERRUPTED status, daemon thread exits cleanly
- [ ] Run `Debug: dump open widgets` button at: world list pane (in-game), login error widget visible, welcome screen visible → confirm dump file created at `<RUNELITE_DIR>/recorder/widget-dump-*.txt` and contains useful info
- [ ] Verify clipboard contents pre-login are restored after login completes

## Deferred items needing widget-dump data (from spec §13)

Send back the widget dumps so the deferred items can close out:

1. **Login error banner widget id** — for `LoginErrorClassifier` real-world calibration. Run dump while a login error message is showing.
2. **Welcome screen click sub-component** — verify `InterfaceID.WelcomeScreen.CONTENT` is the right click target. Run dump while the welcome screen is visible.
3. **§7 WorldSwitcher mechanism (B1 canvas-pixel vs B2 engine-setter)** — `WorldSwitcher` is a stub that throws `UnsupportedOperationException`. World-full / member-world recovery currently logs a warning and falls through. Pick B1 or B2 and implement before relying on world-switch recovery.

## Implementation notes

**Build system:** This is a Gradle project (the plan said Maven). Use:
- `./gradlew :client:compileJava` for compile
- `./gradlew :client:test --tests "<FQCN>"` for a specific test
- `./gradlew :client:test` for all client tests

The Gradle module is named `:client` (not `:runelite-client` as referenced in some earlier plan text).

**Mockito constraint:** Mockito 3.1.0 cannot mock `final` classes. Two changes were made to support testing:
1. `HumanizedInputDispatcher` — removed `final` modifier so it can be mocked
2. `clickCanvas(double, double)` overload added (for proportional coordinates)

**KeychainCredentialStore visibility:** `readKnownUsers` and `writeKnownUsers` were promoted from package-private to `public static` so the recorder panel can re-use the same sidecar JSON for `lastSelected`.

**LoginAssistantTest update:** Three existing tests were updated to match the FSM behavior:
1. `alreadyLoggedIn_isShortCircuit` — now verifies the FSM emits `"finished — logged in as alice"` on `Done`
2. `notOnLoginScreen_isFailure` — passes as-is (status contains "login screen")
3. `nullCredentials_isFailure` — converted to `@Test(expected = IllegalArgumentException.class)`
