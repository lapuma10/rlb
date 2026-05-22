package net.runelite.client.plugins.recorder.agility;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.RuneLite;

@Slf4j
public final class CourseJsonWriter
{
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

	/** Atomic write via temp + rename. Returns the absolute Path written. */
	public Path write(CaptureModel m) throws IOException
	{
		return writeWithBase(m, m.targetId.name().toLowerCase());
	}

	/** Writes to a different filename when the target already exists (save-as-new). */
	public Path writeAs(CaptureModel m, String filenameNoExt) throws IOException
	{
		return writeWithBase(m, filenameNoExt);
	}

	private Path writeWithBase(CaptureModel m, String baseName) throws IOException
	{
		JsonObject root = new JsonObject();
		root.addProperty("id", m.targetId.name());
		root.addProperty("label", m.label);
		root.addProperty("agilityLevel", m.agilityLevelReq);
		root.addProperty("scanRadius", 14);
		root.add("approachTiles", tilesArray(m.approachTiles));
		root.add("startTiles",    tilesArray(m.startTiles));
		root.add("fallTiles",     new JsonArray());
		JsonArray lapEnd = new JsonArray();
		if (m.lapEndTile != null)
		{
			lapEnd.add(toTileArray(m.lapEndTile));
		}
		root.add("lapEndTiles", lapEnd);
		root.add("validTiles", tilesArray(m.validTiles));

		JsonArray obstacles = new JsonArray();
		for (int i = 0; i < m.obstacles.size(); i++)
		{
			boolean isFinal = (i == m.obstacles.size() - 1);
			obstacles.add(obstacleJson(m.obstacles.get(i), isFinal));
		}
		root.add("obstacles", obstacles);

		Path dir = Paths.get(RuneLite.RUNELITE_DIR.getAbsolutePath(), "recorder", "rooftops");
		Files.createDirectories(dir);
		Path tmp = dir.resolve(baseName + ".json.tmp");
		Path finalPath = dir.resolve(baseName + ".json");

		Files.writeString(tmp, gson.toJson(root));
		try
		{
			Files.move(tmp, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException e)
		{
			Files.move(tmp, finalPath, StandardCopyOption.REPLACE_EXISTING);
		}

		log.info("[agility-capture] wrote {}", finalPath);
		return finalPath;
	}

	private JsonObject obstacleJson(ObstacleObservation o, boolean isFinal)
	{
		JsonObject j = new JsonObject();
		j.addProperty("label",    mostFrequentString(o.objectLabels));
		j.addProperty("objectId", mostFrequentInt(o.objectIds));
		j.addProperty("action",   mostFrequentString(o.verbs));
		j.add("objectTiles",  tilesArray(o.objectTiles));
		j.add("stageTiles",   tilesArray(o.stageTiles));
		j.add("successTiles", isFinal ? new JsonArray() : tilesArray(o.successTiles));
		long timeoutMs = Math.max(4_000L, Math.min(12_000L, Math.round(o.maxClickToXpMs * 1.5)));
		j.addProperty("timeoutMs", timeoutMs);
		return j;
	}

	private static JsonArray tilesArray(Iterable<WorldPoint> tiles)
	{
		JsonArray arr = new JsonArray();
		for (WorldPoint p : tiles)
		{
			if (p == null)
			{
				continue;
			}
			arr.add(toTileArray(p));
		}
		return arr;
	}

	private static JsonArray toTileArray(WorldPoint p)
	{
		JsonArray a = new JsonArray();
		a.add(p.getX());
		a.add(p.getY());
		a.add(p.getPlane());
		return a;
	}

	/** Most-frequent integer; on tie, lowest int wins. Returns 0 on empty input. */
	private static int mostFrequentInt(Iterable<Integer> in)
	{
		Map<Integer, Integer> counts = new HashMap<>();
		for (Integer v : in)
		{
			counts.merge(v, 1, Integer::sum);
		}
		int bestVal = 0, bestCount = -1;
		for (Map.Entry<Integer, Integer> e : counts.entrySet())
		{
			int v = e.getKey();
			int c = e.getValue();
			if (c > bestCount || (c == bestCount && v < bestVal))
			{
				bestVal = v;
				bestCount = c;
			}
		}
		return bestVal;
	}

	/** Most-frequent string; on tie, alphabetical lowest wins. Returns "" on empty. */
	private static String mostFrequentString(Iterable<String> in)
	{
		Map<String, Integer> counts = new HashMap<>();
		for (String v : in)
		{
			if (v == null)
			{
				continue;
			}
			counts.merge(v, 1, Integer::sum);
		}
		String bestVal = "";
		int bestCount = -1;
		for (Map.Entry<String, Integer> e : counts.entrySet())
		{
			String v = e.getKey();
			int c = e.getValue();
			if (c > bestCount || (c == bestCount && v.compareTo(bestVal) < 0))
			{
				bestVal = v;
				bestCount = c;
			}
		}
		return bestVal;
	}
}
