package net.runelite.client.plugins.recorder.agility;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.scripts.RooftopAgilityScript.RooftopCourseId;

public class CaptureModel
{
	// Course identity (set in constructor)
	public RooftopCourseId targetId;
	public String label;
	public int agilityLevelReq;
	public int expectedObstacleCount;

	// Per-obstacle merged data
	public List<ObstacleObservation> obstacles = new ArrayList<>();
	public List<ObstacleSignature>   canonicalSequence = null;
	public int cleanMatchingLaps = 0;

	// Course geometry (filled at first-ever SUCCESS / lap completion)
	public Set<WorldPoint> approachTiles = new HashSet<>();
	public Set<WorldPoint> startTiles    = new HashSet<>();
	public WorldPoint      lapEndTile    = null;

	// Tile commit
	public Set<WorldPoint> validTiles = new HashSet<>();

	// Per-lap in-flight buffers
	public Set<WorldPoint>            currentLapTiles = new HashSet<>();
	public List<ObstacleObservation>  currentLapObs   = new ArrayList<>();

	// Lap-state machine
	public PendingClick pendingClick   = null;
	public boolean      currentLapDirty = false;
	public LapState     state          = LapState.ARMED;

	// Approach ring buffer (10s window of player tiles before first SUCCESS)
	public Deque<Sample> approachRing = new ArrayDeque<>();

	public long capturedAtMs = 0L;

	public CaptureModel(RooftopCourseId targetId, String label, int agilityLevelReq, int expectedObstacleCount)
	{
		this.targetId              = targetId;
		this.label                 = label;
		this.agilityLevelReq       = agilityLevelReq;
		this.expectedObstacleCount = expectedObstacleCount;
		this.capturedAtMs          = System.currentTimeMillis();
	}

	public static final class Sample
	{
		public final long t;
		public final WorldPoint p;
		public Sample(long t, WorldPoint p) { this.t = t; this.p = p; }
	}

	public void reset()
	{
		obstacles.clear();
		canonicalSequence = null;
		cleanMatchingLaps = 0;
		approachTiles.clear();
		startTiles.clear();
		lapEndTile = null;
		validTiles.clear();
		currentLapTiles.clear();
		currentLapObs.clear();
		pendingClick = null;
		currentLapDirty = false;
		state = LapState.ARMED;
		approachRing.clear();
	}
}
