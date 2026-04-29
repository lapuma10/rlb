package net.runelite.client.sequence.internal;

import java.util.Optional;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.sequence.affordance.AffordanceReport;
import net.runelite.client.sequence.affordance.BlockingInterface;
import net.runelite.client.sequence.views.InteractionMode;
import net.runelite.client.sequence.views.InteractionView;

/**
 * Per-tick observer for the player's current interaction mode.
 * Composes {@link BankObserver} and {@link WidgetObserver} to determine
 * whether world actions, movement, and banking are currently available.
 *
 * <p>All client reads happen on the caller's thread — callers must be on the
 * client thread (same invariant as {@link ClientObserver}).
 *
 * <p><b>Blocker detection (incremental):</b> currently detects the bank widget,
 * the level-up display, the GE offers UI, and the GE collect UI. Other blockers
 * worth detecting (each pulls a sequence into a stuck state today):
 * item-destroy confirm dialog, NPC chat continue / option select
 * (Chatbox.MES_LAYER_*), trade window, shop window, smithing/crafting
 * interfaces. Add detection for those when a concrete script needs them —
 * consumers' allow-lists must grow alongside.
 */
final class InteractionObserver {
    private final Client client;
    private final BankObserver bank;
    private final WidgetObserver widgets;

    InteractionObserver(Client client, BankObserver bank, WidgetObserver widgets) {
        this.client = client;
        this.bank = bank;
        this.widgets = widgets;
    }

    // ---- mode computation -------------------------------------------------------

    /** Current interaction mode, evaluated fresh from live client state. */
    InteractionMode mode() {
        if (client.getGameState() != GameState.LOGGED_IN) return InteractionMode.LOADING;
        if (bank.open())           return InteractionMode.BANKING;
        if (widgets.isVisible(InterfaceID.GeOffers.UNIVERSE))  return InteractionMode.GRAND_EXCHANGE;
        if (widgets.isVisible(InterfaceID.GeCollect.UNIVERSE)) return InteractionMode.GRAND_EXCHANGE;
        if (client.isMenuOpen())   return InteractionMode.MENU_OPEN;
        return InteractionMode.WORLD;
    }

    // ---- blocker detection ------------------------------------------------------

    /**
     * Detect the topmost UI blocker, if any. Order matters: more specific /
     * higher-priority blockers come first so we don't mis-classify a level-up
     * popup that appears on top of the bank as just "Bank".
     *
     * <p>Returned blockers carry {@code blocksWorld=true} when world clicks are
     * routed to the widget instead of the world. The bank widget itself does
     * not block movement (the user can still walk while it's open) but we
     * still set {@code blocksWorld=true} for consistency with the BANK_ROOTS
     * allow-list pattern in
     * {@link net.runelite.client.sequence.activities.banking.BankingSequenceFactory}.
     */
    Optional<BlockingInterface> detectBlocker() {
        // Level-up display takes precedence over the bank — a level-up popup
        // CAN appear with the bank open, and dismissing it requires Space /
        // click, not a bank action.
        if (widgets.isVisible(InterfaceID.LevelupDisplay.UNIVERSE)) {
            return Optional.of(new BlockingInterface(
                "LevelUpDisplay",
                InterfaceID.LevelupDisplay.UNIVERSE,
                /* blocksWorld */ true,
                /* canBeClosed */ true));
        }
        // GE main offers interface — high priority blocker for GE Core.
        if (widgets.isVisible(InterfaceID.GeOffers.UNIVERSE)) {
            return Optional.of(new BlockingInterface(
                "GrandExchange.Offers",
                InterfaceID.GeOffers.UNIVERSE,
                /* blocksWorld */ true,
                /* canBeClosed */ true));
        }
        // GE collect interface — narrower; world-walk still possible above it.
        if (widgets.isVisible(InterfaceID.GeCollect.UNIVERSE)) {
            return Optional.of(new BlockingInterface(
                "GrandExchange.Collect",
                InterfaceID.GeCollect.UNIVERSE,
                /* blocksWorld */ true,
                /* canBeClosed */ true));
        }
        // Bank widget: report it (even though it's allow-listed by banking
        // sequences) so non-banking code paths can react.
        if (bank.open()) {
            return Optional.of(new BlockingInterface(
                "Bank",
                InterfaceID.Bankmain.UNIVERSE,
                /* blocksWorld */ true,
                /* canBeClosed */ true));
        }
        // TODO(blocker-detection): item-destroy confirm dialog, NPC chat
        // continue / option select (Chatbox.MES_LAYER_*), trade window,
        // shop window, crafting/smithing interfaces. Each one adds a check
        // here; consumers add the root id to their allow-list (or write a
        // reactive that dismisses it) as needed.
        return Optional.empty();
    }

    // ---- snapshot ---------------------------------------------------------------

    /** Returns an {@link InteractionView} from the current mode. */
    InteractionView snapshot() {
        final InteractionMode m = mode();
        final Optional<BlockingInterface> blocker = detectBlocker();

        return new InteractionView() {
            @Override public InteractionMode mode() { return m; }

            @Override
            public boolean worldInteractionAvailable() {
                return m == InteractionMode.WORLD;
            }

            @Override
            public boolean movementAvailable() {
                // Per spec: movement is not blocked by the bank widget being open.
                return m == InteractionMode.WORLD || m == InteractionMode.BANKING;
            }

            @Override
            public Optional<BlockingInterface> blockingInterface() {
                return blocker;
            }

            @Override
            public AffordanceReport affordances() {
                return AffordanceReport.allAllowed();
            }
        };
    }
}
