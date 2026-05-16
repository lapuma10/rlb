package net.runelite.client.plugins.recorder.nav.v2.qc.contracts;

import java.util.UUID;

/** Lane-6 scaffolding mirror of an inferred {@code PathId} value used by
 *  {@code V2Path.id()} in spec §3. Lane 4 will own the production type. */
public record PathId(UUID value)
{
    public static PathId random() { return new PathId(UUID.randomUUID()); }
}
