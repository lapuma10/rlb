package net.runelite.client.sequence.views;

import javax.annotation.Nullable;
import net.runelite.client.sequence.PlayerView;
import net.runelite.client.sequence.WorldSnapshot;

/**
 * A concrete, fully-immutable {@link WorldSnapshot} built via {@link Builder}.
 * Used by tests and by production {@code ClientObserver}.
 * Defaults to empty/null-object views when a field is not set explicitly.
 */
public record ImmutableWorldSnapshot(
    int tick,
    @Nullable PlayerView player,
    InventoryView inventory,
    BankView bank,
    WidgetView widgets,
    InteractionView interaction,
    GrandExchangeView grandExchange,
    EventFacts events
) implements WorldSnapshot {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int tick = 0;
        private @Nullable PlayerView player = null;
        private InventoryView inventory = InventoryView.empty();
        private BankView bank = BankView.empty();
        private WidgetView widgets = WidgetView.empty();
        private InteractionView interaction = InteractionView.world();
        private GrandExchangeView grandExchange = GrandExchangeView.empty();
        private EventFacts events = EventFacts.none();

        private Builder() {}

        public Builder tick(int tick) {
            this.tick = tick;
            return this;
        }

        public Builder player(@Nullable PlayerView player) {
            this.player = player;
            return this;
        }

        public Builder inventory(InventoryView inventory) {
            this.inventory = inventory;
            return this;
        }

        public Builder bank(BankView bank) {
            this.bank = bank;
            return this;
        }

        public Builder widgets(WidgetView widgets) {
            this.widgets = widgets;
            return this;
        }

        public Builder interaction(InteractionView interaction) {
            this.interaction = interaction;
            return this;
        }

        public Builder grandExchange(GrandExchangeView grandExchange) {
            this.grandExchange = grandExchange;
            return this;
        }

        public Builder events(EventFacts events) {
            this.events = events;
            return this;
        }

        public ImmutableWorldSnapshot build() {
            return new ImmutableWorldSnapshot(
                tick, player, inventory, bank, widgets, interaction, grandExchange, events);
        }
    }
}
