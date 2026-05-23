package net.runelite.client.sequence.artemis;

import java.util.Optional;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.session.SessionShape;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.artemis.outcome.OutcomeCheck;
import net.runelite.client.sequence.artemis.query.ItemQuery;
import net.runelite.client.sequence.artemis.query.NpcQuery;
import net.runelite.client.sequence.artemis.query.ObjectQuery;
import net.runelite.client.sequence.artemis.query.WidgetQuery;
import net.runelite.client.sequence.artemis.session.IdlePolicy;
import net.runelite.client.sequence.artemis.view.GameObjRef;
import net.runelite.client.sequence.artemis.view.GroundItemRef;
import net.runelite.client.sequence.artemis.view.InvSlot;
import net.runelite.client.sequence.artemis.view.InventoryView;
import net.runelite.client.sequence.artemis.view.NpcRef;
import net.runelite.client.sequence.artemis.view.PlayerState;
import net.runelite.client.sequence.artemis.view.WidgetRef;
import net.runelite.client.sequence.artemis.zones.NamedZone;
import net.runelite.client.sequence.composite.SequencePlanBuilder;

/**
 * Single script-facing API for automation scripts. Engine owns the
 * rules; scripts express intent. See
 * {@code docs/superpowers/specs/2026-05-23-artemis-design.md}.
 *
 * <p>Reads return synchronously; Action methods return {@link Step}.
 * Scripts that depend on this interface MUST NOT also import the
 * engine internals listed in spec §14 (Import policy). Enforced by
 * grep gate (Phase 1B / Phase 4).
 *
 * <p>Phase 1A.1 — interface only. {@code ArtemisImpl} lands in Phase
 * 1A.2; Step subclasses in Phase 1A.3; walk/idle/logout wiring in
 * Phase 1A.4; grep gate in Phase 1B.
 */
public interface Artemis
{
	// ── READS — synchronous, return Optional<view-record> ───────────

	/** Pick one NPC matching {@code query}. Applies
	 *  {@link net.runelite.client.sequence.artemis.query.RotationPolicy}
	 *  via per-account-seeded RNG — see {@code RotationPolicy} Javadoc
	 *  for the per-call non-stability caveat (resolve once for the
	 *  immediate action; refs carry {@code observedTick} and become
	 *  stale). Returns {@link Optional#empty()} when no match. */
	Optional<NpcRef> findNpc(NpcQuery query);

	/** Pick one in-world object matching {@code query}. Same rotation
	 *  + stability semantics as {@link #findNpc}. {@code GameObjRef.name()}
	 *  may be {@code null} when the object definition could not be
	 *  resolved (distinct from a literal empty name). */
	Optional<GameObjRef> findObject(ObjectQuery query);

	/** Pick one ground item matching {@code query}. Same rotation +
	 *  stability semantics as {@link #findNpc}. {@code GroundItemRef.name()}
	 *  may be {@code null} when {@code ItemManager} lookup fails. */
	Optional<GroundItemRef> findItem(ItemQuery query);

	/** Resolve a widget by id (and optional child slot). Honors
	 *  {@code requireVisible} per spec §6 (walks the parent chain via
	 *  {@code Widget.isHidden()}). Returns {@link Optional#empty()}
	 *  when the widget is absent, the child slot is out of range, or
	 *  {@code requireVisible=true} and the widget (or any ancestor) is
	 *  hidden. */
	Optional<WidgetRef> findWidget(WidgetQuery query);

	InventoryView inventory();

	PlayerState player();

	SessionShape session();

	// ── ACTIONS — return Step ───────────────────────────────────────

	Step walkTo(WorldPoint target);

	Step walkTo(NamedZone zone);

	/** Base click — succeeds when menu verb verified before press AND
	 *  dispatcher reported completion. Does NOT assume animation change
	 *  or state change. Use the {@link OutcomeCheck} overload for those. */
	Step click(NpcRef target, String verb);

	Step click(GameObjRef target, String verb);

	Step click(WidgetRef target, String verb);

	/** Outcome-verifying click — succeeds when base success criteria
	 *  PLUS {@code expected.matches()} returns true within its budget.
	 *  Caller supplies the expected post-condition. Common
	 *  {@link OutcomeCheck} factories:
	 *  {@link OutcomeCheck#playerAnimChanged(int)},
	 *  {@link OutcomeCheck#targetAnimChanged(int)},
	 *  {@link OutcomeCheck#widgetVisible(int)},
	 *  {@link OutcomeCheck#interactingWithMe()}. */
	Step click(NpcRef target, String verb, OutcomeCheck expected);

	Step click(GameObjRef target, String verb, OutcomeCheck expected);

	Step click(WidgetRef target, String verb, OutcomeCheck expected);

	Step take(GroundItemRef item);

	Step useOn(InvSlot source, InvSlot target);

	Step useOn(InvSlot source, GameObjRef target);

	Step useOn(InvSlot source, NpcRef target);

	Step useOn(InvSlot source, WidgetRef target);

	// ── Maintenance (bypass session.shouldContinue gate per spec §3) ─

	Step idle(IdlePolicy policy);

	Step logout();

	// ── Composition ─────────────────────────────────────────────────

	SequencePlanBuilder plan(String name);
}
