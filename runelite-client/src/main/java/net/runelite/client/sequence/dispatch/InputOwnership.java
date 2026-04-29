package net.runelite.client.sequence.dispatch;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-holder lease that gates which controller "owns" the shared input
 * stream at any moment. Used so a long-running script (cooking, GE) and an
 * orthogonal panel button can't both push clicks to the dispatcher without
 * coordination.
 *
 * <p>{@link AtomicReference}-backed; safe to call from any thread.
 *
 * <p>Acquisitions are last-writer-takes-nothing — a {@link #tryAcquire} by a
 * <em>different</em> token while another owner holds the lease returns
 * {@code false} and leaves the existing owner in place. Same-owner
 * re-acquire is idempotent (returns {@code true}). Only the current owner
 * can {@link #release}; double-release is a harmless no-op (returns
 * {@code false}).
 *
 * <p>Owners identify themselves with a string token of their choice
 * ({@code "cooking-banking"}, {@code "ge-script"}, …). Tokens are
 * case-sensitive equality.
 *
 * <p>Null tokens are never valid — all three operations
 * ({@code tryAcquire}, {@code isOwner}, {@code release}) return
 * {@code false} for null rather than NPE-ing.
 */
public final class InputOwnership {

    private final AtomicReference<String> owner = new AtomicReference<>();

    /**
     * Attempt to acquire the lease for the given token.
     * Succeeds if no current owner OR the same owner re-acquires (idempotent).
     * A null token is invalid and always fails.
     */
    public boolean tryAcquire(String token) {
        if (token == null) return false;
        // Idempotent re-acquire by same owner.
        if (token.equals(owner.get())) return true;
        return owner.compareAndSet(null, token);
    }

    /** Returns true iff this token is the current lease holder.
     *  A null token is never the owner. */
    public boolean isOwner(String token) {
        return token != null && token.equals(owner.get());
    }

    /** Returns the current owner token, or empty if the lease is free. */
    public Optional<String> currentOwner() {
        return Optional.ofNullable(owner.get());
    }

    /**
     * Release the lease. Only succeeds when the caller is the current owner.
     * Returns false (harmless no-op) when called by a non-owner, when already
     * released, or when {@code token} is null.
     */
    public boolean release(String token) {
        if (token == null) return false;
        return owner.compareAndSet(token, null);
    }
}
