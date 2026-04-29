package net.runelite.client.sequence.internal;

import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.sequence.views.WidgetView;

/**
 * Per-tick observer for widget visibility state.
 *
 * <p>Replicates the parent-chain walk from
 * {@link net.runelite.client.plugins.recorder.widget.WidgetActions#isVisible} without
 * depending on that class's threading helpers — callers are already on the client thread.
 */
final class WidgetObserver {
    private final Client client;

    WidgetObserver(Client client) {
        this.client = client;
    }

    /** True when the widget and every ancestor report {@code !isHidden()}. */
    boolean isVisible(int widgetId) {
        Widget w = client.getWidget(widgetId);
        return w != null && !isHiddenIncludingAncestors(w);
    }

    /** Complement of {@link #isVisible}. */
    boolean isHidden(int widgetId) {
        return !isVisible(widgetId);
    }

    /**
     * Returns an empty set for the proof.
     * Full enumeration of visible root IDs is deferred per spec §10.1.
     */
    Set<Integer> visibleRootIds() {
        return Set.of();
    }

    /** Returns a snapshot of current visibility state. */
    WidgetView snapshot() {
        // Capture a thin delegate — each call re-reads live widget state via client.
        // This is safe because callers are on the client thread.
        final WidgetObserver self = this;
        return new WidgetView() {
            @Override public boolean isVisible(int id)     { return self.isVisible(id); }
            @Override public boolean isHidden(int id)      { return self.isHidden(id); }
            @Override public Set<Integer> visibleRootIds() { return self.visibleRootIds(); }
        };
    }

    // ---- helpers ----------------------------------------------------------------

    private static boolean isHiddenIncludingAncestors(Widget w) {
        for (Widget cur = w; cur != null; cur = cur.getParent()) {
            if (cur.isHidden()) return true;
        }
        return false;
    }
}
