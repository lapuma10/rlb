package net.runelite.client.plugins.recorder.quest.steps;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.npc.NpcInteraction;
import net.runelite.client.plugins.recorder.npc.NpcScan;
import net.runelite.client.plugins.recorder.quest.QuestStepThreads;
import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardKey;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * Walks an NPC dialogue tree to completion. The caller supplies a
 * {@code doneWhen} predicate against {@link WorldSnapshot} — typically
 * a quest-varbit comparison — that gates {@link #check} success.
 *
 * <p>Resumability comes for free: if {@code doneWhen} is already true
 * when the step starts, {@link #onStart} marks it satisfied without
 * dispatching anything, and {@link #check} returns SUCCEEDED on the
 * next tick.
 */
@Slf4j
public final class TalkToNpcStep implements Step {

    private static final int SCAN_RADIUS = 15;
    private static final long DIALOG_OPEN_TIMEOUT_MS = 4_000L;

    private final NpcInteraction npc;
    private final ClientThread clientThread;
    private final String npcName;
    private final int[] preferredIds;
    private final String[] dialogOptions;
    /** Checked at {@link #onStart}: if true, marks ALREADY_SATISFIED and skips
     *  dispatch (resume-safe skip). Defaults to {@code doneWhen} when the
     *  single-predicate constructor is used. */
    private final Predicate<WorldSnapshot> skipWhen;
    /** Checked in {@link #check}: gates the RUNNING → Succeeded transition
     *  once the dialogue task has exited. Use {@code snap -> true} to succeed
     *  as soon as the dialogue completes regardless of external state. */
    private final Predicate<WorldSnapshot> doneWhen;

    /** Tracks whether the dialogue RUN_TASK is still in flight on the
     *  dispatcher worker. {@code questStarted} can flip to true mid-
     *  dialog (e.g. Veronica sets the quest var on a particular option
     *  click) — without this gate, {@link #check} would return
     *  Succeeded immediately, the engine would pop this step and start
     *  the next, and the next step's dispatch would hit "dispatcher
     *  busy" silently because the dialog task is still running. Same
     *  shape as the ReplayTrailStep taskExited fix. */
    private final AtomicBoolean taskExited = new AtomicBoolean(true);

    /** Single-predicate constructor — {@code skipWhen} and {@code doneWhen}
     *  both use the same predicate (existing behavior). */
    public TalkToNpcStep(NpcInteraction npc,
                         ClientThread clientThread,
                         String npcName,
                         int[] preferredIds,
                         String[] dialogOptions,
                         Predicate<WorldSnapshot> doneWhen) {
        this(npc, clientThread, npcName, preferredIds, dialogOptions, doneWhen, doneWhen);
    }

    /** Two-predicate constructor — {@code skipWhen} governs the ALREADY_SATISFIED
     *  skip at onStart; {@code doneWhen} governs the success gate in check().
     *  Use when the skip condition differs from the success condition, e.g. a
     *  "has any quest item" skip paired with a {@code snap -> true} done (so the
     *  step always advances once the fresh dialogue completes). */
    public TalkToNpcStep(NpcInteraction npc,
                         ClientThread clientThread,
                         String npcName,
                         int[] preferredIds,
                         String[] dialogOptions,
                         Predicate<WorldSnapshot> skipWhen,
                         Predicate<WorldSnapshot> doneWhen) {
        this.npc = npc;
        this.clientThread = clientThread;
        this.npcName = npcName;
        this.preferredIds = preferredIds == null ? new int[0] : preferredIds;
        this.dialogOptions = dialogOptions == null ? new String[0] : dialogOptions;
        this.skipWhen = skipWhen != null ? skipWhen : doneWhen;
        this.doneWhen = doneWhen;
    }

    @Override public String name()                              { return "TalkTo(" + npcName + ")"; }
    @Override public int priority()                             { return 50; }
    @Override public int timeoutTicks()                         { return 40; }   // ~24s
    @Override public PreemptionPolicy preemptionPolicy()        { return PreemptionPolicy.WHEN_SAFE; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard b)      { return s.player() != null; }

    @Override
    public void onStart(StepContext ctx) {
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);

        if (skipWhen.test(ctx.snapshot())) {
            step.put(K_OUTCOME, Outcome.ALREADY_SATISFIED);
            log.info("quest-talk: {} already satisfied — skipping dispatch", npcName);
            return;
        }

        taskExited.set(false);
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.RUN_TASK)
            .channel(ActionRequest.Channel.MOUSE)
            .task(() -> {
                try {
                    findAndTalk();
                } finally {
                    taskExited.set(true);
                }
            })
            .taskName("QUEST_TALK_" + npcName.toUpperCase().replace(' ', '_'))
            .build();
        ctx.dispatcher().dispatch(req);
    }

    @Override public void onEvent(Object e, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        if (step.get(K_OUTCOME).orElse(Outcome.FRESH) == Outcome.ALREADY_SATISFIED) {
            return new Completion.Succeeded("doneWhen already true at onStart");
        }
        if (doneWhen.test(s)) {
            // Hold off Succeeded until the dialogue task has actually exited —
            // otherwise the engine pops us and the next step's RUN_TASK dispatch
            // gets dropped with a silent "dispatcher busy" because the dialog
            // task is still walking continues / picking options.
            if (taskExited.get()) {
                return new Completion.Succeeded("doneWhen true and dialog task drained");
            }
            return Completion.RUNNING;
        }
        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        // Re-dispatch the talkTo flow — npc may have wandered, dialog may
        // have been pre-empted, etc. The find runs fresh each retry.
        return new Recovery.Retry(3);
    }

    // ─── Dispatcher-worker task body ────────────────────────────────────────

    /**
     * Runs on the dispatcher worker (per ActionRequest.Kind.RUN_TASK).
     * Marshals findOnScene to the client thread, then calls talkTo
     * (which must NOT run on the client thread).
     */
    private void findAndTalk() throws InterruptedException {
        NpcScan scan = QuestStepThreads.onClient(clientThread,
            () -> npc.findOnScene(SCAN_RADIUS, preferredIds, npcName));
        if (scan == null || !scan.found()) {
            log.warn("quest-talk: {} not found within {} tiles — will retry", npcName, SCAN_RADIUS);
            return;
        }
        // talkToWithExpectedName reads the dialog's NPC-name widget before
        // driving options, so a wrong NPC walking up between click and
        // dialog-open doesn't pollute the conversation. WRONG_NPC and
        // NEVER_OPENED both fall through; check() stays RUNNING and the
        // step retries on the next tick.
        NpcInteraction.TalkResult result = npc.talkToWithExpectedName(
            scan.npcIndex(), npcName, "Talk-to", DIALOG_OPEN_TIMEOUT_MS, dialogOptions);
        if (result != NpcInteraction.TalkResult.OPENED_AND_COMPLETED) {
            log.warn("quest-talk: {} → {} (will retry)", npcName, result);
        }
    }

    // ─── Blackboard keys ────────────────────────────────────────────────────

    private enum Outcome { FRESH, ALREADY_SATISFIED }

    private static final BlackboardKey<Outcome> K_OUTCOME =
        BlackboardKey.of("talkToNpc.outcome", Outcome.class);
}
