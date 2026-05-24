# Artemis Phase 1B grep gate

Prevents `recorder/scripts/**/*.java` from reaching into engine internals
banned by spec §14 (`docs/superpowers/specs/2026-05-23-artemis-design.md`).

## Run

    ./scripts/check-no-direct-engine-reaches.sh

Exits 0 if clean; 1 if a script imports / calls a banned target that is not
in the allow-list. The allow-list itself lives at
`scripts/check-no-direct-engine-reaches.allowlist.tsv`.

## Adding a new script

If the new script ONLY uses Artemis: no allow-list edits needed. Run the
gate to confirm.

If the new script needs a banned import / call: **think first**. Almost
always the right answer is to extend Artemis, not to grant an allow-list
exemption. The allow-list is for *legacy code in flight*; new code that
needs an exemption is admitting Artemis is incomplete.

If you must add an exemption: one row per `(file, pattern)` pair. Fill all
four columns. The `removal_phase` must be a concrete tag — "TBD" is not
acceptable.

## Removing a legacy entry (per Phase 6 migration)

Each Phase 6 migration commit must remove the allow-list rows for the file
being migrated. Run the gate before AND after the migration; under
`STRICT_ALLOWLIST=1` the gate fails with exit 3 if the migration left
orphan rows behind.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | clean (or only allow-listed hits) |
| 1 | banned-pattern hit not covered by allow-list |
| 2 | malformed allow-list (wrong column count, unknown pattern) |
| 3 | orphan allow-list entry (only fails under `STRICT_ALLOWLIST=1`) |

## Allow-list format

Tab-separated, exactly 5 columns. Header line required. Lines starting
with `#` are comments. Blank lines OK.

```
script_path<TAB>banned_pattern<TAB>allowed_count<TAB>reason<TAB>removal_phase
```

- **`script_path`** — repo-relative path to the `.java` file.
- **`banned_pattern`** — one of the patterns in the gate's
  `BANNED_PATTERNS` array, byte-for-byte (escapes included). Typos fail
  with exit 2 — the gate validates that each `banned_pattern` value
  matches a known pattern.
- **`allowed_count`** — positive integer (≥1); the maximum number of
  matching lines for this `(file, pattern)` pair that the gate will
  tolerate today. The gate counts actual hits with `grep -cE` and
  fails (exit 1) when `actual_count > allowed_count` — so adding a
  new callsite to an already-allow-listed file fails immediately
  even though the file is "on the list."
- **`reason`** — one sentence explaining what's missing in Artemis that
  forces this exemption.
- **`removal_phase`** — the migration tag that retires the row, e.g.
  `Phase 6.6 (CookingScriptV4)` or `Phase 3 (PixelResolver §10 rewrite)`.

The gate's awk pre-pass enforces exactly 5 columns. A 4-column or
6-column row fails with exit 2. `allowed_count` is validated as a
positive integer (`^[1-9][0-9]*$`); empty / non-numeric / zero /
negative values fail with exit 2.

**Why count-bounded:** without `allowed_count`, the gate would allow
*unlimited* new callsites of an already-permitted pattern inside an
already-permitted file — defeating the goal that "new bypasses fail
immediately." With `allowed_count` set to today's actual hit count,
adding a new callsite tomorrow pushes `actual_count` over budget and
fails the gate.

**Shrinking a count:** if a migration removes some callsites but
not all, the entry can be edited to lower `allowed_count` to the new
actual. Optional discipline — `actual_count < allowed_count` is a
PASS, not an orphan, so under-budget entries stay green.

## Phase 1B vs Phase 4 (not yet wired)

|  | Phase 1B (this) | Phase 4 (future) |
|---|---|---|
| Gate exists | ✓ | (unchanged) |
| Run trigger | manual + on-demand | pre-commit hook + GitHub Actions workflow |
| Block merge | no — informational only | yes — CI failure blocks PR |
| Allow-list policy | precise per-file × per-pattern | (unchanged) |

Phase 4 lands `.git/hooks/pre-commit` integration and
`.github/workflows/check-engine-reaches.yml`. Until then, the gate is
manual + on-demand only.

## Maintenance notes

- The `BANNED_PATTERNS` array in the gate is the single source of truth.
  Adding a banned target = update the array + add allow-list entries for
  any existing scripts that match (the gate's exit-1 hint shows the
  paste-able row text).
- Patterns are extended regex (`grep -E`). Escape `.` and `(` properly.
- The allow-list parser validates that every `banned_pattern` column
  value matches a `BANNED_PATTERNS` entry verbatim — typos in the
  allow-list silently exempt nothing, so they fail with exit 2.
- The pre-implementation 15-script count is a one-time sanity check only.
  The gate scans whatever `.java` files it finds under the scope dir —
  new scripts pass as long as they don't hit banned patterns.

## Scope

`runelite-client/src/main/java/net/runelite/client/plugins/recorder/scripts/**/*.java`
only. The `recorder/quest/` sub-tree is not gated by Phase 1B; that's a
Phase 6 item 10 / Phase 4 widening question.

## Tests

    ./scripts/test-check-no-direct-engine-reaches.sh

15 assertions covering: empty / clean / dirty paths, exact-count match,
under-budget count, **over-budget count (the new-callsite case)**,
malformed allow-lists (4-col + 6-col + zero + non-integer + negative),
unknown pattern in allow-list, orphan entries (strict + non-strict),
and the real tree. All temp state lives under a `mktemp -d` that gets
cleaned by a trap on exit.
