package net.runelite.client.plugins.recorder.nav.v2;

import java.util.Objects;

/** Spec §3 (Lane 1 contract): stable identifier for a {@link V2Path}.
 *  Two paths with the same {@code PathId} are interchangeable from the
 *  Navigator's routing memory's point of view. */
public final class PathId
{
    private final String value;

    public PathId(String value)
    {
        if (value == null || value.isEmpty())
            throw new IllegalArgumentException("PathId value blank");
        this.value = value;
    }

    public String value() { return value; }

    public static PathId of(String value) { return new PathId(value); }

    @Override public boolean equals(Object o)
    {
        return o instanceof PathId && ((PathId) o).value.equals(this.value);
    }
    @Override public int hashCode() { return Objects.hashCode(value); }
    @Override public String toString() { return "PathId[" + value + "]"; }
}
