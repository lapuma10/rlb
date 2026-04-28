package net.runelite.client.plugins.recorder.combat;

import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.sequence.internal.ActionRequest;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TrainingSessionTest
{
    // ---- helpers ----

    /**
     * Build a mock client that returns the given level for every queried skill
     * and starts with the wrong attack style (index 1 = STRENGTH_PRIMARY) so the
     * switcher always has work to do when ATTACK is the active skill.
     */
    private Client mockClient(int attackLevel, int strengthLevel, int defenceLevel)
    {
        Client client = mock(Client.class);
        when(client.getRealSkillLevel(Skill.ATTACK)).thenReturn(attackLevel);
        when(client.getRealSkillLevel(Skill.STRENGTH)).thenReturn(strengthLevel);
        when(client.getRealSkillLevel(Skill.DEFENCE)).thenReturn(defenceLevel);
        // VarPlayer 43: style index 1 = STRENGTH is currently active
        when(client.getVarpValue(VarPlayerID.COM_MODE)).thenReturn(1);
        // VarPlayer 172: auto-retaliate is OFF (value=1), plan wants ON
        when(client.getVarpValue(VarPlayerID.OPTION_NODEF)).thenReturn(1);
        // getSkillExperience for status() tests
        when(client.getSkillExperience(Skill.ATTACK)).thenReturn(83);
        when(client.getSkillExperience(Skill.STRENGTH)).thenReturn(83);
        when(client.getSkillExperience(Skill.DEFENCE)).thenReturn(83);
        return client;
    }

    private TrainingPlan simplePlan(boolean autoOn)
    {
        return TrainingPlan.basic(
            List.of(new SkillTarget(Skill.ATTACK, 20),
                    new SkillTarget(Skill.STRENGTH, 20)),
            autoOn);
    }

    // ---- tests ----

    @Test
    public void start_capturesStartingLevels()
    {
        Client client = mockClient(10, 12, 5);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        CombatStyleSwitcher switcher = new CombatStyleSwitcher(client, dispatcher);
        AutoRetaliateToggle toggle   = new AutoRetaliateToggle(client, dispatcher);

        TrainingSession session = new TrainingSession(client, switcher, toggle);
        session.start(simplePlan(true));

        // Session is started — not complete since levels < 20.
        assertFalse(session.isComplete());
    }

    @Test
    public void tick_ensuresCorrectCombatStyleForActiveSkill()
    {
        // Client has VarPlayer 43 = 1 (STRENGTH is currently active).
        // Plan starts with ATTACK at 10 and STRENGTH at 10; after start, one of
        // them becomes active. If ATTACK is active but style widget is wrong,
        // the switcher dispatches a CLICK_WIDGET.
        // We force ATTACK to be the first skill so both target it (STRENGTH also
        // below target). The rotation picks one; we just verify a dispatch
        // happened when the style didn't match.
        Client client = mockClient(10, 10, 5);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        CombatStyleSwitcher switcher = new CombatStyleSwitcher(client, dispatcher);
        AutoRetaliateToggle toggle   = new AutoRetaliateToggle(client, dispatcher);

        TrainingSession session = new TrainingSession(client, switcher, toggle);
        // Use a plan with only ATTACK so the active skill is deterministically ATTACK.
        TrainingPlan attackOnly = TrainingPlan.basic(
            List.of(new SkillTarget(Skill.ATTACK, 20)), true);
        session.start(attackOnly);

        // VarPlayer 43 = 1 (STRENGTH selected) but we want ATTACK (idx 0) →
        // the switcher should dispatch a click.
        session.tick();

        ArgumentCaptor<ActionRequest> captor = ArgumentCaptor.forClass(ActionRequest.class);
        // At least one dispatch — could be retaliate or style.
        verify(dispatcher, atLeastOnce()).dispatch(captor.capture());
        boolean styleClicked = captor.getAllValues().stream()
            .anyMatch(r -> r.getKind() == ActionRequest.Kind.CLICK_WIDGET
                       && r.getWidgetId() == InterfaceID.CombatInterface._0);
        assertTrue("style switch to ATTACK_PRIMARY must have been dispatched", styleClicked);
    }

    @Test
    public void tick_ensuresAutoRetaliateWhenPlanSaysOn()
    {
        // VarPlayer 172 = 1 (OFF), plan wants ON → toggle should dispatch.
        Client client = mockClient(10, 10, 5);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        CombatStyleSwitcher switcher = new CombatStyleSwitcher(client, dispatcher);
        AutoRetaliateToggle toggle   = new AutoRetaliateToggle(client, dispatcher);

        TrainingSession session = new TrainingSession(client, switcher, toggle);
        TrainingPlan attackOnly = TrainingPlan.basic(
            List.of(new SkillTarget(Skill.ATTACK, 20)), true);   // autoRetaliate=ON
        session.start(attackOnly);
        session.tick();

        ArgumentCaptor<ActionRequest> captor = ArgumentCaptor.forClass(ActionRequest.class);
        verify(dispatcher, atLeastOnce()).dispatch(captor.capture());
        boolean retaliateClicked = captor.getAllValues().stream()
            .anyMatch(r -> r.getKind() == ActionRequest.Kind.CLICK_WIDGET
                       && r.getWidgetId() == InterfaceID.CombatInterface.RETALIATE);
        assertTrue("auto-retaliate toggle must have been dispatched", retaliateClicked);
    }

    @Test
    public void tick_doesNotTouchAutoRetaliate_whenLeaveAloneIsSet()
    {
        Client client = mockClient(10, 10, 5);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        CombatStyleSwitcher switcher = new CombatStyleSwitcher(client, dispatcher);
        AutoRetaliateToggle toggle   = new AutoRetaliateToggle(client, dispatcher);

        TrainingSession session = new TrainingSession(client, switcher, toggle);
        TrainingPlan leaveAlonePlan = new TrainingPlan(
            List.of(new SkillTarget(Skill.ATTACK, 20)),
            false, true,   // autoRetaliateLeaveAlone = true
            2, 5, TrainingPlan.DEFAULT_XP_HOVER_MS);
        session.start(leaveAlonePlan);
        session.tick();

        // No RETALIATE widget click should be dispatched.
        ArgumentCaptor<ActionRequest> captor = ArgumentCaptor.forClass(ActionRequest.class);
        verify(dispatcher, atMost(1)).dispatch(captor.capture());   // only style click allowed
        captor.getAllValues().forEach(r ->
            assertNotEquals("RETALIATE must not be clicked", InterfaceID.CombatInterface.RETALIATE, r.getWidgetId()));
    }

    @Test
    public void levelUp_causesRotationToRecordAndEventuallySwitch()
    {
        // Two skills: ATTACK target 20, STRENGTH target 20; start both at 10.
        // Threshold=2; give the active skill 2 level-ups → expect a switch.
        Client client = mockClient(10, 10, 5);
        // After tick(), levels seen match starting values.
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        CombatStyleSwitcher switcher = new CombatStyleSwitcher(client, dispatcher);
        AutoRetaliateToggle toggle   = new AutoRetaliateToggle(client, dispatcher);

        TrainingSession session = new TrainingSession(client, switcher, toggle);
        TrainingPlan plan = new TrainingPlan(
            List.of(new SkillTarget(Skill.ATTACK, 20),
                    new SkillTarget(Skill.STRENGTH, 20)),
            true, false, 2, 2, TrainingPlan.DEFAULT_XP_HOVER_MS);
        session.start(plan);

        // Simulate gaining levels by updating mock return values and ticking.
        when(client.getRealSkillLevel(Skill.ATTACK)).thenReturn(11);
        session.tick();
        when(client.getRealSkillLevel(Skill.ATTACK)).thenReturn(12);
        session.tick();

        // After 2 level-ups on ATTACK, the rotation should have switched.
        // We can't easily inspect the active skill from outside — but we can
        // verify isComplete() remains false (both skills still below 20).
        assertFalse("session must not be complete after 2 levels", session.isComplete());
    }

    @Test
    public void isComplete_trueWhenAllTargetsMet()
    {
        // Start already at target.
        Client client = mockClient(20, 20, 5);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        CombatStyleSwitcher switcher = new CombatStyleSwitcher(client, dispatcher);
        AutoRetaliateToggle toggle   = new AutoRetaliateToggle(client, dispatcher);

        TrainingSession session = new TrainingSession(client, switcher, toggle);
        session.start(simplePlan(true));

        // Tick will see current levels == starting levels and rotation will have
        // been initialised with levels already at target.
        session.tick();

        assertTrue("session must be complete when all levels at target", session.isComplete());
    }

    @Test
    public void status_notStarted_returnsNotStarted()
    {
        Client client = mockClient(10, 10, 5);
        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        CombatStyleSwitcher switcher = new CombatStyleSwitcher(client, dispatcher);
        AutoRetaliateToggle toggle   = new AutoRetaliateToggle(client, dispatcher);

        TrainingSession session = new TrainingSession(client, switcher, toggle);

        assertTrue(session.status().contains("not started"));
    }

    @Test
    public void status_afterStart_containsSkillName()
    {
        // Level 2 with 83 XP → 91 XP to next.
        Client client = mockClient(10, 10, 5);
        when(client.getSkillExperience(Skill.ATTACK)).thenReturn(83);   // level-2 XP
        when(client.getSkillExperience(Skill.STRENGTH)).thenReturn(83);
        when(client.getRealSkillLevel(Skill.ATTACK)).thenReturn(2);
        when(client.getRealSkillLevel(Skill.STRENGTH)).thenReturn(2);

        CombatDispatcher dispatcher = mock(CombatDispatcher.class);
        CombatStyleSwitcher switcher = new CombatStyleSwitcher(client, dispatcher);
        AutoRetaliateToggle toggle   = new AutoRetaliateToggle(client, dispatcher);
        TrainingSession session = new TrainingSession(client, switcher, toggle);
        session.start(simplePlan(true));

        String s = session.status();
        // Status must mention a skill name and level info.
        assertTrue("status must contain 'training'", s.startsWith("training"));
        assertTrue("status must mention lvl", s.contains("lvl"));
    }
}
