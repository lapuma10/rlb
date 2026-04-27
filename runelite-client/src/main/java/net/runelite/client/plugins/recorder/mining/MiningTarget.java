/*
 * Copyright (c) 2026, RuneLite
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.recorder.mining;

import net.runelite.api.coords.WorldPoint;
import javax.annotation.Nullable;

/**
 * Immutable handle to the rock the mining loop is currently locked onto.
 *
 * <p>Identity is the world tile of the rock — object ids change when the
 * engine swaps full↔depleted variants on the same tile, so an id alone is
 * unreliable. The loop re-reads the live object id from the tile on every
 * poll; the {@code gameObjectId} field below is just the snapshot taken at
 * lock time, used for diagnostics and for detecting "rock id flipped this
 * tick" via {@link MiningStateTracker}.
 *
 * <p>{@code oreType} is metadata-only — we don't change behaviour based on
 * it (clicking "Mine" works the same on every rock). It's recorded so the
 * panel can label the kill counter and so future banking strategies can
 * key drop logic to the expected ore item id.
 *
 * @param gameObjectId object id observed on the tile when the lock was acquired.
 * @param tile         world tile of the rock — primary identity.
 * @param oreType      classification of the rock's ore. May be null when the
 *                     loop hasn't been told (e.g., user added a tile from a
 *                     mixed-ore mine).
 * @param lockTick     engine tick at lock-acquisition — caps "stuck waiting
 *                     for animation" timeouts.
 */
public record MiningTarget(int gameObjectId, WorldPoint tile,
                           @Nullable OreType oreType, int lockTick)
{
}
