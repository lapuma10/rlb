package net.runelite.client.plugins.recorder.annotator;

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;

/**
 * Captures area-selection input on the canvas while active. Translates
 * pixel coords to {@link WorldPoint}s via the scene tile lookup, then
 * applies one of three operations on each release / click:
 *
 * <ul>
 *   <li>Drag (no modifier): the rectangle of tiles between press and
 *       release tiles is added to the working set.</li>
 *   <li>Shift+Drag: the rectangle is subtracted.</li>
 *   <li>Single click (press and release within {@link #CLICK_THRESHOLD_PX}):
 *       the tile under the click is toggled.</li>
 * </ul>
 *
 * <p>Pure-function helpers ({@link #tilesInRect}, {@link #applyAdd},
 * {@link #applySubtract}, {@link #applyToggle}) are package-public for
 * unit testing without a live client.
 */
@Slf4j
public final class AreaSelector
{
	public interface Listener
	{
		/** Working set changed (drag finished, click toggled, etc.).
		 *  Called on the EDT — safe to update Swing state. */
		void onSetChanged(Set<WorldPoint> tiles);

		/** User pressed Enter or clicked Done in the HUD. */
		void onCommit(Set<WorldPoint> tiles);

		/** User pressed Esc or clicked Cancel in the HUD. */
		void onCancel();

		/** Drag in progress — for live preview. {@code subtract} indicates
		 *  whether Shift was held at press time. */
		void onDragPreview(@Nullable WorldPoint pressTile, @Nullable WorldPoint dragTile,
		                   boolean subtract);
	}

	/** Pixel movement threshold below which a press+release is treated as a
	 *  click (toggle one tile) rather than a drag. */
	public static final int CLICK_THRESHOLD_PX = 4;

	private final Client client;
	private final ClientThread clientThread;
	private final MouseManager mouseManager;

	private final AtomicReference<Set<WorldPoint>> working = new AtomicReference<>(Set.of());
	@Nullable private volatile Listener listener;
	@Nullable private volatile MouseAdapter activeListener;

	@Nullable private volatile WorldPoint pressTile;
	private volatile int pressX, pressY;
	private volatile boolean pressShift;
	@Nullable private volatile WorldPoint hoverTile;

	public AreaSelector(Client client, ClientThread clientThread, MouseManager mouseManager)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.mouseManager = mouseManager;
	}

	public boolean isActive() { return activeListener != null; }

	public Set<WorldPoint> currentSet() { return working.get(); }

	/** Start a selection session pre-loaded with {@code initialTiles} (pass
	 *  {@code Set.of()} for "new"). Subsequent input mutates the working
	 *  set; the caller must invoke {@link #commit} or {@link #cancel} to
	 *  end the session. Calling start while already active throws. */
	public void start(Set<WorldPoint> initialTiles, Listener l)
	{
		if (activeListener != null)
			throw new IllegalStateException("AreaSelector already active");
		this.listener = l;
		working.set(Set.copyOf(initialTiles));
		pressTile = null;
		hoverTile = null;
		MouseAdapter adapter = new MouseAdapter()
		{
			// Consume press / drag / release / click so the game engine never
			// sees them (no walk-here, no menu open). mouseMoved stays
			// un-consumed so hover effects in the game keep working.
			@Override public MouseEvent mousePressed(MouseEvent e)  { onPress(e);   e.consume(); return e; }
			@Override public MouseEvent mouseDragged(MouseEvent e)  { onDrag(e);    e.consume(); return e; }
			@Override public MouseEvent mouseReleased(MouseEvent e) { onRelease(e); e.consume(); return e; }
			@Override public MouseEvent mouseClicked(MouseEvent e)  { e.consume(); return e; }
			@Override public MouseEvent mouseMoved(MouseEvent e)    { onMove(e); return e; }
		};
		mouseManager.registerMouseListener(adapter);
		activeListener = adapter;
	}

	/** Fires {@link Listener#onCommit} with the working set, then resets internal
	 *  state. The Set instance passed to {@code onCommit} remains valid for the
	 *  caller; {@link #currentSet()} returns an empty set after this method returns. */
	public void commit()
	{
		if (listener != null) listener.onCommit(working.get());
		cleanup();
	}

	/** Fires {@link Listener#onCancel}, then resets internal state. After this
	 *  method returns, {@link #currentSet()} returns an empty set. */
	public void cancel()
	{
		if (listener != null) listener.onCancel();
		cleanup();
	}

	private void cleanup()
	{
		if (activeListener != null) mouseManager.unregisterMouseListener(activeListener);
		activeListener = null;
		listener = null;
		pressTile = null;
		hoverTile = null;
		working.set(Set.of());
	}

	private void onPress(MouseEvent e)
	{
		pressX = e.getX();
		pressY = e.getY();
		pressShift = (e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0;
		pressTile = tileUnder(e.getX(), e.getY());
		hoverTile = pressTile;
		if (listener != null) listener.onDragPreview(pressTile, hoverTile, pressShift);
	}

	private void onDrag(MouseEvent e)
	{
		WorldPoint t = tileUnder(e.getX(), e.getY());
		if (t == null) return;
		hoverTile = t;
		if (listener != null) listener.onDragPreview(pressTile, hoverTile, pressShift);
	}

	private void onMove(MouseEvent e)
	{
		// No effect — preview only updates while a drag is in progress.
	}

	private void onRelease(MouseEvent e)
	{
		if (pressTile == null) return;
		int dx = Math.abs(e.getX() - pressX);
		int dy = Math.abs(e.getY() - pressY);
		WorldPoint releaseTile = tileUnder(e.getX(), e.getY());
		Set<WorldPoint> next;
		if (dx <= CLICK_THRESHOLD_PX && dy <= CLICK_THRESHOLD_PX && releaseTile != null)
		{
			next = applyToggle(working.get(), releaseTile);
		}
		else if (releaseTile == null)
		{
			// Released off-canvas — abandon this drag, leave the set unchanged.
			pressTile = null;
			hoverTile = null;
			if (listener != null) listener.onDragPreview(null, null, false);
			return;
		}
		else
		{
			Set<WorldPoint> rect = tilesInRect(pressTile, releaseTile);
			next = pressShift ? applySubtract(working.get(), rect)
			                  : applyAdd(working.get(), rect);
		}
		working.set(Set.copyOf(next));
		pressTile = null;
		hoverTile = null;
		if (listener != null)
		{
			listener.onDragPreview(null, null, false);
			listener.onSetChanged(working.get());
		}
	}

	@Nullable
	private WorldPoint tileUnder(int canvasX, int canvasY)
	{
		// Use the engine-resolved hover tile (same API TileMarker uses).
		// Iterating the scene tile array from the AWT thread triggers lazy
		// classloading of injected client classes (NoClassDefFoundError: kg)
		// — let the engine do the canvas→tile mapping for us. Args are
		// ignored because getSelectedSceneTile is keyed off the live cursor
		// position, which AWT keeps in sync with the press/drag/release
		// events that drive this method.
		Tile sel = client.getSelectedSceneTile();
		return sel == null ? null : sel.getWorldLocation();
	}

	/** Pure: every tile in the rectangle whose corners are {@code a} and {@code b}.
	 *  The rectangle is normalised (corners are reordered if necessary). The
	 *  resulting set inherits {@code b}'s plane (release determines plane). */
	static Set<WorldPoint> tilesInRect(WorldPoint a, WorldPoint b)
	{
		int minX = Math.min(a.getX(), b.getX());
		int maxX = Math.max(a.getX(), b.getX());
		int minY = Math.min(a.getY(), b.getY());
		int maxY = Math.max(a.getY(), b.getY());
		int plane = b.getPlane();
		Set<WorldPoint> out = new HashSet<>((maxX - minX + 1) * (maxY - minY + 1));
		for (int x = minX; x <= maxX; x++)
			for (int y = minY; y <= maxY; y++)
				out.add(new WorldPoint(x, y, plane));
		return out;
	}

	/** Pure: union. */
	static Set<WorldPoint> applyAdd(Set<WorldPoint> base, Set<WorldPoint> add)
	{
		Set<WorldPoint> out = new HashSet<>(base);
		out.addAll(add);
		return out;
	}

	/** Pure: difference. */
	static Set<WorldPoint> applySubtract(Set<WorldPoint> base, Set<WorldPoint> sub)
	{
		Set<WorldPoint> out = new HashSet<>(base);
		out.removeAll(sub);
		return out;
	}

	/** Pure: toggle one tile. */
	static Set<WorldPoint> applyToggle(Set<WorldPoint> base, WorldPoint t)
	{
		Set<WorldPoint> out = new HashSet<>(base);
		if (!out.add(t)) out.remove(t);
		return out;
	}
}
