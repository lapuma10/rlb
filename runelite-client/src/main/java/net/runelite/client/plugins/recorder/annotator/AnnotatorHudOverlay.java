package net.runelite.client.plugins.recorder.annotator;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Renders a top-anchored panel showing area-selection control hints
 * while {@link AreaSelector} is active. Shows nothing when inactive.
 */
public final class AnnotatorHudOverlay extends OverlayPanel
{
    private final AtomicReference<String> headline = new AtomicReference<>();

    public AnnotatorHudOverlay()
    {
        setPosition(OverlayPosition.TOP_CENTER);
    }

    /** Show the HUD with the given headline (e.g. "Editing: pen_gate" or
     *  "New area"). Pass {@code null} to hide. */
    public void show(@Nullable String h)
    {
        headline.set(h);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        String h = headline.get();
        if (h == null) return null;
        panelComponent.getChildren().clear();
        panelComponent.getChildren().add(TitleComponent.builder()
            .text(h)
            .color(Color.WHITE)
            .build());
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Drag")
            .right("add tiles")
            .build());
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Shift+Drag")
            .right("remove")
            .build());
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Click")
            .right("toggle one")
            .build());
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Enter")
            .right("save")
            .build());
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Esc")
            .right("cancel")
            .build());
        return super.render(g);
    }
}
