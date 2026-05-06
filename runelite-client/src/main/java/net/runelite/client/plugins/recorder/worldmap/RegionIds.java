package net.runelite.client.plugins.recorder.worldmap;

/** Pack/unpack OSRS region IDs. Matches the engine's scheme:
 *  regionId = (regionX << 8) | regionY, where regionX = x >> 6, regionY = y >> 6.
 *  This matches scene-region encoding the client already uses. */
public final class RegionIds
{
    private RegionIds() {}

    public static int regionIdFor(int worldX, int worldY)
    {
        return ((worldX >> 6) << 8) | (worldY >> 6);
    }

    public static int regionXOf(int regionId) { return (regionId >> 8) & 0xff; }
    public static int regionYOf(int regionId) { return regionId & 0xff; }
}
