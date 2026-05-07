/*
 * Copyright (c) 2024, RuneLite
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.recorder;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;

@ConfigGroup("recorder")
public interface RecorderConfig extends Config
{
	@ConfigItem(
		keyName = "markerHotkey",
		name = "Marker hotkey",
		description = "Press to start recording when idle, or to open the marker-label dialog while recording."
	)
	default Keybind markerHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "toggleHotkey",
		name = "Toggle hotkey",
		description = "Press to toggle recording on/off without opening the marker dialog."
	)
	default Keybind toggleHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "captureplayerchat",
		name = "Capture player chat",
		description = "If true, public/private/clan/friends chat is recorded. Default off."
	)
	default boolean capturePlayerChat()
	{
		return false;
	}

	@ConfigItem(
		keyName = "cameraSampleThresholdYaw",
		name = "Camera yaw threshold",
		description = "Yaw delta (jagex units) above which a camera event is emitted."
	)
	default int cameraSampleThresholdYaw()
	{
		return 16;
	}

	@ConfigItem(
		keyName = "cameraSampleThresholdPitch",
		name = "Camera pitch threshold",
		description = "Pitch delta above which a camera event is emitted."
	)
	default int cameraSampleThresholdPitch()
	{
		return 16;
	}

	@ConfigItem(
		keyName = "flushIntervalMs",
		name = "Flush interval (ms)",
		description = "How often the daemon thread flushes the buffer to disk."
	)
	default int flushIntervalMs()
	{
		return 500;
	}

	@ConfigItem(
		keyName = "mouseMoveDownsampleHz",
		name = "Mouse-move downsample (Hz)",
		description = "If > 0, downsample mouse moves to this rate. 0 = keep all."
	)
	default int mouseMoveDownsampleHz()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "recordingsDir",
		name = "Recordings directory",
		description = "Override storage location. Empty = ~/.runelite/sequencer/recordings."
	)
	default String recordingsDir()
	{
		return "";
	}

	@ConfigSection(
		name = "Trail overlay",
		description = "Debug overlay for the trail walker. Paints the recorded trail in yellow (transports in orange) and the walker's most recent click pick in blue.",
		position = 90,
		closedByDefault = true
	)
	String trailOverlaySection = "trailOverlay";

	@ConfigItem(
		keyName = "trailOverlay",
		name = "Show trail overlay",
		description = "When on, the active TrailWalker path is drawn on the world (recorded tiles in yellow, transports in orange) and the latest dispatched walk-click target is highlighted in blue.",
		section = trailOverlaySection,
		position = 0
	)
	default boolean trailOverlay()
	{
		return false;
	}

	@ConfigSection(
		name = "Chicken overlay",
		description = "Debug overlay that highlights chickens by selector eligibility. Closest eligible chicken (= what the combat loop would pick next) gets a brighter colour and a thicker outline.",
		position = 100,
		closedByDefault = true
	)
	String chickenOverlaySection = "chickenOverlay";

	@ConfigItem(
		keyName = "chickenOverlay",
		name = "Show chicken overlay",
		description = "Master toggle. When on, every chicken in view is outlined in a colour reflecting whether it would pass the selector's filters.",
		section = chickenOverlaySection,
		position = 0
	)
	default boolean chickenOverlay()
	{
		return false;
	}

	@ConfigItem(
		keyName = "chickenOverlayShowOutOfRange",
		name = "Show: out of range",
		description = "Outline chickens beyond the selector's range (default 6 tiles). Useful for spotting when the player has drifted away from the flock.",
		section = chickenOverlaySection,
		position = 1
	)
	default boolean chickenOverlayShowOutOfRange()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chickenOverlayShowNotVisible",
		name = "Show: not visible",
		description = "Outline chickens rejected by the visibility filter (behind a fence, off-canvas, under a HUD widget, or under an open right-click menu).",
		section = chickenOverlaySection,
		position = 2
	)
	default boolean chickenOverlayShowNotVisible()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chickenOverlayShowEngagedByOther",
		name = "Show: engaged by other",
		description = "Outline chickens currently being attacked by another player.",
		section = chickenOverlaySection,
		position = 3
	)
	default boolean chickenOverlayShowEngagedByOther()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chickenOverlayShowWrongPlane",
		name = "Show: wrong plane",
		description = "Outline chickens on a different floor than the player. Off by default — rarely interesting.",
		section = chickenOverlaySection,
		position = 4
	)
	default boolean chickenOverlayShowWrongPlane()
	{
		return false;
	}

	@ConfigItem(
		keyName = "chickenOverlayShowDying",
		name = "Show: dying",
		description = "Outline chickens whose HP bar has emptied (transient — usually only visible for a tick or two).",
		section = chickenOverlaySection,
		position = 5
	)
	default boolean chickenOverlayShowDying()
	{
		return false;
	}

	@ConfigItem(
		keyName = "chickenOverlayShowCounts",
		name = "Show counts panel",
		description = "Render a small panel in the top-left listing how many chickens fall in each category (eligible, out of range, not visible, …).",
		section = chickenOverlaySection,
		position = 6
	)
	default boolean chickenOverlayShowCounts()
	{
		return true;
	}

	@ConfigSection(
		name = "Experimental",
		description = "Experimental features that are not yet production-ready.",
		position = 200,
		closedByDefault = true
	)
	String experimentalSection = "experimental";

	@ConfigItem(
		keyName = "useEngineBanking",
		name = "Use engine banking",
		description = "Route cooking-script banking through the sequence engine (experimental).",
		section = experimentalSection,
		position = 0
	)
	default boolean useEngineBanking()
	{
		return false;
	}

	@ConfigItem(
		keyName = "useWorldMemoryPlanner",
		name = "Use WorldMemory planner (experimental)",
		description = "When on, scripts that opt in (currently CookingScriptV3) "
			+ "use the WorldMemory planner to pick interact tiles instead of "
			+ "their hardcoded arrival-area path. Default off.",
		section = experimentalSection,
		position = 1
	)
	default boolean useWorldMemoryPlanner()
	{
		return false;
	}

	/** Phase-7 navigator mode. Picked by the {@link
	 *  net.runelite.client.plugins.recorder.nav.HybridNavigator} on every
	 *  request — turning V2 on must not break ChickenFarmV3, so the
	 *  default ({@link #V1_ONLY}) and the fallback variant
	 *  ({@link #V2_WITH_V1_FALLBACK}) are always usable.
	 *
	 *  <p>{@link #V1_ONLY} — always use TrailWalker/V1.
	 *  <br>{@link #V2_WITH_V1_FALLBACK} — try V2 first; if V2 fails for
	 *  any reason, log why and use V1 for that request.
	 *  <br>{@link #V2_STRICT} — try V2 only; if it fails, report why and
	 *  stop cleanly (no fallback). */
	enum NavigatorMode
	{
		V1_ONLY,
		V2_WITH_V1_FALLBACK,
		V2_STRICT
	}

	@ConfigItem(
		keyName = "navigatorMode",
		name = "Navigator mode",
		description = "How HybridNavigator dispatches requests. V1_ONLY = "
			+ "TrailWalker only (default, safe). V2_WITH_V1_FALLBACK = try "
			+ "V2 first, fall back to V1 if V2 fails for any reason. "
			+ "V2_STRICT = V2 only, fail clearly if V2 can't satisfy.",
		section = experimentalSection,
		position = 2
	)
	default NavigatorMode navigatorMode()
	{
		return NavigatorMode.V1_ONLY;
	}

	/** Phase-11 toggle for V2's top-K alternation + edge-cost noise.
	 *  OFF (default): V2 always returns the deterministic shortest path
	 *  from a single A* run — stable, predictable, easier to debug.
	 *  ON: V2 runs the spec's selection algorithm (top-K with edge-reuse
	 *  penalties, recent-route memory, 30 % noisy A* fallback).
	 *
	 *  <p>Spec Phase 11 acceptance: "with variation OFF, V2 still
	 *  completes bank↔pen; with variation ON, both north and south
	 *  routes are selected when both exist." Off by default per the
	 *  rollout — Phase 11 lives behind this flag so a regression in
	 *  alternation can be killed by flipping it. */
	@ConfigItem(
		keyName = "enableV2RouteVariation",
		name = "V2 route variation",
		description = "When on, V2 alternates between known routes via top-K + "
			+ "edge-reuse penalties + recent-route memory. When off (default), "
			+ "V2 always returns the deterministic shortest path. Off by default "
			+ "so reliability regressions can be killed by flipping the flag.",
		section = experimentalSection,
		position = 3
	)
	default boolean enableV2RouteVariation()
	{
		return false;
	}

	@ConfigSection(
		name = "WorldMap overlay (V2 inspect)",
		description = "Minimap debug overlay for the V2 navigator's world memory: walkable tiles, transport endpoints, the active planned route, and (toggleable) blocked tiles, stale edges, entity sightings.",
		position = 210,
		closedByDefault = true
	)
	String worldMapOverlaySection = "worldMapOverlay";

	@ConfigItem(
		keyName = "showWorldMapOverlay",
		name = "Show WorldMap overlay",
		description = "Master toggle. When on, the V2 minimap overlay paints known walkable tiles in green, transport endpoints in yellow, and the active planned route in blue. Off by default — debug-only.",
		section = worldMapOverlaySection,
		position = 0
	)
	default boolean showWorldMapOverlay()
	{
		return false;
	}

	@ConfigItem(
		keyName = "overlayShowBlocked",
		name = "Show: blocked tiles",
		description = "Paint known-blocked tiles in red. Noisy in dense areas — off by default.",
		section = worldMapOverlaySection,
		position = 1
	)
	default boolean overlayShowBlocked()
	{
		return false;
	}

	@ConfigItem(
		keyName = "overlayShowStale",
		name = "Show: stale tiles / edges",
		description = "Paint tiles or transport edges that have been invalidated this session in orange.",
		section = worldMapOverlaySection,
		position = 2
	)
	default boolean overlayShowStale()
	{
		return false;
	}

	@ConfigItem(
		keyName = "overlayShowEntities",
		name = "Show: entity sightings",
		description = "Paint NPC / object sightings in purple. Useful to verify the entity index has captured the cooks / bankers / chickens you expect.",
		section = worldMapOverlaySection,
		position = 3
	)
	default boolean overlayShowEntities()
	{
		return false;
	}
}
