package net.runelite.client.plugins.recorder.nav.v2.transport;

/** Test helper that pretends to be an unauthorized caller of
 *  {@link TransportTable#replace(TransportLink, TransportLink)}.
 *
 *  <p>The class name deliberately does NOT contain "Test" so the
 *  whitelist's test-caller fallback does not exempt it. The
 *  {@code TransportTable.replace} stack-walk should see this class
 *  name in the caller frame and throw {@link IllegalStateException}.
 *
 *  <p>Used by {@code TransportTableTest.replace_invokedFromExecutorClass_throwsForbidden}. */
public final class SimulatedExecutor
{
	public void invokeReplace(TransportTable table, TransportLink oldLink, TransportLink corrected)
	{
		table.replace(oldLink, corrected);
	}
}
