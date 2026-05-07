package net.runelite.client.plugins.recorder.nav.v2;

import java.awt.Rectangle;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.dispatch.PixelResolver;
import net.runelite.client.sequence.internal.ActionRequest;

/** Minimap-modality click for V2. Bypasses canvas entity-hover ambiguity
 *  by clicking the corresponding spot on the minimap rectangle. The
 *  engine routes minimap clicks via widget hit-test, not the
 *  per-pixel menu, so isLeftClickWalk is not the gate here — the gate
 *  is the precondition stack:
 *
 *  <ul>
 *    <li>no blocking modal (right-click menu open)</li>
 *    <li>target falls inside the clickable minimap bounds</li>
 *    <li>the minimap widget itself is rendered</li>
 *  </ul>
 *
 *  <p>{@link #canClick} is a pure read of live state and MUST be called
 *  on the client thread. {@link #dispatch} also reads live state to
 *  resolve the minimap pixel before enqueuing the dispatcher work, so
 *  it must run on the client thread too.
 *
 *  <p>The dispatcher's worker thread does the cursor move and press;
 *  this class only enqueues the request and returns. The caller
 *  validates progress on the next tick — minimap clicks fail
 *  occasionally (engine drops, visual glitch) and the recovery is
 *  classifier-driven, not a retry inside this class. */
@Slf4j
public final class MinimapClicker
{
    /** Half-side of the bounds box around the resolved minimap pixel.
     *  3 px gives the dispatcher room to sample a click point inside
     *  the box without drifting off the disc. */
    private static final int BOUNDS_HALF = 1;

    /** Tiny seam over the resolver methods MinimapClicker needs.
     *  PixelResolver is final and unmockable, so production wires this
     *  to {@code resolver::resolveMinimapOnly} / {@code ::isMinimapPixel}
     *  and tests substitute deterministic stubs. */
    public interface MinimapAccess
    {
        Point resolveMinimapOnly(WorldPoint target);
        boolean isMinimapPixel(Point p);
    }

    private final Client client;
    private final MinimapAccess access;
    private final HumanizedInputDispatcher dispatcher;

    public MinimapClicker(Client client, PixelResolver resolver,
                          HumanizedInputDispatcher dispatcher)
    {
        this(client, new MinimapAccess()
        {
            @Override public Point resolveMinimapOnly(WorldPoint t) { return resolver.resolveMinimapOnly(t); }
            @Override public boolean isMinimapPixel(Point p) { return resolver.isMinimapPixel(p); }
        }, dispatcher);
    }

    MinimapClicker(Client client, MinimapAccess access, HumanizedInputDispatcher dispatcher)
    {
        this.client = client;
        this.access = access;
        this.dispatcher = dispatcher;
    }

    /** Client thread: true if a minimap click for {@code target} is
     *  safe to issue right now. */
    public boolean canClick(WorldPoint target)
    {
        if (target == null) return false;
        if (client.isMenuOpen()) return false;
        Point p = access.resolveMinimapOnly(target);
        if (p == null) return false;
        if (!access.isMinimapPixel(p)) return false;
        return true;
    }

    /** Client thread for the resolve, dispatcher worker for the click.
     *  Returns true if the request was enqueued; false if the target
     *  could not be resolved to a minimap pixel (caller should
     *  re-evaluate modality on the next tick). */
    public boolean dispatch(WorldPoint target)
    {
        if (target == null) return false;
        Point p = access.resolveMinimapOnly(target);
        if (p == null) return false;
        Rectangle bounds = new Rectangle(
            p.getX() - BOUNDS_HALF, p.getY() - BOUNDS_HALF,
            BOUNDS_HALF * 2 + 1, BOUNDS_HALF * 2 + 1);
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_BOUNDS)
            .channel(ActionRequest.Channel.MOUSE)
            .bounds(bounds)
            .build();
        dispatcher.dispatch(req);
        log.debug("minimap click → {} via px=({},{}) bounds={}", target, p.getX(), p.getY(), bounds);
        return true;
    }
}
