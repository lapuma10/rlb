package net.runelite.client.plugins.recorder.trail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/** Loads and stores {@link Trail}s under a directory (default
 *  {@code ~/.runelite/recorder/trails/}). Each trail lives in its own
 *  {@code <name>.json} file; {@link #save(Trail)} overwrites silently
 *  (the panel UI surfaces a confirm-overwrite dialog before calling). */
@Slf4j
public final class TrailRegistry
{
    private final Path dir;
    private final Map<String, Trail> byName = new LinkedHashMap<>();

    public TrailRegistry(Path dir) { this.dir = dir; }

    public Path directory() { return dir; }

    /** Re-scan the directory, replacing the in-memory map. */
    public synchronized void load()
    {
        byName.clear();
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir))
        {
            stream
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .sorted()
                .forEach(p -> {
                    try
                    {
                        Trail t = TrailIO.readFile(p);
                        byName.put(t.name(), t);
                    }
                    catch (Throwable th)
                    {
                        log.warn("trail: failed to load {}", p, th);
                    }
                });
        }
        catch (IOException e) { log.warn("trail: list dir {} failed", dir, e); }
    }

    /** Persist a trail; subsequent {@link #load()} calls will see it. */
    public synchronized void save(Trail trail) throws IOException
    {
        Files.createDirectories(dir);
        TrailIO.writeFile(trail, dir.resolve(trail.name() + ".json"));
        byName.put(trail.name(), trail);
    }

    public synchronized Trail byName(String name) { return byName.get(name); }

    public synchronized Collection<Trail> all()
    {
        return Collections.unmodifiableCollection(new java.util.ArrayList<>(byName.values()));
    }
}
