package net.runelite.client.sequence.activities.ge;

/**
 * How to resolve the per-item price for a Grand Exchange offer.
 *
 * <p>Phase A only implements {@link Exact}. Future variants
 * ({@code CurrentGuidePrice}, {@code PercentOfGuidePrice}, market-spread,
 * etc.) require a price-lookup source that this proof refuses to introduce.
 * Adding one later is an additive permits-clause edit + a factory
 * pattern-match update.
 */
public sealed interface PricePolicy {

    /** Use this exact coin price per item. */
    record Exact(int coinsEach) implements PricePolicy {
        public Exact {
            if (coinsEach <= 0) {
                throw new IllegalArgumentException("coinsEach must be > 0, got " + coinsEach);
            }
        }
    }
}
