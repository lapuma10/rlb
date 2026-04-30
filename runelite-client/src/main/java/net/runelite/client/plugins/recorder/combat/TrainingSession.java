package net.runelite.client.plugins.recorder.combat;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.recorder.widget.SidebarTab;
import net.runelite.client.plugins.recorder.widget.SidebarTabActions;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-tick orchestrator for the combat training subsystem.
 *
 * <p>The V3 chicken-farm script holds one instance of this class. Each game
 * tick while the bot is in the AT_PEN state, the script calls {@link #tick()}.
 * This class reads current levels, fires level-up notifications to
 * {@link SkillRotation}, ensures the combat style widget matches the active
 * skill, and optionally manages the auto-retaliate toggle.
 *
 * <blockquote>
 * "implement training modes, so skill for attack, strength, defence. set auto
 * retaliate on-off. then i should be able to start the script of chicken farm.
 * select with checkbox what skills I want to train and up to what level. so lets
 * say attack, deff, str to level 20. then it will go ahead and go to chickens
 * and kill them alternating each with 2-5 levels in between randomly, so get 3
 * levels of attack, switch to str, get 5 switch to deff etc. till were at the
 * level I wanted. all of this time were picking up feathers and chicken. Once
 * done with the levels, guess what? We stop, bank and logg off!
 *
 * the script should be checking levels in the stats/skills tab for what were
 * training (by hovering over and waiting to see hm exp is left etc. from time
 * to time). So switching combat stiles etc."
 *
 * — User prompt, 2026-04-29
 * </blockquote>
 */
@Slf4j
public class TrainingSession
{
    private final Client client;
    private final CombatStyleSwitcher styleSwitcher;
    private final AutoRetaliateToggle retaliateToggle;
    private final CombatTabActions combatTab;
    private final SidebarTabActions sidebarTabs;
    /** Dispatcher used by the periodic Skills-tab hover. {@code null} in
     *  unit tests that exercise tick() with mock sub-components. */
    @Nullable private final HumanizedInputDispatcher dispatcher;
    @Nullable private final EventBus eventBus;
    private boolean registeredOnEventBus = false;
    private SkillRotation rotation;
    private TrainingPlan plan;

    /** Wallclock millis at which the next Skills-tab "impulse hover"
     *  should fire, or {@code 0} when no hover is scheduled (i.e. the
     *  session isn't started). Multiple threads read/write this:
     *  {@link #tick()} (client thread) reads + reschedules,
     *  {@link #onStatChanged(StatChanged)} (event-bus thread) reschedules
     *  sooner after a level-up, and the spawned hover worker thread
     *  resets it after the hover lands. */
    private final AtomicLong nextHoverAtMs = new AtomicLong(0L);
    /** True while a hover worker thread is running — prevents the next
     *  tick() from spawning a second one if the first hasn't finished. */
    private final AtomicBoolean hoverInFlight = new AtomicBoolean(false);

    /** Lower bound (ms) of the post-level-up hover delay. A real player
     *  notices a level-up, sometimes finishes the current click, then
     *  glances at their skill icon. 20-60s captures that latency. */
    static final long POST_LEVEL_HOVER_MIN_MS = 20_000L;
    static final long POST_LEVEL_HOVER_MAX_MS = 60_000L;

    /** XP per skill at the moment of the last accepted observation. Used to
     *  filter {@link StatChanged} events: only updates that strictly grow
     *  the XP counter are real "you got XP" events. Boost / drain / login-
     *  sync StatChanged events keep xp identical and must be ignored. */
    private final Map<Skill, Integer> lastSeenXp = new HashMap<>();

    /** Set to true after a level-up that may have changed the active
     *  skill (and therefore the desired combat style). Cleared once we
     *  verify the engine reports the right style. */
    private boolean styleNeedsCheck = true;
    /** True until we've verified auto-retaliate matches the plan once. */
    private boolean retaliateNeedsCheck = true;
    /** Snapshot of the sidebar tab the user/script had open BEFORE we
     *  opened Combat to enforce style/retaliate. Restored on the same
     *  tick we finish the enforcement so the player sees their previous
     *  tab back where they left it. {@code null} when we have nothing
     *  to restore (we never opened Combat for this round). */
    private SidebarTab tabToRestoreAfter = null;

    /** Level at the start of the last tick, keyed by Skill. Used to detect level-ups. */
    private final Map<Skill, Integer> lastSeenLevels = new HashMap<>();

    /** Whether the session has been started. */
    private boolean started = false;

    /**
     * @param client       RuneLite client (read skills/VarPlayers)
     * @param clientThread client-thread reference passed through to sub-components
     * @param dispatcher   humanized input dispatcher
     */
    public TrainingSession(Client client,
                           ClientThread clientThread,
                           HumanizedInputDispatcher dispatcher,
                           @Nullable EventBus eventBus)
    {
        this.client           = client;
        this.styleSwitcher    = new CombatStyleSwitcher(client, clientThread, dispatcher);
        this.retaliateToggle  = new AutoRetaliateToggle(client, clientThread, dispatcher);
        this.combatTab        = new CombatTabActions(client, clientThread, dispatcher);
        this.sidebarTabs      = new SidebarTabActions(client, clientThread, dispatcher);
        this.dispatcher       = dispatcher;
        this.eventBus         = eventBus;
    }

    /** Package-private constructor for testing (accepts pre-built sub-components). */
    TrainingSession(Client client,
                    CombatStyleSwitcher styleSwitcher,
                    AutoRetaliateToggle retaliateToggle)
    {
        this.client          = client;
        this.styleSwitcher   = styleSwitcher;
        this.retaliateToggle = retaliateToggle;
        this.combatTab       = null;   // tests don't exercise the gate
        this.sidebarTabs     = null;
        this.dispatcher      = null;   // tests don't exercise the hover
        this.eventBus        = null;
    }

    /**
     * Starts a training session with the given plan. Captures the player's
     * current levels as the baseline and initialises the skill rotation.
     *
     * @param trainingPlan the plan describing which skills to train and to what level
     */
    public void start(TrainingPlan trainingPlan)
    {
        this.plan     = trainingPlan;
        this.rotation = new SkillRotation(trainingPlan);

        Map<Skill, Integer> startLevels = new HashMap<>();
        for (SkillTarget st : trainingPlan.targets())
        {
            int level = client.getRealSkillLevel(st.skill());
            startLevels.put(st.skill(), level);
            lastSeenLevels.put(st.skill(), level);
            // Baseline XP — gates StatChanged events. Reads are array-
            // backed, safe from any thread.
            lastSeenXp.put(st.skill(), client.getSkillExperience(st.skill()));
            log.info("start: {} = level {}", st.skill(), level);
        }
        rotation.initialize(startLevels);
        started = true;
        if (eventBus != null && !registeredOnEventBus)
        {
            eventBus.register(this);
            registeredOnEventBus = true;
        }
        // Schedule the first idle-curiosity hover. Uniformly random in
        // the configured window so the bot never lands on the same
        // offset twice across runs.
        long firstHover = System.currentTimeMillis()
            + ThreadLocalRandom.current().nextLong(
                trainingPlan.xpHoverMinMs(), trainingPlan.xpHoverMaxMs() + 1);
        nextHoverAtMs.set(firstHover);
        log.info("TrainingSession started; plan={}, firstHoverInMs={}",
            trainingPlan, firstHover - System.currentTimeMillis());
    }

    /** Unregisters from the EventBus and clears state. The combat-loop /
     *  V3 script must call this when the run ends so we don't leak the
     *  subscription across plan switches. */
    public void stop()
    {
        if (eventBus != null && registeredOnEventBus)
        {
            eventBus.unregister(this);
            registeredOnEventBus = false;
        }
        started = false;
        nextHoverAtMs.set(0L);
    }

    /**
     * Engine-driven level-up signal. Fires the same tick the XP lands —
     * faster and more reliable than the 600 ms poll in {@link #tick()}.
     *
     * <p>Filters applied (in order):
     * <ol>
     *   <li>Session must be started.</li>
     *   <li>Skill must be one of the plan targets — boring xp drops on
     *       unrelated skills don't move the rotation.</li>
     *   <li>{@code event.getXp()} must be {@code >} our last-seen xp for
     *       that skill. Login-sync events repeat the saved xp value;
     *       boost / drain events keep xp identical and only move
     *       {@code boostedLevel} — both must be discarded.</li>
     *   <li>{@code event.getLevel()} must be {@code >} our last-seen level
     *       (the rotation only cares about real level transitions). The
     *       level-update path also intentionally tolerates multi-level
     *       skips; we just record the new level.</li>
     * </ol>
     */
    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        if (!started || plan == null || rotation == null) return;
        Skill skill = event.getSkill();
        if (!planContains(skill)) return;
        int prevXp = lastSeenXp.getOrDefault(skill, 0);
        int currXp = event.getXp();
        if (currXp <= prevXp) return;
        lastSeenXp.put(skill, currXp);
        int prevLevel = lastSeenLevels.getOrDefault(skill, 1);
        int currLevel = event.getLevel();
        if (currLevel > prevLevel)
        {
            log.info("level-up via StatChanged: {} {} → {}", skill, prevLevel, currLevel);
            rotation.recordLevelUp(skill, currLevel);
            lastSeenLevels.put(skill, currLevel);
            styleNeedsCheck = true;
            // "Hey I just dinged, let me check my XP to next." Pull the
            // next hover forward into a 20-60s window — but never push
            // it later than the existing schedule. updateAndGet keeps
            // the earlier of the two.
            long postLevel = System.currentTimeMillis()
                + ThreadLocalRandom.current().nextLong(
                    POST_LEVEL_HOVER_MIN_MS, POST_LEVEL_HOVER_MAX_MS + 1);
            nextHoverAtMs.updateAndGet(curr -> curr == 0L ? postLevel : Math.min(curr, postLevel));
        }
    }

    private boolean planContains(Skill skill)
    {
        if (plan == null) return false;
        for (SkillTarget st : plan.targets())
        {
            if (st.skill() == skill) return true;
        }
        return false;
    }

    /**
     * Called every ~600 ms while the script is in the AT_PEN state. Performs:
     * <ol>
     *   <li>Level-up detection (compares current levels to {@code lastSeenLevels}).</li>
     *   <li>Auto-retaliate enforcement (unless the plan says to leave it alone).</li>
     *   <li>Combat style enforcement (clicks the correct style button if needed).</li>
     * </ol>
     */
    public void tick()
    {
        if (!started || plan == null || rotation == null) return;
        // Anti-detection hover runs in parallel with the rest of tick().
        // It spawns its own worker thread; combat / style enforcement
        // still progress here, then back off cleanly when the hover
        // worker grabs the dispatcher's busy flag.
        maybeStartHover();
        if (isComplete()) return;

        // 1. Detect level-ups for each tracked skill. A level-up may
        // have rotated the active skill (e.g. Strength reached the next
        // 2-5 level threshold), in which case the combat style needs
        // re-checking on the next pass.
        for (SkillTarget st : plan.targets())
        {
            Skill skill     = st.skill();
            int currentLevel = client.getRealSkillLevel(skill);
            int lastLevel    = lastSeenLevels.getOrDefault(skill, 1);
            if (currentLevel > lastLevel)
            {
                log.info("level-up detected: {} {} → {}", skill, lastLevel, currentLevel);
                rotation.recordLevelUp(skill, currentLevel);
                lastSeenLevels.put(skill, currentLevel);
                styleNeedsCheck = true;
            }
        }

        if (isComplete()) return;

        // Test-seam compatibility: the package-private constructor used
        // by unit tests passes null for combatTab + sidebarTabs because
        // they don't exercise the tab-aware enforcement. Fall back to
        // the old "always enforce on every tick" behaviour in that case
        // so existing tests still pass.
        if (combatTab == null || sidebarTabs == null)
        {
            if (!plan.autoRetaliateLeaveAlone())
            {
                if (plan.autoRetaliateOn()) retaliateToggle.ensureOn();
                else retaliateToggle.ensureOff();
            }
            Skill active = rotation.activeSkill();
            styleSwitcher.ensureStyleForSkill(active);
            return;
        }

        // 2. Verify state by reading varplayers (NO ui interaction).
        // The retaliate / style varplayers update one tick after the
        // user clicks the widget — we don't need the combat tab open
        // to read them. If the engine already reports the desired
        // value, clear the "needs check" flag and bail. This is the
        // "human pacing" path: most ticks should fall through here
        // without touching the UI at all.
        boolean retaliateMismatched =
            retaliateNeedsCheck
            && !plan.autoRetaliateLeaveAlone()
            && combatTab != null
            && combatTab.isAutoRetaliateOn() != plan.autoRetaliateOn();
        boolean styleMismatched = false;
        if (styleNeedsCheck && combatTab != null)
        {
            Skill active = rotation.activeSkill();
            int desiredSlot = styleSwitcher.resolveSlotForSkill(active);
            if (desiredSlot >= 0)
            {
                styleMismatched = combatTab.currentStyleIndex() != desiredSlot;
            }
            if (!styleMismatched)
            {
                styleNeedsCheck = false;
            }
        }
        if (!retaliateMismatched && retaliateNeedsCheck && combatTab != null)
        {
            // Retaliate is in the right state — mark verified so we
            // don't keep polling the combat tab forever.
            retaliateNeedsCheck = false;
        }

        if (!retaliateMismatched && !styleMismatched)
        {
            // Nothing to do. If we'd previously opened Combat to make a
            // change, restore the prior tab now and clear the snapshot.
            if (sidebarTabs != null
                && tabToRestoreAfter != null
                && combatTab.isCombatTabOpen())
            {
                log.info("training: restoring previous tab {}", tabToRestoreAfter);
                sidebarTabs.openTab(tabToRestoreAfter);
                tabToRestoreAfter = null;
            }
            return;
        }

        // 3. Need to make a change. If Combat tab isn't open, snapshot
        // what's currently visible so we can restore it later, then
        // open Combat. The click is async — wait one tick for the
        // engine to render the new tab before issuing the actual
        // style / retaliate click. Doing both in the same 600 ms tick
        // saturates the dispatcher and the second click gets dropped.
        if (sidebarTabs != null)
        {
            if (!combatTab.isCombatTabOpen())
            {
                if (tabToRestoreAfter == null) tabToRestoreAfter = sidebarTabs.currentTab();
                if (!sidebarTabs.openTab(SidebarTab.COMBAT))
                {
                    log.debug("training: openTab(COMBAT) couldn't dispatch this tick");
                }
                return;
            }
        }

        // 4. Combat tab is open. Issue ONE click this tick — retaliate
        // first if it's the mismatched one, otherwise style. The next
        // tick will re-check via varplayer and either move to the next
        // change OR mark everything verified and restore the prev tab.
        if (retaliateMismatched)
        {
            if (plan.autoRetaliateOn()) retaliateToggle.ensureOn();
            else retaliateToggle.ensureOff();
            return;
        }
        if (styleMismatched)
        {
            Skill active = rotation.activeSkill();
            styleSwitcher.ensureStyleForSkill(active);
        }
    }

    /**
     * Returns {@code true} when all skills in the plan have reached their
     * target levels.
     */
    public boolean isComplete()
    {
        return rotation != null && rotation.isComplete();
    }

    /** If the scheduled-hover wallclock has elapsed and no hover worker
     *  is already running, spawn a worker thread to perform the
     *  open-Stats → hover-icon → restore-tab sequence. Returns
     *  immediately — the hover runs asynchronously while {@code tick()}
     *  proceeds with its own combat-tab work. */
    private void maybeStartHover()
    {
        // Test seam — tests use a constructor that passes null for these
        // sub-components and don't exercise the hover path.
        if (sidebarTabs == null || dispatcher == null || rotation == null) return;
        long due = nextHoverAtMs.get();
        if (due == 0L) return;
        if (System.currentTimeMillis() < due) return;
        if (!hoverInFlight.compareAndSet(false, true)) return;
        Skill active = rotation.activeSkill();
        int iconId = SkillIconLookup.widgetIdFor(active);
        if (iconId < 0)
        {
            // Shouldn't happen for the chicken-farm trainer (Att/Str/Def
            // all map). Reschedule to the idle-curiosity window so we
            // don't spin re-entering this branch every tick.
            log.debug("training: no Stats icon for skill {}, deferring hover", active);
            rescheduleIdleCuriosity();
            hoverInFlight.set(false);
            return;
        }
        Thread t = new Thread(() -> runHoverSequence(iconId), "training-skills-hover");
        t.setDaemon(true);
        t.start();
    }

    /** Body of the spawned hover worker. Always reschedules + clears the
     *  in-flight flag in {@code finally} so a single failure doesn't
     *  permanently block future hovers. */
    private void runHoverSequence(int iconId)
    {
        try
        {
            // Wait for any in-flight click chain to settle. If combat is
            // mid-attack, this gives it ~2s to finish; the hover slips
            // into the gap between attack-and-watch.
            if (!dispatcher.awaitIdle(2_000L))
            {
                log.debug("training: dispatcher still busy after 2s — skipping this hover");
                return;
            }
            SidebarTab prior = sidebarTabs.currentTab();
            if (prior == SidebarTab.STATS)
            {
                // User already has Stats open — just hover the icon.
                holdAndHover(iconId);
                return;
            }
            if (!sidebarTabs.openTabAndWait(SidebarTab.STATS, 2_000L))
            {
                log.debug("training: couldn't open STATS tab — skipping hover");
                return;
            }
            // Wait for the openTab dispatch's worker to release busy
            // before we acquire it for the hover.
            if (!dispatcher.awaitIdle(2_000L)) return;
            holdAndHover(iconId);
            // Restore prior tab if we knew what it was. {@code null}
            // means the engine reported a tab id we don't recognise
            // (e.g. login screen) — leave Stats open in that case.
            if (prior != null)
            {
                sidebarTabs.openTabAndWait(prior, 2_000L);
            }
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
        }
        catch (Throwable th)
        {
            log.warn("training: hover sequence threw", th);
        }
        finally
        {
            rescheduleIdleCuriosity();
            hoverInFlight.set(false);
        }
    }

    /** Acquire the dispatcher's busy flag, hover the skill icon for a
     *  humanized dwell, then release. Holds busy for the full duration
     *  so combat / walker dispatches see {@code isBusy()} and back off
     *  rather than hijacking the cursor mid-hover. */
    private void holdAndHover(int iconId) throws InterruptedException
    {
        boolean ran = dispatcher.runExclusive(() ->
            dispatcher.hoverWidget(iconId, 600L, 1500L));
        if (!ran)
        {
            log.debug("training: someone else held the dispatcher — hover deferred");
        }
    }

    /** Reschedule {@link #nextHoverAtMs} to a fresh uniform value in the
     *  plan's idle-curiosity window. Used after a hover lands AND when
     *  we bail out early so the bot doesn't re-enter the spawn branch
     *  every tick. */
    private void rescheduleIdleCuriosity()
    {
        if (plan == null) { nextHoverAtMs.set(0L); return; }
        long next = System.currentTimeMillis()
            + ThreadLocalRandom.current().nextLong(
                plan.xpHoverMinMs(), plan.xpHoverMaxMs() + 1);
        nextHoverAtMs.set(next);
    }

    // ---- test seams -----------------------------------------------------

    /** Test-only — overwrites the next-hover wallclock so unit tests can
     *  verify scheduling logic without sleeping for minutes. Package
     *  private; not intended for production callers. */
    void setNextHoverAtMsForTest(long wallclockMs)
    {
        nextHoverAtMs.set(wallclockMs);
    }

    /** Test-only — reads the current next-hover wallclock so tests can
     *  assert reschedule behaviour after a level-up event. */
    long nextHoverAtMsForTest()
    {
        return nextHoverAtMs.get();
    }

    /**
     * Returns a human-readable status line, e.g.
     * {@code "training Strength (lvl 14, 73 XP to 15)"}.
     */
    public String status()
    {
        if (!started || rotation == null || plan == null)
        {
            return "not started";
        }
        if (isComplete())
        {
            return "complete";
        }
        Skill active = rotation.activeSkill();
        SkillProgress.Snapshot snap = SkillProgress.read(client, active);
        return String.format("training %s (lvl %d, %d XP to %d)",
            active.getName(),
            snap.level(),
            snap.xpToNextLevel(),
            snap.level() + 1);
    }
}
