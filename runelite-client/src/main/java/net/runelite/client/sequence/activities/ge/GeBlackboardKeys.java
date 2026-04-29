package net.runelite.client.sequence.activities.ge;

import net.runelite.client.sequence.blackboard.BlackboardKey;

/**
 * GE-domain blackboard keys shared across the create / wait / collect step
 * sub-sequence. Engine-internal (LAST_BLOCK_REASON) lives in
 * {@code SequenceBlackboardKeys}.
 */
public final class GeBlackboardKeys {

    private GeBlackboardKeys() {}

    /**
     * SEQUENCE-scoped: the slot index (0..7) the in-flight offer was placed
     * in. Written by {@code ConfirmOfferStep} after the offer surfaces in
     * {@code ge.offers()}; read by {@code WaitForOfferStep} and
     * {@code CollectOfferStep}.
     */
    public static final BlackboardKey<Integer> K_GE_OFFER_SLOT =
        BlackboardKey.of("ge.offerSlot", Integer.class);

    /**
     * SEQUENCE-scoped: the slot index (0..7) {@code StartOfferStep} clicked
     * the BUY/SELL slot button on. Written before any offer surfaces — used
     * by {@code ConfirmOfferStep} to detect a wrong-item offer landing in the
     * slot we just clicked into (so we can surface
     * {@link net.runelite.client.sequence.affordance.GeBlockReason.GeOfferItemMismatch}
     * instead of timing out as {@code GeOfferRejected}).
     */
    public static final BlackboardKey<Integer> K_GE_TENTATIVE_SLOT =
        BlackboardKey.of("ge.tentativeSlot", Integer.class);
}
