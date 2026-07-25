package org.traccar.handler.events;

import org.junit.jupiter.api.Test;
import org.traccar.BaseTest;
import org.traccar.model.Event;
import org.traccar.model.Position;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CardEventHandlerTest extends BaseTest {

    @Test
    public void testRepeatedCardReadsCreateSeparateEvents() {
        CardEventHandler handler = new CardEventHandler();

        Position first = new Position();
        first.set(Position.KEY_CARD, "000000001A2B3C4D");
        first.set(Position.KEY_DRIVER_UNIQUE_ID, "000000001A2B3C4D");
        first.set("rfidRequest", 0);
        first.set("rfidCardType", 0);

        Position second = new Position();
        second.set(Position.KEY_CARD, "000000001A2B3C4D");
        second.set(Position.KEY_DRIVER_UNIQUE_ID, "000000001A2B3C4D");
        second.set("rfidRequest", 1);
        second.set("rfidCardType", 0);

        List<Event> events = new ArrayList<>();
        handler.analyzePosition(first, events::add);
        handler.analyzePosition(second, events::add);

        assertEquals(2, events.size());
        assertEquals(Event.TYPE_CARD_READ, events.get(0).getType());
        assertEquals(Event.TYPE_CARD_READ, events.get(1).getType());
        assertEquals("000000001A2B3C4D", events.get(0).getString(Position.KEY_CARD));
        assertEquals("000000001A2B3C4D", events.get(1).getString(Position.KEY_CARD));
        assertEquals("000000001A2B3C4D", events.get(0).getString(Position.KEY_DRIVER_UNIQUE_ID));
        assertEquals("000000001A2B3C4D", events.get(1).getString(Position.KEY_DRIVER_UNIQUE_ID));
        assertEquals(0, events.get(0).getInteger("rfidRequest"));
        assertEquals(1, events.get(1).getInteger("rfidRequest"));
        assertEquals(0, events.get(0).getInteger("rfidCardType"));
    }

    @Test
    public void testMissingCardDoesNotCreateEvent() {
        CardEventHandler handler = new CardEventHandler();
        Position position = new Position();
        position.set("rfidRequest", 0);

        List<Event> events = new ArrayList<>();
        handler.analyzePosition(position, events::add);

        assertTrue(events.isEmpty());
    }

}
