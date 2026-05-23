package net.runelite.client.plugins.recorder.session;

import java.util.Random;
import java.util.function.LongSupplier;
import net.runelite.api.Client;

/**
 * Per-account RNG seed provider. Centralizes account-scoped runtime
 * variance by deriving every randomness source from one seed tied to
 * {@link Client#getAccountHash()} rather than the globally shared
 * unseeded {@link Random}.
 *
 * <p>This is a <b>live provider</b>, not a one-time factory. The
 * underlying account hash is resolved on every call to {@link #seed()};
 * the {@link Random} instances returned by {@link #forAccount(String)}
 * re-seed themselves when the hash changes (e.g. the pre-login →
 * post-login transition that happens once per session). A consumer that
 * captures the returned Random in a {@code final} field and never asks
 * for a fresh one still sees the correct account-derived stream after
 * login.
 *
 * <p>Within the same account, different {@code purpose} keys produce
 * decorrelated streams. Two unrelated subsystems (e.g. dispatcher
 * cursor jitter and WindMouse path generation) should ask for their own
 * named stream rather than sharing one mutable Random.
 *
 * <p>Not thread-safe across multiple writers. The {@link Random}
 * returned by {@link #forAccount(String)} serializes the re-seed check
 * via {@code synchronized}; callers should still treat each Random as
 * single-worker like any other {@link Random}.
 */
public final class AccountRng
{
	/** Stable seed used when no account is signed in (account hash
	 *  {@code -1L}) or in unit tests that pass a null Client. Picked
	 *  once so behaviour is reproducible across pre-login sessions
	 *  instead of drifting with the VM-default Random seed. */
	static final long NOT_LOGGED_IN_SEED = 0xC0FFEE_BADBADL;

	/** Knuth multiplicative-hashing constant used to decorrelate
	 *  per-purpose sub-streams within the same account. */
	static final long PURPOSE_MIX = 0x9E3779B97F4A7C15L;

	private final Client client;

	public AccountRng(Client client)
	{
		this.client = client;
	}

	/** Raw per-account seed resolved at the moment of the call. Returns
	 *  {@link #NOT_LOGGED_IN_SEED} when there is no signed-in account
	 *  (hash {@code -1L}) or the client reference is null. */
	public long seed()
	{
		if (client == null)
		{
			return NOT_LOGGED_IN_SEED;
		}
		long hash = client.getAccountHash();
		return hash == -1L ? NOT_LOGGED_IN_SEED : hash;
	}

	/** Per-account, per-purpose seed. Different {@code purpose} strings
	 *  produce decorrelated seeds within the same account so unrelated
	 *  consumers do not share an RNG stream. */
	public long seed(String purpose)
	{
		return seed() ^ (purpose.hashCode() * PURPOSE_MIX);
	}

	/** A {@link Random} whose state is keyed off the current account
	 *  seed. The first draw seeds the stream; subsequent draws check the
	 *  account hash and re-seed if it has changed (so a Random captured
	 *  before login switches to the account-derived stream on first
	 *  post-login draw). */
	public Random forAccount(String purpose)
	{
		return new ReseedingRandom(() -> seed(purpose));
	}

	/** {@link Random} that resolves its seed lazily on each draw via a
	 *  {@link LongSupplier}, re-seeding only when the supplied value
	 *  changes. */
	private static final class ReseedingRandom extends Random
	{
		private final LongSupplier seedSupplier;
		private long lastSeed;
		private boolean seeded;

		ReseedingRandom(LongSupplier seedSupplier)
		{
			// Random()'s no-arg constructor seeds from System.nanoTime;
			// the first call to next(int) overwrites that before any
			// draw is observable.
			super();
			this.seedSupplier = seedSupplier;
		}

		@Override
		protected synchronized int next(int bits)
		{
			long current = seedSupplier.getAsLong();
			if (!seeded || current != lastSeed)
			{
				super.setSeed(current);
				lastSeed = current;
				seeded = true;
			}
			return super.next(bits);
		}
	}
}
