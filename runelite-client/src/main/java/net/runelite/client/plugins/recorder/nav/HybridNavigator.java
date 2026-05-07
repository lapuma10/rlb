package net.runelite.client.plugins.recorder.nav;

import java.util.Objects;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.recorder.RecorderConfig.NavigatorMode;

/** Phase-7 dispatcher: a single {@link Navigator} scripts depend on,
 *  internally routing each request to V1, V2, or V2-with-V1-fallback
 *  based on the user-selected {@link NavigatorMode}.
 *
 *  <p>The mode is read live (per tick) so the panel switch takes
 *  effect immediately, not just on the next script start.
 *
 *  <p>Mode behavior (from spec lines 786-799):
 *  <ul>
 *    <li>{@link NavigatorMode#V1_ONLY} — always use V1.</li>
 *    <li>{@link NavigatorMode#V2_WITH_V1_FALLBACK} — try V2 first; on
 *        FAILED, log the reason and delegate the same request to V1.
 *        Once fallback engaged for a request, stay on V1 until the
 *        request resolves (ARRIVED / FAILED) — no oscillation.</li>
 *    <li>{@link NavigatorMode#V2_STRICT} — V2 only; on FAILED, log and
 *        stop cleanly. No fallback.</li>
 *  </ul>
 *
 *  <p>Logging (per spec acceptance "logs show request, mode, V2 result,
 *  fallback decision, and handler"): every tick that produces a state
 *  transition (started, fell back, completed, failed) emits an INFO
 *  line. Per-tick RUNNING ticks emit DEBUG to keep the log readable.
 *
 *  <p>Threading: this class itself is not thread-safe — callers must
 *  not invoke {@link #tick} concurrently. Today's only consumer is
 *  ChickenFarmV3's single tick loop. */
@Slf4j
public final class HybridNavigator implements Navigator
{
    private static final String NAME = "hybrid";

    private final Navigator v1;
    @Nullable private final Navigator v2;
    private final Supplier<NavigatorMode> modeSupplier;

    @Nullable private NavRequest activeRequest;
    @Nullable private Navigator activeHandler;
    /** Mode at the start of the active request — used to decide whether
     *  a mid-request switch should reset state. */
    @Nullable private NavigatorMode activeMode;
    /** Set to true when V2_WITH_V1_FALLBACK has fallen back for the
     *  current request; sticky until the request resolves. */
    private boolean fellBackForActiveRequest;

    public HybridNavigator(Navigator v1, @Nullable Navigator v2, Supplier<NavigatorMode> modeSupplier)
    {
        if (v1 == null) throw new IllegalArgumentException("V1 navigator is required");
        if (modeSupplier == null) throw new IllegalArgumentException("modeSupplier is required");
        this.v1 = v1;
        this.v2 = v2;
        this.modeSupplier = modeSupplier;
    }

    @Override
    public NavStatus tick(NavRequest request) throws InterruptedException
    {
        if (request == null) return NavStatus.FAILED;
        NavigatorMode mode = effectiveMode();

        // New request OR mode change → reset active handler. Cancelling
        // both implementations on the boundary keeps us from carrying
        // half-finished state across.
        if (!Objects.equals(request, activeRequest) || mode != activeMode)
        {
            log.info("hybrid: new request {} mode={} (was {})", request, mode, activeMode);
            cancelInternal();
            activeRequest = request;
            activeMode = mode;
            fellBackForActiveRequest = false;
            activeHandler = null;
        }

        switch (mode)
        {
            case V1_ONLY:
                return runV1(request);
            case V2_STRICT:
                return runV2Strict(request);
            case V2_WITH_V1_FALLBACK:
            default:
                return runV2WithFallback(request);
        }
    }

    private NavStatus runV1(NavRequest request) throws InterruptedException
    {
        if (activeHandler == null)
        {
            log.info("hybrid: V1_ONLY → handler=trail-v1 request={}", request);
        }
        activeHandler = v1;
        NavStatus s = v1.tick(request);
        logTerminal("V1_ONLY", "trail-v1", s);
        return s;
    }

    private NavStatus runV2Strict(NavRequest request) throws InterruptedException
    {
        if (v2 == null)
        {
            log.warn("hybrid: V2_STRICT but V2 unavailable — FAILED (no fallback in strict mode)");
            return NavStatus.FAILED;
        }
        if (activeHandler == null)
        {
            log.info("hybrid: V2_STRICT → handler=worldmap-v2 request={}", request);
        }
        activeHandler = v2;
        NavStatus s = v2.tick(request);
        if (s == NavStatus.FAILED)
        {
            log.warn("hybrid: V2_STRICT FAILED for {} — no fallback. {}",
                request, v2FailureSummary());
        }
        else
        {
            logTerminal("V2_STRICT", "worldmap-v2", s);
        }
        return s;
    }

    /** Best-effort one-line summary of why V2 just failed, for the
     *  hybrid log line. Pulls live tags from {@link V2Navigator} when
     *  the underlying instance is one (production wiring); falls back
     *  to a generic pointer otherwise (test stubs implement
     *  {@link Navigator} directly without a {@code FailureReason}). */
    private String v2FailureSummary()
    {
        if (v2 instanceof net.runelite.client.plugins.recorder.nav.v2.V2Navigator v2n)
        {
            return "v2.reason=" + v2n.lastFailureReason()
                + " executor.reason=" + v2n.lastExecutorFailureReason();
        }
        return "(see worldmap-v2 logs for the specific reason: BAD_REQUEST / "
            + "NO_ROUTE / NO_PLAYER_LOC / ENTITY_NOT_FOUND / EXECUTOR_FAILED)";
    }

    private NavStatus runV2WithFallback(NavRequest request) throws InterruptedException
    {
        // Once we've fallen back for this request, stay on V1 until the
        // request resolves — flipping back to V2 mid-walk would re-plan
        // and re-issue clicks while V1 is still driving the player.
        if (fellBackForActiveRequest)
        {
            activeHandler = v1;
            return v1.tick(request);
        }

        if (v2 == null)
        {
            log.info("hybrid: V2_WITH_V1_FALLBACK but V2 unavailable — falling back to V1");
            fellBackForActiveRequest = true;
            activeHandler = v1;
            return v1.tick(request);
        }

        if (activeHandler == null)
        {
            log.info("hybrid: V2_WITH_V1_FALLBACK → trying V2 first, request={}", request);
        }
        activeHandler = v2;
        NavStatus s = v2.tick(request);
        if (s != NavStatus.FAILED)
        {
            if (s == NavStatus.ARRIVED)
            {
                log.info("hybrid: V2 ARRIVED for {} — handler=worldmap-v2", request);
            }
            return s;
        }

        // V2 failed — log, cancel V2's residual state, hand the SAME
        // request to V1 for the rest of this leg.
        log.info("hybrid: V2 FAILED for {} — falling back to V1 for this request. {}",
            request, v2FailureSummary());
        v2.cancel();
        fellBackForActiveRequest = true;
        activeHandler = v1;
        NavStatus v1s = v1.tick(request);
        logTerminal("V2_WITH_V1_FALLBACK→V1", "trail-v1", v1s);
        return v1s;
    }

    private void logTerminal(String mode, String handler, NavStatus s)
    {
        if (s == NavStatus.ARRIVED || s == NavStatus.FAILED || s == NavStatus.IDLE)
        {
            log.info("hybrid: {} handler={} → {}", mode, handler, s);
        }
        else
        {
            log.debug("hybrid: {} handler={} → {}", mode, handler, s);
        }
    }

    @Override
    public void cancel()
    {
        cancelInternal();
        activeRequest = null;
        activeMode = null;
        activeHandler = null;
        fellBackForActiveRequest = false;
    }

    private void cancelInternal()
    {
        try { v1.cancel(); } catch (Throwable th) { log.debug("v1 cancel threw", th); }
        if (v2 != null)
        {
            try { v2.cancel(); } catch (Throwable th) { log.debug("v2 cancel threw", th); }
        }
    }

    @Override
    public boolean isBusy()
    {
        if (activeHandler == null) return false;
        return activeHandler.isBusy();
    }

    @Override
    public String name()
    {
        return NAME;
    }

    /** Snapshot the current mode for a single tick, defaulting to V1_ONLY
     *  if the supplier returns null (e.g. config not yet wired). Defensive
     *  default — V1_ONLY is always safe. */
    private NavigatorMode effectiveMode()
    {
        NavigatorMode m;
        try { m = modeSupplier.get(); }
        catch (Throwable th)
        {
            log.warn("hybrid: mode supplier threw — defaulting to V1_ONLY", th);
            return NavigatorMode.V1_ONLY;
        }
        return m == null ? NavigatorMode.V1_ONLY : m;
    }
}
