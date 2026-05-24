#!/usr/bin/env bash
# Test harness for check-no-direct-engine-reaches.sh.
# All temp state lives under $TMP and is cleaned up via trap.
set -uo pipefail   # NOT -e — tests must observe non-zero exits

GATE="$(dirname "$0")/check-no-direct-engine-reaches.sh"
TMP="$(mktemp -d -t artemis-phase1b-test.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT

passes=0
failures=0
TAB=$'\t'

assert_exit() {
    local expected="$1" actual="$2" name="$3"
    if [ "$expected" = "$actual" ]; then
        echo "  PASS: $name (exit=$actual)"
        passes=$((passes + 1))
    else
        echo "  FAIL: $name (expected exit=$expected, got=$actual)"
        failures=$((failures + 1))
    fi
}

# Header-only allow-list reused by tests that don't need entries.
EMPTY_ALLOW="$TMP/empty-allowlist.tsv"
printf 'script_path%sbanned_pattern%sallowed_count%sreason%sremoval_phase\n' \
    "$TAB" "$TAB" "$TAB" "$TAB" > "$EMPTY_ALLOW"

# ── Test 1: Empty scripts dir → exit 0 ──
echo "Test 1: empty scripts dir → exit 0"
mkdir -p "$TMP/empty"
ALLOWLIST="$EMPTY_ALLOW" "$GATE" "$TMP/empty" >/dev/null 2>&1
assert_exit 0 $? "empty scripts dir"

# ── Test 2: Clean script (no banned imports) → exit 0 ──
echo "Test 2: clean script with only Artemis imports → exit 0"
mkdir -p "$TMP/clean"
cat > "$TMP/clean/CleanScript.java" <<'JAVA'
package net.runelite.client.plugins.recorder.scripts;
import net.runelite.client.sequence.artemis.Artemis;
import net.runelite.client.sequence.artemis.query.NpcQuery;
public class CleanScript { Artemis artemis; }
JAVA
ALLOWLIST="$EMPTY_ALLOW" "$GATE" "$TMP/clean" >/dev/null 2>&1
assert_exit 0 $? "clean script with only Artemis imports"

# ── Test 3: Dirty script (banned import, no allow-list) → exit 1 ──
echo "Test 3: dirty script (banned import) → exit 1"
mkdir -p "$TMP/dirty"
cat > "$TMP/dirty/BadScript.java" <<'JAVA'
package net.runelite.client.plugins.recorder.scripts;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
public class BadScript { HumanizedInputDispatcher dispatcher; }
JAVA
ALLOWLIST="$EMPTY_ALLOW" "$GATE" "$TMP/dirty" >/dev/null 2>&1
assert_exit 1 $? "dirty script not in allow-list"

# ── Test 4: Dirty script + matching allow-list entry with exact count → exit 0 ──
echo "Test 4: dirty script + allow-list (count matches) → exit 0"
MATCH_ALLOW="$TMP/match-allowlist.tsv"
{
    printf 'script_path%sbanned_pattern%sallowed_count%sreason%sremoval_phase\n' "$TAB" "$TAB" "$TAB" "$TAB"
    printf '%s%s%s%s%s%s%s%s%s\n' \
        "$TMP/dirty/BadScript.java" "$TAB" \
        'net\.runelite\.client\.sequence\.dispatch\.' "$TAB" \
        "1" "$TAB" \
        "test reason" "$TAB" \
        "test phase"
} > "$MATCH_ALLOW"
ALLOWLIST="$MATCH_ALLOW" "$GATE" "$TMP/dirty" >/dev/null 2>&1
assert_exit 0 $? "dirty script covered by allow-list (count=1, actual=1)"

# ── Test 4b: allowed_count > actual is OK (legacy script shrunk) ──
echo "Test 4b: allow-list count exceeds actual → exit 0"
EXCESS_ALLOW="$TMP/excess-allowlist.tsv"
{
    printf 'script_path%sbanned_pattern%sallowed_count%sreason%sremoval_phase\n' "$TAB" "$TAB" "$TAB" "$TAB"
    printf '%s%s%s%s%s%s%s%s%s\n' \
        "$TMP/dirty/BadScript.java" "$TAB" \
        'net\.runelite\.client\.sequence\.dispatch\.' "$TAB" \
        "5" "$TAB" \
        "test reason" "$TAB" \
        "test phase"
} > "$EXCESS_ALLOW"
ALLOWLIST="$EXCESS_ALLOW" "$GATE" "$TMP/dirty" >/dev/null 2>&1
assert_exit 0 $? "allow-list count=5, actual=1 (under-budget is OK)"

# ── Test 4c: actual > allowed_count fails (new callsite snuck in) ──
echo "Test 4c: actual hits exceed allowed_count → exit 1"
mkdir -p "$TMP/leaky"
cat > "$TMP/leaky/LeakyScript.java" <<'JAVA'
package net.runelite.client.plugins.recorder.scripts;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.dispatch.PixelResolver;
public class LeakyScript {
    void m() {
        dispatcher.dispatch(req1);
        dispatcher.dispatch(req2);
        dispatcher.dispatch(req3);   // new callsite — third one is over budget
    }
}
JAVA
LEAKY_ALLOW="$TMP/leaky-allowlist.tsv"
{
    printf 'script_path%sbanned_pattern%sallowed_count%sreason%sremoval_phase\n' "$TAB" "$TAB" "$TAB" "$TAB"
    # Allow only 2 dispatcher.dispatch calls, but the file has 3.
    printf '%s%s%s%s%s%s%s%s%s\n' \
        "$TMP/leaky/LeakyScript.java" "$TAB" \
        '\bdispatcher\.dispatch\(' "$TAB" \
        "2" "$TAB" \
        "legacy 2 callsites" "$TAB" \
        "Phase 6.x"
    # Also allow the bare imports so they don't pollute the failure.
    printf '%s%s%s%s%s%s%s%s%s\n' \
        "$TMP/leaky/LeakyScript.java" "$TAB" \
        'net\.runelite\.client\.sequence\.dispatch\.' "$TAB" \
        "2" "$TAB" \
        "import lines" "$TAB" \
        "Phase 6.x"
} > "$LEAKY_ALLOW"
ALLOWLIST="$LEAKY_ALLOW" "$GATE" "$TMP/leaky" >/dev/null 2>&1
assert_exit 1 $? "new callsite pushes actual above allowed_count"

# ── Test 5: Malformed allow-list (4 columns instead of 5) → exit 2 ──
echo "Test 5: malformed allow-list (4 cols, missing count) → exit 2"
MALFORMED_ALLOW="$TMP/malformed-allowlist.tsv"
printf 'a%sb%sc%sd\n' "$TAB" "$TAB" "$TAB" > "$MALFORMED_ALLOW"
ALLOWLIST="$MALFORMED_ALLOW" "$GATE" "$TMP/clean" >/dev/null 2>&1
assert_exit 2 $? "malformed allow-list (4 columns — missing allowed_count)"

# ── Test 5b: Malformed allow-list (6 columns) → exit 2 ──
echo "Test 5b: malformed allow-list (6 cols) → exit 2"
TOOMANY_ALLOW="$TMP/too-many-cols.tsv"
{
    printf 'script_path%sbanned_pattern%sallowed_count%sreason%sremoval_phase\n' "$TAB" "$TAB" "$TAB" "$TAB"
    printf 'a%sb%s1%sc%sd%sEXTRA\n' "$TAB" "$TAB" "$TAB" "$TAB" "$TAB"
} > "$TOOMANY_ALLOW"
ALLOWLIST="$TOOMANY_ALLOW" "$GATE" "$TMP/clean" >/dev/null 2>&1
assert_exit 2 $? "malformed allow-list (6 columns — awk NF check)"

# ── Test 5c: allowed_count = 0 → exit 2 ──
echo "Test 5c: allowed_count=0 → exit 2"
ZERO_ALLOW="$TMP/zero-allowlist.tsv"
{
    printf 'script_path%sbanned_pattern%sallowed_count%sreason%sremoval_phase\n' "$TAB" "$TAB" "$TAB" "$TAB"
    printf 'a%s\\bdispatcher\\.dispatch\\(%s0%sreason%sphase\n' "$TAB" "$TAB" "$TAB" "$TAB"
} > "$ZERO_ALLOW"
ALLOWLIST="$ZERO_ALLOW" "$GATE" "$TMP/clean" >/dev/null 2>&1
assert_exit 2 $? "allowed_count must be >= 1, zero is rejected"

# ── Test 5d: allowed_count = "abc" → exit 2 ──
echo "Test 5d: non-integer allowed_count → exit 2"
NONINT_ALLOW="$TMP/nonint-allowlist.tsv"
{
    printf 'script_path%sbanned_pattern%sallowed_count%sreason%sremoval_phase\n' "$TAB" "$TAB" "$TAB" "$TAB"
    printf 'a%s\\bdispatcher\\.dispatch\\(%sabc%sreason%sphase\n' "$TAB" "$TAB" "$TAB" "$TAB"
} > "$NONINT_ALLOW"
ALLOWLIST="$NONINT_ALLOW" "$GATE" "$TMP/clean" >/dev/null 2>&1
assert_exit 2 $? "non-integer allowed_count rejected"

# ── Test 5e: allowed_count = "-3" → exit 2 ──
echo "Test 5e: negative allowed_count → exit 2"
NEG_ALLOW="$TMP/neg-allowlist.tsv"
{
    printf 'script_path%sbanned_pattern%sallowed_count%sreason%sremoval_phase\n' "$TAB" "$TAB" "$TAB" "$TAB"
    printf 'a%s\\bdispatcher\\.dispatch\\(%s-3%sreason%sphase\n' "$TAB" "$TAB" "$TAB" "$TAB"
} > "$NEG_ALLOW"
ALLOWLIST="$NEG_ALLOW" "$GATE" "$TMP/clean" >/dev/null 2>&1
assert_exit 2 $? "negative allowed_count rejected"

# ── Test 6: Allow-list pattern not in BANNED_PATTERNS → exit 2 ──
echo "Test 6: allow-list with unknown pattern → exit 2"
UNKNOWN_ALLOW="$TMP/unknown-pattern.tsv"
{
    printf 'script_path%sbanned_pattern%sallowed_count%sreason%sremoval_phase\n' "$TAB" "$TAB" "$TAB" "$TAB"
    printf 'x%snot.a.pattern%s1%sreason%sphase\n' "$TAB" "$TAB" "$TAB" "$TAB"
} > "$UNKNOWN_ALLOW"
ALLOWLIST="$UNKNOWN_ALLOW" "$GATE" "$TMP/clean" >/dev/null 2>&1
assert_exit 2 $? "allow-list pattern not in BANNED_PATTERNS"

# ── Test 7: Orphan allow-list entry → exit 0 (warning), exit 3 with --strict ──
echo "Test 7: orphan allow-list entry"
ORPHAN_ALLOW="$TMP/orphan.tsv"
{
    printf 'script_path%sbanned_pattern%sallowed_count%sreason%sremoval_phase\n' "$TAB" "$TAB" "$TAB" "$TAB"
    printf '%s%s%s%s%s%s%s%s%s\n' \
        "$TMP/nothing/here.java" "$TAB" \
        'net\.runelite\.client\.sequence\.dispatch\.' "$TAB" \
        "1" "$TAB" \
        "stale" "$TAB" \
        "phase"
} > "$ORPHAN_ALLOW"
ALLOWLIST="$ORPHAN_ALLOW" "$GATE" "$TMP/clean" >/dev/null 2>&1
assert_exit 0 $? "orphan allow-list (non-strict, exit 0)"
ALLOWLIST="$ORPHAN_ALLOW" STRICT_ALLOWLIST=1 "$GATE" "$TMP/clean" >/dev/null 2>&1
assert_exit 3 $? "orphan allow-list (STRICT_ALLOWLIST=1, exit 3)"

# ── Test 8: Real tree — should pass with seeded allow-list ──
echo "Test 8: real tree (master) with seeded allow-list → exit 0"
"$GATE" >/dev/null 2>&1
assert_exit 0 $? "real recorder/scripts/ with seeded allow-list"

# ── Summary ──
echo ""
echo "Total: $((passes + failures)). Passed: $passes. Failed: $failures."
[ "$failures" = "0" ] || exit 1
exit 0
