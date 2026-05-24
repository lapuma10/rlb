#!/usr/bin/env bash
# Artemis Phase 1B grep gate.
# Fails when scripts/ contains imports / calls into engine internals banned
# by spec §14, unless the (file, pattern) pair is in the allow-list with
# an allowed_count that bounds the legacy hit budget.
# Phase 4 wires this into pre-commit + CI; until then, run manually:
#   ./scripts/check-no-direct-engine-reaches.sh
set -euo pipefail

SCRIPTS_DIR="${1:-runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts}"
ALLOWLIST="${ALLOWLIST:-scripts/check-no-direct-engine-reaches.allowlist.tsv}"
STRICT_ALLOWLIST="${STRICT_ALLOWLIST:-0}"

# Each pattern is an extended regex. ORDER IS NOT SIGNIFICANT but DUPLICATES
# ARE FORBIDDEN — the allow-list keys on the exact pattern string so two
# patterns matching the same thing would split allow-list entries.
BANNED_PATTERNS=(
  'net\.runelite\.client\.sequence\.dispatch\.'
  'net\.runelite\.client\.sequence\.internal\.ActionRequest'
  'net\.runelite\.client\.plugins\.recorder\.farm\.BankInteraction'
  'net\.runelite\.client\.plugins\.recorder\.farm\.GeInteraction'
  'net\.runelite\.client\.sequence\.activities\.ge\.'
  'net\.runelite\.client\.sequence\.activities\.banking\.'
  'net\.runelite\.client\.plugins\.recorder\.scene\.SceneScanner'
  'net\.runelite\.client\.plugins\.recorder\.combat\.NpcSelector'
  'net\.runelite\.client\.plugins\.recorder\.widget\.WidgetActions'
  'net\.runelite\.client\.plugins\.recorder\.walker\.'
  'net\.runelite\.client\.plugins\.recorder\.nav\.'
  'net\.runelite\.client\.plugins\.recorder\.trail\.'
  'net\.runelite\.client\.plugins\.recorder\.transport\.'
  'net\.runelite\.client\.callback\.ClientThread'
  'java\.awt\.Robot'
  'java\.awt\.event\.MouseEvent'
  '^import net\.runelite\.api\.NPC;'
  '^import net\.runelite\.api\.GameObject;'
  '^import net\.runelite\.api\.widgets\.Widget;'
  '\bsampleNearCentroid\b'
  '\bclickCanvas\('
  '\bdispatcher\.dispatch\('
  '\bActionRequest\.builder\('
  '\bnew ActionRequest\('
  '\bnew NpcSelector\('
)

if [ ! -d "$SCRIPTS_DIR" ]; then
    echo "FAIL: scripts dir does not exist: $SCRIPTS_DIR" >&2
    exit 2
fi

# ── Load allow-list ────────────────────────────────────────────────
# Lines: script_path\tbanned_pattern\tallowed_count\treason\tremoval_phase
# Comments + blanks ignored. Validate:
#   (a) exactly 5 tab-separated columns
#   (b) banned_pattern matches one of BANNED_PATTERNS verbatim
#   (c) allowed_count is a positive integer (>= 1)
declare -a ALLOW_FILES
declare -a ALLOW_PATTERNS
declare -a ALLOW_COUNTS
allow_count=0

if [ -f "$ALLOWLIST" ]; then
    # Pre-pass 1: column-count validation via awk. `IFS=$'\t' read` would
    # silently absorb trailing tabs into the last variable, so it can't
    # reject 6+ column lines on its own. awk's NF check is authoritative.
    bad_line=$(awk -F '\t' '
        /^[[:space:]]*$/ { next }
        /^#/             { next }
        /^script_path\t/ { next }
        NF != 5          { print NR; exit }
    ' "$ALLOWLIST")
    if [ -n "$bad_line" ]; then
        echo "FAIL: allow-list line $bad_line: expected exactly 5 tab-separated columns (file<TAB>pattern<TAB>allowed_count<TAB>reason<TAB>phase)" >&2
        exit 2
    fi

    # Pre-pass 2: validate banned_pattern is in BANNED_PATTERNS, and
    # allowed_count is a positive integer.
    allow_lineno=0
    while IFS=$'\t' read -r f p ac r ph; do
        allow_lineno=$((allow_lineno + 1))
        case "$f" in
            '' | \#* | script_path) continue ;;
        esac
        # Pattern check
        found=0
        for known in "${BANNED_PATTERNS[@]}"; do
            if [ "$p" = "$known" ]; then found=1; break; fi
        done
        if [ "$found" = "0" ]; then
            echo "FAIL: allow-list line $allow_lineno: banned_pattern not in BANNED_PATTERNS: $p" >&2
            exit 2
        fi
        # allowed_count must be a positive integer (1..N). Reject empty,
        # non-numeric, zero, negative.
        if ! [[ "$ac" =~ ^[1-9][0-9]*$ ]]; then
            echo "FAIL: allow-list line $allow_lineno: allowed_count must be a positive integer (>=1), got: '$ac'" >&2
            exit 2
        fi
        ALLOW_FILES[allow_count]="$f"
        ALLOW_PATTERNS[allow_count]="$p"
        ALLOW_COUNTS[allow_count]="$ac"
        allow_count=$((allow_count + 1))
    done < "$ALLOWLIST"
fi

# ── Scan + apply ───────────────────────────────────────────────────
violations=0
files_scanned=0
allow_applied=0          # number of (file, pattern) entries with >0 hits
allowed_hits_total=0     # sum of actual_count for matched allow-list entries
declare -a ALLOW_HIT
for ((i=0; i<allow_count; i++)); do ALLOW_HIT[i]=0; done

while IFS= read -r -d '' file; do
    files_scanned=$((files_scanned + 1))
    for pattern in "${BANNED_PATTERNS[@]}"; do
        # grep -c returns 0 with non-zero exit when no match; capture both.
        actual_count=$(grep -cE "$pattern" "$file" 2>/dev/null || true)
        actual_count="${actual_count:-0}"
        if [ "$actual_count" = "0" ]; then continue; fi

        # Find this (file, pattern) in the allow-list.
        idx=-1
        for ((i=0; i<allow_count; i++)); do
            if [ "${ALLOW_FILES[i]}" = "$file" ] && [ "${ALLOW_PATTERNS[i]}" = "$pattern" ]; then
                idx=$i
                break
            fi
        done

        if [ "$idx" -ge 0 ]; then
            permitted="${ALLOW_COUNTS[idx]}"
            ALLOW_HIT[idx]="$actual_count"
            if [ "$actual_count" -le "$permitted" ]; then
                allow_applied=$((allow_applied + 1))
                allowed_hits_total=$((allowed_hits_total + actual_count))
                continue
            fi
            # actual > allowed_count → fail with the extra hits visible.
            excess=$((actual_count - permitted))
            echo "FAIL: $file matches banned pattern $pattern $actual_count time(s)"
            echo "      allow-list permits $permitted; $excess new uncovered hit(s):"
            # Print the actual matching lines so the new ones surface.
            grep -nE "$pattern" "$file" | sed 's/^/        /'
            echo "      to allow the new total, bump allowed_count to $actual_count in:"
            echo "      $ALLOWLIST"
            violations=$((violations + 1))
            continue
        fi

        # No allow-list entry at all → print every hit.
        while IFS= read -r hit; do
            line_num="${hit%%:*}"
            echo "FAIL: $file:$line_num matches banned pattern: $pattern"
            echo "      not covered by allow-list"
            echo "      to allow temporarily, add to $ALLOWLIST:"
            printf '      %s\t%s\t%s\t%s\t%s\n' "$file" "$pattern" "$actual_count" "<one-sentence-reason>" "<phase-that-removes-it>"
            violations=$((violations + 1))
        done < <(grep -nE "$pattern" "$file" 2>/dev/null)
    done
done < <(find "$SCRIPTS_DIR" -name "*.java" -type f -print0)

# ── Orphan check ───────────────────────────────────────────────────
# Entry is an orphan when the file no longer matches the pattern at all.
orphans=0
for ((i=0; i<allow_count; i++)); do
    if [ "${ALLOW_HIT[i]}" = "0" ]; then
        if [ "$orphans" = "0" ]; then echo "" >&2; fi
        printf 'ORPHAN: allow-list entry never matched: %s\t%s\n' "${ALLOW_FILES[i]}" "${ALLOW_PATTERNS[i]}" >&2
        orphans=$((orphans + 1))
    fi
done

# ── Exit ───────────────────────────────────────────────────────────
if [ "$violations" -gt 0 ]; then
    echo "" >&2
    echo "Scanned $files_scanned files. $violations violation(s) found, $allow_applied allow-list exemption(s) applied (covering $allowed_hits_total hit(s)), $orphans orphan(s)." >&2
    exit 1
fi
if [ "$orphans" -gt 0 ] && [ "$STRICT_ALLOWLIST" = "1" ]; then
    echo "" >&2
    echo "Strict mode: $orphans orphan allow-list entry/entries — clean up." >&2
    exit 3
fi
# Explicit branch — `${orphans:+...}` is unsafe because orphans=0 is still
# set, so the suffix would always render.
if [ "$orphans" -gt 0 ]; then
    echo "OK: scanned $files_scanned files, $allow_applied allow-list entries applied (covering $allowed_hits_total hit(s)), $orphans orphan(s) — non-fatal without STRICT_ALLOWLIST=1."
else
    echo "OK: scanned $files_scanned files, $allow_applied allow-list entries applied (covering $allowed_hits_total hit(s))."
fi
exit 0
