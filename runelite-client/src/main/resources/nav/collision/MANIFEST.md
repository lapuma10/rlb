# Skretzo Collision Snapshot — Manifest

This directory bundles the global-world tile collision snapshot vendored from
the open-source RuneLite shortest-path plugin by Skretzo.

| Field          | Value                                                                |
|----------------|----------------------------------------------------------------------|
| Source repo    | https://github.com/Skretzo/shortest-path                             |
| Source path    | `src/main/resources/collision-map.zip`                               |
| Source ref     | `master` @ `d3b9b0f7e76fb52c76c7ef03c52ebac18812a82c` (2026-05-14)   |
| File           | `collision-map.zip`                                                  |
| Size           | 1164284 bytes                                                        |
| SHA256         | `b08f3a558ca270d48f0b2c2ba394be3cf6a71ef67e593cbc7d89850b5667067b`   |
| License        | BSD-2-Clause                                                         |
| Pulled         | 2026-05-16                                                           |

## Format

The zip contains one file per OSRS region. Each entry is named `REGIONX_REGIONY`
(e.g. `50_94`). The bytes are a Skretzo `FlagMap` encoding: two boolean flags
per tile per plane (flag 0 = "can move north", flag 1 = "can move east"). South
and west are derived from the neighbour's north/east bits.

Total flag bits per tile = 2 × 4 planes = 8 (but planeCount is per region;
sparse regions may store fewer planes).

`GlobalCollisionSnapshot` translates Skretzo's two-bit format into RuneLite's
`CollisionDataFlag` bitfield at query time:

- Skretzo flag 0 OFF (n=false) ⇒ `BLOCK_MOVEMENT_NORTH` ON in the returned int.
- Skretzo flag 1 OFF (e=false) ⇒ `BLOCK_MOVEMENT_EAST` ON.
- Symmetric derivation for S/W from the neighbour's bits.
- Diagonal blocks are computed from the four cardinal directions per
  Skretzo's `ne/nw/se/sw` formulas.

## Refresh procedure

When syncing a newer Skretzo snapshot:

1. Download `src/main/resources/collision-map.zip` from the commit you choose.
2. Replace `collision-map.zip` in this directory.
3. Update this manifest with the new SHA256, commit ref, and pulled date.
4. Run the Lane 2 collision tests:
   ```
   ./gradlew :client:test --tests "*nav.v2.collision.*"
   ```
5. Run Lane 6 acceptance harness if available.
