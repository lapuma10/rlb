package net.runelite.client.plugins.recorder.nav.v2.transport;

import java.util.Objects;

/** Spec §3 contract: transport-traversal leg of a planned path.
 *
 *  <p><b>Local mock note</b>: Lane 5 owns the canonical
 *  {@code nav/v2/TransportStep.java}. Lane 4 ships this here.
 *  Integration consolidates. */
public final class TransportStep implements PathStep
{
	private final TransportLeg transport;

	public TransportStep(TransportLeg transport)
	{
		if (transport == null) throw new IllegalArgumentException("transport null");
		this.transport = transport;
	}

	public TransportLeg transport() { return transport; }

	@Override
	public boolean equals(Object o)
	{
		if (this == o) return true;
		if (!(o instanceof TransportStep)) return false;
		return transport.equals(((TransportStep) o).transport);
	}

	@Override
	public int hashCode() { return Objects.hash(transport); }

	@Override
	public String toString() { return "TransportStep{" + transport + "}"; }
}
