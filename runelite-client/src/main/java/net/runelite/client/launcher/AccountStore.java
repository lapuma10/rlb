package net.runelite.client.launcher;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class AccountStore
{
    public record AccountEntry(String id, String name, boolean jagex) {}

    static final Path ACCOUNTS_DIR =
        net.runelite.client.RuneLite.RUNELITE_DIR.toPath().resolve("rlb").resolve("accounts");
    private static final Path ACCOUNTS_JSON = ACCOUNTS_DIR.resolve("accounts.json");
    static final Path ACTIVE_CREDS =
        net.runelite.client.RuneLite.RUNELITE_DIR.toPath().resolve("credentials.properties");

    private AccountStore() {}

    public static List<AccountEntry> load()
    {
        if (!Files.exists(ACCOUNTS_JSON)) return new ArrayList<>();
        try
        {
            JsonObject obj = new Gson().fromJson(Files.readString(ACCOUNTS_JSON), JsonObject.class);
            if (obj == null || !obj.has("accounts")) return new ArrayList<>();
            List<AccountEntry> out = new ArrayList<>();
            for (var e : obj.getAsJsonArray("accounts"))
            {
                JsonObject a = e.getAsJsonObject();
                boolean jagex = !a.has("jagex") || a.get("jagex").getAsBoolean();
                out.add(new AccountEntry(a.get("id").getAsString(), a.get("name").getAsString(), jagex));
            }
            return out;
        }
        catch (Exception e) { return new ArrayList<>(); }
    }

    public static void save(List<AccountEntry> accounts)
    {
        try
        {
            Files.createDirectories(ACCOUNTS_DIR);
            JsonArray arr = new JsonArray();
            for (AccountEntry a : accounts)
            {
                JsonObject e = new JsonObject();
                e.addProperty("id", a.id());
                e.addProperty("name", a.name());
                e.addProperty("jagex", a.jagex());
                arr.add(e);
            }
            JsonObject obj = new JsonObject();
            obj.add("accounts", arr);
            Path tmp = ACCOUNTS_JSON.resolveSibling("accounts.json.tmp");
            Files.writeString(tmp, new Gson().toJson(obj));
            Files.move(tmp, ACCOUNTS_JSON, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException ignored) {}
    }

    public static Path credentialsPath(String id)
    {
        return ACCOUNTS_DIR.resolve(id).resolve("credentials.properties");
    }

    public static boolean hasCredentials(String id)
    {
        return Files.exists(credentialsPath(id));
    }

    public static void saveCredentials(String id, Path source) throws IOException
    {
        Path dest = credentialsPath(id);
        Files.createDirectories(dest.getParent());
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    public static void activateCredentials(String id) throws IOException
    {
        Files.copy(credentialsPath(id), ACTIVE_CREDS, StandardCopyOption.REPLACE_EXISTING);
    }
}
