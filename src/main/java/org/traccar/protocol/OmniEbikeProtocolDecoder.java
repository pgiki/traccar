/*
 * Copyright 2024 Anton Tananaev (anton@traccar.org)
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
package org.traccar.protocol;

import io.netty.channel.Channel;
import org.traccar.BaseProtocolDecoder;
import org.traccar.NetworkMessage;
import org.traccar.Protocol;
import org.traccar.helper.DateBuilder;
import org.traccar.helper.UnitsConverter;
import org.traccar.session.DeviceSession;
import org.traccar.model.Position;

import java.net.SocketAddress;

public class OmniEbikeProtocolDecoder extends BaseProtocolDecoder {

    public OmniEbikeProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    private void sendResponse(Channel channel, SocketAddress remoteAddress,
                              String responseHeader, String imei, String cmd) {
        if (channel != null) {
            String response = "\u00ff\u00ff" + responseHeader + ",OM," + imei + "," + cmd + "#\n";
            channel.writeAndFlush(new NetworkMessage(response, remoteAddress));
        }
    }

    static double parseCoordinate(String value, String hemisphere) {
        double raw = Double.parseDouble(value);
        int degrees = (int) (raw / 100);
        double minutes = raw % 100;
        double coord = degrees + minutes / 60.0;
        return hemisphere.equals("S") || hemisphere.equals("W") ? -coord : coord;
    }

    private static int safeInt(String[] values, int index) {
        if (index < values.length && !values[index].isEmpty()) {
            return Integer.parseInt(values[index].trim());
        }
        return 0;
    }

    private static String safeStr(String[] values, int index) {
        if (index < values.length) {
            return values[index].trim();
        }
        return "";
    }

    @Override
    protected Object decode(Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        String sentence = (String) msg;

        // Strip any leading 0xFF bytes that may appear when the server's own messages loop back
        int start = 0;
        while (start < sentence.length() && sentence.charAt(start) == '\u00ff') {
            start++;
        }
        if (start > 0) {
            sentence = sentence.substring(start);
        }

        sentence = sentence.replaceAll("[#\r\n]+$", "").trim();
        if (sentence.isEmpty()) {
            return null;
        }

        String[] values = sentence.split(",");
        if (values.length < 4) {
            return null;
        }

        String header = values[0]; // *SCOR or *CMDR (or *SCOS for doc-typo C0)

        // Beacon messages use *CMDR/*CMDS with an extra field at index [3]
        boolean isBeacon = header.startsWith("*CMDR") || header.startsWith("*CMDS");
        String imei = values[2];
        String type = isBeacon ? safeStr(values, 4) : values[3];
        int dataIndex = isBeacon ? 5 : 4;

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, imei);
        if (deviceSession == null) {
            return null;
        }

        // Response header mirrors the incoming direction
        String responseHeader = isBeacon ? "*CMDS" : "*SCOS";

        // --- D0: GPS location ---
        if (type.equals("D0")) {
            if (values.length < dataIndex + 3) {
                return null;
            }

            Position position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());

            int trigger = safeInt(values, dataIndex);
            position.set("locationTrigger", trigger);

            String timeStr = safeStr(values, dataIndex + 1);
            String validity = safeStr(values, dataIndex + 2);

            if (!validity.equals("A") || safeStr(values, dataIndex + 3).isEmpty()) {
                getLastLocation(position, null);
                position.setValid(false);
                return position;
            }

            DateBuilder dateBuilder = new DateBuilder()
                    .setTime(
                            Integer.parseInt(timeStr.substring(0, 2)),
                            Integer.parseInt(timeStr.substring(2, 4)),
                            Integer.parseInt(timeStr.substring(4, 6)));

            position.setValid(true);
            position.setLatitude(parseCoordinate(
                    safeStr(values, dataIndex + 3), safeStr(values, dataIndex + 4)));
            position.setLongitude(parseCoordinate(
                    safeStr(values, dataIndex + 5), safeStr(values, dataIndex + 6)));

            if (!safeStr(values, dataIndex + 7).isEmpty()) {
                position.set(Position.KEY_SATELLITES, safeInt(values, dataIndex + 7));
            }
            if (!safeStr(values, dataIndex + 8).isEmpty()) {
                position.set(Position.KEY_HDOP, Double.parseDouble(safeStr(values, dataIndex + 8)));
            }

            String dateStr = safeStr(values, dataIndex + 9);
            if (dateStr.length() == 6) {
                dateBuilder.setDateReverse(
                        Integer.parseInt(dateStr.substring(0, 2)),
                        Integer.parseInt(dateStr.substring(2, 4)),
                        Integer.parseInt(dateStr.substring(4, 6)));
            }
            position.setTime(dateBuilder.getDate());

            if (!safeStr(values, dataIndex + 10).isEmpty()) {
                position.setAltitude(Double.parseDouble(safeStr(values, dataIndex + 10)));
            }

            return position;
        }

        // --- D1: real-time tracking interval echo ---
        if (type.equals("D1")) {
            Position position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());
            getLastLocation(position, null);
            if (!safeStr(values, dataIndex).isEmpty()) {
                position.set("trackingInterval", safeInt(values, dataIndex));
            }
            return position;
        }

        // --- All other commands ---
        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        getLastLocation(position, null);

        switch (type) {

            case "Q0" -> {
                // Check-in: IoT module voltage, vehicle battery %, signal strength
                position.set(Position.KEY_POWER, safeInt(values, dataIndex) / 100.0);
                position.set(Position.KEY_BATTERY_LEVEL, safeInt(values, dataIndex + 1));
                position.set(Position.KEY_RSSI, safeInt(values, dataIndex + 2));
            }

            case "H0" -> {
                // Heartbeat: lock status, IoT voltage, RSSI, vehicle battery %, charging
                position.set(Position.KEY_BLOCKED, safeInt(values, dataIndex) == 1);
                position.set(Position.KEY_POWER, safeInt(values, dataIndex + 1) / 100.0);
                position.set(Position.KEY_RSSI, safeInt(values, dataIndex + 2));
                position.set(Position.KEY_BATTERY_LEVEL, safeInt(values, dataIndex + 3));
                position.set(Position.KEY_CHARGE, safeInt(values, dataIndex + 4) == 1);
            }

            case "R0" -> {
                // Unlock/lock request response — IoT returns session KEY
                // Decoder auto-relays the appropriate lock/unlock command using the key
                int op = safeInt(values, dataIndex);
                String key = safeStr(values, dataIndex + 1);
                String userId = safeStr(values, dataIndex + 2);
                String timestamp = safeStr(values, dataIndex + 3);

                position.set("r0Op", op);
                position.set("r0Key", Integer.parseInt(key));
                position.set("r0UserId", Integer.parseInt(userId));
                position.set("r0Timestamp", Long.parseLong(timestamp));

                if (op == 0 || op == 2 || op == 6) {
                    sendResponse(channel, remoteAddress, responseHeader, imei,
                            "L0," + key + "," + userId + "," + timestamp);
                } else if (op == 1 || op == 3) {
                    sendResponse(channel, remoteAddress, responseHeader, imei, "L1," + key);
                }
            }

            case "L0" -> {
                // Unlock result — two-way verification ACK required
                int status = safeInt(values, dataIndex);
                position.set(Position.KEY_RESULT, String.valueOf(status));
                position.set(Position.KEY_LOCK, status != 0); // false = unlocked (success)
                sendResponse(channel, remoteAddress, responseHeader, imei, "L0");
            }

            case "L1" -> {
                // Lock result — two-way verification ACK required
                int status = safeInt(values, dataIndex);
                position.set(Position.KEY_RESULT, String.valueOf(status));
                position.set(Position.KEY_LOCK, status == 0); // true = locked (success)
                if (values.length > dataIndex + 3 && !safeStr(values, dataIndex + 3).isEmpty()) {
                    int rideMins = safeInt(values, dataIndex + 3);
                    position.set(Position.KEY_DRIVING_TIME, (long) rideMins * 60L * 1000L);
                }
                sendResponse(channel, remoteAddress, responseHeader, imei, "L1");
            }

            case "S5" -> {
                // IoT device settings echo-back
                position.set("accelSensitivity", safeInt(values, dataIndex));
                position.set("s6UploadEnabled", safeInt(values, dataIndex + 1));
                position.set("heartbeatInterval", safeInt(values, dataIndex + 2));
                position.set("s6Interval", safeInt(values, dataIndex + 3));
            }

            case "S6" -> {
                // Vehicle data: battery, speed, charging, voltages, lock status, signal, mileage
                position.set(Position.KEY_BATTERY_LEVEL, safeInt(values, dataIndex));
                position.set("speedMode", safeInt(values, dataIndex + 1));
                // Speed field is in 0.1 km/h units (e.g. 221 → 22.1 km/h)
                position.setSpeed(UnitsConverter.knotsFromKph(safeInt(values, dataIndex + 2) / 10.0));
                position.set(Position.KEY_CHARGE, safeInt(values, dataIndex + 3) == 1);
                position.set(Position.KEY_POWER, safeInt(values, dataIndex + 4) / 10.0);
                position.set("battery2Voltage", safeInt(values, dataIndex + 5) / 10.0);
                position.set(Position.KEY_BLOCKED, safeInt(values, dataIndex + 6) == 1);
                position.set(Position.KEY_RSSI, safeInt(values, dataIndex + 7));
                if (!safeStr(values, dataIndex + 8).isEmpty()) {
                    position.set("tripMileage", safeStr(values, dataIndex + 8));
                }
                if (!safeStr(values, dataIndex + 9).isEmpty()) {
                    position.set("totalMileage", safeStr(values, dataIndex + 9));
                }
            }

            case "S7" -> {
                // Vehicle settings 1 echo-back: headlight, speed mode, throttle, taillight
                position.set("headlight", safeInt(values, dataIndex));
                position.set("speedMode", safeInt(values, dataIndex + 1));
                position.set("throttle", safeInt(values, dataIndex + 2));
                position.set("taillight", safeInt(values, dataIndex + 3));
            }

            case "S4" -> {
                // Vehicle settings 2 echo-back: speed limits and feature toggles
                position.set("speedMeterMph", safeInt(values, dataIndex));
                position.set("cruiseControl", safeInt(values, dataIndex + 1));
                position.set("startMode", safeInt(values, dataIndex + 2));
                position.set("speedModeBtn", safeInt(values, dataIndex + 3));
                position.set("headlightBtn", safeInt(values, dataIndex + 4));
                position.set("lowSpeedLimit", safeInt(values, dataIndex + 5));
                position.set("midSpeedLimit", safeInt(values, dataIndex + 6));
                position.set("highSpeedLimit", safeInt(values, dataIndex + 7));
            }

            case "W0" -> {
                // Alarm — ACK required
                int alarmType = safeInt(values, dataIndex);
                switch (alarmType) {
                    case 1 -> position.addAlarm(Position.ALARM_MOVEMENT);
                    case 2 -> position.addAlarm(Position.ALARM_FALL_DOWN);
                    case 3 -> position.addAlarm(Position.ALARM_TAMPERING);
                    case 4 -> position.addAlarm(Position.ALARM_LOW_BATTERY);
                    case 6 -> position.set("tipOverCleared", true);
                    case 7 -> position.set("tamperCleared", true);
                }
                sendResponse(channel, remoteAddress, responseHeader, imei, "W0");
            }

            case "V0" -> {
                // Audible alert echo-back
                position.set("audioAlertCode", safeInt(values, dataIndex));
            }

            case "G0" -> {
                // Firmware versions
                position.set(Position.KEY_VERSION_FW, safeStr(values, dataIndex));
                if (!safeStr(values, dataIndex + 1).isEmpty()) {
                    position.set("firmwareBuildDate", safeStr(values, dataIndex + 1));
                }
                if (!safeStr(values, dataIndex + 2).isEmpty()) {
                    position.set("controllerVersion", safeStr(values, dataIndex + 2));
                }
                if (!safeStr(values, dataIndex + 3).isEmpty()) {
                    position.set("bmsVersion", safeStr(values, dataIndex + 3));
                }
                if (!safeStr(values, dataIndex + 4).isEmpty()) {
                    position.set("audioVersion", safeStr(values, dataIndex + 4));
                }
                if (!safeStr(values, dataIndex + 5).isEmpty()) {
                    position.set("bluetoothVersion", safeStr(values, dataIndex + 5));
                }
                if (!safeStr(values, dataIndex + 6).isEmpty()) {
                    position.set("tcpProtocolVersion", safeStr(values, dataIndex + 6));
                }
                if (!safeStr(values, dataIndex + 7).isEmpty()) {
                    position.set("chargingRxMcuVersion", safeStr(values, dataIndex + 7));
                }
                if (!safeStr(values, dataIndex + 8).isEmpty()) {
                    position.set("chargingRxBtVersion", safeStr(values, dataIndex + 8));
                }
            }

            case "E0" -> {
                // Controller error code — ACK required
                position.set("controllerError", safeInt(values, dataIndex));
                sendResponse(channel, remoteAddress, responseHeader, imei, "E0");
            }

            case "U0" -> {
                // Upgrade check: IoT firmware info sent by device
                position.set("upgradeIotFw", safeStr(values, dataIndex));
                position.set("upgradeDeviceId", safeStr(values, dataIndex + 1));
                if (!safeStr(values, dataIndex + 2).isEmpty()) {
                    position.set("upgradeControllerFw", safeStr(values, dataIndex + 2));
                }
            }

            case "U1" -> {
                // Upgrade data packet request
                position.set("upgradePacketIdx", safeInt(values, dataIndex));
                position.set("upgradeDeviceId", safeStr(values, dataIndex + 1));
            }

            case "U2" -> {
                // Upgrade result notification (no server response)
                position.set("upgradeDeviceId", safeStr(values, dataIndex));
                position.set("upgradeResult", safeInt(values, dataIndex + 1));
            }

            case "K0" -> {
                // BLE communication KEY echo-back
                position.set("bleKey", safeStr(values, dataIndex));
            }

            case "S1" -> {
                // Event notification echo-back
                position.set(Position.KEY_EVENT, safeInt(values, dataIndex));
            }

            case "L5" -> {
                // External device control result
                position.set("externalDeviceOp", safeInt(values, dataIndex));
                if (!safeStr(values, dataIndex + 1).isEmpty()) {
                    position.set("externalDeviceResult", safeInt(values, dataIndex + 1));
                }
            }

            case "Z0" -> {
                // Controller customized data
                position.set("controllerDataType", safeInt(values, dataIndex));
                position.set("controllerDataLength", safeInt(values, dataIndex + 1));
                if (!safeStr(values, dataIndex + 2).isEmpty()) {
                    position.set("controllerData", safeStr(values, dataIndex + 2));
                }
            }

            case "U5" -> {
                // HTTP upgrade echo-back confirmation
                position.set("httpUpgradeType", safeInt(values, dataIndex));
                // dataIndex+1 = timeout (not stored)
                String url = safeStr(values, dataIndex + 2);
                if (!url.isEmpty()) {
                    position.set("httpUpgradeUrl", url);
                }
                // checksum is at dataIndex+6 (reqType, timeout, url, type1, type2, reserved, checksum, reserved)
                String checksum = safeStr(values, dataIndex + 6);
                if (!checksum.isEmpty()) {
                    position.set("httpUpgradeChecksum", checksum);
                }
            }

            case "I0" -> {
                // SIM card ICCID
                position.set(Position.KEY_ICCID, safeStr(values, dataIndex));
            }

            case "M0" -> {
                // Bluetooth MAC address
                position.set("btMac", safeStr(values, dataIndex));
            }

            case "V1" -> {
                // Device sound settings echo-back
                position.set("alarmSound", safeInt(values, dataIndex));
                position.set("unlockSound", safeInt(values, dataIndex + 1));
                position.set("lockSound", safeInt(values, dataIndex + 2));
            }

            case "C0" -> {
                // RFID card unlock request from IoT (doc typo uses *SCOS header, handle regardless)
                position.set("rfidRequest", safeInt(values, dataIndex));
                position.set("rfidCardType", safeInt(values, dataIndex + 1));
                if (!safeStr(values, dataIndex + 2).isEmpty()) {
                    position.set("rfidCardId", safeStr(values, dataIndex + 2));
                }
                // Server responds manually with L0 after card verification (no auto-ack)
            }

            case "B0" -> {
                // Beacon validation — uses *CMDR header; response uses *CMDS with extra field
                position.set("beaconDetected", safeInt(values, dataIndex));
                if (!safeStr(values, dataIndex + 1).isEmpty()) {
                    position.set("beaconTypeId", safeInt(values, dataIndex + 1));
                }
                String beaconMac = safeStr(values, dataIndex + 2);
                if (!beaconMac.isEmpty()) {
                    position.set("beaconMac", beaconMac);
                }
                if (!safeStr(values, dataIndex + 3).isEmpty()) {
                    position.set("beaconTimestamp", Long.parseLong(safeStr(values, dataIndex + 3)));
                }
                if (!safeStr(values, dataIndex + 4).isEmpty()) {
                    position.set("beaconBattery", safeInt(values, dataIndex + 4));
                }
                if (!safeStr(values, dataIndex + 5).isEmpty()) {
                    position.set("beaconLongitude", safeStr(values, dataIndex + 5));
                }
                if (!safeStr(values, dataIndex + 6).isEmpty()) {
                    position.set("beaconLatitude", safeStr(values, dataIndex + 6));
                }
                // Beacon response uses the full *CMDS header with the extra routing field (values[3])
                if (channel != null && isBeacon) {
                    String beaconRouting = safeStr(values, 3);
                    String response = "\u00ff\u00ff*CMDS,OM," + imei + "," + beaconRouting + ",B0,0#\n";
                    channel.writeAndFlush(new NetworkMessage(response, remoteAddress));
                }
            }

            case "SC" -> {
                // Charging station information
                String stationImei = safeStr(values, dataIndex);
                if (!stationImei.isEmpty()) {
                    position.set("chargingStationImei", stationImei);
                }
                if (values.length > dataIndex + 1) {
                    position.set("chargingStationLock", safeInt(values, dataIndex + 1));
                }
                if (values.length > dataIndex + 2) {
                    position.set("chargingStationBt", safeInt(values, dataIndex + 2));
                }
                if (values.length > dataIndex + 3) {
                    position.set("chargingStatus", safeInt(values, dataIndex + 3));
                }
                if (values.length > dataIndex + 4) {
                    position.set("chargingRxTemp", safeInt(values, dataIndex + 4));
                }
                if (values.length > dataIndex + 5) {
                    position.set("chargingRxOutputVoltage", safeInt(values, dataIndex + 5) / 10.0);
                }
                if (values.length > dataIndex + 6) {
                    position.set("chargingRxInputVoltage", safeInt(values, dataIndex + 6) / 10.0);
                }
                if (values.length > dataIndex + 7) {
                    position.set("chargingRxCurrent", safeInt(values, dataIndex + 7));
                }
                if (values.length > dataIndex + 8) {
                    position.set("chargingRxSwitch", safeInt(values, dataIndex + 8) == 1);
                }
                if (values.length > dataIndex + 9) {
                    position.set("chargingTxTemp", safeInt(values, dataIndex + 9));
                }
                if (values.length > dataIndex + 10) {
                    position.set("chargingTxVoltage", safeInt(values, dataIndex + 10) / 10.0);
                }
                if (values.length > dataIndex + 11) {
                    position.set("chargingTxCurrent", safeInt(values, dataIndex + 11));
                }
            }

            default -> {
                return null;
            }
        }

        return !position.getAttributes().isEmpty() ? position : null;
    }

}
