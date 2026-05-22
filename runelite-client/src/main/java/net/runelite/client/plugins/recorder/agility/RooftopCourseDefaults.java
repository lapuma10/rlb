package net.runelite.client.plugins.recorder.agility;

import java.util.EnumMap;
import java.util.Map;
import net.runelite.client.plugins.recorder.scripts.RooftopAgilityScript.RooftopCourseId;

public final class RooftopCourseDefaults
{
    public static final class Row
    {
        public final String label;
        public final int level;
        public final int obstacleCount;

        public Row(String label, int level, int obstacleCount)
        {
            this.label = label;
            this.level = level;
            this.obstacleCount = obstacleCount;
        }
    }

    public static final Map<RooftopCourseId, Row> ROWS = new EnumMap<>(RooftopCourseId.class);
    static {
        ROWS.put(RooftopCourseId.DRAYNOR,      new Row("Draynor Village Rooftop",  1,  7));
        ROWS.put(RooftopCourseId.AL_KHARID,    new Row("Al Kharid Rooftop",        20, 8));
        ROWS.put(RooftopCourseId.VARROCK,      new Row("Varrock Rooftop",          30, 9));
        ROWS.put(RooftopCourseId.CANIFIS,      new Row("Canifis Rooftop",          40, 8));
        ROWS.put(RooftopCourseId.FALADOR,      new Row("Falador Rooftop",          50, 13));
        ROWS.put(RooftopCourseId.SEERS,        new Row("Seers' Village Rooftop",   60, 6));
        ROWS.put(RooftopCourseId.POLLNIVNEACH, new Row("Pollnivneach Rooftop",     70, 9));
        ROWS.put(RooftopCourseId.RELLEKKA,     new Row("Rellekka Rooftop",         80, 7));
        ROWS.put(RooftopCourseId.ARDOUGNE,     new Row("Ardougne Rooftop",         90, 7));
    }

    private RooftopCourseDefaults() {}
}
