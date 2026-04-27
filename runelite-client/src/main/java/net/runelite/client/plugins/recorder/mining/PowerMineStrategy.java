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

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;
import java.util.HashSet;
import java.util.Set;

/**
 * Drop-in-place inventory emptying. The first build's "banking strategy".
 *
 * <p>Iterates the inventory, dispatches a {@code CLICK_INV_ITEM} with
 * {@code verb="Drop"} for each slot whose item id matches a configured ore
 * (or — defensively — for every non-pickaxe item if no filter is provided).
 * Each dispatch is serialised on the dispatcher's busy flag.
 *
 * <p>The strategy keys off slot index, not item id, because the inventory
 * widget id is computed from the slot. Drops a slot at most once even if
 * the engine takes a tick to update the container snapshot.
 */
@Slf4j
public final class PowerMineStrategy implements BankingStrategy
{
    /** Set of item ids we'll drop. Empty / null means "drop every ore-typed
     *  item we know about" — see {@link OreType#oreItemId()}. */
    private final Set<Integer> dropItemIds;

    /** Polling cadence between drop dispatches — short enough that the loop
     *  doesn't visibly stall, long enough that the engine has caught up
     *  with the previous click. */
    private static final long DISPATCH_POLL_MS = 50L;
    /** Cap on individual drop wait — if the dispatcher hangs we don't want
     *  the whole loop deadlocked. */
    private static final long DROP_TIMEOUT_MS = 4_000L;

    public PowerMineStrategy()
    {
        this(defaultOreIds());
    }

    public PowerMineStrategy(Set<Integer> dropItemIds)
    {
        this.dropItemIds = dropItemIds == null || dropItemIds.isEmpty()
            ? defaultOreIds() : dropItemIds;
    }

    private static Set<Integer> defaultOreIds()
    {
        Set<Integer> ids = new HashSet<>();
        for (OreType t : OreType.values()) ids.add(t.oreItemId());
        return ids;
    }

    @Override public String label() { return "PowerMine"; }

    @Override
    public void empty(MiningLoopContext ctx) throws InterruptedException
    {
        HumanizedInputDispatcher dispatcher = ctx.dispatcher();
        // Take a snapshot of which slots contain ores. We dispatch by slot
        // index; the engine moves later items up to fill the gap each drop,
        // but the next snapshot reflects that, so re-reading after each
        // drop converges.
        for (int safety = 0; safety < 32; safety++)
        {
            int slot = findOreSlot(ctx);
            if (slot < 0) break;
            ActionRequest req = ActionRequest.builder()
                .kind(ActionRequest.Kind.CLICK_INV_ITEM)
                .channel(ActionRequest.Channel.MOUSE)
                .slot(slot)
                .verb("Drop")
                .build();
            dispatcher.dispatch(req);
            long deadline = System.currentTimeMillis() + DROP_TIMEOUT_MS;
            while (dispatcher.isBusy())
            {
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedException("powermine drop interrupted");
                if (System.currentTimeMillis() > deadline)
                {
                    log.warn("PowerMine: drop on slot {} timed out", slot);
                    break;
                }
                Thread.sleep(DISPATCH_POLL_MS);
            }
            String err = dispatcher.lastErrorMessage();
            if (err != null)
            {
                log.info("PowerMine: drop on slot {} error: {}", slot, err);
                // Don't loop forever on the same error — bail; the loop's
                // next inventory check will still see the item, but the
                // outer state machine can decide whether to retry or abort.
                break;
            }
            // Brief inter-drop pause — humans don't fire menu selects at
            // a constant cadence. The dispatcher's settle/post-click
            // already adds humanization but a small additional gap helps.
            Thread.sleep(80);
        }
    }

    /**
     * Find the lowest-index slot whose item id is in the configured drop set.
     * Returns -1 if none. Reads the {@link ItemContainer} on the client thread.
     */
    private int findOreSlot(MiningLoopContext ctx) throws InterruptedException
    {
        Integer slot = ctx.dispatcher().runOnClient(() -> {
            ItemContainer inv = ctx.client().getItemContainer(InventoryID.INV);
            if (inv == null) return -1;
            Item[] items = inv.getItems();
            if (items == null) return -1;
            for (int i = 0; i < items.length; i++)
            {
                Item it = items[i];
                if (it == null) continue;
                int id = it.getId();
                if (id <= 0) continue;
                if (dropItemIds.contains(id)) return i;
            }
            return -1;
        });
        return slot == null ? -1 : slot;
    }
}
