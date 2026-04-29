package net.runelite.client.plugins.recorder.widget;

import net.runelite.api.gameval.InterfaceID;

/** The OSRS sidebar tabs, with each tab's icon-widget id for every
 *  layout the engine supports. The user's layout (Fixed / Resizable
 *  Classic / Resizable Modern) decides which widget id is actually
 *  rendered; {@link SidebarTabActions} probes all of them and clicks
 *  whichever one is currently visible. We never hardcode canvas pixels
 *  or assume a layout — every dispatch uses the live widget bounds.
 *
 *  <p>Tab order matches the standard OSRS sidebar: Combat, Stats,
 *  Quests, Inventory, Equipment, Prayer, Magic, Friends, Account,
 *  Logout, Settings, Emotes, Music. */
public enum SidebarTab
{
    COMBAT(
        InterfaceID.Toplevel.STONE0,
        InterfaceID.ToplevelOsrsStretch.STONE0,
        InterfaceID.ToplevelPreEoc.ICON0),
    STATS(
        InterfaceID.Toplevel.STONE1,
        InterfaceID.ToplevelOsrsStretch.STONE1,
        InterfaceID.ToplevelPreEoc.ICON1),
    QUESTS(
        InterfaceID.Toplevel.STONE2,
        InterfaceID.ToplevelOsrsStretch.STONE2,
        InterfaceID.ToplevelPreEoc.ICON2),
    INVENTORY(
        InterfaceID.Toplevel.STONE3,
        InterfaceID.ToplevelOsrsStretch.STONE3,
        InterfaceID.ToplevelPreEoc.ICON3),
    EQUIPMENT(
        InterfaceID.Toplevel.STONE4,
        InterfaceID.ToplevelOsrsStretch.STONE4,
        InterfaceID.ToplevelPreEoc.ICON4),
    PRAYER(
        InterfaceID.Toplevel.STONE5,
        InterfaceID.ToplevelOsrsStretch.STONE5,
        InterfaceID.ToplevelPreEoc.ICON5),
    MAGIC(
        InterfaceID.Toplevel.STONE6,
        InterfaceID.ToplevelOsrsStretch.STONE6,
        InterfaceID.ToplevelPreEoc.ICON6);

    /** Icon widget ids in layout-priority order: Fixed, Resizable
     *  Classic (Stretch), Resizable Modern (PreEoc). The first one
     *  whose widget reports visible (including ancestors) wins. */
    private final int[] iconWidgetIds;

    SidebarTab(int... iconWidgetIds)
    {
        this.iconWidgetIds = iconWidgetIds;
    }

    public int[] iconWidgetIds()
    {
        return iconWidgetIds.clone();
    }
}
