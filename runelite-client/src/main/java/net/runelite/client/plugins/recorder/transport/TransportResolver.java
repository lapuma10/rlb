/*
 * Copyright (c) 2026, RuneLite
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
package net.runelite.client.plugins.recorder.transport;

import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.coords.WorldPoint;
import javax.annotation.Nullable;

/**
 * Looks up the on-tile object that should receive a transport verb at a given
 * world tile. Doors / gates are usually {@link WallObject}s; ladders, stairs,
 * trapdoors are {@link GameObject}s; the {@code interact:} prefix can match any
 * object whose composition advertises the requested verb. All scene reads
 * happen on the client thread — callers are responsible for hopping there.
 */
public final class TransportResolver
{
    private final Client client;

    public TransportResolver(Client client)
    {
        this.client = client;
    }

    /** Result of a lookup. Exactly one of {@link #wallObject()},
     *  {@link #gameObject()}, {@link #decorativeObject()},
     *  {@link #groundObject()} is non-null on success; on failure
     *  {@link #failure()} explains why. */
    public static final class Match
    {
        private final WallObject wall;
        private final GameObject game;
        private final DecorativeObject decorative;
        private final GroundObject ground;
        private final String matchedVerb;
        private final String failure;
        private final int matchedObjectId;
        private Match(WallObject w, GameObject g, DecorativeObject d, GroundObject ground,
                      String matchedVerb, int objectId, String failure)
        {
            this.wall = w; this.game = g; this.decorative = d; this.ground = ground;
            this.matchedVerb = matchedVerb;
            this.matchedObjectId = objectId;
            this.failure = failure;
        }
        public static Match found(WallObject w, String verb, int id)
        { return new Match(w, null, null, null, verb, id, null); }
        public static Match found(GameObject g, String verb, int id)
        { return new Match(null, g, null, null, verb, id, null); }
        public static Match found(DecorativeObject d, String verb, int id)
        { return new Match(null, null, d, null, verb, id, null); }
        public static Match found(GroundObject g, String verb, int id)
        { return new Match(null, null, null, g, verb, id, null); }
        public static Match failed(String reason)
        { return new Match(null, null, null, null, null, 0, reason); }
        @Nullable public WallObject wallObject() { return wall; }
        @Nullable public GameObject gameObject() { return game; }
        @Nullable public DecorativeObject decorativeObject() { return decorative; }
        @Nullable public GroundObject groundObject() { return ground; }
        @Nullable public String matchedVerb() { return matchedVerb; }
        public int matchedObjectId() { return matchedObjectId; }
        @Nullable public String failure() { return failure; }
        public boolean isSuccess() { return failure == null; }
    }

    /** Find an object on {@code tile} whose menu actions include {@code verb}.
     *  For doors/gates, prefers WallObject; for stairs/ladders/trapdoors,
     *  prefers GameObject. Decorative + ground objects are checked as
     *  fallbacks because a few transports (e.g. agility shortcuts) live there.
     */
    public Match findTransport(WorldPoint world, String verb)
    {
        if (world == null) return Match.failed("null tile");
        if (verb == null || verb.isBlank()) return Match.failed("empty verb");
        Tile tile = tileAt(world);
        if (tile == null) return Match.failed(
            "no tile at " + world.getX() + "," + world.getY() + ",p=" + world.getPlane()
                + " (out of loaded scene?)");
        // Wall first — doors / gates dominate this code path.
        WallObject wall = tile.getWallObject();
        if (wall != null)
        {
            String hit = matchedAction(wall.getId(), verb);
            if (hit != null) return Match.found(wall, hit, wall.getId());
        }
        // Then GameObject array — stairs, ladders, trapdoors, most interactive
        // scenery. May contain duplicates for multi-tile objects; first match
        // wins because the object is the same regardless of which tile cell
        // we picked.
        GameObject[] gobs = tile.getGameObjects();
        if (gobs != null)
        {
            for (GameObject go : gobs)
            {
                if (go == null) continue;
                String hit = matchedAction(go.getId(), verb);
                if (hit != null) return Match.found(go, hit, go.getId());
            }
        }
        DecorativeObject deco = tile.getDecorativeObject();
        if (deco != null)
        {
            String hit = matchedAction(deco.getId(), verb);
            if (hit != null) return Match.found(deco, hit, deco.getId());
        }
        GroundObject ground = tile.getGroundObject();
        if (ground != null)
        {
            String hit = matchedAction(ground.getId(), verb);
            if (hit != null) return Match.found(ground, hit, ground.getId());
        }
        return Match.failed("no object at " + world.getX() + "," + world.getY()
            + ",p=" + world.getPlane() + " has verb '" + verb + "'");
    }

    /** Returns the matching action string from the object's composition, or
     *  null if no action matches. The string returned is the verbatim deob
     *  action text, suitable for displaying in errors and (after normalisation)
     *  for menu-entry lookup. */
    @Nullable
    private String matchedAction(int objectId, String verb)
    {
        try
        {
            ObjectComposition def = client.getObjectDefinition(objectId);
            if (def == null) return null;
            // Some objects have a different composition under "impostor" form
            // (multi-state objects: e.g. open vs closed door). When the def
            // advertises impostor IDs, the impostor form is what the engine
            // shows the player; check its actions instead.
            if (def.getImpostorIds() != null)
            {
                try
                {
                    ObjectComposition imp = def.getImpostor();
                    if (imp != null) def = imp;
                }
                catch (Throwable ignored) { /* impostor lookup needs a varbit
                    state we may not have on the test thread — fall back to
                    the base def's actions */ }
            }
            String[] actions = def.getActions();
            if (actions == null) return null;
            for (String a : actions)
            {
                if (a != null && VerbMatcher.matches(verb, a)) return a;
            }
        }
        catch (Throwable th) { /* ignore — some object IDs have no def */ }
        return null;
    }

    /** Returns the tile at the given world coords, or null if the tile is
     *  not in the loaded scene. */
    @Nullable
    public Tile tileAt(WorldPoint world)
    {
        if (world == null) return null;
        var wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        Scene scene = wv.getScene();
        if (scene == null) return null;
        int sceneX = world.getX() - wv.getBaseX();
        int sceneY = world.getY() - wv.getBaseY();
        Tile[][][] tiles = scene.getTiles();
        if (tiles == null) return null;
        int plane = world.getPlane();
        if (plane < 0 || plane >= tiles.length) return null;
        if (sceneX < 0 || sceneY < 0
            || sceneX >= tiles[plane].length
            || sceneY >= tiles[plane][sceneX].length) return null;
        return tiles[plane][sceneX][sceneY];
    }
}
