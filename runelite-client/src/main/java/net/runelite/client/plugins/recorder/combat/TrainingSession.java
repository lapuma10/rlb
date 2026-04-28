package net.runelite.client.plugins.recorder.combat;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;

import java.util.HashMap;
import java.util.Map;

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
    private SkillRotation rotation;
    private TrainingPlan plan;

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
                           HumanizedInputDispatcher dispatcher)
    {
        this.client           = client;
        this.styleSwitcher    = new CombatStyleSwitcher(client, clientThread, dispatcher);
        this.retaliateToggle  = new AutoRetaliateToggle(client, clientThread, dispatcher);
    }

    /** Package-private constructor for testing (accepts pre-built sub-components). */
    TrainingSession(Client client,
                    CombatStyleSwitcher styleSwitcher,
                    AutoRetaliateToggle retaliateToggle)
    {
        this.client          = client;
        this.styleSwitcher   = styleSwitcher;
        this.retaliateToggle = retaliateToggle;
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
            log.info("start: {} = level {}", st.skill(), level);
        }
        rotation.initialize(startLevels);
        started = true;
        log.info("TrainingSession started; plan={}", trainingPlan);
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
        if (isComplete()) return;

        // 1. Detect level-ups for each tracked skill.
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
            }
        }

        if (isComplete()) return;

        // 2. Auto-retaliate enforcement.
        if (!plan.autoRetaliateLeaveAlone())
        {
            if (plan.autoRetaliateOn())
            {
                retaliateToggle.ensureOn();
            }
            else
            {
                retaliateToggle.ensureOff();
            }
        }

        // 3. Combat style enforcement.
        Skill active = rotation.activeSkill();
        CombatStyleIndex.forSkill(active).ifPresent(styleSwitcher::ensureStyle);
    }

    /**
     * Returns {@code true} when all skills in the plan have reached their
     * target levels.
     */
    public boolean isComplete()
    {
        return rotation != null && rotation.isComplete();
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
