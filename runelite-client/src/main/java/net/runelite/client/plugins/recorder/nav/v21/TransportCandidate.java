package net.runelite.client.plugins.recorder.nav.v21;

import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;

/** Router-internal return type for {@link TransportRouter}.
 *
 *  <p>Carries the real {@link TransportEdge} the router picked, a
 *  ready-to-execute {@link BlockerCandidate} the navigator stores in
 *  {@code pendingTransport} and dispatches, and lightweight diagnostics
 *  ({@code estimatedTotalCost}, {@code chainLength}) for logging /
 *  reactive-solver judgement.
 *
 *  <p>The edge is never synthetic — only edges already present in the
 *  {@link net.runelite.client.plugins.recorder.worldmap.TransportIndex}
 *  reach this type. The router is the chooser; the index is the truth.
 *
 *  <p>{@code chainLength == 1} indicates the picked edge is the
 *  immediate hop (the router currently sets a placeholder of 1 here for
 *  diagnostics; the meaningful field is {@code estimatedTotalCost}). */
public record TransportCandidate(
	TransportEdge edge,
	BlockerCandidate executable,
	double estimatedTotalCost,
	int chainLength)
{
	/** Build a candidate from an edge plus the live scene object and the
	 *  player-side approach tile the navigator should be standing on. The
	 *  {@link BlockerCandidate} signature is {@code (TileObject, String,
	 *  WorldPoint)} — verb comes from the edge, the approach tile is the
	 *  player's current position (or a near-tile chosen by the caller). */
	public static TransportCandidate of(
		TransportEdge edge,
		TileObject obj,
		WorldPoint playerApproach,
		double cost,
		int chain)
	{
		BlockerCandidate exec = new BlockerCandidate(obj, edge.verb(), playerApproach);
		return new TransportCandidate(edge, exec, cost, chain);
	}
}
