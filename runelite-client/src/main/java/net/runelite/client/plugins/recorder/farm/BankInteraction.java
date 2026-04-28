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
package net.runelite.client.plugins.recorder.farm;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;

/**
 * Drives the bank booth interaction at Lumbridge upstairs.
 *
 * <p>The flow: find a bank booth NPC inside the bank area, click it,
 * wait for the bank widget to appear, click the "Deposit inventory" orb,
 * close with Escape. Each step uses the same humanized dispatcher the
 * rest of the system uses. Widget probes are read on whatever thread the
 * caller invokes from; click/key dispatches block briefly during the
 * humanized scheduling phase, so callers should run this off the EDT
 * (the outer farm-loop thread is the right place).
 */
public final class BankInteraction
{
    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;

    public BankInteraction(Client client, ClientThread clientThread,
                           HumanizedInputDispatcher dispatcher)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
    }

    /** True if the bank-main widget is loaded and visible. */
    public boolean isBankOpen()
    {
        Widget w = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
        return w != null && !w.isHidden();
    }

    /** Step 1: find a bank booth NPC and click its convex hull. Returns
     *  true if a click was dispatched, false if no booth NPC is on screen. */
    public boolean clickBankBooth() throws InterruptedException
    {
        NPC booth = findBankBoothNPC();
        if (booth == null) return false;
        Shape hull = booth.getConvexHull();
        if (hull == null) return false;
        Rectangle b = hull.getBounds();
        int cx = b.x + b.width / 2;
        int cy = b.y + b.height / 2;
        dispatcher.clickCanvas(cx, cy);
        return true;
    }

    private NPC findBankBoothNPC() throws InterruptedException
    {
        CompletableFuture<NPC> fut = new CompletableFuture<>();
        clientThread.invoke(() -> {
            try
            {
                WorldView wv = client.getTopLevelWorldView();
                if (wv == null) { fut.complete(null); return; }
                for (NPC npc : wv.npcs())
                {
                    if (npc == null) continue;
                    String name = npc.getName();
                    if (name == null) continue;
                    if (name.equalsIgnoreCase("Banker")
                        || name.equalsIgnoreCase("Bank booth"))
                    {
                        fut.complete(npc);
                        return;
                    }
                }
                fut.complete(null);
            }
            catch (Throwable t)
            {
                fut.completeExceptionally(t);
            }
        });
        try
        {
            return fut.get(2, TimeUnit.SECONDS);
        }
        catch (TimeoutException | ExecutionException ex)
        {
            return null;
        }
    }

    /** Step 2: click the Deposit-inventory orb on the bank widget.
     *  Caller is responsible for verifying {@link #isBankOpen()} first. */
    public boolean clickDepositInventory() throws InterruptedException
    {
        Widget w = client.getWidget(InterfaceID.Bankmain.DEPOSITINV);
        if (w == null || w.isHidden()) return false;
        Rectangle b = w.getBounds();
        if (b == null || b.isEmpty()) return false;
        dispatcher.clickCanvas(b.x + b.width / 2, b.y + b.height / 2);
        return true;
    }

    /** Step 3: close the bank widget by sending Escape via the dispatcher. */
    public void closeBank() throws InterruptedException
    {
        if (!isBankOpen()) return;
        dispatcher.tapKey(KeyEvent.VK_ESCAPE);
    }
}
