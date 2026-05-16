# NOTICES

This file lists third-party software bundled into this repository with attribution
and license information.

## Skretzo — shortest-path RuneLite plugin

We vendor the global tile collision snapshot and the transport-link
TSV data from the open-source RuneLite shortest-path plugin by
Skretzo.

| Field            | Value                                                                     |
|------------------|---------------------------------------------------------------------------|
| Project          | shortest-path                                                             |
| Author           | Skretzo                                                                   |
| Repository       | https://github.com/Skretzo/shortest-path                                  |
| License          | BSD-2-Clause                                                              |
| Bundled (Lane 2) | `runelite-client/src/main/resources/nav/collision/collision-map.zip`      |
| Bundled (Lane 4) | `runelite-client/src/main/resources/nav/transports/*.tsv`                 |
| Manifests        | `runelite-client/src/main/resources/nav/collision/MANIFEST.md`            |
|                  | `runelite-client/src/main/resources/nav/transports/MANIFEST.md`           |

Note: spec §9 originally proposed `runelite/nav/...` paths, but the
`runelite-gradle-plugin`'s `IndexTask` iterates `runelite/*` and
`parseInt`s every subdirectory name. Top-level `/nav/` resource paths
avoid that build-task conflict.

Refresh procedure: see the per-resource MANIFEST files.

### BSD-2-Clause license (Skretzo)

```
Copyright (c) Skretzo and contributors
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```
