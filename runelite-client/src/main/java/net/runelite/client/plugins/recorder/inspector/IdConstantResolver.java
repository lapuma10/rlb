package net.runelite.client.plugins.recorder.inspector;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;

/**
 * Maps packed widget ids / varbit ids / varc ids to their declared
 * constant name. Built once via reflection over the runelite-api
 * {@code gameval} classes.
 *
 * <ul>
 *   <li>Widgets: scans every public nested class of {@link InterfaceID}
 *       for {@code public static final int} fields; the resolved name is
 *       {@code "<NestedClass>.<FIELD>"} (e.g. {@code "Bankmain.NOTE"}).</li>
 *   <li>Varbits: flat scan of {@link VarbitID}.</li>
 *   <li>Varc int + varc str: flat scan of {@link VarClientID} (single
 *       id-space — the int and str ids are declared together).</li>
 * </ul>
 *
 * <p>Unknown ids fall back to a hex / decimal token that's still useful
 * as a grep target ({@code "0xGGGG_CCCC"} for widgets, {@code "varbit#N"}
 * / {@code "varc#N"} otherwise).
 */
@Slf4j
public final class IdConstantResolver {

    private final Map<Integer, String> widgets;
    private final Map<Integer, String> varbits;
    private final Map<Integer, String> varcs;

    public IdConstantResolver() {
        this.widgets = scanInterfaceIds();
        this.varbits = scanIntConstants(VarbitID.class);
        this.varcs   = scanIntConstants(VarClientID.class);
        log.debug("IdConstantResolver loaded: widgets={} varbits={} varcs={}",
            widgets.size(), varbits.size(), varcs.size());
    }

    /** Returns {@code "Group.NAME"} or hex fallback {@code "0xGGGG_CCCC"}. */
    public String widget(int packedId) {
        String name = widgets.get(packedId);
        if (name != null) return name;
        return String.format("0x%04x_%04x",
            (packedId >>> 16) & 0xffff, packedId & 0xffff);
    }

    /** Returns the named varbit / varplayer constant or {@code "varbit#N"}. */
    public String varbit(int id) {
        String name = varbits.get(id);
        return name != null ? name : "varbit#" + id;
    }

    /** Returns the named varc constant (covers both int and str varcs)
     *  or {@code "varc#N"} fallback. */
    public String varc(int id) {
        String name = varcs.get(id);
        return name != null ? name : "varc#" + id;
    }

    private static Map<Integer, String> scanInterfaceIds() {
        Map<Integer, String> m = new HashMap<>();
        for (Class<?> nested : InterfaceID.class.getDeclaredClasses()) {
            if (!Modifier.isPublic(nested.getModifiers())) continue;
            String groupName = nested.getSimpleName();
            for (Field f : nested.getDeclaredFields()) {
                if (!isStaticFinalInt(f)) continue;
                try {
                    int id = f.getInt(null);
                    // First declaration wins on collision.
                    m.putIfAbsent(id, groupName + "." + f.getName());
                } catch (IllegalAccessException ignored) {
                    // skip — generated gameval files are public anyway
                }
            }
        }
        return m;
    }

    private static Map<Integer, String> scanIntConstants(Class<?> clz) {
        Map<Integer, String> m = new HashMap<>();
        for (Field f : clz.getDeclaredFields()) {
            if (!isStaticFinalInt(f)) continue;
            try {
                int id = f.getInt(null);
                m.putIfAbsent(id, f.getName());
            } catch (IllegalAccessException ignored) {
                // skip
            }
        }
        return m;
    }

    private static boolean isStaticFinalInt(Field f) {
        int mods = f.getModifiers();
        return f.getType() == int.class
            && Modifier.isStatic(mods)
            && Modifier.isFinal(mods);
    }
}
