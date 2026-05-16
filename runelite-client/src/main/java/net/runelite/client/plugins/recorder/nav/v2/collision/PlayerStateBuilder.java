package net.runelite.client.plugins.recorder.nav.v2.collision;

import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.client.callback.ClientThread;

/** Builds an immutable {@link PlayerState} from a live {@link Client}.
 *
 *  <p>Skill levels and {@code isMember} are captured eagerly at construction.
 *  Varbits / varplayers are lazy — see field docs on {@link PlayerStateImpl}.
 *
 *  <p>Threading: this builder must be invoked on the client thread, or with
 *  a non-null {@link ClientThread} to marshall through. The lazy
 *  varbit/varplayer reads also need the client thread; the
 *  {@link PlayerStateImpl} captures the {@link ClientThread} reference and
 *  marshalls on demand. The skill-level arrays are copied at capture
 *  time so consumers can read levels without re-marshalling. */
@Slf4j
public final class PlayerStateBuilder
{
    private PlayerStateBuilder() {}

    /** Build a {@link PlayerState} from the live client.
     *
     *  @param client       the live client.
     *  @param clientThread used only to marshall lazy varbit/varplayer reads
     *                      from worker threads after construction.
     *                      May be {@code null} if the caller commits to
     *                      always reading on the client thread. */
    public static PlayerState fromClient(Client client, @Nullable ClientThread clientThread)
    {
        if (client == null) throw new IllegalArgumentException("client");
        if (!client.isClientThread())
        {
            throw new IllegalStateException(
                "PlayerStateBuilder.fromClient must be called on the client thread "
                    + "(off-thread callers should marshall first).");
        }

        int[] real = client.getRealSkillLevels();
        int[] boosted = client.getBoostedSkillLevels();
        // Defensive copy — RuneLite mutates these arrays in place on tick.
        int[] realCopy = real != null ? real.clone() : new int[Skill.values().length];
        int[] boostedCopy = boosted != null ? boosted.clone() : new int[Skill.values().length];

        boolean member = client.getWorldType() != null
            && client.getWorldType().contains(WorldType.MEMBERS);

        // Inventory + equipment refs. RuneLite ItemContainer's snapshot is per
        // tick; consumers should treat the reference as read-only. We avoid a
        // deep copy here because it would multiply allocations per plan call.
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);

        return new PlayerStateImpl(realCopy, boostedCopy, member, inv, eq, client, clientThread);
    }

    /** Concrete {@link PlayerState}. */
    static final class PlayerStateImpl implements PlayerState
    {
        private final int[] realLevels;
        private final int[] boostedLevels;
        private final boolean member;
        @Nullable private final ItemContainer inv;
        @Nullable private final ItemContainer eq;
        private final Client client;
        @Nullable private final ClientThread clientThread;

        PlayerStateImpl(int[] realLevels, int[] boostedLevels, boolean member,
                        @Nullable ItemContainer inv, @Nullable ItemContainer eq,
                        Client client, @Nullable ClientThread clientThread)
        {
            this.realLevels = realLevels;
            this.boostedLevels = boostedLevels;
            this.member = member;
            this.inv = inv;
            this.eq = eq;
            this.client = client;
            this.clientThread = clientThread;
        }

        @Override
        public int skillLevel(Skill skill)
        {
            return readLevel(realLevels, skill);
        }

        @Override
        public int boostedLevel(Skill skill)
        {
            return readLevel(boostedLevels, skill);
        }

        private static int readLevel(int[] arr, Skill skill)
        {
            if (skill == null) return 0;
            int ord = skill.ordinal();
            if (arr == null || ord < 0 || ord >= arr.length) return 0;
            return arr[ord];
        }

        @Override
        public int varbit(int varbitId)
        {
            return onClient(() -> client.getVarbitValue(varbitId));
        }

        @Override
        public int varplayer(int varpId)
        {
            return onClient(() -> client.getVarpValue(varpId));
        }

        @Override
        public ItemContainer inventory()
        {
            return inv;
        }

        @Override
        public ItemContainer equipment()
        {
            return eq;
        }

        @Override
        public boolean isMember()
        {
            return member;
        }

        /** Lazy-read helper: directly reads on the client thread if we're
         *  there, otherwise marshalls via the captured {@link ClientThread}
         *  and blocks for up to 1s. */
        private int onClient(java.util.function.IntSupplier r)
        {
            if (client.isClientThread())
            {
                return r.getAsInt();
            }
            if (clientThread == null)
            {
                throw new IllegalStateException(
                    "PlayerState read invoked off client thread without a ClientThread marshaller");
            }
            int[] out = { 0 };
            Throwable[] err = { null };
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            clientThread.invoke(() ->
            {
                try { out[0] = r.getAsInt(); }
                catch (Throwable t) { err[0] = t; }
                finally { latch.countDown(); }
            });
            try
            {
                if (!latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS))
                {
                    throw new IllegalStateException("varbit/varplayer read timed out (1s)");
                }
            }
            catch (InterruptedException ie)
            {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("varbit/varplayer read interrupted", ie);
            }
            if (err[0] != null)
            {
                throw new IllegalStateException("varbit/varplayer read failed", err[0]);
            }
            return out[0];
        }
    }
}
