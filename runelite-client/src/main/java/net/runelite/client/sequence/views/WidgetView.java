package net.runelite.client.sequence.views;

import java.util.Set;

public interface WidgetView {
    boolean isVisible(int widgetId);
    boolean isHidden(int widgetId);
    Set<Integer> visibleRootIds();

    static WidgetView empty() {
        return new WidgetView() {
            @Override public boolean isVisible(int id)       { return false; }
            @Override public boolean isHidden(int id)        { return false; }
            @Override public Set<Integer> visibleRootIds()   { return Set.of(); }
        };
    }
}
