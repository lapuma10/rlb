package net.runelite.client.plugins.recorder.nav.v2.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.runelite.api.coords.WorldPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Parses Skretzo's transport TSV format into {@link TransportLink}
 *  records. Loud per-row failure reporting per spec §4 Lane 4 — every
 *  malformed row is captured in {@link LoadResult#invalidRows()} with
 *  source file + line number + reason. Never silently drops.
 *
 *  <p>The Skretzo TSV format varies per file. The loader inspects the
 *  first {@code #}-prefixed header to map columns and to pick a default
 *  {@link TransportType}. Subsequent rows split on {@code \t}, then the
 *  third column ({@code menuOption menuTarget objectID}) is space-split
 *  internally.
 *
 *  <p>Files supported in this Lane-4 ship:
 *  <ul>
 *    <li>{@code transports.tsv} — doors, gates, stairs, ladders.</li>
 *    <li>{@code agility_shortcuts.tsv}</li>
 *    <li>{@code fairy_rings.tsv}</li>
 *    <li>{@code spirit_trees.tsv}</li>
 *    <li>{@code teleportation_items.tsv}</li>
 *    <li>{@code teleportation_spells.tsv}</li>
 *  </ul>
 *
 *  <p>Coordinate parsing: tile encoded as {@code "X Y plane"}
 *  (space-separated, no commas). Empty origin/destination strings
 *  parse to {@code null} (e.g. teleport-from-anywhere, or
 *  configure-fairy-ring rows where destination is chosen at use).
 *
 *  <p>Skill requirements: {@code "<level> <SkillName>"} — e.g.
 *  {@code "1 Agility"}. Multiple skills separated by {@code ;}. */
public final class TransportTableLoader
{
	private static final Logger log = LoggerFactory.getLogger(TransportTableLoader.class);

	/** Classpath root for the vendored TSVs (top-level {@code /nav/transports/}
	 *  — NOT under {@code runelite/} per the gradle-plugin parseInt conflict). */
	public static final String CLASSPATH_ROOT = "/nav/transports/";

	/** Canonical filenames the vendored ship includes. Refresh procedure
	 *  in {@code MANIFEST.md}. */
	public static final List<String> BUNDLED_FILES = Collections.unmodifiableList(Arrays.asList(
		"transports.tsv",
		"agility_shortcuts.tsv",
		"fairy_rings.tsv",
		"spirit_trees.tsv",
		"teleportation_items.tsv",
		"teleportation_spells.tsv",
		// Local overrides — transports Skretzo's upstream data doesn't
		// model (e.g. the Lumbridge chicken pen gate). Loaded last and
		// merged into the same TransportTable; same TSV format as
		// transports.tsv. Add rows here when the collision-debug overlay
		// shows V2 hitting a wall that has a real gate/door in-game
		// without a corresponding entry.
		"transports-overrides.tsv"
	));

	private TransportTableLoader() {}

	/** Result of one {@link #load(InputStream, String)} call. */
	public static final class LoadResult
	{
		private final String sourceFile;
		private final List<TransportLink> links;
		private final List<InvalidRow> invalidRows;

		LoadResult(String sourceFile, List<TransportLink> links, List<InvalidRow> invalidRows)
		{
			this.sourceFile = sourceFile;
			this.links = Collections.unmodifiableList(links);
			this.invalidRows = Collections.unmodifiableList(invalidRows);
		}

		public String sourceFile() { return sourceFile; }
		public List<TransportLink> links() { return links; }
		public List<InvalidRow> invalidRows() { return invalidRows; }
	}

	/** Diagnostic record for a row that failed to parse. */
	public static final class InvalidRow
	{
		private final String sourceFile;
		private final int sourceLine;
		private final String rawLine;
		private final String reason;

		InvalidRow(String sourceFile, int sourceLine, String rawLine, String reason)
		{
			this.sourceFile = sourceFile;
			this.sourceLine = sourceLine;
			this.rawLine = rawLine;
			this.reason = reason;
		}

		public String sourceFile() { return sourceFile; }
		public int sourceLine() { return sourceLine; }
		public String rawLine() { return rawLine; }
		public String reason() { return reason; }
	}

	/** Load all bundled TSVs from the classpath. Logs per-file load
	 *  stats at INFO. */
	public static List<LoadResult> loadAllFromClasspath()
	{
		List<LoadResult> results = new ArrayList<>();
		for (String name : BUNDLED_FILES)
		{
			InputStream in = TransportTableLoader.class.getResourceAsStream(CLASSPATH_ROOT + name);
			if (in == null)
			{
				log.warn("[nav-v2.transport] bundled TSV missing on classpath: {}{}",
					CLASSPATH_ROOT, name);
				continue;
			}
			try (InputStream closable = in)
			{
				LoadResult r = load(closable, name);
				log.info("[nav-v2.transport] loaded {}: {} links, {} invalid",
					name, r.links().size(), r.invalidRows().size());
				results.add(r);
			}
			catch (IOException e)
			{
				log.error("[nav-v2.transport] failed to load {}: {}", name, e.getMessage(), e);
			}
		}
		return results;
	}

	/** Parse one TSV stream. {@code sourceFile} is a label used in
	 *  {@link InvalidRow#sourceFile()} for diagnostics. */
	public static LoadResult load(InputStream in, String sourceFile)
	{
		List<TransportLink> links = new ArrayList<>();
		List<InvalidRow> invalid = new ArrayList<>();

		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
		{
			ColumnMap cols = null;
			TransportType defaultType = guessDefaultTypeFromFilename(sourceFile);
			int lineNumber = 0;
			String line;
			while ((line = r.readLine()) != null)
			{
				lineNumber++;
				if (line.isEmpty())
				{
					continue;
				}
				// Lines that are entirely tab-whitespace (section dividers
				// in Skretzo TSVs) are not malformed — skip silently.
				if (isBlankOrTabsOnly(line))
				{
					continue;
				}
				// First # line we see is the header; later # lines are
				// comments / dividers and are skipped.
				if (line.startsWith("#"))
				{
					if (cols == null)
					{
						ColumnMap parsed = ColumnMap.fromHeader(line);
						// If the header does not carry any recognized field
						// names (e.g. test-supplied "# header"), keep cols
						// null so subsequent non-# rows trigger the default
						// fallback below.
						if (parsed.hasAnyKnownField())
						{
							cols = parsed;
						}
					}
					continue;
				}
				if (cols == null)
				{
					// No useful header seen — assume default columns for the file.
					cols = ColumnMap.defaultsFor(sourceFile);
				}
				try
				{
					TransportLink link = parseRow(line, cols, defaultType, sourceFile, lineNumber);
					if (link != null)
					{
						links.add(link);
					}
				}
				catch (RuntimeException ex)
				{
					String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
					log.error("[nav-v2.transport] invalid row in {}:{}: {} | line=\"{}\"",
						sourceFile, lineNumber, reason, line);
					invalid.add(new InvalidRow(sourceFile, lineNumber, line, reason));
				}
			}
		}
		catch (IOException e)
		{
			throw new RuntimeException("I/O error reading " + sourceFile, e);
		}

		return new LoadResult(sourceFile, links, invalid);
	}

	/** Default type for a file when the header doesn't carry it. */
	private static TransportType guessDefaultTypeFromFilename(String filename)
	{
		String n = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
		if (n.contains("agility")) return TransportType.AGILITY_SHORTCUT;
		if (n.contains("fairy")) return TransportType.FAIRY_RING;
		if (n.contains("spirit_tree")) return TransportType.SPIRIT_TREE;
		if (n.contains("teleportation_item")) return TransportType.TELEPORT_ITEM;
		if (n.contains("teleportation_spell")) return TransportType.TELEPORT_SPELL;
		if (n.contains("charter")) return TransportType.CHARTER;
		if (n.contains("boat") || n.contains("ship")) return TransportType.CHARTER;
		if (n.contains("portal") || n.contains("box") || n.contains("minigame") || n.contains("lever"))
			return TransportType.TELEPORT_ITEM;
		// transports.tsv is mixed — type is determined per-row from the menuOption verb.
		return TransportType.DOOR;
	}

	/** Per-row type inference for {@code transports.tsv} based on the
	 *  menu verb. */
	private static TransportType inferTypeFromVerb(String verb, TransportType defaultType, WorldPoint from, WorldPoint to)
	{
		if (verb == null) return defaultType;
		String v = verb.toLowerCase(Locale.ROOT);
		if (v.contains("climb-up") || v.contains("climb up")) return TransportType.STAIRS_UP;
		if (v.contains("climb-down") || v.contains("climb down")) return TransportType.STAIRS_DOWN;
		if (v.contains("ascend") || v.contains("ladder up")) return TransportType.STAIRS_UP;
		if (v.contains("descend") || v.contains("ladder down")) return TransportType.STAIRS_DOWN;
		if (from != null && to != null && from.getPlane() != to.getPlane())
		{
			return to.getPlane() > from.getPlane() ? TransportType.STAIRS_UP : TransportType.STAIRS_DOWN;
		}
		if (v.contains("open gate") || v.contains("gate")) return TransportType.GATE;
		if (v.contains("open door") || v.contains("door")) return TransportType.DOOR;
		return defaultType;
	}

	private static TransportLink parseRow(String line, ColumnMap cols,
										  TransportType defaultType,
										  String sourceFile, int lineNumber)
	{
		String[] fields = line.split("\t", -1);
		String originStr = cols.originIdx >= 0 ? safeField(fields, cols.originIdx) : "";
		String destStr = cols.destIdx >= 0 ? safeField(fields, cols.destIdx) : "";
		String menuStr = cols.menuIdx >= 0 ? safeField(fields, cols.menuIdx) : "";
		String skillsStr = cols.skillsIdx >= 0 ? safeField(fields, cols.skillsIdx) : "";
		String itemsStr = cols.itemsIdx >= 0 ? safeField(fields, cols.itemsIdx) : "";
		String questsStr = cols.questsIdx >= 0 ? safeField(fields, cols.questsIdx) : "";
		String varbitsStr = cols.varbitsIdx >= 0 ? safeField(fields, cols.varbitsIdx) : "";
		String varplayersStr = cols.varplayersIdx >= 0 ? safeField(fields, cols.varplayersIdx) : "";
		String durationStr = cols.durationIdx >= 0 ? safeField(fields, cols.durationIdx) : "";

		WorldPoint origin = parseCoordOrNull(originStr);
		WorldPoint dest = parseCoordOrNull(destStr);

		// A row with neither origin nor destination is meaningless.
		if (origin == null && dest == null)
		{
			throw new IllegalArgumentException("row has neither origin nor destination");
		}

		MenuParse mp = parseMenu(menuStr);
		String verb = mp.verb;
		Optional<Integer> objId = mp.objectId;

		TransportType type = inferTypeFromVerb(verb, defaultType, origin, dest);

		TransportRequirement requirement = buildRequirement(
			skillsStr, itemsStr, questsStr, varbitsStr, varplayersStr);

		int duration = parseDurationOrDefault(durationStr);

		return new TransportLink(
			origin,
			dest,
			type,
			objId,
			verb == null || verb.isEmpty() ? Optional.empty() : Optional.of(verb),
			requirement,
			duration,
			false,
			sourceFile,
			lineNumber);
	}

	private static String safeField(String[] fields, int idx)
	{
		if (idx < 0 || idx >= fields.length) return "";
		return fields[idx];
	}

	/** True iff every character is whitespace (incl. tabs). Used to
	 *  skip Skretzo's empty section-divider rows. */
	static boolean isBlankOrTabsOnly(String line)
	{
		for (int i = 0; i < line.length(); i++)
		{
			if (!Character.isWhitespace(line.charAt(i))) return false;
		}
		return true;
	}

	/** Parse "{@code X Y plane}" → {@link WorldPoint}; empty → null. */
	static WorldPoint parseCoordOrNull(String s)
	{
		if (s == null) return null;
		String t = s.trim();
		if (t.isEmpty()) return null;
		String[] parts = t.split("\\s+");
		if (parts.length < 3)
		{
			throw new IllegalArgumentException("bad coord (need X Y plane): \"" + s + "\"");
		}
		try
		{
			int x = Integer.parseInt(parts[0]);
			int y = Integer.parseInt(parts[1]);
			int p = Integer.parseInt(parts[2]);
			return new WorldPoint(x, y, p);
		}
		catch (NumberFormatException nfe)
		{
			throw new IllegalArgumentException("bad coord (non-int): \"" + s + "\"", nfe);
		}
	}

	private static final class MenuParse
	{
		final String verb;
		final Optional<Integer> objectId;
		MenuParse(String verb, Optional<Integer> objectId)
		{
			this.verb = verb;
			this.objectId = objectId;
		}
	}

	/** Parse Skretzo's "{@code Open Door 9398}" format. The last
	 *  numeric token is the objectID; everything before is the verb.
	 *  Some rows (configure fairy ring) end in a number too — same
	 *  convention. */
	static MenuParse parseMenu(String s)
	{
		if (s == null) return new MenuParse(null, Optional.empty());
		String t = s.trim();
		if (t.isEmpty()) return new MenuParse(null, Optional.empty());
		String[] parts = t.split("\\s+");
		// Try to peel a trailing integer as the objectID.
		Optional<Integer> objId = Optional.empty();
		int verbEnd = parts.length;
		try
		{
			int id = Integer.parseInt(parts[parts.length - 1]);
			objId = Optional.of(id);
			verbEnd = parts.length - 1;
		}
		catch (NumberFormatException ignored)
		{
			// No trailing integer; whole string is the verb.
		}
		String verb = String.join(" ", Arrays.copyOfRange(parts, 0, verbEnd));
		return new MenuParse(verb.isEmpty() ? null : verb, objId);
	}

	/** Build a composite {@link TransportRequirement} from the relevant
	 *  TSV columns. {@code TransportRequirement.NONE} when all are empty. */
	private static TransportRequirement buildRequirement(String skills, String items,
														 String quests, String varbits,
														 String varplayers)
	{
		List<TransportRequirement> reqs = new ArrayList<>();
		addSkillRequirements(reqs, skills);
		addItemRequirements(reqs, items);
		addVarbitRequirements(reqs, varbits);
		addVarplayerRequirements(reqs, varplayers);
		// Quest gating uses varbits in OSRS; if the TSV's Quests column has
		// content but no varbit row, treat as a generic "true if member" no-op
		// for now (quest progress is read via varbits in PlayerState). The
		// resolver lives in TransportRequirementEvaluator and is enriched there.
		Objects.requireNonNull(quests);  // intentionally unused — parsed downstream
		if (reqs.isEmpty()) return TransportRequirement.NONE;
		if (reqs.size() == 1) return reqs.get(0);
		return TransportRequirementEvaluator.requireAll(reqs);
	}

	private static void addSkillRequirements(List<TransportRequirement> out, String s)
	{
		if (s == null || s.trim().isEmpty()) return;
		// Multiple skills separated by ';' (Skretzo convention).
		for (String entry : s.split(";"))
		{
			String e = entry.trim();
			if (e.isEmpty()) continue;
			String[] tok = e.split("\\s+");
			if (tok.length < 2)
			{
				// Bad row; let the outer try/catch capture it.
				throw new IllegalArgumentException("bad skill spec: \"" + entry + "\"");
			}
			try
			{
				int level = Integer.parseInt(tok[0]);
				String skillName = tok[1];
				out.add(TransportRequirementEvaluator.requireSkill(skillName, level));
			}
			catch (NumberFormatException nfe)
			{
				throw new IllegalArgumentException("bad skill level in \"" + entry + "\"", nfe);
			}
		}
	}

	private static void addItemRequirements(List<TransportRequirement> out, String s)
	{
		if (s == null || s.trim().isEmpty()) return;
		// Skretzo's item-spec grammar (compiled from a preprocessor):
		//   - "id=qty" — plain
		//   - "id=qty||id=qty" or "id=qty|id=qty" — OR
		//   - "id=qty&&id=qty" or "id=qty&id=qty" — AND
		//   - symbolic constants (e.g. CROSSBOW=1) — resolved at their
		//     build step; here we drop the symbolic leaf and keep going.
		//   - ";" — separates independent item gates (logical AND).
		// Lane 4 parses the OR cases and the simple cases; AND cases
		// with symbolic constants and bit-masks (`&` / `@`) are dropped
		// as symbolic.
		for (String entry : s.split(";"))
		{
			String e = entry.trim();
			if (e.isEmpty()) continue;
			// AND-of-items: split on && or single & (when not part of an
			// "@" or other operator). Treat each arm as its own item.
			if (e.contains("&&"))
			{
				List<TransportRequirement> ands = new ArrayList<>();
				for (String arm : e.split("&&"))
				{
					addItemArm(ands, arm.trim());
				}
				if (!ands.isEmpty()) out.add(TransportRequirementEvaluator.requireAll(ands));
				continue;
			}
			if (e.contains("||") || e.contains("|"))
			{
				out.add(TransportRequirementEvaluator.requireAnyItemFromList(e));
				continue;
			}
			addItemArm(out, e);
		}
	}

	/** One arm of an item spec — emits a single requirement or drops
	 *  the leaf as symbolic. */
	private static void addItemArm(List<TransportRequirement> out, String entry)
	{
		String e = entry.trim();
		if (e.isEmpty()) return;
		// Skretzo "id=qty" — accept ints only.
		String[] kv = e.split("=");
		if (kv.length != 2)
		{
			log.debug("[nav-v2.transport] non-id item spec \"{}\" — dropped", entry);
			return;
		}
		try
		{
			int id = Integer.parseInt(kv[0].trim());
			int qty = Integer.parseInt(kv[1].trim());
			out.add(TransportRequirementEvaluator.requireItem(id, qty));
		}
		catch (NumberFormatException nfe)
		{
			log.debug("[nav-v2.transport] symbolic item spec \"{}\" — dropped", entry);
		}
	}

	private static void addVarbitRequirements(List<TransportRequirement> out, String s)
	{
		if (s == null || s.trim().isEmpty()) return;
		for (String entry : s.split(";"))
		{
			String e = entry.trim();
			if (e.isEmpty()) continue;
			int op = -1;
			char operator = '=';
			for (int i = 0; i < e.length(); i++)
			{
				char c = e.charAt(i);
				if (c == '=' || c == '<' || c == '>')
				{
					op = i;
					operator = c;
					break;
				}
				if (c == '&' || c == '@')
				{
					log.debug("[nav-v2.transport] bit-op varbit spec \"{}\" — dropped", entry);
					op = -2;
					break;
				}
			}
			if (op == -2) continue;
			if (op < 0)
			{
				throw new IllegalArgumentException("bad varbit spec: \"" + entry + "\"");
			}
			try
			{
				int id = Integer.parseInt(e.substring(0, op).trim());
				int val = Integer.parseInt(e.substring(op + 1).trim());
				out.add(TransportRequirementEvaluator.requireVarbit(id, val, operator));
			}
			catch (NumberFormatException nfe)
			{
				log.debug("[nav-v2.transport] symbolic varbit spec \"{}\" — dropped", entry);
			}
		}
	}

	private static void addVarplayerRequirements(List<TransportRequirement> out, String s)
	{
		if (s == null || s.trim().isEmpty()) return;
		for (String entry : s.split(";"))
		{
			String e = entry.trim();
			if (e.isEmpty()) continue;
			// Recognized forms: "id=value" / "id<value" / "id>value".
			// Skretzo also uses "id&bitmask" and "id@cmp" as compiled
			// bit-extraction operators. Lane 4 treats unknown operators
			// as symbolic and drops the leaf (link still loads).
			int op = -1;
			char operator = '=';
			for (int i = 0; i < e.length(); i++)
			{
				char c = e.charAt(i);
				if (c == '=' || c == '<' || c == '>')
				{
					op = i;
					operator = c;
					break;
				}
				if (c == '&' || c == '@')
				{
					log.debug("[nav-v2.transport] bit-op varplayer spec \"{}\" — dropped", entry);
					op = -2;
					break;
				}
			}
			if (op == -2) continue;
			if (op < 0)
			{
				throw new IllegalArgumentException("bad varplayer spec: \"" + entry + "\"");
			}
			try
			{
				int id = Integer.parseInt(e.substring(0, op).trim());
				int val = Integer.parseInt(e.substring(op + 1).trim());
				out.add(TransportRequirementEvaluator.requireVarplayer(id, val, operator));
			}
			catch (NumberFormatException nfe)
			{
				log.debug("[nav-v2.transport] symbolic varplayer spec \"{}\" — dropped", entry);
			}
		}
	}

	private static int parseDurationOrDefault(String s)
	{
		if (s == null) return 1;
		String t = s.trim();
		if (t.isEmpty()) return 1;
		try { return Integer.parseInt(t); }
		catch (NumberFormatException nfe) { return 1; }
	}

	/** Column-index mapping derived from the file's header row. */
	static final class ColumnMap
	{
		int originIdx = -1;
		int destIdx = -1;
		int menuIdx = -1;
		int skillsIdx = -1;
		int itemsIdx = -1;
		int questsIdx = -1;
		int varbitsIdx = -1;
		int varplayersIdx = -1;
		int durationIdx = -1;

		static ColumnMap fromHeader(String headerLine)
		{
			// Strip leading '#' and split.
			String h = headerLine.startsWith("#") ? headerLine.substring(1) : headerLine;
			String[] cols = h.split("\t");
			ColumnMap map = new ColumnMap();
			Map<String, Integer> byName = new HashMap<>();
			for (int i = 0; i < cols.length; i++)
			{
				byName.put(cols[i].trim().toLowerCase(Locale.ROOT), i);
			}
			map.originIdx = byName.getOrDefault("origin", -1);
			map.destIdx = byName.getOrDefault("destination", -1);
			map.menuIdx = findFirst(byName, "menuoption menutarget objectid",
				"menu option menu target objectid",
				"menuoption  menutarget  objectid");
			map.skillsIdx = byName.getOrDefault("skills", -1);
			map.itemsIdx = byName.getOrDefault("items", -1);
			map.questsIdx = byName.getOrDefault("quests", -1);
			map.varbitsIdx = byName.getOrDefault("varbits", -1);
			map.varplayersIdx = byName.getOrDefault("varplayers", -1);
			map.durationIdx = byName.getOrDefault("duration", -1);
			return map;
		}

		static ColumnMap defaultsFor(String filename)
		{
			// Fallback if no useful header is seen. Mirrors transports.tsv layout.
			ColumnMap map = new ColumnMap();
			map.originIdx = 0;
			map.destIdx = 1;
			map.menuIdx = 2;
			map.skillsIdx = 3;
			map.itemsIdx = 4;
			map.questsIdx = 5;
			map.varbitsIdx = 6;
			map.varplayersIdx = 7;
			map.durationIdx = 8;
			return map;
		}

		boolean hasAnyKnownField()
		{
			return originIdx >= 0 || destIdx >= 0 || menuIdx >= 0
				|| skillsIdx >= 0 || itemsIdx >= 0 || questsIdx >= 0
				|| varbitsIdx >= 0 || varplayersIdx >= 0 || durationIdx >= 0;
		}

		private static int findFirst(Map<String, Integer> by, String... candidates)
		{
			for (String c : candidates)
			{
				Integer i = by.get(c);
				if (i != null) return i;
			}
			return -1;
		}
	}
}
