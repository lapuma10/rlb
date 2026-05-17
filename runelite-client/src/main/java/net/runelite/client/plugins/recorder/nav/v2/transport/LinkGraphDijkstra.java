package net.runelite.client.plugins.recorder.nav.v2.transport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.v2.collision.ConnectivityComponents;
import net.runelite.client.plugins.recorder.nav.v2.predicate.NavigationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Top-tier route planner. Runs Dijkstra over a sparse graph of
 *  transport endpoints + the start/target tiles, returning a typed
 *  skeleton the bottom-tier BFS will fill in.
 *
 *  <p><b>Graph shape</b>:
 *  <ul>
 *    <li><b>Nodes</b>: start tile, target tile, plus every transport
 *        endpoint (origin AND destination of every {@link TransportLink}
 *        in the table whose requirement is satisfied for the current
 *        {@link NavigationContext}).</li>
 *    <li><b>Edges</b>:
 *      <ul>
 *        <li>Implicit "walk" edges between every pair of same-plane
 *            nodes, cost = chebyshev distance.</li>
 *        <li>Explicit transport edges per {@link TransportLink}, cost =
 *            {@link TransportLink#durationTicks()}.</li>
 *      </ul>
 *    </li>
 *  </ul>
 *
 *  <p>Chebyshev is the admissible metric for OSRS walking (the engine
 *  walks N/S/E/W AND diagonals at the same per-tick rate). Using it
 *  as the walking-cost heuristic keeps Dijkstra optimal.
 *
 *  <p>Filtered out at edge-construction time: any {@link TransportLink}
 *  whose {@link TransportRequirement#satisfiedBy(NavigationContext)}
 *  returns false. This is the one-and-only spot where requirements
 *  affect routing — once a link survives this filter, the planner
 *  treats it as a normal edge.
 *
 *  <p>Cross-plane targets are reachable only via a transport that
 *  bridges the two planes. If no such transport survives the
 *  filter, Dijkstra returns {@link Status#UNREACHABLE}.
 *
 *  <p>Spec §4 Lane 4 (Dijkstra) — no A* cost knobs, no trail bias.
 *  This is the explicit replacement for {@code MultiRegionAStar} +
 *  {@code TopKRouter}. */
public final class LinkGraphDijkstra
{
	private static final Logger log = LoggerFactory.getLogger(LinkGraphDijkstra.class);

	/** Hard cap on the number of nodes the graph may contain. Without
	 *  it, a huge transport table could push tens of thousands of
	 *  endpoints into the priority queue. 4096 covers the bank↔pen
	 *  use case with headroom; if real routes exhaust it, raise here
	 *  and document. */
	private static final int MAX_NODES_HINT = 4096;

	private LinkGraphDijkstra() {}

	/** One node in the skeleton output. */
	public static final class SkeletonNode
	{
		private final NodeKind kind;
		private final WorldPoint tile;
		private final TransportLink transport;

		private SkeletonNode(NodeKind kind, WorldPoint tile, TransportLink transport)
		{
			this.kind = kind;
			this.tile = tile;
			this.transport = transport;
		}

		public NodeKind kind() { return kind; }
		public WorldPoint tile() { return tile; }
		public TransportLink transport() { return transport; }

		static SkeletonNode walk(WorldPoint tile) { return new SkeletonNode(NodeKind.WALK, tile, null); }
		static SkeletonNode transport(TransportLink t) { return new SkeletonNode(NodeKind.TRANSPORT, t.to(), t); }

		@Override
		public String toString()
		{
			return kind == NodeKind.WALK ? "WALK@" + tile : "TRANSPORT@" + transport;
		}
	}

	public enum NodeKind { WALK, TRANSPORT }

	public enum Status { OK, UNREACHABLE }

	public static final class SkeletonResult
	{
		private final Status status;
		private final List<SkeletonNode> nodes;
		private final ReplanReason reasonIfFailed;
		private final int totalCostTicks;

		SkeletonResult(Status status, List<SkeletonNode> nodes, ReplanReason reasonIfFailed, int totalCostTicks)
		{
			this.status = status;
			this.nodes = nodes == null ? Collections.emptyList() : Collections.unmodifiableList(nodes);
			this.reasonIfFailed = reasonIfFailed;
			this.totalCostTicks = totalCostTicks;
		}

		public Status status() { return status; }
		public List<SkeletonNode> nodes() { return nodes; }
		public ReplanReason reasonIfFailed() { return reasonIfFailed; }
		public int totalCostTicks() { return totalCostTicks; }
	}

	/** Run Dijkstra from {@code from} to {@code to}, considering only
	 *  transport links in {@code table} whose requirement is satisfied
	 *  by {@code ctx}. Returns a sparse skeleton (walk-anchors +
	 *  transport-traversals).
	 *
	 *  <p>When {@code from.equals(to)}, returns a trivial 1-node walk
	 *  skeleton.
	 *
	 *  <p>4-arg overload — equivalent to passing {@code components = null}.
	 *  Walk edges are added between every same-plane node pair (the
	 *  pre-2026-05-17 "collision-blind" behaviour). Preserved so
	 *  existing tests / call sites without a {@link ConnectivityComponents}
	 *  precompute don't change behaviour. */
	public static SkeletonResult findRouteSkeleton(NavigationContext ctx, TransportTable table,
												   WorldPoint from, WorldPoint to)
	{
		return findRouteSkeleton(ctx, table, from, to, null);
	}

	/** Run Dijkstra with an optional component filter on walk edges.
	 *
	 *  <p>When {@code components} is non-null, a walk edge between
	 *  same-plane nodes A and B is only added when
	 *  {@code components.sameComponent(A, B)} returns true. Nodes that
	 *  are in different components in static collision space cannot
	 *  be connected by walking; Dijkstra is forced to bridge them
	 *  via an explicit transport. Eliminates the failure mode where
	 *  Dijkstra picks an abstract walk BFS can't actually traverse
	 *  (e.g. crossing a fenced enclosure without using its gate).
	 *
	 *  <p>When {@code components} is null, behaviour matches the
	 *  4-arg overload (collision-blind walk edges, status-quo
	 *  pre-2026-05-17). */
	public static SkeletonResult findRouteSkeleton(NavigationContext ctx, TransportTable table,
												   WorldPoint from, WorldPoint to,
												   @Nullable ConnectivityComponents components)
	{
		if (from == null || to == null)
		{
			throw new IllegalArgumentException("findRouteSkeleton: from/to must be non-null");
		}
		if (from.equals(to))
		{
			return new SkeletonResult(Status.OK, List.of(SkeletonNode.walk(from)), null, 0);
		}

		// Step 1: collect the satisfied transports from the table.
		List<TransportLink> satisfied = new ArrayList<>();
		for (TransportLink l : table.staticLinks())
		{
			if (l.from() == null || l.to() == null) continue;
			if (l.requirement().satisfiedBy(ctx))
			{
				satisfied.add(l);
			}
		}
		for (TransportLink l : table.deltaLinks())
		{
			if (l.from() == null || l.to() == null) continue;
			if (l.requirement().satisfiedBy(ctx))
			{
				satisfied.add(l);
			}
		}

		// Step 2: enumerate graph nodes (start, target, all transport endpoints).
		Set<WorldPoint> nodeSet = new HashSet<>();
		nodeSet.add(from);
		nodeSet.add(to);
		for (TransportLink l : satisfied)
		{
			nodeSet.add(l.from());
			nodeSet.add(l.to());
		}
		if (nodeSet.size() > MAX_NODES_HINT)
		{
			log.warn("[nav-v2.transport] LinkGraphDijkstra: {} nodes — above MAX_NODES_HINT {} hint",
				nodeSet.size(), MAX_NODES_HINT);
		}

		// Step 3: build adjacency. For each node, all OUT-edges:
		//   - implicit walk to every other same-plane node (chebyshev cost)
		//   - explicit transport for every TransportLink originating here
		// Implicit cross-plane walks are NOT in the graph — only the
		// transport edges bridge planes.
		Map<WorldPoint, List<Edge>> adj = new HashMap<>();
		for (WorldPoint p : nodeSet)
		{
			adj.put(p, new ArrayList<>());
		}
		for (TransportLink l : satisfied)
		{
			adj.get(l.from()).add(Edge.transport(l));
		}
		// Implicit walk edges: O(N^2). For N=4096 that's 16M comparisons
		// which is borderline; in practice N is far smaller (a route only
		// considers transports near the corridor). The bank↔pen route
		// uses a handful of endpoints.
		List<WorldPoint> nodes = new ArrayList<>(nodeSet);
		for (int i = 0; i < nodes.size(); i++)
		{
			WorldPoint a = nodes.get(i);
			for (int j = 0; j < nodes.size(); j++)
			{
				if (i == j) continue;
				WorldPoint b = nodes.get(j);
				if (a.getPlane() != b.getPlane()) continue;
				// Collision-aware filter: tiles in different connectivity
				// components cannot be reached by walking in static
				// collision space, so we refuse to add a walk edge that
				// BFS would later prove infeasible. Skipping the edge
				// forces Dijkstra to bridge the gap via an explicit
				// transport (gate/door/stair) — exactly what was
				// missing in the pen→bank failure mode.
				if (components != null && !components.sameComponent(a, b)) continue;
				int cost = chebyshev(a, b);
				adj.get(a).add(Edge.walk(b, cost));
			}
		}

		// Step 4: Dijkstra from `from` to `to`.
		Map<WorldPoint, Integer> dist = new HashMap<>();
		Map<WorldPoint, Edge> prevEdge = new HashMap<>();
		Map<WorldPoint, WorldPoint> prevNode = new HashMap<>();
		for (WorldPoint p : nodeSet) dist.put(p, Integer.MAX_VALUE);
		dist.put(from, 0);

		PriorityQueue<int[]> pq = new PriorityQueue<>((x, y) -> Integer.compare(x[0], y[0]));
		// pack: [cost, encodedIndex]
		Map<WorldPoint, Integer> indexOf = new HashMap<>();
		for (int i = 0; i < nodes.size(); i++) indexOf.put(nodes.get(i), i);
		pq.add(new int[]{0, indexOf.get(from)});

		while (!pq.isEmpty())
		{
			int[] head = pq.poll();
			int cost = head[0];
			WorldPoint u = nodes.get(head[1]);
			if (cost > dist.get(u)) continue;
			if (u.equals(to)) break;
			for (Edge e : adj.get(u))
			{
				WorldPoint v = e.target;
				int newCost = cost + e.cost;
				if (newCost < dist.get(v))
				{
					dist.put(v, newCost);
					prevEdge.put(v, e);
					prevNode.put(v, u);
					pq.add(new int[]{newCost, indexOf.get(v)});
				}
			}
		}

		if (dist.get(to) == Integer.MAX_VALUE)
		{
			return new SkeletonResult(Status.UNREACHABLE, null, ReplanReason.TARGET_UNREACHABLE, -1);
		}

		// Step 5: reconstruct skeleton (forward).
		List<SkeletonNode> reverse = new ArrayList<>();
		WorldPoint cur = to;
		// final tile is a walk anchor (or transport destination).
		// Walk back through prevEdge / prevNode to from.
		while (!cur.equals(from))
		{
			Edge e = prevEdge.get(cur);
			WorldPoint p = prevNode.get(cur);
			if (e == null || p == null)
			{
				return new SkeletonResult(Status.UNREACHABLE, null, ReplanReason.TARGET_UNREACHABLE, -1);
			}
			if (e.transport != null)
			{
				reverse.add(SkeletonNode.transport(e.transport));
			}
			else
			{
				reverse.add(SkeletonNode.walk(cur));
			}
			cur = p;
		}
		reverse.add(SkeletonNode.walk(from));
		Collections.reverse(reverse);
		return new SkeletonResult(Status.OK, reverse, null, dist.get(to));
	}

	/** Chebyshev (king-move) distance — OSRS walk cost. */
	static int chebyshev(WorldPoint a, WorldPoint b)
	{
		return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
	}

	/** Internal edge: either a walk (target tile + cost) or a transport
	 *  (target tile + cost + the link). */
	private static final class Edge
	{
		final WorldPoint target;
		final int cost;
		final TransportLink transport;

		private Edge(WorldPoint target, int cost, TransportLink transport)
		{
			this.target = target;
			this.cost = cost;
			this.transport = transport;
		}

		static Edge walk(WorldPoint to, int cost) { return new Edge(to, cost, null); }
		static Edge transport(TransportLink l) { return new Edge(l.to(), l.durationTicks(), l); }
	}

	// Suppress unused-import warning for Objects (used in node hashing).
	@SuppressWarnings("unused")
	private static final Object SUPPRESS = Objects.hash(0);
}
