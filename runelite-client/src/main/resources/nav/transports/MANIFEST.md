# Skretzo transport TSVs — manifest

These TSVs are vendored from the open-source RuneLite plugin
[shortest-path](https://github.com/Skretzo/shortest-path) by Skretzo,
under BSD-2-Clause. See repo-root `NOTICES.md` for the full license
text and attribution.

## Source

| Field   | Value                                                           |
|---------|-----------------------------------------------------------------|
| Repo    | `https://github.com/Skretzo/shortest-path`                      |
| Branch  | `master`                                                        |
| Commit  | `d3b9b0f7e76fb52c76c7ef03c52ebac18812a82c`                      |
| Date    | `2026-05-14T01:29:26Z`                                          |
| Origin  | `src/main/resources/transports/*.tsv`                           |

## Bundled files (SHA-256)

| File                        | SHA-256                                                          |
|-----------------------------|------------------------------------------------------------------|
| `agility_shortcuts.tsv`     | `59d68025bdc64cf2156dfdc3881e00194785b1853cfaa272d9a8835031219160` |
| `fairy_rings.tsv`           | `3d4133b8ad9b10709775678f0a8cf3c2a6f77b69eebd904ef24a64af655ae8e1` |
| `spirit_trees.tsv`          | `055aca3e72c17095861c05190ad5cd76e0156f27dd30b1ee21b33408058a7bc8` |
| `teleportation_items.tsv`   | `883c87196ffc0ef73ab1ee56a445c959cf51cf68cbabdb777051bf1d522af8af` |
| `teleportation_spells.tsv`  | `6c5874165a1f8b43f8de34b62eae75f2ea0c98173689e7e392ac7a8724d6b26e` |
| `transports.tsv`            | `d3fb702c533cbeb572749e620ebfc54c9a7379bfc9a9cb6d86c8893d41daf3c9` |

## Format

Tab-separated. The first line of each file is a `#`-prefixed header
that names the columns. Blank-field columns are still tab-separated
(no `\t` is collapsed). Comment rows begin with `#` and are skipped at
load time. Column layout varies per file; the loader inspects the
header row to map fields.

### `transports.tsv` (main: doors / gates / stairs / ladders)

```
# Origin  Destination  menuOption menuTarget objectID  Skills  Items  Quests  Varbits  VarPlayers  Duration  Display info
```

### `agility_shortcuts.tsv`

```
# Origin  Destination  menuOption menuTarget objectID  Skills  Items  Varbits  VarPlayers  Duration
```

### `fairy_rings.tsv` / `spirit_trees.tsv`

Destination column is empty for the "configure" rows — the transport
network nodes are the rings/trees themselves; destinations are picked
at use time.

### `teleportation_items.tsv` / `teleportation_spells.tsv`

Origin column is implicit (anywhere). `Destination` and `Items` /
`Skills` are present.

## Refresh procedure

1. Download the upstream TSVs from the Skretzo repo at a chosen
   commit SHA.
2. Diff against the current vendored copies to surface intentional
   changes vs. accidental ones.
3. Recompute SHA-256 and update this MANIFEST.
4. Re-run `:client:test --tests "*nav.v2.transport.*"` — the loader
   tests cover parse-stability across schema variations.
5. If `TransportTable`'s startup `Invalid: Y` count grows beyond a
   handful of known-bad rows in the upstream, surface the regressed
   rows in the manifest and open an issue upstream.
