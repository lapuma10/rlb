package net.runelite.client.sequence.dispatch;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-holder lease that gates which controller "owns" the shared input
 * stream at any moment. Used so a long-running script (cooking, GE) and an
 * orthogonal panel button can't both push clicks to the dispatcher without
 * coordination.
 *
 * <p>Acquisitions are last-writer-takes-nothing — a second {@link #tryAcquire}
 * while held returns {@code false} and leaves the existing owner in place.
 * Only the current owner can {@link #release}; double-release is a harmless
 * no-op (returns {@code false}).
 *
 * <p>Owners identify themselves with a string token of their choice
 * ({@code "cooking-banking"}, {@code "ge-script"}, …). Tokens are
 * case-sensitive equality.
 *
 * <p>Thread-safe via {@link AtomicReference#compareAndSet}.
 */
public final class InputOwnership {

    private final AtomicReference<String> owner = new AtomicReference<>();

    /** Try to take the lease. Returns {@code true} on success, {@code false}
     *  if another owner already holds it. */
    public boolean tryAcquire(String name) {
        if (name == null) throw new IllegalArgumentException("owner name must not be null");
        return owner.compareAndSet(null, name);
    }

    /** True iff {@code name} is the current owner. */
    public boolean isOwner(String name) {
        return name != null && name.equals(owner.get());
    }

    /** Current owner if any, otherwise empty. */
    public Optional<String> currentOwner() {
        return Optional.ofNullable(owner.get());
    }

    /** Release the lease. Returns {@code true} only if {@code name} was the
     *  current owner. Releases by a non-owner (or after a previous release)
     *  return {@code false} without error. */
    public boolean release(String name) {
        if (name == null) return false;
        return owner.compareAndSet(name, null);
    }
}
