#!/usr/bin/env bash
#
# sync-recorder-data.sh — install committed recorder data into ~/.runelite/recorder/
#
# Run this ONCE on a fresh PC after `git clone`, or any time you `git pull`
# new trail/rooftop/world data. Safe to re-run: account-scoped files
# (per-character buy-limits, training-plans) are seeded only when absent;
# pure-data subtrees (trails, rooftops, worldmap) are mirrored wholesale.
#
# Direction of sync is ALWAYS repo → ~/.runelite. Use git to move data the
# other way: capture/record on PC A, `git add data/recorder/...`, push,
# `git pull` on PC B, then re-run this script.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
SRC="${REPO_ROOT}/data/recorder"
DST="${HOME}/.runelite/recorder"

if [[ ! -d "${SRC}" ]]; then
  echo "error: ${SRC} not found — are you in the rlb repo?" >&2
  exit 1
fi

mkdir -p "${DST}"

# --- Pure-data subtrees (mirrored wholesale; new commits overwrite) ---
for dir in trails rooftops worldmap; do
  if [[ -d "${SRC}/${dir}" ]]; then
    mkdir -p "${DST}/${dir}"
    # cp -R src/. dst/ copies *contents* of src into dst; safer than cp -R src dst
    # which would create dst/src nesting when dst already exists.
    cp -R "${SRC}/${dir}/." "${DST}/${dir}/"
    count=$(find "${SRC}/${dir}" -type f | wc -l | tr -d ' ')
    echo "mirrored:  ${dir}  (${count} files)"
  fi
done

# --- Account-scoped seeds (only copied when target is absent) ---
seed_if_absent() {
  local rel="$1"
  local src="${SRC}/${rel}"
  local dst="${DST}/${rel}"
  if [[ -e "${src}" && ! -e "${dst}" ]]; then
    mkdir -p "$(dirname "${dst}")"
    cp "${src}" "${dst}"
    echo "seeded:    ${rel}"
  fi
}

seed_if_absent "buy-limits/default.json"
seed_if_absent "training-plans/default.properties"

echo ""
echo "done. recorder data at ${DST}"
