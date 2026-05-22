package net.runelite.client.plugins.recorder.agility;

import java.util.HashSet;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;

public class ObstacleObservation
{
	public int orderIndex = 0;
	public Set<Integer> objectIds = new HashSet<>();
	public Set<String> verbs = new HashSet<>();
	public Set<String> objectLabels = new HashSet<>();
	public Set<WorldPoint> stageTiles = new HashSet<>();
	public Set<WorldPoint> objectTiles = new HashSet<>();
	public Set<WorldPoint> successTiles = new HashSet<>();
	public long maxClickToXpMs = 0L;
	public int successCount = 0;
	public ObstacleSignature signature = null;
}
