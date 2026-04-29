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
}
