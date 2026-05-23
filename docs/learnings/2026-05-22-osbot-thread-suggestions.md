# Anti-detection suggestions from the OSBot mouse-movement thread

Source: https://osbot.org/forum/topic/153397-osbots-mouse-movement-is-easily-detected/ (151 posts, April 2019 – April 2020). Full extraction archived alongside this file as `2026-05-22-osbot-thread-archive.md`.

This document collects **what participants in that thread suggested** towards better anti-detection — nothing else. No mapping to our codebase, no remediation plan, no judgment on whether the suggestions are correct. Quotes are verbatim; page numbers refer to the thread.

Reader's note: the thread is OSBot-specific and 7 years old. Participants are botting-script developers and a couple of OSBot staff. Take each claim at the credibility you think the speaker deserves.

---

## OP suggestions — `asdttt`

The starting position is that OSBot's mouse mover produces a detectable pattern: when sampling at the same 50 ms cadence Jagex uses, the deltas at the end of every move follow a "low → high → low → high" rhythm that real human mouse paths don't.

### What he proposes

- **Build your own mouse mover instead of using the bot client's API for movement.** (p1, p3)
  > "I'd personally recommend you just make yourself your own random mouse mover which will hide the OSBot consistent flaws and use OSBot to still click entities. It doesn't need to be advanced, hell you could even record your own small movements and apply it." (p3)

- **Six parameters a mouse mover should model.** (p9)
  > "Factors are: Reaction time, reaction time variation, mouse speed, mouse speed variation, mouse step variation, deviation (Very important, mind you, the human wrist cannot easily move the mouse without deviation), noise, and OVER-move (When you move your mouse, but accidentally go over a button and have to return to it)."

- **Profile these parameters per-script / per-account.** (p9)
  > "All of these should also be editable by scripters. A 12 year old would have different reaction time and mouse speed compared to a pro CS:GO player for instance"

- **Moving the cursor outside the screen is safer than randomly moving it inside.** (p3)
  > "Moving the mouse outside the screen DOES NOT produce patterns. […] Even keeping the mouse perfectly still is better then moving it randomly around with OSBot's mouse API."

- **Click hold-duration must have real variance.** (p15, on press → release timing)
  > "There's a delay between mouse press and the mouse release event on a normal mouse, 99% of autoclickers such as Gary's Hood execute both events instantly whereas mine delays it within the bounds of a normal mouseclick (Around 50-100MS generally)."

- **Record yourself playing for 20 minutes; apply your own timings/AFK-ness/reaction-time/movement patterns to your script.** (p3)
  > "I personally recommend you record yourself playing for 20 minutes, then apply your own timings, AFK-ness, reaction time, and mouse pattern (such as moving the mouse outside the window, or randomly moving it around) to your script."

- **No single fix solves the problem.** (p4, p6)
  > "There's no single method in bypassing either, so don't assume messing with mouse movement will suddenly make you bypass."
  > "Once this is fixed, I'll move onto other OSBot API related aspects that are easily detected (If there is any). Until then, fix the mouse movement"

### Specific defects he describes (so a fix can target them)

- OSBot's last 3-4 mouse deltas at the end of every move are "low, big, low, big" — a detectable rhythm. (p1, with sample paste link)
- Most OSBot moves fall into Jagex's "small/medium delta" packet bucket, which is sampled per-tick. (p1)
- Autoclickers using `SendInput` with 0 ms between `press` and `release` are trivially detectable in the JVM event stream. (p15)

### His own reported ban-rate change

> "Before implementing new mouse movement: 56/56 bans — Each bot lasting exactly 1 day. After implementing new mouse movement: 0/12 bans" (p5)

He explicitly disclaims this as anecdotal, not statistical evidence.

---

## Recorded-path replay — `Molly`

> "A straightforward solution would be to record a couple thousand mouse paths of varying distances and base your script's mouse movements off these. Example: I need to move my mouse 70 pixels, I grab from my thousands of human mouse paths some that are around 70 pixels, say between 40-100. I grab one of those paths, stretch it or shrink it, add some noise to it and use that path to move the mouse." (p4)
>
> "The downside to this is it requires a fair amount of work recording that many mouse paths and realistically is easier to do and probably better for not being 'detected' if done by each scripter individually for their own scripts."

She also flagged a meta-point worth quoting on its own:

> "I think the important thing to take away here is that they do at least send the mouse data to their servers, it doesn't look very human, and if they wanted to they could use this as one of many metrics to detect bots. That alone should be enough to encourage a change" (p2)

---

## Crowdsourced human-mouse data — `RoundBox`

> "I remember years and years ago when botting in OSRS was still in its infancy (first 12 months) there was a client that upon booting up the client, users could opt in to help their mouse algorithm by doing a quick 1 minute clicking test (similar to the ones used to build click accuracy). Once done, it would save a file to the users Documents folder and then there was a thread for the users to upload the document to. All of this was used to help develop human mouse movement for their client. If OSbot implemented this, would this s[olve it]?" (p9)

---

## Behavioural / script-level suggestions — `ThoughtVNC`

> "Bots will make less mistakes than a human and click at consistent RPM. If anything, bots need to double click, spam click in some cases, become more delayed over time, use middle mouse button to change camera angle, etc etc." (p2)

---

## Pessimistic counterpoint — `Alek` (OSBot dev)

His position is that mouse-movement tweaks alone won't change the ban rate:

> "Antiban doesn't matter — plain and simple. If you do any research into official claims made by Jagex, you can see why. They claim that both autoclickers and simulated mouse keys are detectable, and yes people do get banned for using them." (p7)

He argues `SendInput`-based clicks can be detected by native hooks, regardless of how the press/release timing looks:

> "All Windows API functions can be hooked and detected. Look into JNI/JNA (Java). Please don't say something is undetected/hardware call when you're using a usermode public Windows API function call." (p7)

And speculates the same applies to GC:

> "Additionally a while back they determined that HD clients are indistinguishable from botting clients, which also makes me believe they are looking at the garbage collector." (p7)

OSBot dev `Patrick` was more measured:

> "I already told you I believe mouse movement can be used for detection — albeit only a very small part of the system —, I also told you it's something we're interested in changing and are discussing." (p2)

---

## Account-similarity / ML hypothesis — `Search12`

> "My own belief is that while things like third-party clients probably do contribute; detection is massively based on whether your account has similarities to other accounts (including banned accounts) — basic machine learning. Similarities including but not limited to delays and timings, account progression and so on. Reports probably trigger the server side AC system. Maybe large disparity trades do too." (p8)

He framed this as common knowledge in the SRL community. Implication for a scripter: two accounts running the same script with the same parameters end up in the same ML cluster, and a ban on one lights up the others.

---

## Player-reports beat mouse movement — `Charlotte`

> "Player reports > mouse movement. Mouse movement contributes little to none imo. I've done tons of 100~hr progs on highly repetitive tasks such as fishing/agility/hunter etc on injection. Not a fan of mirror mode btw. Till date, I believe player reports has the highest contributing factor to getting your account banned." (p2)

The implied suggestion: invest in not-being-reported (don't be visibly botting to other players) ahead of investing in technical evasion.

---

## "Bot smart" — `Protoprize`, `Tesh`, others

A recurring informal suggestion across multiple posters. Not technical — but several long-time botters credited it as the reason they didn't get banned where others did.

- `Protoprize` (p2): "I broke the one rule everyone says not to. I bot my main… 2 months ago with no ban still because I'm botting smart. The reason for getting banned is sometimes just luck, but most of the time, it's stupidity."
- `Tesh` (p2): "All bots become detected at some point and there is no way of counteracting that. […] only use accounts you're willing to lose."
- `Tesh` (p7): other factors include "run time, botting hotspots, age of account, flagged IP's, similar names of bot accounts."

---

## Tutorial-island "delay bans" — `Gabriel Ramuglia`

> "Just realized that the impacted accounts were probably already 'delay banned' due to botting tutorial island." (p10)

Implication: accounts that were botted through tutorial-island get flagged at account creation, then banned later when scripting begins — no amount of in-script humanization will save them. Suggested practice: manual tutorial + warm-up before scripting.

His other practical observation:

> "Bot didn't run 'full speed' — there were random short delays built in, with occasional long delays as well." (p10) — i.e. mix short and long delays, don't run a flat distribution.

---

## Mouse-recorder approach — `caketeaparty`

> "I've been wondering why my mouse recorder used correctly results in virtually 0 bans vs. Injection and even Mirror, even on flagged proxies. By the way, I'm pretty sure the random lag spikes on Mirror skews the mouse data a bit as well, which might be why it tends to result in fewer bans." (p8)

Implication: a "mouse recorder" workflow (record real human paths, replay them) is what some long-running operators were already using to achieve much lower ban rates. Aligns with `Molly` and `RoundBox`. He also suggests that *adding noise into the data stream by accident* (e.g. Mirror-mode lag spikes) seems to help — i.e. natural jitter, not synthetic.

---

## Forward-looking — `AceKingSuited` (Jan 2020)

> "This thread has brought a lot of confirmation to what I've spent the last few weeks mulling over. Even down to the recording of movements to compare against the client's mouse movement and attempting to see if the client's mouse movement can easily be identified." (p9)

Adds his own thought-experiment ("Cat and Mouse" project), but does not publish specifics in-thread.

---

## What no one in the thread suggested

Worth noting these *don't* appear as suggestions:

- Bypassing the JVM input layer via hardware loop-back or driver-level synthesis (Alek raises it as a *risk* but no one proposes building it).
- Modifying the OSRS client packet contents directly (everyone treats the packet contents as fixed; the suggestions are about *what input you generate* before it's encoded).
- Spoofing different operating systems / locales / hardware profiles per account.
- Using anti-fingerprinting techniques outside the input stream (mouse-DPI, polling-rate, etc.).

---

## Summary of distinct suggestions, deduplicated

In rough order of how many participants endorsed each:

1. **Record real human mouse paths and replay them with stretching + noise.** (Molly, RoundBox, caketeaparty, asdttt indirectly)
2. **Build a parametric mouse mover with realistic factors — reaction time, speed, deviation/wrist tremor, noise, overshoot/over-move.** (asdttt, with explicit parameter list)
3. **Vary parameters per profile / per account — different "personalities".** (asdttt)
4. **Real press → release delay (50–100 ms typical), not 0 ms, not a fixed range.** (asdttt)
5. **Mix short and long delays at random; build in breaks; don't run flat-distribution.** (Gabriel Ramuglia, asdttt)
6. **Move cursor off-screen during idle — safer than random in-canvas motion.** (asdttt, considered "AFK-watching-Netflix" behaviour)
7. **Avoid behavioural similarity across accounts running the same script (ML clustering risk).** (Search12)
8. **Invest in not-being-reported before investing in technical evasion.** (Charlotte)
9. **Manually do tutorial island + warm up the account before scripting.** (Gabriel Ramuglia)
10. **Add occasional double-clicks, fatigue-over-time, middle-mouse-button camera moves — small "imperfect" behaviours.** (ThoughtVNC)
11. **Treat detection as multi-factor — fix one thing then move to the next, no single change is enough.** (asdttt, repeated)
12. **Accept that some accounts will be lost and only bot accounts you're willing to lose.** (Tesh, Protoprize, multiple)
