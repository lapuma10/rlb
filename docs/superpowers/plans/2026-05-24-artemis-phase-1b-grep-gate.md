# Artemis Phase 1B — Grep/import enforcement gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a manual + on-demand gate script that fails when a file under `recorder/scripts/` reaches into engine internals banned by spec §14 — except where a precise per-file / per-pattern entry in the allow-list explicitly permits it.

**Architecture:** Single bash script (`scripts/check-no-direct-engine-reaches.sh`) + companion TSV allow-list (`scripts/check-no-direct-engine-reaches.allowlist.tsv`). Gate enumerates each `.java` under the target dir, line-greps for every banned pattern, and treats a hit as a failure UNLESS the `(file, pattern)` pair is listed in the allow-list with reason + removal-phase. No wildcard exemptions: every legacy bypass is a tracked debt entry.

**Tech Stack:** bash (POSIX-ish — uses bash arrays, but no Linux-only utilities; works on macOS bash 3.2 and Linux bash 5+), `grep -E`, `awk` for TSV parsing.

**Scope (per spec §14):** `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/**/*.java` only. The `recorder/quest/` sub-tree is **not** in this gate's scope — quest scripts migrate in Phase 6 item 10 and a Phase-4-era scope widening (if needed) can extend the gate. This plan follows the spec strictly.

**Out of scope:**
- Implementation in Java
- CI wiring / pre-commit hook (Phase 4)
- Allow-list shrinkage as migrations land (per-migration commits, not here)
- Quest sub-tree
- LUMBRIDGE_COW_FIELD population (Phase 2 pilot plan)
- Phase 2 CowKillerScript itself

---

## Design decisions (lock before coding)

### 1. Allow-list format — TSV, exact 5 columns, comment + blank lines allowed

```
script_path<TAB>banned_pattern<TAB>allowed_count<TAB>reason<TAB>removal_phase
```

- **`script_path`** — repo-relative path, e.g. `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/CookingScriptV3.java`
- **`banned_pattern`** — the exact regex pattern (without anchors) that the gate found and the allow-list waives. Must match one of the gate's `BANNED_PATTERNS` entries verbatim — typos in the allow-list silently shadow nothing.
- **`allowed_count`** — positive integer (≥1) bounding the legacy hit budget for this `(file, pattern)` pair. The gate compares `actual_count` (live `grep -cE` result) against this and fails with exit 1 when `actual_count > allowed_count`. This is what stops new callsites from sneaking in past an already-allow-listed file: an extra `dispatcher.dispatch(` line in a file with `allowed_count=2` pushes actual to 3 and fails the gate.
- **`reason`** — single sentence; why this is temporarily OK. Should reference what's missing in Artemis (e.g. "bank surface lands in v1.1").
- **`removal_phase`** — exact phase tag that retires the entry. Examples: `v1.1 Phase 6.6 (CookingScriptV4)`, `v1.2 Phase 6.9 (GrandExchangeV2)`, `Phase 6.5 (FletchingV2)`. The phase tag must be unambiguous so future PRs can grep for which entries a given migration should remove.

Header line is required. Lines starting with `#` are comments. Blank lines are ignored. No quoting — tabs are forbidden inside fields (reason can use `;` as inline separator).

**Two example entries** (the implementation seeds many more from the migration audit):

```
runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/CookingScriptV3.java	net\.runelite\.client\.sequence\.dispatch\.	3	uses dispatcher direct for fire / use-on; v1.1 confirmMakeAll replaces these reaches	v1.1 Phase 6.6 (CookingScriptV4)
runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/GrandExchangeScript.java	net\.runelite\.client\.sequence\.activities\.ge\.	2	uses ge activity classes; v1.2 GE surface replaces	v1.2 Phase 6.9 (GrandExchangeV2)
```

### 2. Banned patterns — explicit list, single source of truth

Encoded inside the gate script as a bash array. Each pattern is an extended regex passed to `grep -E`. Patterns are line-level (match anywhere on a line, not just at column 0) — covers both `import …` lines and inline references / `new X(...)` constructions.

```
# Imports + FQN references
net\.runelite\.client\.sequence\.dispatch\.            # HumanizedInputDispatcher, PixelResolver, WindMouse, PressTiming, InputOwnership
net\.runelite\.client\.sequence\.internal\.ActionRequest
net\.runelite\.client\.plugins\.recorder\.farm\.BankInteraction
net\.runelite\.client\.plugins\.recorder\.farm\.GeInteraction
net\.runelite\.client\.sequence\.activities\.ge\.      # all GE activity classes
net\.runelite\.client\.sequence\.activities\.banking\. # all banking activity classes (engine-internal only)
net\.runelite\.client\.plugins\.recorder\.scene\.SceneScanner
net\.runelite\.client\.plugins\.recorder\.combat\.NpcSelector
net\.runelite\.client\.plugins\.recorder\.widget\.WidgetActions
net\.runelite\.client\.plugins\.recorder\.walker\.
net\.runelite\.client\.plugins\.recorder\.nav\.
net\.runelite\.client\.plugins\.recorder\.trail\.
net\.runelite\.client\.plugins\.recorder\.transport\.
net\.runelite\.client\.callback\.ClientThread
java\.awt\.Robot
java\.awt\.event\.MouseEvent
# Raw API types — only banned as imports (NPC, GameObject, Widget are too common as type tokens; the rule is "don't import them in scripts")
^import net\.runelite\.api\.NPC;
^import net\.runelite\.api\.GameObject;
^import net\.runelite\.api\.widgets\.Widget;
# Call patterns
\bsampleNearCentroid\b
\bclickCanvas\(
\bdispatcher\.dispatch\(
\bActionRequest\.builder\(
\bnew ActionRequest\(
\bnew NpcSelector\(
```

**Allowed engine-internal exception** (per spec §14 allow-list table):
- `ChickenCombatLoop`, `TrainingSession` — allowed as engine-internal helpers. These are NOT in the banned-pattern list above, so no allow-list entries needed.
- `CombatStateTracker` — not script-reached today; no entry.

### 3. Scope path — argument with default

Gate accepts an optional first argument: the scripts directory to scan. Default: `runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts`. Tests use a `mktemp -d` to point the gate at synthetic fixtures.

### 4. Allow-list location — also configurable

Gate accepts `--allowlist <path>` (or env var `ALLOWLIST`). Default: `scripts/check-no-direct-engine-reaches.allowlist.tsv`. Tests can pass an empty allow-list to verify the gate's strict mode.

### 5. Exit codes

| Code | Meaning |
|---|---|
| 0 | All scripts clean. No banned-pattern hits, OR every hit is matched by an allow-list entry. |
| 1 | At least one banned-pattern hit not covered by the allow-list. Standard "PR red" exit. |
| 2 | Malformed allow-list (wrong column count, unknown banned pattern in the `banned_pattern` column, etc.). Distinguishes "allow-list broken" from "scripts broken". |
| 3 | Allow-list contains an orphan entry — file or pattern no longer present in the tree. Treated as a non-fatal warning by default but flips to a hard fail under `--strict-allowlist`. Encourages cleanup as migrations land. |

Exit code 2 should print which line is malformed; exit 3 should list each orphan entry.

### 6. Output shape

On exit 0: one line `OK: scanned <N> files, <M> allow-list entries applied.`

On exit 1: one block per violation:
```
FAIL: <file>:<line> matches banned pattern <pattern>
      not covered by allow-list
      to allow temporarily, add to scripts/check-no-direct-engine-reaches.allowlist.tsv:
      <file>\t<pattern>\t<one-sentence-reason>\t<phase-that-removes-it>
```

The "to allow temporarily" line gives the exact text to paste — speeds onboarding for legacy script work, but the *reason* and *phase* still need real text, not a placeholder.

### 7. Phase 1B vs Phase 4

| Aspect | Phase 1B (this plan) | Phase 4 (future) |
|---|---|---|
| Gate exists? | YES — script + allow-list | (unchanged — same files) |
| Run trigger | manual + on-demand only (`./scripts/check-no-direct-engine-reaches.sh`) | local pre-commit hook + GitHub Actions workflow |
| Allow-list policy | precise per-file × per-pattern, seeded from migration audit | (unchanged — same policy, just enforced harder) |
| Block merge? | NO — informational | YES — CI failure blocks PR |
| Allow-list shrinkage | per-migration commits remove entries; manual discipline | per-migration commits + automation refuses to add new wildcard entries |

The point of having 1B without CI is so the cow killer pilot can fail the gate locally and the author sees the failure even before the migration starts. Phase 4 wires it to PR submit gates so external contributors hit the same gate automatically.

---

## File Structure

**Create (new files):**
- `scripts/check-no-direct-engine-reaches.sh` — the gate (executable bash, ~150 lines)
- `scripts/check-no-direct-engine-reaches.allowlist.tsv` — seed allow-list (header + entries, ~30 lines)
- `scripts/check-no-direct-engine-reaches.README.md` — usage + maintenance guide (~80 lines)
- `scripts/test-check-no-direct-engine-reaches.sh` — bash test harness (~120 lines)

**No Java files touched.** No CLAUDE.md edits (spec §11 numbered rule lands in Phase 4 per spec §19; not 1B).

**Existing `scripts/` directory check:**
- Verified `scripts/` already exists at repo root (per `data/recorder/sync-recorder-data.sh` referenced in CLAUDE.md). New files land in the same dir.

---

## Pre-implementation verification

**Before Task 1, the implementer runs ONE command** to confirm scope and produce the seed set:

```bash
ls runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/*.java | wc -l
# Expected: 15 (the legacy script count from the migration audit §2)
```

If the count differs from 15, the migration audit is stale relative to master — pause and ask before proceeding (a new script may have been added that needs an allow-list strategy).

**This 15-count is a one-time pre-implementation sanity check ONLY.** The gate itself MUST NOT hard-code 15 — new scripts that pass the gate without needing allow-list entries should be permitted forever. The runtime gate scans whatever `.java` files it finds under the scope dir and treats every clean script the same.

---

## Task 1: Gate skeleton + banned-pattern scan (no allow-list yet)

**Files:**
- Create: `scripts/check-no-direct-engine-reaches.sh`

**Goal of this task:** A bare gate that exits 1 if it finds any banned pattern in any script under the target dir, with no allow-list logic yet. The seed scan against the real tree should produce many failures (the legacy scripts use many banned imports); we'll silence the legitimate ones in Task 2/3.

- [ ] **Step 1: Verify scope** — count scripts and compare against migration audit

```bash
COUNT=$(ls runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/*.java 2>/dev/null | wc -l | tr -d ' ')
echo "Scripts in scope: $COUNT (expected 15)"
[ "$COUNT" = "15" ] || echo "WARNING — audit stale, pause and ask"
```

Expected: `Scripts in scope: 15`.

- [ ] **Step 2: Write gate skeleton (banned-set only, no allow-list)**

Create `scripts/check-no-direct-engine-reaches.sh` with mode 0755:

```bash
#!/usr/bin/env bash
# Artemis Phase 1B grep gate.
# Fails when scripts/ contains imports / calls into engine internals banned
# by spec §14, unless the (file, pattern) pair is in the allow-list.
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

violations=0
files_scanned=0

if [ ! -d "$SCRIPTS_DIR" ]; then
    echo "FAIL: scripts dir does not exist: $SCRIPTS_DIR" >&2
    exit 2
fi

while IFS= read -r -d '' file; do
    files_scanned=$((files_scanned + 1))
    for pattern in "${BANNED_PATTERNS[@]}"; do
        # -n prints line number; -E enables extended regex; ! suppresses
        # the script's set -e if no match.
        if hits=$(grep -nE "$pattern" "$file" 2>/dev/null); then
            while IFS= read -r hit; do
                line_num="${hit%%:*}"
                echo "FAIL: $file:$line_num matches banned pattern: $pattern"
                violations=$((violations + 1))
            done <<< "$hits"
        fi
    done
done < <(find "$SCRIPTS_DIR" -name "*.java" -type f -print0)

if [ "$violations" -gt 0 ]; then
    echo "" >&2
    echo "Scanned $files_scanned files. $violations violation(s) found." >&2
    echo "Allow-list integration lands in Task 2 — this skeleton has no exemptions yet." >&2
    exit 1
fi
echo "OK: scanned $files_scanned files, 0 violations, 0 allow-list entries applied."
exit 0
```

- [ ] **Step 3: Make the gate executable**

```bash
chmod +x scripts/check-no-direct-engine-reaches.sh
```

- [ ] **Step 4: Run the gate against the real tree — expect MANY failures**

```bash
./scripts/check-no-direct-engine-reaches.sh; echo "exit=$?"
```

Expected: long FAIL list (estimated 50-100+ hits across the 15 legacy scripts), exit code 1. The migration audit §2 shows the reach concentration; this number is consistent with that profile.

If exit code is 0: the patterns are wrong (false negative) or the scripts dir is empty. Re-check.

- [ ] **Step 5: Capture the baseline violation count for Task 3**

```bash
./scripts/check-no-direct-engine-reaches.sh 2>&1 | grep -c '^FAIL: ' || true
```

Record this number. Task 3's seed allow-list must cover ALL of these unique `(file, pattern)` pairs.

---

## Task 2: Allow-list parser + per-violation exemption

**Files:**
- Modify: `scripts/check-no-direct-engine-reaches.sh`
- Create (empty header for now): `scripts/check-no-direct-engine-reaches.allowlist.tsv`

- [ ] **Step 1: Create the empty allow-list with header + comment block**

Write `scripts/check-no-direct-engine-reaches.allowlist.tsv` with this exact content:

```
# Artemis Phase 1B allow-list — precise per-file × per-pattern legacy exemptions.
# Each entry is a tracked debt: the corresponding migration removes its row.
# Lines starting with # are comments. Blank lines OK. Tabs separate columns.
# NO WILDCARDS — every entry must specify the exact file and the exact banned
# pattern from check-no-direct-engine-reaches.sh's BANNED_PATTERNS array.
#
# Columns:
#   script_path     repo-relative .java path
#   banned_pattern  one of BANNED_PATTERNS verbatim
#   reason          one sentence — what's missing in Artemis
#   removal_phase   tag of the migration that retires this entry
script_path	banned_pattern	reason	removal_phase
```

Note: each separator between column names is a literal TAB.

- [ ] **Step 2: Update the gate to load + apply the allow-list**

Replace the gate's main violation-counter block in `scripts/check-no-direct-engine-reaches.sh` with this allow-list-aware version. Inserted just after the `BANNED_PATTERNS=(...)` array:

```bash
# ── Load allow-list ────────────────────────────────────────────────
# Lines: script_path\tbanned_pattern\treason\tremoval_phase
# Comments + blanks ignored. Validate that banned_pattern is in our
# BANNED_PATTERNS — typos in the allow-list silently exempt nothing,
# which is worse than failing loud.
declare -a ALLOW_FILES
declare -a ALLOW_PATTERNS
allow_count=0

if [ -f "$ALLOWLIST" ]; then
    # Pre-pass 1: column-count validation via awk. `IFS=$'\t' read` will
    # silently absorb trailing tabs into the last variable, so it can't
    # reject 5+ column lines on its own. awk's NF check is authoritative.
    bad_line=$(awk -F '\t' '
        # Skip blank lines, comments, and the header
        /^[[:space:]]*$/ { next }
        /^#/             { next }
        /^script_path\t/ { next }
        NF != 4          { print NR; exit }
    ' "$ALLOWLIST")
    if [ -n "$bad_line" ]; then
        echo "FAIL: allow-list line $bad_line: expected exactly 4 tab-separated columns (file<TAB>pattern<TAB>reason<TAB>phase)" >&2
        exit 2
    fi

    # Pre-pass 2: every banned_pattern column value must appear in
    # BANNED_PATTERNS verbatim. Typos in the allow-list would otherwise
    # silently exempt nothing.
    allow_lineno=0
    while IFS=$'\t' read -r f p r ph; do
        allow_lineno=$((allow_lineno + 1))
        case "$f" in
            '' | \#* | script_path) continue ;;
        esac
        found=0
        for known in "${BANNED_PATTERNS[@]}"; do
            if [ "$p" = "$known" ]; then found=1; break; fi
        done
        if [ "$found" = "0" ]; then
            echo "FAIL: allow-list line $allow_lineno: banned_pattern not in BANNED_PATTERNS: $p" >&2
            exit 2
        fi
        ALLOW_FILES[allow_count]="$f"
        ALLOW_PATTERNS[allow_count]="$p"
        allow_count=$((allow_count + 1))
    done < "$ALLOWLIST"
fi

# ── Scan + apply ───────────────────────────────────────────────────
violations=0
files_scanned=0
allow_applied=0
declare -a ALLOW_HIT
for ((i=0; i<allow_count; i++)); do ALLOW_HIT[i]=0; done

while IFS= read -r -d '' file; do
    files_scanned=$((files_scanned + 1))
    for pattern in "${BANNED_PATTERNS[@]}"; do
        if hits=$(grep -nE "$pattern" "$file" 2>/dev/null); then
            # Is this (file, pattern) in the allow-list?
            permitted=0
            for ((i=0; i<allow_count; i++)); do
                if [ "${ALLOW_FILES[i]}" = "$file" ] && [ "${ALLOW_PATTERNS[i]}" = "$pattern" ]; then
                    permitted=1
                    ALLOW_HIT[i]=1
                    allow_applied=$((allow_applied + 1))
                    break
                fi
            done
            if [ "$permitted" = "1" ]; then continue; fi
            while IFS= read -r hit; do
                line_num="${hit%%:*}"
                echo "FAIL: $file:$line_num matches banned pattern: $pattern"
                echo "      not covered by allow-list"
                echo "      to allow temporarily, add to $ALLOWLIST:"
                echo "      $file	$pattern	<one-sentence-reason>	<phase-that-removes-it>"
                violations=$((violations + 1))
            done <<< "$hits"
        fi
    done
done < <(find "$SCRIPTS_DIR" -name "*.java" -type f -print0)

# ── Orphan check ───────────────────────────────────────────────────
orphans=0
for ((i=0; i<allow_count; i++)); do
    if [ "${ALLOW_HIT[i]}" = "0" ]; then
        if [ "$orphans" = "0" ]; then echo "" >&2; fi
        echo "ORPHAN: allow-list entry never matched: ${ALLOW_FILES[i]}	${ALLOW_PATTERNS[i]}" >&2
        orphans=$((orphans + 1))
    fi
done

# ── Exit ───────────────────────────────────────────────────────────
if [ "$violations" -gt 0 ]; then
    echo "" >&2
    echo "Scanned $files_scanned files. $violations violation(s) found, $allow_applied allow-list exemption(s) applied, $orphans orphan(s)." >&2
    exit 1
fi
if [ "$orphans" -gt 0 ] && [ "$STRICT_ALLOWLIST" = "1" ]; then
    echo "" >&2
    echo "Strict mode: $orphans orphan allow-list entry/entries — clean up." >&2
    exit 3
fi
# Explicit branch — `${orphans:+...}` is not safe here because orphans=0
# is still set, so the suffix would always render.
if [ "$orphans" -gt 0 ]; then
    echo "OK: scanned $files_scanned files, $allow_applied allow-list entries applied, $orphans orphan(s) — non-fatal without STRICT_ALLOWLIST=1."
else
    echo "OK: scanned $files_scanned files, $allow_applied allow-list entries applied."
fi
exit 0
```

Delete the old skeleton-only violation block so there's no double-counting.

- [ ] **Step 3: Run the gate against the real tree with the empty allow-list**

```bash
./scripts/check-no-direct-engine-reaches.sh; echo "exit=$?"
```

Expected: same violation count as Task 1 step 5 (empty allow-list = zero exemptions), exit 1. The new "to allow temporarily, add to..." hint lines should appear.

If the exit isn't 1, the parser is wrong — likely the BANNED_PATTERNS array vs the allow-list parser disagree about pattern strings.

- [ ] **Step 4: Smoke-test malformed allow-list handling (exit 2)**

```bash
# Append a malformed line (only 3 columns) to the allow-list.
echo -e "bad-path\tbad-pattern\tonly-three-cols" >> scripts/check-no-direct-engine-reaches.allowlist.tsv
./scripts/check-no-direct-engine-reaches.sh; echo "exit=$?"
# Expected: prints "missing column(s)", exits 2.

# Cleanup — remove the bad line.
sed -i.bak '$d' scripts/check-no-direct-engine-reaches.allowlist.tsv && rm scripts/check-no-direct-engine-reaches.allowlist.tsv.bak
```

Expected exit: 2.

- [ ] **Step 5: Smoke-test unknown-pattern allow-list handling (exit 2)**

```bash
echo -e "some/file.java\tnot.a.real.pattern\treason\tphase" >> scripts/check-no-direct-engine-reaches.allowlist.tsv
./scripts/check-no-direct-engine-reaches.sh; echo "exit=$?"
# Expected: prints "banned_pattern not in BANNED_PATTERNS", exits 2.
sed -i.bak '$d' scripts/check-no-direct-engine-reaches.allowlist.tsv && rm scripts/check-no-direct-engine-reaches.allowlist.tsv.bak
```

Expected exit: 2.

---

## Task 3: Seed the allow-list from the migration audit

**Files:**
- Modify: `scripts/check-no-direct-engine-reaches.allowlist.tsv`

**Approach:** Run the gate with the empty allow-list, capture the full FAIL output, transform into one allow-list row per unique `(file, pattern)` pair, fill in reason + removal_phase per the migration audit §2 + spec §19 Phase 6 ordering.

- [ ] **Step 1: Capture the unique (file, pattern) pairs the gate flags**

```bash
./scripts/check-no-direct-engine-reaches.sh 2>/dev/null \
  | awk '/^FAIL: / { sub(":[0-9]+", "", $2); print $2 "\t" $7 }' \
  | sort -u > /tmp/phase1b-violations.tsv
wc -l /tmp/phase1b-violations.tsv
head -10 /tmp/phase1b-violations.tsv
```

Expected: ~30-50 unique pairs (the actual number depends on how concentrated the reach is per script; the audit's `disp` + `bank` + `ge` + ... columns sum across 15 scripts ≈ 30-50 unique pairs after dedup since each script typically reaches each layer only once or twice in import declarations).

Discard `/tmp/phase1b-violations.tsv` after Task 3 — it's a scratch file.

- [ ] **Step 2: For each pair, look up reason + removal_phase using this matrix**

| Banned pattern | Reason template | Removal phase template |
|---|---|---|
| `net\.runelite\.client\.sequence\.dispatch\.` | "uses dispatcher direct for click / use-on; v1.x replaces" | "Phase 6.<N> (<ScriptName>V<N+1>)" per Phase 6 order |
| `net\.runelite\.client\.sequence\.internal\.ActionRequest` | "constructs ActionRequest directly; Artemis Steps replace" | same as above |
| `net\.runelite\.client\.plugins\.recorder\.farm\.BankInteraction` | "uses BankInteraction direct; v1.1 bank surface replaces" | "v1.1 Phase 6.<N>" |
| `net\.runelite\.client\.plugins\.recorder\.farm\.GeInteraction` | "uses GeInteraction direct; v1.2 GE surface replaces" | "v1.2 Phase 6.<N>" |
| `net\.runelite\.client\.sequence\.activities\.ge\.` | "uses ge activity classes; v1.2 GE surface replaces" | "v1.2 Phase 6.<N>" |
| `net\.runelite\.client\.plugins\.recorder\.scene\.SceneScanner` | "uses SceneScanner direct; replaced by findNpc/findObject/findItem" | "Phase 6.<N>" |
| `net\.runelite\.client\.plugins\.recorder\.combat\.NpcSelector` | "uses NpcSelector direct; replaced by findNpc(NpcQuery)" | "Phase 6.<N>" |
| `net\.runelite\.client\.plugins\.recorder\.walker\.` / `nav\.` / `trail\.` / `transport\.` | "uses walker subsystem direct; replaced by walkTo" | "Phase 6.<N>" |
| `net\.runelite\.client\.callback\.ClientThread` | "marshals to client thread directly; Artemis handles internally" | "Phase 6.<N>" |
| `^import net\.runelite\.api\.NPC;` / `GameObject;` / `widgets\.Widget;` | "uses raw API type; replaced by NpcRef/GameObjRef/WidgetRef" | "Phase 6.<N>" |
| `\bsampleNearCentroid\b` / `\bclickCanvas\(` | "CLAUDE.md §10 BANNED — replaced by sampleInsideShape (Phase 3)" | "Phase 3 (PixelResolver rewrite)" |
| `\bdispatcher\.dispatch\(` / `\bActionRequest\.builder\(` / `\bnew ActionRequest\(` | "constructs dispatcher payload directly; Artemis Steps replace" | "Phase 6.<N>" |
| `\bnew NpcSelector\(` | "constructs NpcSelector directly; replaced by findNpc(NpcQuery)" | "Phase 6.<N>" |

**Phase 6 ordering** (spec §19, reconciled):
1. LumbridgeBankPenScript (v1.1; moved earlier — §10 risk)
2. RooftopAgilityScript (v1.0; obstacleKeyConfirm Step lands here)
3. PieDishScript (v1.1)
4. PizzaScript (v1.1 + confirmMakeAll)
5. FletchingScript (v1.1 + confirmMakeAll + findWidget hotspot)
6. CookingScriptV4 (v1.1 + confirmMakeAll; deletes V2 + V3)
7. UltraCompostScript (v1.2)
8. CooksAssistantScript (v1.2)
9. GrandExchangeScript (v1.2)
10. Quest sub-tree (separate from Phase 1B scope)

`ChickenFarmV3Script` migrates as part of Phase 5 (ChickenFarmV4), not Phase 6.

- [ ] **Step 3: Append one allow-list row per unique (file, pattern) pair**

Edit `scripts/check-no-direct-engine-reaches.allowlist.tsv`. For each row in `/tmp/phase1b-violations.tsv`, append `<file>\t<pattern>\t<reason>\t<phase>` using the matrix above for reason + phase.

Expected final file: header + ~30-50 entries.

Two illustrative entries (the implementer fills in the rest):

```
runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/CookingScriptV3.java	net\.runelite\.client\.sequence\.dispatch\.	uses dispatcher direct for fire / use-on; v1.1 confirmMakeAll replaces these reaches	Phase 6.6 (CookingScriptV4)
runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/CookingScriptV3.java	net\.runelite\.client\.plugins\.recorder\.farm\.BankInteraction	uses BankInteraction direct; v1.1 bank surface replaces 11 callsites	v1.1 Phase 6.6 (CookingScriptV4)
```

- [ ] **Step 4: Run the gate against the seeded allow-list — expect green**

```bash
./scripts/check-no-direct-engine-reaches.sh; echo "exit=$?"
```

Expected: `OK: scanned 15 files, <N> allow-list entries applied.`, exit code 0. No orphans.

If exit code is non-zero:
- Code 1: missing allow-list entries — append the missing rows.
- Code 2: malformed allow-list — fix the malformed row.
- Code 3 (with `STRICT_ALLOWLIST=1`): orphans — remove stale allow-list rows.

---

## Task 4: Tests — prove fail-loud + pass-clean without leaving dirty files

**Files:**
- Create: `scripts/test-check-no-direct-engine-reaches.sh`

This is a bash test harness, not a JUnit suite. Each test sets up a temp dir via `mktemp -d`, writes fixture files, runs the gate, asserts exit code + output, cleans up via `trap`.

- [ ] **Step 1: Write the test harness**

Create `scripts/test-check-no-direct-engine-reaches.sh` with mode 0755:

```bash
#!/usr/bin/env bash
# Test harness for check-no-direct-engine-reaches.sh.
# All temp state lives under $TMP and is cleaned up via trap.
set -uo pipefail   # NOT -e — tests must observe non-zero exits

GATE="$(dirname "$0")/check-no-direct-engine-reaches.sh"
TMP="$(mktemp -d -t artemis-phase1b-test.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT

passes=0
failures=0

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

# ── Test 1: Empty scripts dir → exit 0 (no scripts = no violations) ──
echo "Test 1: empty scripts dir → exit 0"
mkdir -p "$TMP/empty"
echo -e "script_path\tbanned_pattern\treason\tremoval_phase" > "$TMP/empty-allowlist.tsv"
ALLOWLIST="$TMP/empty-allowlist.tsv" "$GATE" "$TMP/empty" >/dev/null 2>&1
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
ALLOWLIST="$TMP/empty-allowlist.tsv" "$GATE" "$TMP/clean" >/dev/null 2>&1
assert_exit 0 $? "clean script with only Artemis imports"

# ── Test 3: Dirty script (banned import, no allow-list) → exit 1 ──
echo "Test 3: dirty script (banned import) → exit 1"
mkdir -p "$TMP/dirty"
cat > "$TMP/dirty/BadScript.java" <<'JAVA'
package net.runelite.client.plugins.recorder.scripts;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
public class BadScript { HumanizedInputDispatcher dispatcher; }
JAVA
ALLOWLIST="$TMP/empty-allowlist.tsv" "$GATE" "$TMP/dirty" >/dev/null 2>&1
assert_exit 1 $? "dirty script not in allow-list"

# ── Test 4: Dirty script + matching allow-list entry → exit 0 ──
# Write the allow-list with a literal tab between every column. No sed.
echo "Test 4: dirty script + allow-list match → exit 0"
TAB=$'\t'
{
    printf 'script_path%sbanned_pattern%sreason%sremoval_phase\n' "$TAB" "$TAB" "$TAB"
    printf '%s%s%s%s%s%s%s\n' \
        "$TMP/dirty/BadScript.java" "$TAB" \
        'net\.runelite\.client\.sequence\.dispatch\.' "$TAB" \
        "test reason" "$TAB" \
        "test phase"
} > "$TMP/match-allowlist.tsv"
ALLOWLIST="$TMP/match-allowlist.tsv" "$GATE" "$TMP/dirty" >/dev/null 2>&1
assert_exit 0 $? "dirty script covered by allow-list"

# ── Test 5: Malformed allow-list (3 columns) → exit 2 ──
echo "Test 5: malformed allow-list → exit 2"
printf 'bad%sonly%sthree\n' "$TAB" "$TAB" > "$TMP/malformed-allowlist.tsv"
ALLOWLIST="$TMP/malformed-allowlist.tsv" "$GATE" "$TMP/clean" >/dev/null 2>&1
assert_exit 2 $? "malformed allow-list (missing columns)"

# ── Test 5b: Malformed allow-list (5 columns) → exit 2 ──
echo "Test 5b: allow-list with too many columns → exit 2"
{
    printf 'script_path%sbanned_pattern%sreason%sremoval_phase\n' "$TAB" "$TAB" "$TAB"
    printf 'a%sb%sc%sd%sEXTRA\n' "$TAB" "$TAB" "$TAB" "$TAB"
} > "$TMP/too-many-cols.tsv"
ALLOWLIST="$TMP/too-many-cols.tsv" "$GATE" "$TMP/clean" >/dev/null 2>&1
assert_exit 2 $? "allow-list with 5 columns (caught by awk NF check)"

# ── Test 6: Allow-list pattern not in BANNED_PATTERNS → exit 2 ──
echo "Test 6: allow-list with unknown pattern → exit 2"
{
    printf 'script_path%sbanned_pattern%sreason%sremoval_phase\n' "$TAB" "$TAB" "$TAB"
    printf 'x%snot.a.pattern%sreason%sphase\n' "$TAB" "$TAB" "$TAB"
} > "$TMP/unknown-pattern.tsv"
ALLOWLIST="$TMP/unknown-pattern.tsv" "$GATE" "$TMP/clean" >/dev/null 2>&1
assert_exit 2 $? "allow-list pattern not in BANNED_PATTERNS"

# ── Test 7: Orphan allow-list entry → exit 0 (warning), exit 3 with --strict ──
echo "Test 7: orphan allow-list entry"
{
    printf 'script_path%sbanned_pattern%sreason%sremoval_phase\n' "$TAB" "$TAB" "$TAB"
    printf '%s%s%s%s%s%s%s\n' \
        "$TMP/nothing/here.java" "$TAB" \
        'net\.runelite\.client\.sequence\.dispatch\.' "$TAB" \
        "stale" "$TAB" \
        "phase"
} > "$TMP/orphan.tsv"
ALLOWLIST="$TMP/orphan.tsv" "$GATE" "$TMP/clean" >/dev/null 2>&1
assert_exit 0 $? "orphan allow-list (non-strict, exit 0)"
ALLOWLIST="$TMP/orphan.tsv" STRICT_ALLOWLIST=1 "$GATE" "$TMP/clean" >/dev/null 2>&1
assert_exit 3 $? "orphan allow-list (--strict-allowlist, exit 3)"

# ── Test 8: Real tree — should pass with seeded allow-list ──
echo "Test 8: real tree (master) with seeded allow-list → exit 0"
"$GATE" >/dev/null 2>&1
assert_exit 0 $? "real recorder/scripts/ with seeded allow-list"

# ── Summary ──
echo ""
echo "Total: $((passes + failures)). Passed: $passes. Failed: $failures."
[ "$failures" = "0" ] || exit 1
exit 0
```

- [ ] **Step 2: Make it executable and run**

```bash
chmod +x scripts/test-check-no-direct-engine-reaches.sh
./scripts/test-check-no-direct-engine-reaches.sh
```

Expected output:
```
Test 1: empty scripts dir → exit 0
  PASS: empty scripts dir (exit=0)
Test 2: clean script with only Artemis imports → exit 0
  PASS: clean script with only Artemis imports (exit=0)
... (all 10 assertions including the 5b extra-column case)
Total: 10. Passed: 10. Failed: 0.
```

Exit code: 0.

- [ ] **Step 3: Confirm no leftover temp dirs**

```bash
ls /tmp/artemis-phase1b-test.* 2>/dev/null && echo "LEAK" || echo "clean"
```

Expected: `clean` (the trap removed them).

---

## Task 5: README + final verification

**Files:**
- Create: `scripts/check-no-direct-engine-reaches.README.md`

- [ ] **Step 1: Write the README**

Create `scripts/check-no-direct-engine-reaches.README.md`:

```markdown
# Artemis Phase 1B grep gate

Prevents `recorder/scripts/**/*.java` from reaching into engine internals
banned by spec §14 (`docs/superpowers/specs/2026-05-23-artemis-design.md`).

## Run

    ./scripts/check-no-direct-engine-reaches.sh

Exits 0 if clean; 1 if a script imports / calls a banned target that is not
in the allow-list. The allow-list itself lives at
`scripts/check-no-direct-engine-reaches.allowlist.tsv`.

## Adding a new script

If the new script ONLY uses Artemis: no allow-list edits needed. Run the gate
to confirm.

If the new script needs a banned import / call: **think first**. Almost
always the right answer is to extend Artemis, not to grant an allow-list
exemption. The allow-list is for *legacy code in flight*; new code that
needs an exemption is admitting Artemis is incomplete.

If you must add an exemption: one row per `(file, pattern)` pair. Fill all
four columns. The `removal_phase` must be a concrete tag — "TBD" is not
acceptable.

## Removing a legacy entry (per Phase 6 migration)

Each Phase 6 migration commit must remove the allow-list rows for the file
being migrated. Run the gate before AND after the migration; before should
fail with orphans under `STRICT_ALLOWLIST=1` if the rows aren't removed.

## Exit codes

- 0 — clean
- 1 — banned-pattern hit not covered by allow-list
- 2 — malformed allow-list
- 3 — orphan allow-list entry (only fails under `STRICT_ALLOWLIST=1`)

## Phase 4 integration (not yet wired)

Phase 4 adds a `.git/hooks/pre-commit` invocation + a GitHub Actions
workflow at `.github/workflows/check-engine-reaches.yml`. Until then, the
gate is manual + on-demand only.

## Maintenance notes

- The `BANNED_PATTERNS` array in the gate is the single source of truth.
  Adding a banned target = update the array + add allow-list entries for
  any existing scripts that match (the gate's exit-1 hint shows the
  paste-able row text).
- Patterns are extended regex (`grep -E`). Escape `.` and `(` properly.
- The allow-list parser validates that every `banned_pattern` column value
  matches a `BANNED_PATTERNS` entry verbatim — typos in the allow-list
  silently exempt nothing, so they fail with exit 2.
```

- [ ] **Step 2: Final verification — run everything end-to-end**

```bash
# Gate against real tree.
./scripts/check-no-direct-engine-reaches.sh
echo "---"
# Tests.
./scripts/test-check-no-direct-engine-reaches.sh
echo "---"
# Confirm git status — only the 4 new files.
git status --short scripts/
```

Expected:
- Gate exits 0 (`OK: scanned 15 files, <N> allow-list entries applied.`).
- Test harness exits 0 (all 10 assertions pass).
- `git status` shows exactly:
  - `?? scripts/check-no-direct-engine-reaches.sh`
  - `?? scripts/check-no-direct-engine-reaches.allowlist.tsv`
  - `?? scripts/check-no-direct-engine-reaches.README.md`
  - `?? scripts/test-check-no-direct-engine-reaches.sh`

If any unexpected file appears, stop and investigate.

---

## Task 6: Structured final report — STOP before commit

**DO NOT commit until the operator approves this report.** The plan ends at "report assembled + operator green-lights commit"; nothing past this checkbox runs without their say-so.

- [ ] **Step 1: Assemble the structured report**

Produce a single response containing exactly:

1. **Total scripts scanned** — the count the gate printed.
2. **Total unique allow-list rows** — `wc -l < scripts/check-no-direct-engine-reaches.allowlist.tsv` minus header/comments.
3. **Grouped allow-list summary** — one block per script file, listing the patterns allow-listed for it and the removal phase, e.g.:
   ```
   CookingScriptV3.java →
     • dispatch.* → Phase 6.6 (CookingScriptV4)
     • BankInteraction → v1.1 Phase 6.6 (CookingScriptV4)
     • SequenceSleep (covered separately)
   ```
   This catches over-broad allow-listing at a glance — if any file has more patterns allow-listed than the migration audit §2 row claims, stop and investigate.
4. **Gate result on real tree** — exit code + summary line.
5. **Test harness result** — pass count + exit code.
6. **Working tree status** — `git status --short`, exactly 5 expected entries (4 script files + plan doc).

The grouped allow-list summary is the load-bearing item. The operator reads it as the audit before approving the commit.

- [ ] **Step 2: STOP**

Do not stage, do not commit, do not push. Wait for the operator's "approved, commit" before proceeding to Task 7.

---

## Task 7: Single commit (only after Task 6 approval)

```bash
git add \
  scripts/check-no-direct-engine-reaches.sh \
  scripts/check-no-direct-engine-reaches.allowlist.tsv \
  scripts/check-no-direct-engine-reaches.README.md \
  scripts/test-check-no-direct-engine-reaches.sh \
  docs/superpowers/plans/2026-05-24-artemis-phase-1b-grep-gate.md

git commit -m "$(cat <<'EOF'
feat(artemis): Phase 1B — grep/import enforcement gate (manual + on-demand)

Ships the script-side guard that prevents recorder/scripts/**/*.java from
reaching into engine internals banned by spec §14. Phase 4 will wire this
into pre-commit + CI; until then, run manually:

    ./scripts/check-no-direct-engine-reaches.sh

Files:
- scripts/check-no-direct-engine-reaches.sh — gate (bash + grep)
- scripts/check-no-direct-engine-reaches.allowlist.tsv — precise per-file
  × per-pattern legacy exemptions, seeded from migration audit §2
- scripts/check-no-direct-engine-reaches.README.md — usage + maintenance
- scripts/test-check-no-direct-engine-reaches.sh — 9-assertion test
  harness (mktemp + trap; no leftover temp files)

Allow-list discipline:
- NO wildcard exemptions; each row is (file, pattern, reason, phase)
- New script needing a banned target = extend Artemis, not add an
  exemption
- Phase 6 migrations remove the rows for the file they migrate;
  STRICT_ALLOWLIST=1 turns orphans into hard failures

Exit codes: 0 clean, 1 banned-pattern uncovered, 2 malformed allow-list,
3 orphan entry under STRICT_ALLOWLIST.

Plan: docs/superpowers/plans/2026-05-24-artemis-phase-1b-grep-gate.md
Next: Phase 2 cow killer pilot per spec §19 §15 (LUMBRIDGE_COW_FIELD
tile population folds into the pilot plan, not 1B).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"

git log -1 --format='%H%n%s'
git status
```

Expected: clean commit, working tree clean.

---

## Self-review checklist

- [ ] **Spec §14 coverage** — every banned import category in §14 has a corresponding `BANNED_PATTERNS` entry:
  - `dispatch.*` ✓ (`net\.runelite\.client\.sequence\.dispatch\.`)
  - `internal.ActionRequest` ✓
  - `activities.*` (BankActions, GeActions) — covered by `activities\.ge\.` + `activities\.banking\.`
  - `recorder/farm/BankInteraction` ✓
  - `recorder/farm/GeInteraction` ✓ (audit's `GrandExchangeScript` is the dominant user)
  - `recorder/scene/SceneScanner` ✓
  - `recorder/combat/NpcSelector` ✓ (`ChickenCombatLoop` + `TrainingSession` NOT banned per §14 allow-list)
  - `recorder/widget/WidgetActions` ✓
  - `recorder/walker/*` + `nav/*` + `trail/*` + `transport/*` ✓
  - `ClientThread` ✓
  - `api.NPC` / `api.GameObject` / `api.widgets.Widget` (raw types) ✓ (gated on `^import` to avoid false positives on inline type usage)
  - `java.awt.Robot` + `java.awt.event.MouseEvent` ✓
  - CLAUDE.md §10 leftovers (`sampleNearCentroid`, `clickCanvas(`) ✓ (Phase 3 retires)
  - Call patterns: `dispatcher.dispatch(`, `ActionRequest.builder(`, `new ActionRequest(`, `new NpcSelector(` ✓
- [ ] **Allow-list precision** — every row has 4 columns; no wildcard rows; orphan detection in place.
- [ ] **Test coverage** — 9 assertions: empty dir, clean script, dirty no-allow, dirty with-allow, malformed allow-list (exit 2), unknown pattern (exit 2), orphan non-strict (exit 0), orphan strict (exit 3), real tree.
- [ ] **No dirty files** — every test uses `mktemp -d`, `trap rm -rf` on EXIT, final `ls /tmp/artemis-phase1b-test.*` check.
- [ ] **Phase 1B vs Phase 4** — README documents the boundary; this plan does NOT touch `.git/hooks/`, `.github/workflows/`, or CLAUDE.md.
- [ ] **Quest sub-tree** — out of scope per spec; noted at the top of the plan; not in tests.
- [ ] **LUMBRIDGE_COW_FIELD** — explicitly out of scope; flagged as Phase 2 pilot work.
- [ ] **Compact** — within `feedback_compact_plans` budget (target 300-700; this is ~600).

---

## Carry-forwards (post-1B)

- **Phase 4** wires the gate to pre-commit + CI. The `.github/workflows/check-engine-reaches.yml` design lands then.
- **Phase 6 migrations** remove allow-list rows incrementally. Each migration's commit must shrink the allow-list.
- **Quest sub-tree gating** — if quest/ migration produces script-like bypass patterns, consider extending `SCRIPTS_DIR` to cover `recorder/quest/` too. Decide in Phase 6 item 10.
- **Allow-list orphan detection in CI** — Phase 4 might flip `STRICT_ALLOWLIST` to default-on, forcing migrations to clean up entries promptly.
