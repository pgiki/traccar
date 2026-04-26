package org.traccar.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SocketFilterConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static Map<String, String[]> params(String... kv) {
        Map<String, String[]> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], new String[] {kv[i + 1]});
        }
        return m;
    }

    @Test
    public void testBboxFromQueryParams() throws Exception {
        SocketFilterConfig f = SocketFilterConfig.fromRequestParameters(
                params(
                        "minLat", "10",
                        "maxLat", "20",
                        "minLon", "30",
                        "maxLon", "40"),
                null,
                0);

        assertTrue(f.wantsAnyFilter());
        Position in = position(1, true, 15, 35, "gps103");
        Position out = position(1, true, 5, 35, "gps103");
        Position invalid = position(1, false, 15, 35, "gps103");

        assertTrue(f.acceptsPosition(in));
        assertFalse(f.acceptsPosition(out));
        assertFalse(f.acceptsPosition(invalid));
    }

    @Test
    public void testProtocolFilter() throws Exception {
        SocketFilterConfig f = SocketFilterConfig.fromRequestParameters(
                params("protocol", "omniebike"),
                null,
                0);

        assertTrue(f.acceptsPosition(position(1, true, 0, 0, "omniebike")));
        assertFalse(f.acceptsPosition(position(1, true, 0, 0, "gps103")));
    }

    @Test
    public void testEventTypeFilter() throws Exception {
        SocketFilterConfig f = SocketFilterConfig.fromRequestParameters(
                params("eventType", "alarm"),
                null,
                0);

        Event alarm = new Event(Event.TYPE_ALARM, 1L);
        Event online = new Event(Event.TYPE_DEVICE_ONLINE, 1L);

        assertTrue(f.acceptsEvent(alarm));
        assertFalse(f.acceptsEvent(online));
    }

    @Test
    public void testFilterPositionsList() throws Exception {
        SocketFilterConfig f = SocketFilterConfig.fromRequestParameters(
                params(
                        "minLat", "0",
                        "maxLat", "10",
                        "minLon", "0",
                        "maxLon", "10"),
                null,
                0);

        List<Position> list = List.of(
                position(1, true, 5, 5, "p"),
                position(2, true, 50, 50, "p"));
        assertEquals(1, f.filterPositions(list).size());
    }

    @Test
    public void testMergeJsonProtocol() throws Exception {
        SocketFilterConfig base = SocketFilterConfig.empty();
        String json = "{\"positions\":{\"protocol\":\"teltonika\"}}";
        SocketFilterConfig merged = base.mergeWithJson(objectMapper.readTree(json), null, 0);
        assertTrue(merged.acceptsPosition(position(1, true, 0, 0, "teltonika")));
        assertFalse(merged.acceptsPosition(position(1, true, 0, 0, "omniebike")));
    }

    @Test
    public void testFromRequestParameterListMatchesStringArray() throws Exception {
        Map<String, List<String>> listMap = Map.of(
                "minLat", List.of("1"),
                "maxLat", List.of("2"),
                "minLon", List.of("3"),
                "maxLon", List.of("4"));
        SocketFilterConfig fromList = SocketFilterConfig.fromRequestParameterList(listMap, null, 0);
        SocketFilterConfig fromArray = SocketFilterConfig.fromRequestParameters(
                params("minLat", "1", "maxLat", "2", "minLon", "3", "maxLon", "4"), null, 0);
        assertTrue(fromList.wantsAnyFilter() && fromArray.wantsAnyFilter());
        assertEquals(fromList.acceptsPosition(position(1, true, 1.5, 3.5, "p")),
                fromArray.acceptsPosition(position(1, true, 1.5, 3.5, "p")));
    }

    @Test
    public void testEmptyConfigPassesAll() throws Exception {
        SocketFilterConfig f = SocketFilterConfig.empty();
        assertFalse(f.wantsAnyFilter());
        assertTrue(f.acceptsPosition(position(1, true, 0, 0, "any")));
        assertTrue(f.acceptsPosition(position(1, false, 0, 0, "any")));
        assertTrue(f.acceptsEvent(new Event(Event.TYPE_DEVICE_ONLINE, 1L)));
        Device d = new Device();
        d.setId(99);
        assertTrue(f.acceptsDevice(d));
        List<Position> list = List.of(position(1, true, 1, 1, "p"), position(2, true, 9, 9, "p"));
        assertSame(list, f.filterPositions(list));
    }

    @Test
    public void testDeviceIdFilterRespectsPermission() throws Exception {
        Storage storage = mock(Storage.class);
        Device device7 = new Device();
        device7.setId(7);
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(device7);

        SocketFilterConfig f = SocketFilterConfig.fromRequestParameters(
                params("deviceId", "7"),
                storage,
                1L);

        assertTrue(f.wantsAnyFilter());
        assertTrue(f.acceptsPosition(position(7, true, 0, 0, "p")));
        assertFalse(f.acceptsPosition(position(8, true, 0, 0, "p")));
        assertTrue(f.acceptsDevice(device7));
    }

    @Test
    public void testBboxJsonPartialMergeKeepsPreviousCorners() throws Exception {
        String init = "{\"positions\":{\"minLat\":0,\"maxLat\":10,\"minLon\":0,\"maxLon\":10}}";
        SocketFilterConfig a = SocketFilterConfig.empty().mergeWithJson(
                objectMapper.readTree(init), null, 0);
        String pan = "{\"positions\":{\"minLat\":0,\"maxLat\":5}}";
        SocketFilterConfig b = a.mergeWithJson(objectMapper.readTree(pan), null, 0);
        assertTrue(b.acceptsPosition(position(1, true, 2, 2, "p")));
        assertFalse(b.acceptsPosition(position(1, true, 7, 2, "p")));
    }

    private static Position position(long deviceId, boolean valid, double lat, double lon, String protocol) {
        Position p = new Position();
        p.setDeviceId(deviceId);
        p.setValid(valid);
        p.setLatitude(lat);
        p.setLongitude(lon);
        p.setProtocol(protocol);
        return p;
    }
}
