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

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.combat.ChickenCombatLoop;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.plugins.recorder.transport.Waypoint;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;

/**
 * Outer state machine: KILLING → (inv full) → WALKING_TO_BANK → BANKING →
 * WALKING_TO_PEN → KILLING. Wraps {@link ChickenCombatLoop},
 * {@link RouteWalker}, and {@link BankInteraction}.
 *
 * <p>Pure state-decision logic is exposed via {@link #decideResume} so
 * tests can exercise it without mocking the client.
 */
@Slf4j
public final class ChickenFarmLoop
{
    public enum State { IDLE, KILLING, WALKING_TO_BANK, BANKING,
                        WALKING_TO_PEN, ABORTED }

    private final Client client;
    private final FarmConfig config;
    private final RouteWalker walker;
    private final BankInteraction bank;
    private final ChickenCombatLoop combat;

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicReference<String> status = new AtomicReference<>("idle");
    private final AtomicInteger routeIdx = new AtomicInteger(0);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Thread> worker = new AtomicReference<>();

    public ChickenFarmLoop(Client client, ClientThread clientThread,
                           HumanizedInputDispatcher dispatcher, FarmConfig config)
    {
        this.client = client;
        this.config = config;
        TransportResolver resolver = new TransportResolver(client);
        this.walker = new RouteWalker(client, dispatcher, resolver);
        this.bank = new BankInteraction(client, clientThread, dispatcher);
        this.combat = new ChickenCombatLoop(dispatcher, client, clientThread, config.penArea());
    }

    public State state() { return state.get(); }
    public String status() { return status.get(); }

    public void start()
    {
        if (!running.compareAndSet(false, true)) return;
        Player self = client.getLocalPlayer();
        WorldPoint here = self == null ? null : self.getWorldLocation();
        int free = InventoryUtil.freeSlotCount(client);
        State decided = here == null ? State.ABORTED
            : decideResume(config.bankArea(), config.penArea(),
                config.routeBankToPen(), here, free);
        state.set(decided);
        status.set("resume → " + decided);
        if (decided == State.ABORTED)
        {
            running.set(false);
            return;
        }
        Thread t = new Thread(this::tickLoop, "chicken-farm");
        t.setDaemon(true);
        worker.set(t);
        t.start();
    }

    public void stop()
    {
        running.set(false);
        state.set(State.IDLE);
        status.set("stopped");
        Thread t = worker.getAndSet(null);
        if (t != null) t.interrupt();
        combat.stop();
    }

    private void tickLoop()
    {
        try
        {
            while (running.get() && !Thread.currentThread().isInterrupted())
            {
                State s = state.get();
                switch (s)
                {
                    case KILLING:           tickKilling();       break;
                    case WALKING_TO_BANK:   tickWalking(true);   break;
                    case BANKING:           tickBanking();       break;
                    case WALKING_TO_PEN:    tickWalking(false);  break;
                    case ABORTED:
                    case IDLE:
                    default:                running.set(false);  break;
                }
                Thread.sleep(200 + (int)(Math.random() * 400));
            }
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
        }
        finally
        {
            running.set(false);
        }
    }

    private void tickKilling() throws InterruptedException
    {
        if (combat.state() == ChickenCombatLoop.State.IDLE) combat.start();
        if (InventoryUtil.isInventoryFull(client))
        {
            combat.stop();
            // Reset to 0 so tickWalking(toBank=true) walks routePenToBank from the
            // start. The plan's reference set this to routeBankToPen.size()-1 to
            // "skip the route", but that wedges if the player is mid-pen and the
            // last bank-to-pen waypoint's arrived() geometry fires prematurely.
            // Walking the reverse route from waypoint 0 is the safer model.
            routeIdx.set(0);
            state.set(State.WALKING_TO_BANK);
        }
    }

    private void tickWalking(boolean toBank) throws InterruptedException
    {
        List<Waypoint> route = toBank ? config.routePenToBank() : config.routeBankToPen();
        int idx = routeIdx.get();
        if (idx < 0 || idx >= route.size())
        {
            // Past the route — check terminal arrival.
            Player self = client.getLocalPlayer();
            WorldPoint here = self == null ? null : self.getWorldLocation();
            if (here != null && contains(toBank ? config.bankArea() : config.penArea(), here))
            {
                state.set(toBank ? State.BANKING : State.KILLING);
                routeIdx.set(0);
            }
            return;
        }
        Waypoint wp = route.get(idx);
        if (walker.arrived(wp))
        {
            routeIdx.set(idx + 1);
            return;
        }
        walker.tick(wp);
    }

    private void tickBanking() throws InterruptedException
    {
        if (!bank.isBankOpen())
        {
            if (!bank.clickBankBooth()) status.set("bank booth not visible");
            return;
        }
        if (!InventoryUtil.isInventoryFull(client) && InventoryUtil.freeSlotCount(client) >= 27)
        {
            // Close-and-walk after deposit succeeded (free slots ≈ 28).
            bank.closeBank();
            routeIdx.set(0);
            state.set(State.WALKING_TO_PEN);
            return;
        }
        bank.clickDepositInventory();
    }

    /** Pure resume decision for tests. */
    public static State decideResume(WorldArea bank, WorldArea pen,
                                     List<Waypoint> route, WorldPoint here, int freeSlots)
    {
        if (contains(pen, here)) return freeSlots == 0 ? State.WALKING_TO_BANK : State.KILLING;
        // >= 27 mirrors tickBanking's deposit-success threshold: the engine
        // occasionally returns 27 instead of 28 immediately after a deposit
        // (one-slot counting quirk). A player resuming at the bank with 27
        // free is treated as "already deposited, walk back."
        if (contains(bank, here)) return freeSlots >= 27 ? State.WALKING_TO_PEN : State.BANKING;
        if (nearAnyWaypoint(route, here, FarmConfig.RESUME_TILE_TOLERANCE))
            return freeSlots == 0 ? State.WALKING_TO_BANK : State.WALKING_TO_PEN;
        return State.ABORTED;
    }

    private static boolean contains(WorldArea a, WorldPoint p)
    {
        return a.getPlane() == p.getPlane()
            && p.getX() >= a.getX() && p.getX() < a.getX() + a.getWidth()
            && p.getY() >= a.getY() && p.getY() < a.getY() + a.getHeight();
    }

    private static boolean nearAnyWaypoint(List<Waypoint> route, WorldPoint here, int tol)
    {
        for (Waypoint w : route)
        {
            WorldArea a = w.area();
            if (a != null)
            {
                if (a.getPlane() != here.getPlane()) continue;
                if (here.getX() >= a.getX() - tol
                    && here.getX() < a.getX() + a.getWidth() + tol
                    && here.getY() >= a.getY() - tol
                    && here.getY() < a.getY() + a.getHeight() + tol)
                    return true;
            }
            else if (w.tile() != null)
            {
                if (w.tile().getPlane() != here.getPlane()) continue;
                if (Math.abs(w.tile().getX() - here.getX()) <= tol
                    && Math.abs(w.tile().getY() - here.getY()) <= tol)
                    return true;
            }
        }
        return false;
    }
}
