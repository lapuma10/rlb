# jbane777 ban — what we ran, when, for how long

**Date discovered:** 2026-05-22 (around 18:10 CEST — script Stop pressed from the panel, then 4 h of game-client inactivity, then RuneLite restarted at 22:22 with no further login attempts in the log).

**Status:** factual record only. Humanization remediation will be tracked separately; this file is just the timeline of what jbane777 actually did in the two days before the ban.

**Source data:**
- `~/.runelite/logs/client_2026-05-21.0.log`
- `~/.runelite/logs/client.log` (2026-05-22)
- `~/.runelite/recorder/sessions/jbane777/{2026-05-19,2026-05-20,2026-05-21,2026-05-22}.json`

No in-client ban dialog, mod-chat message, kick, or disconnect was logged. Ban was delivered out-of-band, viewable on the account's offence-evidence page on the official site. Full notice text recorded below under **Ban notice (verbatim)**. Detection was both auto-flag and moderator-confirmed.

---

## Ban notice (verbatim)

Captured from the official Jagex offence-evidence page on 2026-05-23, recorded verbatim. ("Game: RuneScape" is how Jagex's offence DB labels Old School bans — the offence DB lumps OSRS under the RuneScape umbrella.)

| Field | Value |
|---|---|
| Offence | **Macroing Major** |
| Type | Ban |
| Offence Date | 22-May-2026 |
| Offence Penalty Ends | **Permanent** |
| Game | RuneScape |
| Evidence Type | Jagex Moderator Comment |

> Account permanently banned for macroing in OldSchool. Our macro detection system has been monitoring your account closely and has detected that you are using illegal 3rd party software which violates the rules of the game and breaches your terms of service with Jagex. Our team have reviewed the evidence and can confirm that you were using illegal botting software. Jagex are able to accurately detect all illegal 3rd party software and any promises from their makers about being 'totally undetectable' or 'no ban guaranteed' are inaccurate. As such your account has been permanently removed from the game. Please take this time to familiarise yourself with the rules.

### What this text actually tells us

- **Macroing Major**, not Minor — top-tier macroing ban. Not the 2-week first-strike category. The mod was confident enough to permaban without an intermediate step.
- **Two-layer confirmation.** "Our macro detection system has detected" = auto-flag fired. "Our team have reviewed the evidence and can confirm" = a moderator reviewed and signed off. We can't attribute the ban to defeating only one layer.
- **No specific evidence cited.** This is the standard boilerplate template every Macroing Major receives — the "totally undetectable / no ban guaranteed" jab is aimed at script-sellers in general, not personalized to this case. There is no clue in this text about which signal tripped the detection (mouse statistics, gameplay heuristics, cross-account correlation, IP / device fingerprint, etc.).
- **"Monitoring your account closely"** *may* be literal — i.e. the account was on a watchlist for some period before action — or it may also be template. If literal, it fits the IP-triage hypothesis: cross-border IP changes (Norway home → Lithuania ~2 wk → Norway home → Norway office in the months before May) bumped the account onto a review list, the next 14 h of scripted activity confirmed the flag for the reviewing mod.
- **Permanent, no realistic appeal.** Macroing Major appeals against confirmed mod-reviewed evidence essentially never succeed. The account is gone.

### Practical consequence for remediation priority

A human reviewer doesn't replay mouse-pixel coordinates — they watch gameplay and look at event logs. So the priority order for the remediation work in the two registers should weight **mod-visible patterns** (target identity, no real breaks, broken/erratic agility behaviour) above **auto-detection-only signals** (PressTiming holds, Bezier control-point distribution, pixel-jitter envelopes). The 2026-05-22 click-pattern fingerprint register and the 2026-05-23 target-identity rotation register both remain in scope — the auto-flag layer fired too — but the target-identity register and the open agility-bug investigation are the higher-leverage items given a mod reviewed and signed off.

---

## Scripts that ran on the account

| Script class | Where in repo | What it did |
|---|---|---|
| `FletchingScript` | `recorder/scripts/` | Bow stringing / log cutting loop at the bank. |
| `UltraCompostScript` | `recorder/scripts/` | Withdraw 27 super-compost + volcanic ash from Lumbridge banker (npc 25085 / 25092), make ultracompost in-inventory, deposit, repeat. |
| `RooftopAgilityScript` | `recorder/scripts/` | Draynor rooftop course laps, with mark-of-grace pickup and HP/eat threshold. |
| `PieDishScript` | `recorder/scripts/` | Pie-dish production (small share, mostly used earlier in May). |
| `pizza` / `cooking_v3` / `ernest_quest` | (recorder session IDs only) | Background usage on May 19-20, not load-bearing for the ban. |

All scripts route clicks through the shared `HumanizedInputDispatcher`. Same humanizer parameters across scripts (cursor jitter, menu-row pick, park-cursor, throttle).

---

## Timeline — when each script ran, how long, when it stopped

Times below are local (EEST on 2026-05-21, CEST on 2026-05-22). Durations are from the recorder session JSONs (login-to-logout) and from script `→ IDLE` transitions in the log.

### 2026-05-21 (yesterday)

| Login window | Duration | Scripts in this session |
|---|---|---|
| 09:17 → 09:20 | 3 m | ultra_compost (1.3 m) |
| 09:22 → 10:06 | 44 m | fletching (8.6 m), fletching (3.1 m), fletching (5.3 m) |
| 10:07 → 10:12 | 5 m | rooftop_agility ×2 (tiny, 0 laps) |
| 10:13 → 10:30 | 17 m | rooftop_agility (0.5 m), fletching (13.6 m) |
| 10:31 → 10:38 | 7 m | rooftop_agility, fletching |
| 10:39 → 11:01 | 22 m | rooftop_agility, fletching (12.8 m) |
| **11:04 → 13:06** | **122 m** | **ultra_compost (51 m) → fletching (63.1 m) back-to-back** |
| 13:20 → 13:29 | 9 m | rooftop_agility (0.4 m) |
| 13:32 → 14:30 | 57 m | fletching (39.2 m), fletching (16.8 m) |
| 14:30 → 14:40 | 10 m | rooftop_agility ×2 |
| 14:40 → 14:42 | 1 m | rooftop_agility (0.8 m) |
| 14:50 → 14:57 | 8 m | rooftop_agility (6.7 m) |
| **15:24 → 17:39** | **135 m** | **rooftop_agility (2 m) → fletching (65 m) → rooftop_agility (9.2 m) → ultra_compost (54 m) — four scripts chained in one login** |

Last 2026-05-21 activity: `MAKING_ULTRACOMPOST → IDLE` at 17:38:36 EEST, then the client process kept running but the game session went idle (logged in at 23:05 the night before the gap, sessions counted here are the EEST clock).

### 2026-05-22 (today — day of ban discovery)

| Login window | Duration | Scripts in this session |
|---|---|---|
| 01:57 → 02:10 | 13 m | (login + idle, no script) |
| **02:12 → 03:48** | **95 m** | **ultra_compost 02:14 → 03:43 (89.2 m unbroken)** |
| **08:55 → 12:00** | **185 m** | **ultra_compost 08:57 → 10:17 (80.0 m) → fletching 10:21 → 12:00 (98.1 m). 178 m of script work in a single login.** |
| **14:41 → 16:25** | **103 m** | **rooftop_agility 0.8 m → ultra_compost 14:47 → 16:21 (93.8 m unbroken)** |
| 16:32 → 16:38 | 7 m | rooftop_agility (5.6 m) |
| 16:40 → 16:52 | 13 m | rooftop_agility (6.4 m) |
| **16:54 → 18:11** | **77 m** | **rooftop_agility (10 m + 0.6 m + 15.2 m) → ultra_compost (31.2 m). Mixed laps + crafting, stopped manually at 18:10:26.** |

Last in-game action recorded: `ultra-compost: MAKING_ULTRACOMPOST → IDLE` at **18:10:26 CEST**, triggered from `AWT-EventQueue-0` — i.e. the Stop button in `RecorderPanel`, not the script's own state machine. This is the moment the operator pulled the plug. Game client then sat idle, RuneLite restarted at 22:22 with no subsequent login.

---

## Account-state context (factors outside the script timeline)

Recorded 2026-05-23 from operator recollection — relevant to understanding *why* the account was flagged for review, distinct from the in-game script activity that the mod then observed.

- **Gold buy ~2026-05-19/20 (2-3 days before the ban).** Account received ~27m gp from another player. The receiving account (jbane777) was lightly played for its wealth at that point; sender was likely a gold-farm exit account already on Jagex's watch list. RWT-receiver flagging runs in parallel with macro-detection and is a strong account-elevation signal independently — receiving a large unexplained sum from a known farm-exit account often triages the receiver into manual review within 1-3 days. Operator initial assessment: "I doubt it" was the trigger. Honest counter-read: a 27m incoming transfer is likely a *stronger* triage signal than the IP changes alone, and the timing fits (transfer → days of botting on a watched account → mod review → ban). Probably contributory, possibly the trigger.
- **IP topology in the weeks before ban.** Account registered on Norway home IP, used from Lithuania ~2 weeks during travel, returned to Norway home IP, also seen from Norway office IP. Multi-country IP fingerprint over short window. By itself this is "person with a job who travelled" — combined with the RWT signal above, it adds to the manual-review elevation rationale.
- **Five accounts on the same machine/network.** All scripts share the same `HumanizedInputDispatcher`, unseeded `new Random()`, no per-account variance (see [`2026-05-23-target-identity-rotation.md`](2026-05-23-target-identity-rotation.md) entry #7). Even if one account looked clean in isolation, the cluster correlation across five behaviorally-identical accounts on one machine is its own signal.

These are *contributing factors* to the elevation, not the ban reason. The ban reason on the mod-comment evidence page is squarely "macroing" — but the gold buy and IP topology are the more plausible explanations for *why this account got reviewed at this time*.

## Aggregate volume (the part that matters for detection)

Totals are summed directly from the run start/end times in the recorder session JSONs (same numbers the in-client panel shows).

| Metric | 2026-05-21 | 2026-05-22 | 2-day total |
|---|---:|---:|---:|
| Ultra-compost craft cycles (bank → make → repeat) | **161** | **336** | **497** |
| Rooftop Agility laps completed | 6 | 29 | 35 |
| Distinct login sessions (recorder count) | 17 | 7 | 24 |
| Longest unbroken single-script run | 65 min (fletching) | **98 min (fletching)** | — |
| Longest unbroken multi-script chain (one login) | 135 min (4 scripts) | **185 min (2 scripts)** | — |
| Total time logged in | 8.6 h | 8.2 h | **16.8 h** |
| Total time with a script actively running | **6.8 h** | **7.2 h** | **14.0 h** |
| ↳ split by script (2026-05-21) | fletching 4.1 h · ultra_compost 2.3 h · rooftop_agility 0.4 h | | |
| ↳ split by script (2026-05-22) | | ultra_compost 4.9 h · fletching 1.6 h · rooftop_agility 0.6 h | |

Every ultra-compost cycle is the same menu sequence at the same NPC: right-click banker (25085 on May 21, 25092 on May 22) → "Bank" verb → Deposit-All `21483` → Withdraw-All `6034` → close via X button → make ultracompost on bucket-of-super-compost → repeat. Same widget bounds, same click pipeline, same humanizer jitter envelope.

---

## What this tells us so far (no remediation yet)

1. **Volume × repetition.** 497 identical bank-cycles on a single banker NPC over 48 h is the dominant signal. Any per-click humanization we have is uncorrelated noise *around* a perfectly periodic loop — and a perfectly periodic loop is what behavioural detection looks for.
2. **Multi-hour script chains inside one login.** 185 m, 135 m, 122 m, 103 m, 95 m, 77 m of continuous scripted activity per login session. Humans don't chain ultra_compost → fletching for 178 minutes without a logout.
3. **Same banker NPC, same widget bounds, same item slots.** No location / world / banker rotation. The dispatcher's pixel jitter is bounded inside the same widget rectangle every single cycle.
4. **Rooftop Agility at Draynor.** A historically high-scrutiny skilling activity. We ran 35 perfect-ish laps over two days, including a 15-lap continuous run (17:06 → 17:33 today) with timeout-backstop recoveries indistinguishable from successful laps.
5. **Stop button, not a crash.** The session did not end on a disconnect, a script error, or a stack trace. The operator stopped it — meaning the ban was noticed externally (email / official-client login screen / world list / friends-list strikethrough), not detected by anything in our pipeline.

---

## External reference reading

- `2026-05-22-osbot-thread-archive.md` — full 151-post extraction of the 2019 OSBot forum thread on mouse-movement detection (`https://osbot.org/forum/topic/153397-osbots-mouse-movement-is-easily-detected/`). Saved as a reference; not endorsed.
- `2026-05-22-osbot-thread-suggestions.md` — the anti-detection suggestions made by participants in that thread, attributed and quoted. Reading material only; no mapping to our code, no remediation plan.

## Click-pattern audit

Detailed per-pattern register of fingerprintable behaviours surfaced from auditing `PixelResolver`, `HumanizedInputDispatcher`, `PressTiming`, `WindMouse`, `BankInteraction`, `UltraCompostScript`, `FletchingScript`, and `RooftopAgilityScript`: see [`2026-05-22-click-pattern-fingerprints.md`](2026-05-22-click-pattern-fingerprints.md). 20 patterns logged, each with file:line citation and a `Fixed:` field (empty until verified remediation lands).

The headline finding from that audit: three different files (`BankInteraction`, `UltraCompostScript`, `FletchingScript`) all click `bounds.x + width/2, bounds.y + height/2` — exact widget centre, zero jitter — for high-frequency actions (bank close, use-ash, use-super, use-knife, use-log). Five different clicks per cycle, all pixel-perfect, all 497 times. Where jitter does exist, the envelope is a fixed-fraction inset (1/4 or 1/6) every call, so the spread is reproducible too.

## Open questions (to investigate next, separately)

- ~~Ban category — macroing-major vs minor, length, account state on official launcher.~~ **Answered 2026-05-23:** Macroing Major, permanent, see **Ban notice (verbatim)** section above.
- Whether other accounts on the same machine / network are still clean.
- Whether the click pixel distribution at the Lumbridge banker is statistically distinguishable from a real player's (we have the log data to test this).
- Whether any neighbouring players reported us (no in-game chat captured, but a `reportabuse` from a passerby would not show up in our log either way).
- Whether the RooftopAgilityScript was actually misfiring during the 35 laps (operator recollection 2026-05-23: "agility script was buggy, would click through weird places, do weird things, just broken"). Two specific failure modes the operator recalled:
  - **Walk-to-start opened the world map** instead of walking. Recovery dispatches `CLICK_TILE` (`RooftopAgilityScript.java:1166-1170`); `PixelResolver`'s minimap-pick path can land on the world-map orb adjacent to the minimap; engine opens map, click consumed, script stalls or spam-retries.
  - **Obstacle clicks routed to the chatbox.** Low camera pitch / lap-end tiles project hull or tile poly partially behind the chatbox widget; `PixelResolver` samples inside chatbox bounds; engine routes click to chatbox, world action never fires; spam-guard 300ms retry hits the same wall. This is exactly the "same weird failure every time" pattern a mod reviewer notices.
  - Both are textbook dispatcher outcome 2 ("click reached the engine but resolved to a different menu entry") per `CLAUDE.md` §8. Suggests `PixelResolver` is missing a UI-occluded-bounds exclusion mask (world-map orb, chatbox, minimap inset, sidebar tabs).

**Log evidence (2026-05-23 grep over `client_2026-05-21.0.log` and `client.log`):**

  - **29 "no startTile on player's plane (1) — skipping recovery walk" events**, all on 2026-05-22, none on 2026-05-21. Draynor's `startTiles` set is plane-0 only (`RooftopAgilityScript.java:1424` per click-pattern register High #9), so any fall / transition leaving the player on plane 1 left the recovery walker with literally no target. Bot stood frozen for ~1 minute at a time between recovery attempts. Worst clustered window: 16:34–17:03 CEST, 13 events in 29 minutes.
  - **Two "stage 0 (Rough wall) failed 6 times in a row" warnings**, both on 2026-05-22, both at `WorldPoint(x=3103, y=3279, plane=0)` (the Draynor start tile), 17 minutes apart (16:47:49 and 17:04:53). Each preceded by escalating `fail 1/6 → 5/6` `obstacle timeout` lines at ~9 s cadence. ~45 s of clicking the same wall on the same tile, zero progress, twice in the same hour. Click was being consumed *somewhere* between the dispatcher and the obstacle (UI widget absorbing it, hull projection occluded, etc.) — we can't tell *where* from these logs, but the operator's "world-map opens / chatbox swallows clicks" recollection maps cleanly onto these symptoms.
  - **May 21 had isolated single-fail obstacle-timeouts** (one each on Tightrope 2, Narrow wall, Wall, Rough wall, Gap, Crate, Wall again, Gap again — different obstacles, no consecutive same-obstacle pattern, each recovered to a successful next attempt). The "stuck on the same wall N times" failure mode only appeared on May 22.
  - The May-21-vs-May-22 asymmetry (clean on day 1, broken on day 2) is itself worth investigating — likely cumulative state (camera drift, plane confusion after a Climb-down) but could be a code path May 21's 6 laps never hit.

**Mod-reviewer visibility:** Any human scrubbing the May 22 session timeline would see 29 stretches of "player frozen on a rooftop" plus two stretches of "player clicking the same wall 6 times in 45 s with no progress." This is high-confidence ban-clicker bait — not statistical, observably non-human.

---

## Files referenced

- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/UltraCompostScript.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/FletchingScript.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/RooftopAgilityScript.java`
- `runelite-client/src/main/java/net/runelite/client/plugins/recorder/farm/BankInteraction.java`
- `runelite-client/src/main/java/net/runelite/client/sequence/dispatch/HumanizedInputDispatcher.java`
- `~/.runelite/recorder/sessions/jbane777/2026-05-2{1,2}.json`
- `~/.runelite/logs/client_2026-05-21.0.log`, `~/.runelite/logs/client.log`
