package net.runelite.client.sequence.artemis;

import java.util.Optional;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.recorder.session.AccountRng;
import net.runelite.client.plugins.recorder.session.SessionShape;
import net.runelite.client.sequence.artemis.query.ObjectQuery;
import net.runelite.client.sequence.artemis.view.GameObjRef;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins {@link ArtemisImpl#findObject} name-resolution semantics —
 * specifically the Phase 1A.2b change that surfaces a null
 * {@link GameObjRef#name()} when the underlying
 * {@code client.getObjectDefinition(id)} returns null (engine couldn't
 * resolve the definition), distinct from a literal blank name.
 *
 * <p>Heavy scene-mock test isolated into its own file so the simpler
 * {@code ArtemisImplReadsTest} stays focused on inventory/player/session.
 */
public class ArtemisImplObjectFindTest
{
	/** Build the minimal scene mock needed for findObject to see one
	 *  GameObject at the player's location. */
	private ArtemisImpl artemisWithObjectAtPlayer(Client client, int objectId)
	{
		ClientThread ct = mock(ClientThread.class);
		when(client.isClientThread()).thenReturn(true);
		when(client.getTickCount()).thenReturn(42);

		Player self = mock(Player.class);
		WorldPoint selfLoc = new WorldPoint(3200, 3200, 0);
		when(client.getLocalPlayer()).thenReturn(self);
		when(self.getWorldLocation()).thenReturn(selfLoc);

		WorldView wv = mock(WorldView.class);
		Scene scene = mock(Scene.class);
		when(client.getTopLevelWorldView()).thenReturn(wv);
		when(wv.getScene()).thenReturn(scene);
		when(wv.getBaseX()).thenReturn(3100);
		when(wv.getBaseY()).thenReturn(3100);

		// 4 planes × 104 × 104 scene; only one tile populated with one
		// GameObject at the player's exact world location.
		Tile[][][] tiles = new Tile[4][104][104];
		Tile myTile = mock(Tile.class);
		GameObject go = mock(GameObject.class);
		when(go.getId()).thenReturn(objectId);
		when(go.getWorldLocation()).thenReturn(selfLoc);
		when(myTile.getGameObjects()).thenReturn(new GameObject[] { go });
		// other tile.getXObject() default to null via mockito
		int sx = selfLoc.getX() - 3100;
		int sy = selfLoc.getY() - 3100;
		tiles[0][sx][sy] = myTile;
		when(scene.getTiles()).thenReturn(tiles);

		java.util.concurrent.atomic.AtomicLong tick = new java.util.concurrent.atomic.AtomicLong(42L);
		SessionShape session = new SessionShape(tick::get, tick::get, 1_000L);
		return new ArtemisImpl(client, ct, new AccountRng(null), session, mock(ItemManager.class), null);
	}

	@Test
	public void findObjectReturnsRefWithNullNameWhenObjectDefinitionMissing()
	{
		Client client = mock(Client.class);
		int objectId = 12345;
		// The engine couldn't resolve a definition for this id.
		when(client.getObjectDefinition(objectId)).thenReturn(null);

		ArtemisImpl artemis = artemisWithObjectAtPlayer(client, objectId);

		Optional<GameObjRef> ref = artemis.findObject(ObjectQuery.byId(objectId));
		assertTrue("findObject should still resolve the GameObject even when its name is unknown",
			ref.isPresent());
		assertNull("name() must be null when getObjectDefinition returns null — distinct from \"\"",
			ref.get().name());
		assertEquals(objectId, ref.get().id());
		assertEquals(42L, ref.get().observedTick());
	}

	@Test
	public void findObjectPopulatesNameWhenObjectDefinitionResolves()
	{
		Client client = mock(Client.class);
		int objectId = 25808;
		ObjectComposition def = mock(ObjectComposition.class);
		when(def.getName()).thenReturn("Bank booth");
		when(client.getObjectDefinition(objectId)).thenReturn(def);

		ArtemisImpl artemis = artemisWithObjectAtPlayer(client, objectId);

		Optional<GameObjRef> ref = artemis.findObject(ObjectQuery.byId(objectId));
		assertTrue(ref.isPresent());
		assertNotNull(ref.get().name());
		assertEquals("Bank booth", ref.get().name());
	}
}
