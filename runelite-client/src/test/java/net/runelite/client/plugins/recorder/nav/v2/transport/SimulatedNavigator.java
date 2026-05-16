package net.runelite.client.plugins.recorder.nav.v2.transport;

/** Test helper that simulates the {@code V2Navigator}-via-{@code
 *  TransportCorrectionRequest} caller path for
 *  {@link TransportTable#replace(TransportLink, TransportLink)}.
 *
 *  <p>The short class name matches the whitelist's short-name
 *  fallback so the auth check passes. Used by
 *  {@code TransportTableTest.replace_invokedFromNavigatorClass_succeeds}. */
public final class SimulatedNavigator
{
	public void invokeReplace(TransportTable table, TransportLink oldLink, TransportLink corrected)
	{
		table.replace(oldLink, corrected);
	}
}
