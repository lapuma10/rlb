package net.runelite.client.sequence.activities.ge;

import java.awt.Rectangle;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import org.junit.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class GeInteractionSearchResultTest {

    @SuppressWarnings("unchecked")
    private static void wireRunOnClient(HumanizedInputDispatcher dispatcher) throws Exception {
        doAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(0)).get())
            .when(dispatcher).runOnClient(any());
    }

    @Test
    public void waitsForOnScreenSearchResultBoundsBeforeClicking() throws Exception {
        HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
        Client client = mock(Client.class);
        wireRunOnClient(dispatcher);

        when(dispatcher.typeChatboxOnWorker(anyString(), anyLong(), anyLong(), anyLong(), eq(false)))
            .thenReturn(true);
        doNothing().when(dispatcher).boundsClickOnWorker(any(), any());

        Rectangle offscreen = new Rectangle(-1, -1, 161, 32);
        Rectangle onscreen = new Rectangle(11, 691, 161, 32);

        Widget row = mock(Widget.class);
        when(row.isHidden()).thenReturn(false);
        when(row.getName()).thenReturn("Jug of water");
        when(row.getText()).thenReturn("");
        when(row.getActions()).thenReturn(new String[]{"Select"});
        when(row.getBounds()).thenReturn(offscreen, onscreen, onscreen);

        Widget results = mock(Widget.class);
        when(results.isHidden()).thenReturn(false);
        when(results.getDynamicChildren()).thenReturn(new Widget[]{row});

        when(client.getCanvasWidth()).thenReturn(1000);
        when(client.getCanvasHeight()).thenReturn(800);
        when(client.getVarcIntValue(anyInt())).thenReturn(0);
        when(client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS)).thenReturn(results);
        when(client.getWidget(InterfaceID.GeOffers.SETUP)).thenReturn(null);

        GeInteraction ge = new GeInteraction(client, null, dispatcher);
        ge.runPickSearchResult(1937, "Jug of water");

        verify(dispatcher, times(1)).boundsClickOnWorker(onscreen, null);
    }
}
