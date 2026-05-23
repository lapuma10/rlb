package net.runelite.client.sequence.artemis.session;

/**
 * Sampling window for {@link net.runelite.client.sequence.artemis.Artemis#idle}.
 * v1.0 ships in-game idle only — {@link #logoutPreferred} is always
 * {@code false} here. NATURAL_BREAK / MEAL variants (logout + sleep +
 * AccountLauncher re-login + resume) are deferred to v1.3 per spec §18
 * — they need {@code login(world)} integration which is itself
 * deferred indefinitely (spec §4).
 *
 * <p>Spec §10.
 */
public record IdlePolicy(int minMs, int maxMs, boolean logoutPreferred)
{
	public static final IdlePolicy PHONE_GLANCE = new IdlePolicy(30_000,  90_000,  false);
	public static final IdlePolicy BATHROOM     = new IdlePolicy(180_000, 270_000, false);
}
