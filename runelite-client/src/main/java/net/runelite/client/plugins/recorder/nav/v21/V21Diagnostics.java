package net.runelite.client.plugins.recorder.nav.v21;

import net.runelite.api.coords.WorldPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Single point of structured logging for v2.1. Every failure mode
 *  passes through one of these methods so the log-line shape is
 *  consistent — no more "look up which executor field tells you the
 *  reason" archaeology.
 *
 *  <p>Goal of these logs is operability: if the bot stops navigating,
 *  the last 5-10 log lines tell you exactly what it tried, what was
 *  in the scene, and where it gave up. That's the entire point of
 *  v2.1's typed failures. */
public final class V21Diagnostics
{
	private static final Logger log = LoggerFactory.getLogger("nav-v21");

	public void plan(WorldPoint from, Goal goal, PlanResult result)
	{
		if (result instanceof PlanResult.Success s)
		{
			log.info("plan ok: from={} goal={} tiles={} approach={} reachedGoal={}",
				from, summarize(goal), s.tiles().size(), s.approach(), s.reachedGoal());
		}
		else if (result instanceof PlanResult.BlockedEdge be)
		{
			log.info("plan blocked-edge: from={} goal={} edge={}→{} reason={}",
				from, summarize(goal), be.from(), be.to(), be.reason());
		}
		else if (result instanceof PlanResult.PlaneMismatch pm)
		{
			log.info("plan plane-mismatch: from={} goal={} fromPlane={} toPlane={}",
				from, summarize(goal), pm.fromPlane(), pm.toPlane());
		}
		else if (result instanceof PlanResult.BudgetExhausted bu)
		{
			log.info("plan budget-exhausted: from={} goal={} expanded={} — walking partway",
				from, summarize(goal), bu.expanded());
		}
		else if (result instanceof PlanResult.NoCandidate nc)
		{
			log.warn("plan no-candidate: from={} goal={} why={}",
				from, summarize(goal), nc.why());
		}
	}

	public void blockerFound(WorldPoint edgeFrom, WorldPoint edgeTo, BlockerCandidate b)
	{
		log.info("blocker found: edge={}→{} verb={} object={} at={}",
			edgeFrom, edgeTo, b.verb(), b.objectId(), b.objectTile());
	}

	public void blockerNotFound(WorldPoint edgeFrom, WorldPoint edgeTo)
	{
		log.warn("blocker NOT found on edge {}→{} — likely missing collision data, "
			+ "non-interactable obstacle, or scene still loading", edgeFrom, edgeTo);
	}

	public void planeMismatchScanning(WorldPoint here)
	{
		log.info("plane-mismatch: scanning for Climb verb near {}", here);
	}

	public void hardStall(int ticks, Goal goal, WorldPoint where)
	{
		log.warn("HARD_STALL after {} ticks at {} goal={} — returning FAILED",
			ticks, where, summarize(goal));
	}

	public void arrived(Goal goal, WorldPoint where)
	{
		log.info("ARRIVED: goal={} at={}", summarize(goal), where);
	}

	public void newRequest(Object newReq, Object oldReq)
	{
		log.info("new request: {} (was {})", newReq, oldReq);
	}

	public void cancelled()
	{
		log.info("cancel");
	}

	public void noPlayer()
	{
		log.warn("NO_PLAYER_LOC — yielding FAILED (login/logout?)");
	}

	private static String summarize(Goal goal)
	{
		if (goal instanceof Goal.Tile t) return "Tile@" + t.at();
		if (goal instanceof Goal.Area a) return "Area@" + a.center() + "r=" + a.radius();
		if (goal instanceof Goal.NearNpc n) return "NearNpc(" + n.name() + ")@" + n.anchor();
		if (goal instanceof Goal.NearObject o) return "NearObject(" + o.name() + ")@" + o.anchor();
		return goal.toString();
	}
}
