package net.runelite.client.sequence.internal;

import java.util.Optional;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.sequence.affordance.BlockingInterface;
import net.runelite.client.sequence.views.InteractionMode;
import net.runelite.client.sequence.views.InteractionView;

/**
 * Reads widget visibility on the client thread to detect blocking interfaces
 * and classify the high-level interaction mode.
 *
 * <p>Phase A scope: detects only Grand-Exchange interfaces (offers, collect)
 * and game-state LOADING. Banking will refine to detect bank widgets,
 * dialog modals, world-hop nags, etc., on rebase.
 */
public final class InteractionObserver {

    private final Client client;

    public InteractionObserver(Client client) {
        this.client = client;
    }

    public InteractionView read(int tick) {
        GameState state = client.getGameState();
        if (state != GameState.LOGGED_IN) {
            return new SnapshotInteractionView(InteractionMode.LOADING, false, false, Optional.empty());
        }

        // GE main offers interface — high priority blocker for GE Core.
        if (isVisible(InterfaceID.GeOffers.UNIVERSE)) {
            BlockingInterface bi = new BlockingInterface(
                "GrandExchange.Offers", InterfaceID.GeOffers.UNIVERSE,
                /* blocksWorld */ true, /* canBeClosed */ true);
            return new SnapshotInteractionView(
                InteractionMode.GRAND_EXCHANGE, false, true, Optional.of(bi));
        }

        // GE collect interface — narrower; world-walk still possible above it.
        if (isVisible(InterfaceID.GeCollect.UNIVERSE)) {
            BlockingInterface bi = new BlockingInterface(
                "GrandExchange.Collect", InterfaceID.GeCollect.UNIVERSE,
                /* blocksWorld */ true, /* canBeClosed */ true);
            return new SnapshotInteractionView(
                InteractionMode.GRAND_EXCHANGE, false, true, Optional.of(bi));
        }

        return new SnapshotInteractionView(
            InteractionMode.WORLD, true, true, Optional.empty());
    }

    private boolean isVisible(int widgetId) {
        Widget w = client.getWidget(widgetId);
        return w != null && !w.isHidden();
    }

    private record SnapshotInteractionView(
        InteractionMode mode,
        boolean worldInteractionAvailable,
        boolean movementAvailable,
        Optional<BlockingInterface> blockingInterface
    ) implements InteractionView {}
}
