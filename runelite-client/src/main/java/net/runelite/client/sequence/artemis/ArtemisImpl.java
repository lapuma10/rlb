package net.runelite.client.sequence.artemis;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import javax.annotation.Nullable;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.recorder.RecorderManager;
import net.runelite.client.plugins.recorder.analyse.StepEvent;
import net.runelite.client.plugins.recorder.nav.Navigator;
import net.runelite.client.plugins.recorder.session.AccountRng;
import net.runelite.client.plugins.recorder.session.SessionShape;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.activities.script.ClickGameObjStep;
import net.runelite.client.sequence.activities.script.ClickNpcStep;
import net.runelite.client.sequence.activities.script.ClickWidgetStep;
import net.runelite.client.sequence.activities.script.IdleStep;
import net.runelite.client.sequence.activities.script.LogoutStep;
import net.runelite.client.sequence.activities.script.TakeGroundItemStep;
import net.runelite.client.sequence.activities.script.UseOnStep;
import net.runelite.client.sequence.activities.script.WalkToWorldPointStep;
import net.runelite.client.sequence.artemis.outcome.OutcomeCheck;
import net.runelite.client.sequence.artemis.query.ItemQuery;
import net.runelite.client.sequence.artemis.query.NpcQuery;
import net.runelite.client.sequence.artemis.query.ObjectQuery;
import net.runelite.client.sequence.artemis.query.RotationPolicySelector;
import net.runelite.client.sequence.artemis.query.WidgetQuery;
import net.runelite.client.sequence.artemis.session.IdlePolicy;
import net.runelite.client.sequence.artemis.view.GameObjRef;
import net.runelite.client.sequence.artemis.view.GroundItemRef;
import net.runelite.client.sequence.artemis.view.InvSlot;
import net.runelite.client.sequence.artemis.view.InventoryView;
import net.runelite.client.sequence.artemis.view.NpcRef;
import net.runelite.client.sequence.artemis.view.ObjectKind;
import net.runelite.client.sequence.artemis.view.PlayerState;
import net.runelite.client.sequence.artemis.view.WidgetRef;
import net.runelite.client.sequence.artemis.zones.NamedZone;
import net.runelite.client.sequence.composite.SequencePlanBuilder;

/**
 * Phase 1A.3 / 1A.4a / 1A.4b / 1A.4c implementation of {@link Artemis}.
 * Read methods marshal to the client thread and apply per-query
 * {@code RotationPolicy} (Phase 1A.2). Action methods {@code click(...)},
 * {@code take(GroundItemRef)}, {@code useOn(InvSlot, ...)} return Step
 * subclasses under {@code sequence/activities/script/} (Phase 1A.3).
 * {@code idle(IdlePolicy)} and {@code logout()} return maintenance Steps
 * (Phase 1A.4b). {@code walkTo(WorldPoint)} returns a daemon-worker-driven
 * navigation Step (Phase 1A.4c). {@code walkTo(NamedZone)} and {@code
 * plan(...)} still throw {@link UnsupportedOperationException} — they
 * land in 1A.4d (NamedZone) + 1A.4e (plan).
 *
 * <p>Phase 1A.4a introduced {@link ArtemisDeps} as the sole constructor
 * argument so the surface stays one parameter as new engine pieces land
 * ({@link Navigator}, {@link LogoutAction}, future additions). Fields
 * are unpacked into private finals to preserve the hot-path read
 * pattern.
 *
 * <p>Read methods marshal to the client thread via {@link #readOnClient}
 * (inline if already on client thread, else queued via
 * {@link ClientThread#invoke}). Every returned ref carries
 * {@code observedTick = client.getTickCount()} per spec §8.
 *
 * <p><b>RotationPolicy is honored here</b> — not delegated to
 * {@code NpcSelector} or {@code SceneScanner} (those use strict-closest
 * + index tiebreak). This impl reads the candidate list directly from
 * the {@link WorldView} / {@link Scene} and applies the policy via
 * {@link RotationPolicySelector} with a per-account-seeded
 * {@link java.util.Random} — spec §3 principle 7.
 */
@Slf4j
public final class ArtemisImpl implements Artemis
{
	private final Client client;
	private final ClientThread clientThread;
	private final SessionShape session;
	@Nullable private final ItemManager itemManager;
	private final AccountRng accountRng;
	private final RotationPolicySelector selector;
	/** StepEvent sink, bound to {@code recorder::recordStepEvent} via
	 *  the production constructor when a {@link RecorderManager} is
	 *  provided. {@code null} when no recorder is wired (tests, or the
	 *  no-arg compatibility constructor) — Step subclasses no-op
	 *  emission in that case. */
	@Nullable private final Consumer<StepEvent> stepEventSink;
	/** Walker contract for {@code walkTo(...)} (Phase 1A.4c/d). Stored
	 *  in 1A.4a; the throwing stubs ignore it. When walkTo lands, the
	 *  Step subclass checks this for null and fails loud with a clear
	 *  diagnostic rather than NPE'ing inside the dispatch path. */
	@Nullable private final Navigator navigator;
	/** Logout contract for {@code logout()} (Phase 1A.4b). Same nullable
	 *  semantics as {@link #navigator} — stub throws in 1A.4a; LogoutStep
	 *  checks for null when wired. */
	@Nullable private final LogoutAction logoutAction;

	/** Sole constructor — takes a single {@link ArtemisDeps} bundle so
	 *  the surface doesn't grow with each new engine piece. The deps
	 *  record's per-field Javadoc documents which fields are nullable
	 *  and what {@code null} means.
	 *
	 *  <p>The Phase 1A.3 sole-constructor discipline carries forward —
	 *  if a caller needs new wiring (production: {@code RecorderPlugin};
	 *  tests: any new fixture), they add the dep to {@link ArtemisDeps}
	 *  rather than introducing an overload. */
	public ArtemisImpl(ArtemisDeps deps)
	{
		this.client = deps.client();
		this.clientThread = deps.clientThread();
		this.session = deps.session();
		this.itemManager = deps.itemManager();
		this.accountRng = deps.accountRng();
		this.selector = new RotationPolicySelector(accountRng.forAccount("artemis-rotation"));
		this.stepEventSink = deps.recorder() == null ? null : deps.recorder()::recordStepEvent;
		this.navigator = deps.navigator();
		this.logoutAction = deps.logoutAction();
	}

	// ── READ HELPERS ────────────────────────────────────────────────

	/** Bounded wait for marshaled reads. 5 s tolerates a slow tick but
	 *  surfaces a genuine engine-stall loudly rather than hanging
	 *  callers indefinitely. Typical reads on a healthy client complete
	 *  in microseconds; if 5 seconds pass without the client-thread
	 *  queue draining, something is wrong enough that the caller
	 *  should fail rather than wait. Callers that can't tolerate this
	 *  surface area should call from the client thread (inline path,
	 *  no timeout). */
	private static final long READ_TIMEOUT_SECONDS = 5L;

	/** Run a read on the client thread. Inline when the caller is
	 *  already on the client thread (saves a marshal hop for engine
	 *  Step.check() calls); marshals via {@link ClientThread#invoke}
	 *  otherwise and blocks the caller up to {@link #READ_TIMEOUT_SECONDS}
	 *  (5 seconds — see field Javadoc for rationale).
	 *
	 *  <p>Uses {@code Client.isClientThread()} for the inline check
	 *  rather than a {@code Thread.currentThread() == getClientThread()}
	 *  compare so a null engine thread (pre-init) is handled by the
	 *  engine, not by Artemis.
	 *
	 *  <p>If the supplier throws, the original cause is propagated to
	 *  the caller (unwrapped from {@link ExecutionException} /
	 *  {@link java.util.concurrent.CompletionException}). On timeout,
	 *  throws {@link RuntimeException} — read failures are surfaced,
	 *  not swallowed.
	 *
	 *  <p>Centralised here so reads never sprinkle ad-hoc
	 *  {@code clientThread.invoke(...)} calls. */
	private <T> T readOnClient(Supplier<T> readFn)
	{
		if (client.isClientThread())
		{
			return readFn.get();
		}
		CompletableFuture<T> future = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			try
			{
				future.complete(readFn.get());
			}
			catch (Throwable t)
			{
				future.completeExceptionally(t);
			}
		});
		try
		{
			return future.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new RuntimeException("Interrupted waiting for client-thread Artemis read", e);
		}
		catch (ExecutionException e)
		{
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			if (cause instanceof RuntimeException re)
			{
				throw re;
			}
			if (cause instanceof Error err)
			{
				throw err;
			}
			throw new RuntimeException("Artemis read failed on client thread", cause);
		}
		catch (TimeoutException e)
		{
			throw new RuntimeException(
				"Artemis read timed out after " + READ_TIMEOUT_SECONDS + "s on client thread", e);
		}
	}

	// ── READS ───────────────────────────────────────────────────────

	@Override
	public Optional<NpcRef> findNpc(NpcQuery q)
	{
		return readOnClient(() ->
		{
			Player self = client.getLocalPlayer();
			if (self == null)
			{
				return Optional.<NpcRef>empty();
			}
			WorldPoint selfLoc = self.getWorldLocation();
			long tickNow = client.getTickCount();

			List<NPC> candidates = new ArrayList<>();
			WorldView wv = client.getTopLevelWorldView();
			for (NPC npc : wv.npcs())
			{
				if (!matches(npc, q, selfLoc, self))
				{
					continue;
				}
				candidates.add(npc);
			}
			NPC chosen = selector.pick(candidates, n -> distance(n.getWorldLocation(), selfLoc),
				q.rotation());
			if (chosen == null)
			{
				return Optional.<NpcRef>empty();
			}
			return Optional.of(toNpcRef(chosen, tickNow));
		});
	}

	@Override
	public Optional<GameObjRef> findObject(ObjectQuery q)
	{
		return readOnClient(() ->
		{
			Player self = client.getLocalPlayer();
			if (self == null)
			{
				return Optional.<GameObjRef>empty();
			}
			WorldPoint selfLoc = self.getWorldLocation();
			long tickNow = client.getTickCount();

			List<Candidate<TileObjectFinding>> candidates = scanTilesForObjects(q, selfLoc);
			Candidate<TileObjectFinding> chosen = selector.pick(candidates,
				c -> distance(c.value.loc, selfLoc), q.rotation());
			if (chosen == null)
			{
				return Optional.<GameObjRef>empty();
			}
			return Optional.of(new GameObjRef(
				chosen.value.id, chosen.value.name, chosen.value.loc,
				chosen.value.kind, tickNow));
		});
	}

	@Override
	public Optional<GroundItemRef> findItem(ItemQuery q)
	{
		return readOnClient(() ->
		{
			Player self = client.getLocalPlayer();
			if (self == null)
			{
				return Optional.<GroundItemRef>empty();
			}
			WorldPoint selfLoc = self.getWorldLocation();
			long tickNow = client.getTickCount();

			List<TileItemFinding> candidates = scanTilesForItems(q, selfLoc);
			TileItemFinding chosen = selector.pick(candidates,
				c -> distance(c.loc, selfLoc), q.rotation());
			if (chosen == null)
			{
				return Optional.<GroundItemRef>empty();
			}
			return Optional.of(new GroundItemRef(
				chosen.itemId, chosen.name, chosen.quantity, chosen.loc, tickNow));
		});
	}

	@Override
	public Optional<WidgetRef> findWidget(WidgetQuery q)
	{
		return readOnClient(() ->
		{
			Widget w = client.getWidget(q.widgetId());
			if (w == null)
			{
				return Optional.<WidgetRef>empty();
			}
			Widget target = w;
			if (q.childSlot() != null)
			{
				target = w.getChild(q.childSlot());
				if (target == null)
				{
					return Optional.<WidgetRef>empty();
				}
			}
			// Visibility check per spec §8: Widget.isHidden() in RuneLite
			// already walks the parent chain.
			if (q.requireVisible() && target.isHidden())
			{
				return Optional.<WidgetRef>empty();
			}
			int itemId = target.getItemId();   // -1 when not item-bearing
			long tickNow = client.getTickCount();
			return Optional.of(new WidgetRef(q.widgetId(), q.childSlot(), itemId, tickNow));
		});
	}

	@Override
	public InventoryView inventory()
	{
		return readOnClient(() ->
		{
			ItemContainer inv = client.getItemContainer(InventoryID.INV);
			if (inv == null)
			{
				return new InventoryView(List.of());
			}
			Item[] items = inv.getItems();
			List<InvSlot> slots = new ArrayList<>(items.length);
			for (int i = 0; i < items.length; i++)
			{
				Item it = items[i];
				if (it == null || it.getId() == -1)
				{
					slots.add(new InvSlot(i, InvSlot.EMPTY_ITEM_ID, 0, null));
					continue;
				}
				String name = itemManager == null
					? null
					: itemManager.getItemComposition(it.getId()).getName();
				slots.add(new InvSlot(i, it.getId(), it.getQuantity(), name));
			}
			// InventoryView's compact constructor defensively copies the
			// list, so the returned view is decoupled from the engine.
			return new InventoryView(slots);
		});
	}

	@Override
	public PlayerState player()
	{
		return readOnClient(() ->
		{
			Player p = client.getLocalPlayer();
			if (p == null)
			{
				return new PlayerState(null, -1, -1, 0, 0, 0, true);
			}
			WorldPoint loc = p.getWorldLocation();
			int plane = loc == null ? -1 : loc.getPlane();
			boolean idle = p.getAnimation() == -1 && p.getInteracting() == null;
			return new PlayerState(
				loc,
				plane,
				p.getAnimation(),
				client.getBoostedSkillLevel(Skill.HITPOINTS),
				client.getBoostedSkillLevel(Skill.PRAYER),
				client.getEnergy(),
				idle);
		});
	}

	@Override
	public SessionShape session()
	{
		// Return the injected, runtime-owned instance — never create a
		// new SessionShape per call (spec §3 design principle: ownership
		// is runtime, not script).
		return session;
	}

	// ── ACTIONS ─────────────────────────────────────────────────────
	// click / take / useOn implemented in Phase 1A.3. idle / logout
	// implemented in Phase 1A.4b. walkTo(WorldPoint) implemented in
	// Phase 1A.4c. walkTo(NamedZone) and plan(...) still throw.

	@Override
	public Step walkTo(WorldPoint target)
	{
		return new WalkToWorldPointStep(this, stepEventSink, target, navigator);
	}

	@Override public Step walkTo(NamedZone zone)                  { return notInPhase1A4c("walkTo(NamedZone)"); }

	@Override
	public Step click(NpcRef target, String verb)
	{
		return new ClickNpcStep(this, stepEventSink, target, verb);
	}

	@Override
	public Step click(GameObjRef target, String verb)
	{
		return new ClickGameObjStep(this, stepEventSink, target, verb);
	}

	@Override
	public Step click(WidgetRef target, String verb)
	{
		return new ClickWidgetStep(this, stepEventSink, target, verb);
	}

	@Override
	public Step click(NpcRef t, String v, OutcomeCheck o)
	{
		return new ClickNpcStep(this, stepEventSink, t, v, o);
	}

	@Override
	public Step click(GameObjRef t, String v, OutcomeCheck o)
	{
		return new ClickGameObjStep(this, stepEventSink, t, v, o);
	}

	@Override
	public Step click(WidgetRef t, String v, OutcomeCheck o)
	{
		return new ClickWidgetStep(this, stepEventSink, t, v, o);
	}

	@Override
	public Step take(GroundItemRef item)
	{
		return new TakeGroundItemStep(this, stepEventSink, item);
	}

	@Override
	public Step useOn(InvSlot src, InvSlot tgt)
	{
		return UseOnStep.onInvSlot(this, stepEventSink, src, tgt);
	}

	@Override
	public Step useOn(InvSlot src, GameObjRef tgt)
	{
		return UseOnStep.onGameObj(this, stepEventSink, src, tgt);
	}

	@Override
	public Step useOn(InvSlot src, NpcRef tgt)
	{
		return UseOnStep.onNpc(this, stepEventSink, src, tgt);
	}

	@Override
	public Step useOn(InvSlot src, WidgetRef tgt)
	{
		return UseOnStep.onWidget(this, stepEventSink, src, tgt);
	}

	@Override
	public Step idle(IdlePolicy policy)
	{
		return new IdleStep(this, stepEventSink, policy, accountRng);
	}

	@Override
	public Step logout()
	{
		return new LogoutStep(this, stepEventSink, client, logoutAction);
	}

	// ── COMPOSITION ─────────────────────────────────────────────────

	@Override
	public SequencePlanBuilder plan(String name)
	{
		// Composition scaffold — returns a builder; Step instances added
		// via .then(...) come from action methods. The composition wrapper
		// itself lands in Phase 1A.4e; until then, this throws so plan(...)
		// chains fail loud rather than silently constructing a broken plan.
		return notInPhase1A4cBuilder("plan(\"" + name + "\")");
	}

	private static Step notInPhase1A4c(String surface)
	{
		throw new UnsupportedOperationException(
			"Artemis." + surface + " lands in Phase 1A.4d — Phase 1A.4c implemented walkTo(WorldPoint) only. "
				+ "walkTo(NamedZone) follows in 1A.4d (needs NamedZone tile population); "
				+ "plan(...) follows in 1A.4e.");
	}

	private static SequencePlanBuilder notInPhase1A4cBuilder(String surface)
	{
		throw new UnsupportedOperationException(
			"Artemis." + surface + " composition wires up in Phase 1A.4e.");
	}

	// ── INTERNAL FILTERS / TILE SCANS ───────────────────────────────

	private static boolean matches(NPC npc, NpcQuery q, WorldPoint selfLoc, Player self)
	{
		String name = npc.getName();
		if (q.name() != null && (name == null || !q.name().equalsIgnoreCase(name)))
		{
			return false;
		}
		if (q.id() != null && q.id() != npc.getId())
		{
			return false;
		}
		WorldPoint loc = npc.getWorldLocation();
		if (loc == null)
		{
			return false;
		}
		if (q.plane() != NpcQuery.ANY_PLANE && loc.getPlane() != q.plane())
		{
			return false;
		}
		if (distance(loc, selfLoc) > q.rangeTiles())
		{
			return false;
		}
		if (q.excludeIndices().contains(npc.getIndex()))
		{
			return false;
		}
		if (q.requireUnengaged())
		{
			net.runelite.api.Actor engaged = npc.getInteracting();
			if (engaged != null && engaged != self && engaged instanceof Player)
			{
				return false;
			}
		}
		return true;
	}

	/** Chebyshev distance between two WorldPoints; {@link Integer#MAX_VALUE}
	 *  when planes differ (so plane-mismatch always loses to in-plane
	 *  candidates regardless of policy). */
	private static int distance(WorldPoint a, WorldPoint b)
	{
		if (a == null || b == null)
		{
			return Integer.MAX_VALUE;
		}
		if (a.getPlane() != b.getPlane())
		{
			return Integer.MAX_VALUE;
		}
		return a.distanceTo2D(b);
	}

	/** Internal candidate wrapper that carries the distance-source loc
	 *  alongside the value (so the selector can compute distance without
	 *  re-reading the API). */
	private record Candidate<T>(T value, WorldPoint loc) {}

	/** Internal record for object-scan hits, carrying the per-type
	 *  identification needed to build a {@link GameObjRef}. */
	private record TileObjectFinding(int id, String name, WorldPoint loc, ObjectKind kind) {}

	/** Internal record for ground-item scan hits. */
	private record TileItemFinding(int itemId, String name, int quantity, WorldPoint loc) {}

	private List<Candidate<TileObjectFinding>> scanTilesForObjects(ObjectQuery q, WorldPoint center)
	{
		List<Candidate<TileObjectFinding>> out = new ArrayList<>();
		WorldView wv = client.getTopLevelWorldView();
		Scene scene = wv.getScene();
		Tile[][][] tiles = scene.getTiles();
		int planeFilter = q.plane();

		int baseX = wv.getBaseX();
		int baseY = wv.getBaseY();
		int range = q.rangeTiles();

		for (int p = 0; p < tiles.length; p++)
		{
			if (planeFilter != ObjectQuery.ANY_PLANE && p != planeFilter)
			{
				continue;
			}
			if (tiles[p] == null)
			{
				continue;
			}
			for (int dx = -range; dx <= range; dx++)
			{
				int sx = center.getX() - baseX + dx;
				if (sx < 0 || sx >= tiles[p].length)
				{
					continue;
				}
				if (tiles[p][sx] == null)
				{
					continue;
				}
				for (int dy = -range; dy <= range; dy++)
				{
					int sy = center.getY() - baseY + dy;
					if (sy < 0 || sy >= tiles[p][sx].length)
					{
						continue;
					}
					Tile t = tiles[p][sx][sy];
					if (t == null)
					{
						continue;
					}
					collectObjectsFromTile(t, q, out);
				}
			}
		}
		return out;
	}

	private void collectObjectsFromTile(Tile t, ObjectQuery q,
		List<Candidate<TileObjectFinding>> out)
	{
		for (GameObject go : t.getGameObjects())
		{
			if (go == null)
			{
				continue;
			}
			collectIfObjectMatches(go.getId(), go.getWorldLocation(), ObjectKind.GAME_OBJECT, q, out);
		}
		WallObject wo = t.getWallObject();
		if (wo != null)
		{
			collectIfObjectMatches(wo.getId(), wo.getWorldLocation(), ObjectKind.WALL_OBJECT, q, out);
		}
		DecorativeObject deco = t.getDecorativeObject();
		if (deco != null)
		{
			collectIfObjectMatches(deco.getId(), deco.getWorldLocation(), ObjectKind.DECORATIVE_OBJECT, q, out);
		}
		GroundObject ground = t.getGroundObject();
		if (ground != null)
		{
			collectIfObjectMatches(ground.getId(), ground.getWorldLocation(), ObjectKind.GROUND_OBJECT, q, out);
		}
	}

	private void collectIfObjectMatches(int objectId, WorldPoint loc, ObjectKind kind,
		ObjectQuery q, List<Candidate<TileObjectFinding>> out)
	{
		if (q.id() != null && q.id() != objectId)
		{
			return;
		}
		String name = null;
		if (q.name() != null)
		{
			name = objectName(objectId);
			if (name == null || !q.name().equalsIgnoreCase(name))
			{
				return;
			}
		}
		if (loc == null)
		{
			return;
		}
		// Lazy-fill name when caller only filtered by id.
		if (name == null)
		{
			name = objectName(objectId);
		}
		// name may legitimately be null when getObjectDefinition fails —
		// pass through so callers can distinguish "unknown" (null) from
		// "literally blank name" (""). Surfaces as GameObjRef.name() == null.
		out.add(new Candidate<>(new TileObjectFinding(objectId, name, loc, kind), loc));
	}

	private String objectName(int objectId)
	{
		try
		{
			var def = client.getObjectDefinition(objectId);
			return def == null ? null : def.getName();
		}
		catch (Exception e)
		{
			log.debug("objectName({}) failed: {}", objectId, e.toString());
			return null;
		}
	}

	private List<TileItemFinding> scanTilesForItems(ItemQuery q, WorldPoint center)
	{
		List<TileItemFinding> out = new ArrayList<>();
		WorldView wv = client.getTopLevelWorldView();
		Scene scene = wv.getScene();
		Tile[][][] tiles = scene.getTiles();
		int planeFilter = q.plane();
		int baseX = wv.getBaseX();
		int baseY = wv.getBaseY();
		int range = q.rangeTiles();

		for (int p = 0; p < tiles.length; p++)
		{
			if (planeFilter != ItemQuery.ANY_PLANE && p != planeFilter)
			{
				continue;
			}
			if (tiles[p] == null)
			{
				continue;
			}
			for (int dx = -range; dx <= range; dx++)
			{
				int sx = center.getX() - baseX + dx;
				if (sx < 0 || sx >= tiles[p].length)
				{
					continue;
				}
				if (tiles[p][sx] == null)
				{
					continue;
				}
				for (int dy = -range; dy <= range; dy++)
				{
					int sy = center.getY() - baseY + dy;
					if (sy < 0 || sy >= tiles[p][sx].length)
					{
						continue;
					}
					Tile t = tiles[p][sx][sy];
					if (t == null)
					{
						continue;
					}
					List<TileItem> ground = t.getGroundItems();
					if (ground == null || ground.isEmpty())
					{
						continue;
					}
					WorldPoint tloc = t.getWorldLocation();
					for (TileItem ti : ground)
					{
						if (ti == null || !matchesItem(ti, q))
						{
							continue;
						}
						String name = itemName(ti.getId());
						// name may legitimately be null when ItemManager
						// lookup fails — pass through so callers can
						// distinguish "unknown" from "literally blank".
						out.add(new TileItemFinding(ti.getId(), name,
							ti.getQuantity(), tloc));
					}
				}
			}
		}
		return out;
	}

	private boolean matchesItem(TileItem ti, ItemQuery q)
	{
		if (q.itemId() != null && q.itemId() != ti.getId())
		{
			return false;
		}
		if (ti.getQuantity() < q.minQuantity())
		{
			return false;
		}
		if (q.name() != null)
		{
			String name = itemName(ti.getId());
			if (name == null || !q.name().equalsIgnoreCase(name))
			{
				return false;
			}
		}
		return true;
	}

	private String itemName(int itemId)
	{
		if (itemManager == null)
		{
			return null;
		}
		try
		{
			return itemManager.getItemComposition(itemId).getName();
		}
		catch (Exception e)
		{
			log.debug("itemName({}) failed: {}", itemId, e.toString());
			return null;
		}
	}

	private static NpcRef toNpcRef(NPC npc, long observedTick)
	{
		// name may legitimately be null when NPC has no defined display
		// name — pass through so callers can distinguish "unknown" from
		// "literally blank". Surfaces as NpcRef.name() == null.
		return new NpcRef(
			npc.getIndex(),
			npc.getId(),
			npc.getName(),
			npc.getWorldLocation(),
			npc.getHealthRatio(),
			observedTick);
	}
}
