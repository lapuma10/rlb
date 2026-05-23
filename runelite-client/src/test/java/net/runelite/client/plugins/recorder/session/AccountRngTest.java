package net.runelite.client.plugins.recorder.session;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.Client;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AccountRngTest
{
	// ─── 1. pre-login fallback is deterministic ─────────────────────

	@Test
	public void preLoginFallbackSeedIsDeterministic()
	{
		// Two separate AccountRng instances over different
		// not-logged-in clients must produce the same seed.
		long s1 = new AccountRng(mockClient(-1L)).seed();
		long s2 = new AccountRng(mockClient(-1L)).seed();
		assertEquals(s1, s2);
		assertEquals(AccountRng.NOT_LOGGED_IN_SEED, s1);
	}

	@Test
	public void preLoginRandomProducesDeterministicStream()
	{
		Random a = new AccountRng(mockClient(-1L)).forAccount("dispatcher");
		Random b = new AccountRng(mockClient(-1L)).forAccount("dispatcher");
		for (int i = 0; i < 16; i++)
		{
			assertEquals(a.nextLong(), b.nextLong());
		}
	}

	// ─── 2. live re-seed when account hash becomes available ────────

	@Test
	public void seedSwitchesFromFallbackToAccountSeedAfterLogin()
	{
		AtomicLong currentHash = new AtomicLong(-1L);
		Client c = mockDynamicClient(currentHash);
		AccountRng ar = new AccountRng(c);

		assertEquals(AccountRng.NOT_LOGGED_IN_SEED, ar.seed());

		currentHash.set(987_654_321L);

		assertEquals(987_654_321L, ar.seed());
		assertNotEquals(AccountRng.NOT_LOGGED_IN_SEED, ar.seed());
	}

	@Test
	public void randomCapturedPreLoginReseedsAfterLogin()
	{
		// This is the regression test for the load-bearing flaw the
		// first design had: a Random taken pre-login MUST start drawing
		// from the account-derived stream once the player signs in,
		// without the caller re-fetching it.
		AtomicLong currentHash = new AtomicLong(-1L);
		Client c = mockDynamicClient(currentHash);
		AccountRng ar = new AccountRng(c);

		Random rng = ar.forAccount("dispatcher");

		// Consume a draw while logged out — pulls from fallback stream.
		rng.nextLong();

		// Simulate login.
		currentHash.set(424_242L);

		// The very next draw must come from a Random seeded by the
		// account-derived value, not the fallback stream.
		long observed = rng.nextLong();

		Random expected = new Random(ar.seed("dispatcher"));
		long expectedFirstDraw = expected.nextLong();
		assertEquals(expectedFirstDraw, observed);
	}

	@Test
	public void randomReseedsAgainOnAccountChange()
	{
		// Logout then login to a different account must move the stream
		// to that new account's seed.
		AtomicLong currentHash = new AtomicLong(111L);
		Client c = mockDynamicClient(currentHash);
		AccountRng ar = new AccountRng(c);

		Random rng = ar.forAccount("dispatcher");
		rng.nextLong(); // burn one draw on account 111

		currentHash.set(222L);

		long observed = rng.nextLong();
		long expectedFirstDraw = new Random(ar.seed("dispatcher")).nextLong();
		assertEquals(expectedFirstDraw, observed);
	}

	// ─── 3. same account + same purpose → same seed ─────────────────

	@Test
	public void sameAccountSamePurposeGivesSameSeed()
	{
		long s1 = new AccountRng(mockClient(42L)).seed("dispatcher");
		long s2 = new AccountRng(mockClient(42L)).seed("dispatcher");
		assertEquals(s1, s2);
	}

	// ─── 4. same account + different purpose → different seed ───────

	@Test
	public void sameAccountDifferentPurposeGivesDifferentSeed()
	{
		AccountRng ar = new AccountRng(mockClient(42L));
		assertNotEquals(ar.seed("dispatcher"), ar.seed("windmouse"));
	}

	@Test
	public void differentPurposesProduceDecorrelatedStreams()
	{
		AccountRng ar = new AccountRng(mockClient(42L));
		Random a = ar.forAccount("dispatcher");
		Random b = ar.forAccount("windmouse");
		boolean anyDiff = false;
		for (int i = 0; i < 16; i++)
		{
			if (a.nextLong() != b.nextLong())
			{
				anyDiff = true;
				break;
			}
		}
		assertTrue("expected decorrelated streams for different purposes", anyDiff);
	}

	// ─── 5. different account + same purpose → different seed ───────

	@Test
	public void differentAccountSamePurposeGivesDifferentSeed()
	{
		long s1 = new AccountRng(mockClient(111L)).seed("dispatcher");
		long s2 = new AccountRng(mockClient(222L)).seed("dispatcher");
		assertNotEquals(s1, s2);
	}

	@Test
	public void differentAccountsProduceDivergentStreamsForSamePurpose()
	{
		Random a = new AccountRng(mockClient(111L)).forAccount("dispatcher");
		Random b = new AccountRng(mockClient(222L)).forAccount("dispatcher");
		boolean anyDiff = false;
		for (int i = 0; i < 8; i++)
		{
			if (a.nextLong() != b.nextLong())
			{
				anyDiff = true;
				break;
			}
		}
		assertTrue("expected divergent streams for different accounts", anyDiff);
	}

	// ─── 6. zero account hash is valid (not fallback) ───────────────

	@Test
	public void zeroAccountHashIsValidNotFallback()
	{
		long seed = new AccountRng(mockClient(0L)).seed();
		assertNotEquals(AccountRng.NOT_LOGGED_IN_SEED, seed);
		assertEquals(0L, seed);
	}

	// ─── 7. -1 account hash is fallback ─────────────────────────────

	@Test
	public void minusOneAccountHashIsFallback()
	{
		assertEquals(AccountRng.NOT_LOGGED_IN_SEED, new AccountRng(mockClient(-1L)).seed());
	}

	@Test
	public void nullClientFallsBackToStableSeed()
	{
		assertEquals(AccountRng.NOT_LOGGED_IN_SEED, new AccountRng(null).seed());
	}

	@Test
	public void nullClientAndMinusOneCollapseToSameSeed()
	{
		assertEquals(
			new AccountRng(null).seed(),
			new AccountRng(mockClient(-1L)).seed()
		);
	}

	// ─── helpers ────────────────────────────────────────────────────

	private static Client mockClient(long accountHash)
	{
		Client c = mock(Client.class);
		when(c.getAccountHash()).thenReturn(accountHash);
		return c;
	}

	private static Client mockDynamicClient(AtomicLong currentHash)
	{
		Client c = mock(Client.class);
		when(c.getAccountHash()).thenAnswer(inv -> currentHash.get());
		return c;
	}
}
