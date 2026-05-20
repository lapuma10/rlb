package net.runelite.client.plugins.recorder.scripts;

import java.awt.event.KeyEvent;
import net.runelite.api.gameval.ItemID;

public final class FletchingScript
{
    private static final int BOWSTRING = ItemID.BOW_STRING;  // 1777
    private static final int KNIFE     = ItemID.KNIFE;        // 946

    public enum Mode { FLETCH, STRING, CUT_AND_STRING }
    private enum Action { CUT, STRING }

    public enum FletchItem
    {
        // Normal logs
        ARROW_SHAFTS(
            ItemID.LOGS, -1, -1,
            1, KeyEvent.VK_1, 1, 15, 5.0, false, true, "Arrow shafts"),
        SHORTBOW_U(
            ItemID.LOGS, ItemID.UNSTRUNG_SHORTBOW, ItemID.SHORTBOW,
            5, KeyEvent.VK_SPACE, 1, 1, 5.0, true, true, "Shortbow (u)"),
        LONGBOW_U(
            ItemID.LOGS, ItemID.UNSTRUNG_LONGBOW, ItemID.LONGBOW,
            10, KeyEvent.VK_4, 1, 1, 10.0, true, true, "Longbow (u)"),

        // Oak logs
        OAK_ARROW_SHAFTS(
            ItemID.OAK_LOGS, -1, -1,
            15, KeyEvent.VK_1, 1, 30, 10.0, false, true, "Oak arrow shafts"),
        OAK_SHORTBOW_U(
            ItemID.OAK_LOGS, ItemID.UNSTRUNG_OAK_SHORTBOW, ItemID.OAK_SHORTBOW,
            20, KeyEvent.VK_2, 1, 1, 16.5, true, true, "Oak shortbow (u)"),
        OAK_LONGBOW_U(
            ItemID.OAK_LOGS, ItemID.UNSTRUNG_OAK_LONGBOW, ItemID.OAK_LONGBOW,
            25, KeyEvent.VK_SPACE, 1, 1, 25.0, true, true, "Oak longbow (u)"),

        // Willow logs
        WILLOW_ARROW_SHAFTS(
            ItemID.WILLOW_LOGS, -1, -1,
            30, KeyEvent.VK_1, 1, 45, 15.0, false, true, "Willow arrow shafts"),
        WILLOW_SHORTBOW_U(
            ItemID.WILLOW_LOGS, ItemID.UNSTRUNG_WILLOW_SHORTBOW, ItemID.WILLOW_SHORTBOW,
            35, KeyEvent.VK_2, 1, 1, 33.3, true, true, "Willow shortbow (u)"),
        WILLOW_LONGBOW_U(
            ItemID.WILLOW_LOGS, ItemID.UNSTRUNG_WILLOW_LONGBOW, ItemID.WILLOW_LONGBOW,
            40, KeyEvent.VK_SPACE, 1, 1, 41.5, true, true, "Willow longbow (u)"),

        // Maple logs
        MAPLE_ARROW_SHAFTS(
            ItemID.MAPLE_LOGS, -1, -1,
            45, KeyEvent.VK_1, 1, 60, 20.0, false, true, "Maple arrow shafts"),
        MAPLE_SHORTBOW_U(
            ItemID.MAPLE_LOGS, ItemID.UNSTRUNG_MAPLE_SHORTBOW, ItemID.MAPLE_SHORTBOW,
            50, KeyEvent.VK_2, 1, 1, 50.0, true, true, "Maple shortbow (u)"),
        MAPLE_LONGBOW_U(
            ItemID.MAPLE_LOGS, ItemID.UNSTRUNG_MAPLE_LONGBOW, ItemID.MAPLE_LONGBOW,
            55, KeyEvent.VK_SPACE, 1, 1, 58.3, true, true, "Maple longbow (u)"),

        // Yew logs
        YEW_ARROW_SHAFTS(
            ItemID.YEW_LOGS, -1, -1,
            60, KeyEvent.VK_1, 1, 75, 25.0, false, true, "Yew arrow shafts"),
        YEW_SHORTBOW_U(
            ItemID.YEW_LOGS, ItemID.UNSTRUNG_YEW_SHORTBOW, ItemID.YEW_SHORTBOW,
            65, KeyEvent.VK_2, 1, 1, 67.5, true, true, "Yew shortbow (u)"),
        YEW_LONGBOW_U(
            ItemID.YEW_LOGS, ItemID.UNSTRUNG_YEW_LONGBOW, ItemID.YEW_LONGBOW,
            70, KeyEvent.VK_SPACE, 1, 1, 75.0, true, true, "Yew longbow (u)"),

        // Magic logs
        MAGIC_ARROW_SHAFTS(
            ItemID.MAGIC_LOGS, -1, -1,
            75, KeyEvent.VK_1, 1, 90, 30.0, false, true, "Magic arrow shafts"),
        MAGIC_SHORTBOW_U(
            ItemID.MAGIC_LOGS, ItemID.UNSTRUNG_MAGIC_SHORTBOW, ItemID.MAGIC_SHORTBOW,
            80, KeyEvent.VK_2, 1, 1, 83.3, true, true, "Magic shortbow (u)"),
        MAGIC_LONGBOW_U(
            ItemID.MAGIC_LOGS, ItemID.UNSTRUNG_MAGIC_LONGBOW, ItemID.MAGIC_LONGBOW,
            85, KeyEvent.VK_SPACE, 1, 1, 91.5, true, true, "Magic longbow (u)");

        final int     logId;
        final int     unstrungId;
        final int     strungId;
        final int     levelReq;
        final int     fletchKey;
        final int     logsPerAction;
        final int     outputPerLog;
        final double  xp;
        final boolean canString;
        final boolean verified;
        final String  label;

        FletchItem(int logId, int unstrungId, int strungId,
                   int levelReq, int fletchKey,
                   int logsPerAction, int outputPerLog, double xp,
                   boolean canString, boolean verified, String label)
        {
            this.logId         = logId;
            this.unstrungId    = unstrungId;
            this.strungId      = strungId;
            this.levelReq      = levelReq;
            this.fletchKey     = fletchKey;
            this.logsPerAction = logsPerAction;
            this.outputPerLog  = outputPerLog;
            this.xp            = xp;
            this.canString     = canString;
            this.verified      = verified;
            this.label         = label;
        }

        /** 26 for 2-log items (shields), 27 otherwise. */
        int logWithdrawCount() { return logsPerAction == 2 ? 26 : 27; }

        public boolean canString()   { return canString; }
        public boolean verified()    { return verified; }
        public String  label()       { return label; }
        public String  displayName() { return label; }

        public boolean supportsMode(Mode mode)
        {
            return switch (mode)
            {
                case FLETCH          -> true;
                case STRING, CUT_AND_STRING -> canString;
            };
        }

        @Override public String toString() { return label; }
    }
}
