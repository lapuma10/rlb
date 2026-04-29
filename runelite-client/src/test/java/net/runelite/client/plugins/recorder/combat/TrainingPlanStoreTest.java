package net.runelite.client.plugins.recorder.combat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.runelite.api.Client;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TrainingPlanStoreTest
{
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private TrainingPlanStore store;
    private Client client;

    @Before
    public void setUp() throws IOException
    {
        store = new TrainingPlanStore(tmp.getRoot().toPath().resolve("training-plans"));
        client = mock(Client.class);
    }

    @Test
    public void loadReturnsNullWhenNoFileExists()
    {
        when(client.getUsername()).thenReturn("alice@example.com");
        assertNull(store.load(client));
    }

    @Test
    public void roundTripPreservesEverySetting()
    {
        when(client.getUsername()).thenReturn("alice@example.com");
        TrainingPlanStore.Settings in = new TrainingPlanStore.Settings();
        in.attackEnabled = true;
        in.strengthEnabled = true;
        in.defenceEnabled = false;
        in.attackLevel = 40;
        in.strengthLevel = 50;
        in.defenceLevel = 1;
        in.autoRetaliate = "OFF";
        store.save(client, in);

        TrainingPlanStore.Settings out = store.load(client);
        assertNotNull(out);
        assertTrue(out.attackEnabled);
        assertTrue(out.strengthEnabled);
        assertFalse(out.defenceEnabled);
        assertEquals(40, out.attackLevel);
        assertEquals(50, out.strengthLevel);
        assertEquals(1,  out.defenceLevel);
        assertEquals("OFF", out.autoRetaliate);
    }

    @Test
    public void differentAccountsDoNotCollide()
    {
        when(client.getUsername()).thenReturn("alice@example.com");
        TrainingPlanStore.Settings a = new TrainingPlanStore.Settings();
        a.attackEnabled = true; a.attackLevel = 99;
        store.save(client, a);

        when(client.getUsername()).thenReturn("bob@example.com");
        TrainingPlanStore.Settings b = new TrainingPlanStore.Settings();
        b.strengthEnabled = true; b.strengthLevel = 50;
        store.save(client, b);

        TrainingPlanStore.Settings bobLoaded = store.load(client);
        assertNotNull(bobLoaded);
        assertTrue(bobLoaded.strengthEnabled);
        assertEquals(50, bobLoaded.strengthLevel);
        assertFalse("bob's file should not have alice's attack flag", bobLoaded.attackEnabled);

        when(client.getUsername()).thenReturn("alice@example.com");
        TrainingPlanStore.Settings aliceLoaded = store.load(client);
        assertNotNull(aliceLoaded);
        assertTrue(aliceLoaded.attackEnabled);
        assertEquals(99, aliceLoaded.attackLevel);
    }

    @Test
    public void filenameIsHashedNotPlaintextUsername()
    {
        when(client.getUsername()).thenReturn("alice@example.com");
        store.save(client, new TrainingPlanStore.Settings());
        Path dir = tmp.getRoot().toPath().resolve("training-plans");
        assertTrue(Files.isDirectory(dir));
        try (var stream = Files.list(dir))
        {
            stream.forEach(file -> {
                String name = file.getFileName().toString();
                assertFalse("filename leaked username: " + name,
                    name.contains("alice") || name.contains("example"));
                // SHA-256 hex = 64 chars + ".properties"
                assertEquals(64 + ".properties".length(), name.length());
            });
        }
        catch (IOException ex) { fail(ex.getMessage()); }
    }

    @Test
    public void hashIsDeterministic()
    {
        String a = TrainingPlanStore.hashKey("alice@example.com");
        String b = TrainingPlanStore.hashKey("alice@example.com");
        assertEquals(a, b);
        String c = TrainingPlanStore.hashKey("bob@example.com");
        assertNotEquals(a, c);
    }

    @Test
    public void nullClientUsesDefaultKey()
    {
        TrainingPlanStore.Settings in = new TrainingPlanStore.Settings();
        in.defenceEnabled = true;
        in.defenceLevel = 75;
        store.save(null, in);
        TrainingPlanStore.Settings out = store.load(null);
        assertNotNull(out);
        assertTrue(out.defenceEnabled);
        assertEquals(75, out.defenceLevel);
    }
}
