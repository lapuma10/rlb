package net.runelite.client.sequence.dispatch;

import java.util.concurrent.ThreadLocalRandom;

/** Pre-press dwell / button-down hold / post-release hold ranges for
 *  the {@code press(...)} primitive. Three values cover every press
 *  site in {@code HumanizedInputDispatcher} — see the audit notes in
 *  the spec at
 *  {@code docs/superpowers/specs/2026-05-22-chatbox-deadzone-click-guard.md}.
 *
 *  <p>Audit reconciliation (2026-05-22): the original 4-profile
 *  proposal (TIGHT_COMMIT / LIGHT_COMMIT split by attackVerb at
 *  npcClick line 949) conflated the press primitive with phase 6's
 *  "settle before verify" sleep. Phase 6's sleep is followed by two
 *  state reads ({@code isNpcClaimedByOtherPlayer},
 *  {@code isTopVerbOnNpc}) before the press itself; folding it into
 *  the press primitive would change ordering. The actual press at
 *  phase 8 is uniform regardless of attackVerb:
 *  {@code mousePress; sleep 40..80; mouseRelease; sleep 100..350}.
 *  The attackVerb conditional remains in {@code npcClick}'s phase 6
 *  body; it is not a press-timing concern.
 *
 *  <p>Ranges below are the empirically-correct values read from
 *  {@code HumanizedInputDispatcher} source. Migration MUST preserve
 *  them exactly. */
public enum PressTiming
{
    /** General-purpose: 180..500 / 40..80 / 100..350 ms. Used by
     *  every {@code clickPress(BUTTON1)} and {@code clickPress(BUTTON3)}
     *  call site today (~33 sites). */
    STANDARD       (180, 500, 40, 80, 100, 350),
    /** Post-verify commit press: pre=0 / 40..80 / 100..350 ms. The
     *  caller already settled the cursor and ran a state-verify
     *  ({@code isTopVerbOnNpc}, {@code isSafeLeftClickTake}) right
     *  before the press, so no additional pre-dwell. Used at two
     *  sites today: npcClick phase 8 (left-click after hover verify)
     *  and groundItemClick phase 5 (Take with safety verify). */
    FAST_COMMIT    (  0,   0, 40, 80, 100, 350),
    /** Right-click menu row selection: pre=0 / 40..80 / 80..260 ms.
     *  Eight sites today (npcClick row, groundItem row, widget row,
     *  bounds row, etc.). The caller does
     *  {@code moveCursorTo(row) + settle 40..100ms} before this
     *  press; that settle is kept at the call site because some
     *  paths interpose a state read (claimedByOther) between the
     *  settle and the press. */
    MENU_SELECTION (  0,   0, 40, 80,  80, 260);

    private final int preMin, preMax, holdMin, holdMax, postMin, postMax;

    PressTiming(int preMin, int preMax, int holdMin, int holdMax,
                int postMin, int postMax)
    {
        this.preMin  = preMin;  this.preMax  = preMax;
        this.holdMin = holdMin; this.holdMax = holdMax;
        this.postMin = postMin; this.postMax = postMax;
    }

    public int samplePreMs()
    {
        if (preMin == 0 && preMax == 0) return 0;
        return preMin == preMax ? preMin
            : preMin + ThreadLocalRandom.current().nextInt(preMax - preMin);
    }

    public int sampleHoldMs()
    {
        return holdMin == holdMax ? holdMin
            : holdMin + ThreadLocalRandom.current().nextInt(holdMax - holdMin);
    }

    public int samplePostMs()
    {
        return postMin == postMax ? postMin
            : postMin + ThreadLocalRandom.current().nextInt(postMax - postMin);
    }

    // Range accessors for unit tests — these are not used in production,
    // they're only here so a test can assert "STANDARD pre min == 180".
    int preMin()  { return preMin;  }
    int preMax()  { return preMax;  }
    int holdMin() { return holdMin; }
    int holdMax() { return holdMax; }
    int postMin() { return postMin; }
    int postMax() { return postMax; }
}
