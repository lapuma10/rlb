/*
 * Copyright (c) 2024, RuneLite
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
package net.runelite.client.sequence;

import javax.annotation.Nullable;
import net.runelite.client.sequence.views.GrandExchangeView;
import net.runelite.client.sequence.views.InteractionView;
import net.runelite.client.sequence.views.InventoryView;

public interface WorldSnapshot {
    int tick();
    @Nullable PlayerView player();

    /** Inventory view. Default returns {@link InventoryView#empty()} so
     *  existing {@code WorldSnapshot} implementations compile unchanged. */
    default InventoryView inventory()       { return InventoryView.empty(); }

    /** Interaction view (mode + blocking interface). Default returns
     *  {@link InteractionView#empty()}. */
    default InteractionView interaction()   { return InteractionView.empty(); }

    /** Grand Exchange view (slots + interface state). Default returns
     *  {@link GrandExchangeView#empty()}. */
    default GrandExchangeView grandExchange() { return GrandExchangeView.empty(); }
}
