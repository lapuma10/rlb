package net.runelite.client.plugins.recorder.nav.v2.qc.contracts;

import java.util.List;

/** Lane-6 scaffolding mirror of spec §3 {@code V2Path}.
 *  Production {@code nav.v2.V2Path} (the older A* class) coexists today
 *  but the spec replaces it with this typed-step shape. This mirror
 *  carries that new shape and is used by Lane-6 tests only. */
public interface V2Path
{
    List<PathStep> steps();
    PathId id();
    long planEpochMs();
}
