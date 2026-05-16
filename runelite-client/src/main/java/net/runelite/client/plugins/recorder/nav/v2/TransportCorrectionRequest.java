package net.runelite.client.plugins.recorder.nav.v2;

import net.runelite.api.coords.WorldPoint;

/** Spec §3 / plan Task 6 (Lane 5 file owner): typed correction request
 *  emitted by the executor when it observes a transport-state-mismatch.
 *
 *  <p>This replaces the previous flow where {@code V2Executor} called
 *  {@code env.correctTransportEdge(...)} directly (and set a now-removed
 *  replan-from-here boolean). Mid-route mutation of the transport table
 *  by the executor is a spec violation (§7 rule "Transport data is
 *  never mutated by executor"). Instead:
 *
 *  <ol>
 *    <li>Executor emits {@code TransportCorrectionRequest} via
 *        {@link ExecutorTickResult#transportCorrection()}.</li>
 *    <li>Navigator inspects the request, applies the correction (by
 *        delegating to its {@code TransportTable.replace(...)} hook or
 *        to {@code V2ExecutorEnv.correctTransportEdge(...)} during the
 *        transition window), and triggers a replan from current
 *        player position.</li>
 *  </ol>
 *
 *  <p>The executor MUST NOT call any mutation method on the transport
 *  data store directly. Lane 6 asserts this with a spy. */
public interface TransportCorrectionRequest
{
    WorldPoint plannedTo();
    WorldPoint actualTo();
    TransportLeg edge();
    ReplanReason inferredReason();
}
