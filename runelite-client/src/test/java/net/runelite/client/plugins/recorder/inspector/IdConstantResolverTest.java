package net.runelite.client.plugins.recorder.inspector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;

public class IdConstantResolverTest {

    private final IdConstantResolver resolver = new IdConstantResolver();

    @Test
    public void widget_resolvesNamedConstant() {
        assertEquals("Bankmain.UNIVERSE", resolver.widget(InterfaceID.Bankmain.UNIVERSE));
        assertEquals("Chatbox.MES_LAYER", resolver.widget(InterfaceID.Chatbox.MES_LAYER));
    }

    @Test
    public void widget_unknownReturnsHexFallback() {
        // 0xFEED_BEEF is a synthetic id no one would declare.
        String s = resolver.widget(0xFEED_BEEF);
        assertTrue("expected hex fallback, got: " + s, s.startsWith("0x"));
    }

    @Test
    public void varbit_resolvesNamedConstant() {
        // BANK_WITHDRAWNOTES is the canonical "note mode" toggle.
        assertEquals("BANK_WITHDRAWNOTES",
            resolver.varbit(VarbitID.BANK_WITHDRAWNOTES));
    }

    @Test
    public void varbit_unknownReturnsFallback() {
        assertEquals("varbit#9999999", resolver.varbit(9999999));
    }

    @Test
    public void varc_resolvesBothIntAndStrIds() {
        // VarClientID is a single id-space — int (MESLAYERMODE = 5) and
        // str (MESLAYERINPUT = 359) ids share it. Resolver names by id;
        // the caller (ClickInspector) tags the line with varcInt vs
        // varcStr based on which event fired.
        assertEquals("MESLAYERMODE",
            resolver.varc(VarClientID.MESLAYERMODE));
        assertEquals("MESLAYERINPUT",
            resolver.varc(VarClientID.MESLAYERINPUT));
    }

    @Test
    public void varc_unknownReturnsFallback() {
        assertEquals("varc#9999999", resolver.varc(9999999));
    }
}
