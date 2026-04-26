/*
 * Copyright 2015 - 2025 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.traccar.helper.model.DeviceUtil;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.model.User;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Per-connection WebSocket stream filters. Immutable; replace on update from JSON.
 */
public final class SocketFilterConfig {

    private final double minLat;
    private final double maxLat;
    private final double minLon;
    private final double maxLon;
    private final boolean bboxSet;

    private final Set<Long> deviceIds;
    private final boolean deviceFilterSet;

    private final String protocol;
    private final boolean protocolSet;

    private final String eventType;
    private final boolean eventTypeSet;

    private SocketFilterConfig(Builder b) {
        this.minLat = b.minLat;
        this.maxLat = b.maxLat;
        this.minLon = b.minLon;
        this.maxLon = b.maxLon;
        this.bboxSet = b.bboxSet;
        this.deviceIds = b.deviceIds != null ? Set.copyOf(b.deviceIds) : Set.of();
        this.deviceFilterSet = b.deviceFilterSet;
        this.protocol = b.protocol;
        this.protocolSet = b.protocolSet;
        this.eventType = b.eventType;
        this.eventTypeSet = b.eventTypeSet;
    }

    public static SocketFilterConfig empty() {
        return new Builder().build();
    }

    public static SocketFilterConfig fromRequestParameters(
            Map<String, String[]> params, Storage storage, long userId) throws StorageException {
        return new Builder().fromParams(params, storage, userId).build();
    }

    public static SocketFilterConfig fromRequestParameterList(
            Map<String, ? extends List<String>> params,
            Storage storage,
            long userId) throws StorageException {
        if (params == null || params.isEmpty()) {
            return fromRequestParameters(Map.of(), storage, userId);
        }
        Map<String, String[]> arrayMap = new HashMap<>();
        for (var e : params.entrySet()) {
            var v = e.getValue();
            arrayMap.put(e.getKey(), v != null && !v.isEmpty() ? v.toArray(new String[0]) : new String[0]);
        }
        return fromRequestParameters(arrayMap, storage, userId);
    }

    public SocketFilterConfig mergeWithJson(
            JsonNode root, Storage storage, long userId) throws StorageException {
        if (root == null || root.isNull() || !root.isObject()) {
            return this;
        }
        Builder b = toBuilder();
        if (root.has("positions") && !root.get("positions").isNull() && root.get("positions").isObject()) {
            b.applyPositionJson(this, root.get("positions"), storage, userId);
        }
        if (root.has("events") && !root.get("events").isNull() && root.get("events").isObject()) {
            b.applyEventJson(this, root.get("events"));
        }
        return b.build();
    }

    private Builder toBuilder() {
        return new Builder(this);
    }

    public boolean wantsAnyFilter() {
        return bboxSet || deviceFilterSet || protocolSet || eventTypeSet;
    }

    public boolean acceptsPosition(Position position) {
        if (position == null) {
            return false;
        }
        if (deviceFilterSet && !deviceIds.contains(position.getDeviceId())) {
            return false;
        }
        if (protocolSet) {
            if (position.getProtocol() == null || !position.getProtocol().equals(protocol)) {
                return false;
            }
        }
        if (bboxSet) {
            if (!position.getValid()) {
                return false;
            }
            double lat = position.getLatitude();
            double lon = position.getLongitude();
            return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
        }
        return true;
    }

    public boolean acceptsDevice(Device device) {
        if (device == null) {
            return false;
        }
        if (deviceFilterSet) {
            return deviceIds.contains(device.getId());
        }
        return true;
    }

    public boolean acceptsEvent(Event event) {
        if (event == null) {
            return false;
        }
        if (deviceFilterSet && !deviceIds.contains(event.getDeviceId())) {
            return false;
        }
        if (eventTypeSet) {
            return event.getType() != null && event.getType().equals(eventType);
        }
        return true;
    }

    public Collection<Position> filterPositions(Collection<Position> positions) {
        if (positions == null || positions.isEmpty() || !wantsAnyFilter()) {
            return positions;
        }
        return positions.stream().filter(this::acceptsPosition).toList();
    }

    public static final class Builder {

        private double minLat;
        private double maxLat;
        private double minLon;
        private double maxLon;
        private boolean bboxSet;
        private Set<Long> deviceIds;
        private boolean deviceFilterSet;
        private String protocol;
        private boolean protocolSet;
        private String eventType;
        private boolean eventTypeSet;

        public Builder() {
        }

        private Builder(SocketFilterConfig c) {
            this.minLat = c.minLat;
            this.maxLat = c.maxLat;
            this.minLon = c.minLon;
            this.maxLon = c.maxLon;
            this.bboxSet = c.bboxSet;
            this.deviceIds = c.deviceFilterSet ? new HashSet<>(c.deviceIds) : new HashSet<>();
            this.deviceFilterSet = c.deviceFilterSet;
            this.protocol = c.protocol;
            this.protocolSet = c.protocolSet;
            this.eventType = c.eventType;
            this.eventTypeSet = c.eventTypeSet;
        }

        public SocketFilterConfig build() {
            if (deviceFilterSet && (deviceIds == null || deviceIds.isEmpty())) {
                deviceFilterSet = false;
            }
            if (eventTypeSet && (eventType == null || eventType.isEmpty())) {
                eventTypeSet = false;
            }
            if (protocolSet && (protocol == null || protocol.isEmpty())) {
                protocolSet = false;
            }
            if (deviceFilterSet) {
                deviceIds = new HashSet<>(deviceIds);
            } else {
                deviceIds = Set.of();
            }
            return new SocketFilterConfig(this);
        }

        private Builder fromParams(Map<String, String[]> params, Storage storage, long userId)
                throws StorageException {
            if (params == null) {
                return this;
            }
            String minLatS = first(params, "minLat", "minLatitude");
            String maxLatS = first(params, "maxLat", "maxLatitude");
            String minLonS = first(params, "minLon", "minLongitude");
            String maxLonS = first(params, "maxLon", "maxLongitude");
            if (minLatS != null && maxLatS != null && minLonS != null && maxLonS != null) {
                try {
                    this.minLat = Double.parseDouble(minLatS);
                    this.maxLat = Double.parseDouble(maxLatS);
                    this.minLon = Double.parseDouble(minLonS);
                    this.maxLon = Double.parseDouble(maxLonS);
                    if (this.minLat <= this.maxLat && this.minLon <= this.maxLon) {
                        this.bboxSet = true;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            var explicit = new HashSet<Long>();
            String[] v = params.get("deviceId");
            if (v != null) {
                for (String s : v) {
                    if (s != null && !s.isEmpty()) {
                        try {
                            explicit.add(Long.parseLong(s));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
            long group = parseLongParam(params, "groupId", 0);
            resolveDeviceFilter(storage, userId, explicit, group);
            String p = first(params, "protocol");
            if (p != null && !p.isEmpty()) {
                this.protocol = p;
                this.protocolSet = true;
            }
            String et = first(params, "eventType", "event");
            if (et != null && !et.isEmpty()) {
                this.eventType = et;
                this.eventTypeSet = true;
            }
            return this;
        }

        private void applyPositionJson(
                SocketFilterConfig previous, JsonNode p, Storage storage, long userId) throws StorageException {
            JsonNode bboxNode = p.has("bbox") && p.get("bbox").isObject() ? p.get("bbox") : p;
            if (hasBboxKey(bboxNode)) {
                double minL = dOrWithPrevious(bboxNode, "minLat", "minLatitude", previous.minLat);
                double maxL = dOrWithPrevious(bboxNode, "maxLat", "maxLatitude", previous.maxLat);
                double minO = dOrWithPrevious(bboxNode, "minLon", "minLongitude", previous.minLon);
                double maxO = dOrWithPrevious(bboxNode, "maxLon", "maxLongitude", previous.maxLon);
                this.minLat = minL;
                this.maxLat = maxL;
                this.minLon = minO;
                this.maxLon = maxO;
                this.bboxSet = (minL <= maxL) && (minO <= maxO);
            } else {
                this.bboxSet = previous.bboxSet;
                this.minLat = previous.minLat;
                this.maxLat = previous.maxLat;
                this.minLon = previous.minLon;
                this.maxLon = previous.maxLon;
            }
            if (p.has("deviceId") || p.has("groupId")) {
                this.deviceFilterSet = false;
                this.deviceIds = new HashSet<>();
                var explicit = new HashSet<Long>();
                if (p.has("deviceId") && p.get("deviceId").isArray()) {
                    for (JsonNode n : p.get("deviceId")) {
                        if (n.isNumber()) {
                            explicit.add(n.asLong());
                        } else {
                            try {
                                explicit.add(Long.parseLong(n.asText()));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                } else if (p.has("deviceId") && p.get("deviceId").isNumber()) {
                    explicit.add(p.get("deviceId").asLong());
                } else if (p.has("deviceId") && p.get("deviceId").isTextual()) {
                    try {
                        explicit.add(Long.parseLong(p.get("deviceId").asText()));
                    } catch (NumberFormatException ignored) {
                    }
                }
                long group;
                if (p.has("groupId") && p.get("groupId").isNumber()) {
                    group = p.get("groupId").asLong();
                } else if (p.has("groupId") && p.get("groupId").isTextual()) {
                    try {
                        group = Long.parseLong(p.get("groupId").asText());
                    } catch (NumberFormatException e) {
                        group = 0L;
                    }
                } else {
                    group = 0L;
                }
                resolveDeviceFilter(storage, userId, explicit, group);
            } else {
                this.deviceFilterSet = previous.deviceFilterSet;
                this.deviceIds = previous.deviceFilterSet
                        ? new HashSet<>(previous.deviceIds) : new HashSet<>();
            }
            if (p.has("protocol")) {
                if (p.get("protocol").isNull() || p.get("protocol").asText("").isEmpty()) {
                    this.protocolSet = false;
                } else {
                    this.protocol = p.get("protocol").asText();
                    this.protocolSet = true;
                }
            } else {
                this.protocolSet = previous.protocolSet;
                this.protocol = previous.protocol;
            }
        }

        private void applyEventJson(SocketFilterConfig previous, JsonNode e) {
            if (e.has("type") && e.get("type").isNull()) {
                this.eventTypeSet = false;
            } else if (e.has("type") && e.get("type").isTextual() && e.get("type").asText().isEmpty()) {
                this.eventTypeSet = false;
            } else if (e.has("type") && e.get("type").isTextual()) {
                this.eventType = e.get("type").asText();
                this.eventTypeSet = true;
            } else if (e.has("eventType") && e.get("eventType").isTextual() && !e.get("eventType").asText().isEmpty()) {
                this.eventType = e.get("eventType").asText();
                this.eventTypeSet = true;
            } else {
                this.eventType = previous.eventType;
                this.eventTypeSet = previous.eventTypeSet;
            }
        }

        private void resolveDeviceFilter(Storage storage, long userId, Set<Long> explicit, long group)
                throws StorageException {
            if (storage == null) {
                return;
            }
            if (group > 0) {
                Collection<Device> inGroup = DeviceUtil.getAccessibleDevices(
                        storage, userId, Collections.emptyList(), List.of(group));
                var fromGroup = inGroup.stream().map(Device::getId).collect(Collectors.toSet());
                if (explicit.isEmpty()) {
                    this.deviceIds = fromGroup;
                } else {
                    this.deviceIds = new HashSet<>();
                    for (long id : explicit) {
                        if (fromGroup.contains(id)) {
                            this.deviceIds.add(id);
                        }
                    }
                }
            } else if (!explicit.isEmpty()) {
                for (long id : explicit) {
                    if (canAccessDevice(storage, userId, id)) {
                        if (this.deviceIds == null) {
                            this.deviceIds = new HashSet<>();
                        }
                        this.deviceIds.add(id);
                    }
                }
            }
            if (this.deviceIds != null && !this.deviceIds.isEmpty()) {
                this.deviceFilterSet = true;
            }
        }
    }

    private static boolean hasBboxKey(JsonNode bboxNode) {
        if (bboxNode == null || !bboxNode.isObject()) {
            return false;
        }
        return bboxNode.has("minLat") || bboxNode.has("minLatitude")
                || bboxNode.has("maxLat") || bboxNode.has("maxLatitude")
                || bboxNode.has("minLon") || bboxNode.has("minLongitude")
                || bboxNode.has("maxLon") || bboxNode.has("maxLongitude");
    }

    private static double dOrWithPrevious(
            JsonNode p, String k1, String k2, double previous) {
        if (p.has(k1) && p.get(k1).isNumber()) {
            return p.get(k1).asDouble();
        }
        if (p.has(k1) && p.get(k1).isTextual() && !p.get(k1).asText().isEmpty()) {
            try {
                return Double.parseDouble(p.get(k1).asText());
            } catch (NumberFormatException e) {
                return previous;
            }
        }
        if (p.has(k2) && p.get(k2).isNumber()) {
            return p.get(k2).asDouble();
        }
        if (p.has(k2) && p.get(k2).isTextual() && !p.get(k2).asText().isEmpty()) {
            try {
                return Double.parseDouble(p.get(k2).asText());
            } catch (NumberFormatException e) {
                return previous;
            }
        }
        return previous;
    }

    private static boolean canAccessDevice(Storage storage, long userId, long deviceId) {
        if (deviceId <= 0) {
            return false;
        }
        try {
            var device = storage.getObject(Device.class, new Request(
                    new Columns.Include("id"),
                    new Condition.And(
                            new Condition.Equals("id", deviceId),
                            new Condition.Permission(User.class, userId, Device.class))));
            return device != null;
        } catch (StorageException e) {
            return false;
        }
    }

    private static long parseLongParam(Map<String, String[]> params, String key, long d) {
        String[] v = params.get(key);
        if (v == null || v.length == 0 || v[0] == null || v[0].isEmpty()) {
            return d;
        }
        try {
            return Long.parseLong(v[0]);
        } catch (NumberFormatException e) {
            return d;
        }
    }

    private static String first(Map<String, String[]> params, String... keys) {
        for (String k : keys) {
            String[] v = params.get(k);
            if (v != null && v.length > 0 && v[0] != null && !v[0].isEmpty()) {
                return v[0];
            }
        }
        return null;
    }
}
