package org.traccar.protocol;

import org.junit.jupiter.api.Test;
import org.traccar.ProtocolTest;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Position;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OmniEbikeProtocolDecoderTest extends ProtocolTest {

    @Test
    public void testCoordinateConversion() {
        // Spec example: lat 2237.7514 N → 22.62919°
        assertEquals(22.62919, OmniEbikeProtocolDecoder.parseCoordinate("2237.7514", "N"), 1e-5);
        // Southern hemisphere negates
        assertEquals(-22.62919, OmniEbikeProtocolDecoder.parseCoordinate("2237.7514", "S"), 1e-5);
        // Spec example: lon 11408.6214 E → 114.14369°
        assertEquals(114.14369, OmniEbikeProtocolDecoder.parseCoordinate("11408.6214", "E"), 1e-5);
        // Western hemisphere negates
        assertEquals(-114.14369, OmniEbikeProtocolDecoder.parseCoordinate("11408.6214", "W"), 1e-5);
    }

    @Test
    public void testDecode() throws Exception {

        var decoder = inject(new OmniEbikeProtocolDecoder(null));

        // --- Q0: Check-in (establishes device session) ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,Q0,412,80,28#"),
                Position.KEY_POWER, 4.12);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,Q0,412,80,28#"),
                Position.KEY_BATTERY_LEVEL, 80);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,Q0,412,80,28#"),
                Position.KEY_RSSI, 28);

        // --- H0: Heartbeat (unlocked, not charging) ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,H0,0,412,28,80,0#"),
                Position.KEY_LOCK, false);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,H0,0,412,28,80,0#"),
                Position.KEY_POWER, 4.12);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,H0,0,412,28,80,0#"),
                Position.KEY_BATTERY_LEVEL, 80);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,H0,0,412,28,80,0#"),
                Position.KEY_RSSI, 28);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,H0,0,412,28,80,0#"),
                Position.KEY_CHARGE, false);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,H0,0,412,28,80,0#"),
                Position.KEY_TYPE, "H0");

        // --- H0: Heartbeat (locked + charging) ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,H0,1,380,20,55,1#"),
                Position.KEY_LOCK, true);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,H0,1,380,20,55,1#"),
                Position.KEY_CHARGE, true);

        // --- R0: Unlock/lock request response (op=0 = unlock, key=55) ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,R0,0,55,1234,1497689816#"),
                "r0Op", 0);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,R0,0,55,1234,1497689816#"),
                "r0Key", 55);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,R0,0,55,1234,1497689816#"),
                "r0UserId", 1234);

        // --- R0: Lock request (op=1) ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,R0,1,55,1234,1497689816#"),
                "r0Op", 1);

        // --- L0: Unlock result (success) ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,L0,0,1234,1497689816#"),
                Position.KEY_RESULT, "0");
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,L0,0,1234,1497689816#"),
                Position.KEY_LOCK, false);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,L0,0,1234,1497689816#"),
                "operationUserId", 1234);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,L0,0,1234,1497689816#"),
                "operationSequence", 1497689816L);

        // --- L0: Unlock result (KEY error) ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,L0,2,1234,1497689816#"),
                Position.KEY_RESULT, "2");

        // --- L1: Lock result (success, 3 min ride) ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,L1,0,1234,1497689816,3#"),
                Position.KEY_RESULT, "0");
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,L1,0,1234,1497689816,3#"),
                Position.KEY_LOCK, true);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,L1,0,1234,1497689816,3#"),
                Position.KEY_DRIVING_TIME, 180000L);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,L1,0,1234,1497689816,3#"),
                "operationUserId", 1234);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,L1,0,1234,1497689816,3#"),
                "operationSequence", 1497689816L);

        // --- L1: Lock result (riding in progress, cannot lock) ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,L1,3,1234,1497689816,0#"),
                Position.KEY_RESULT, "3");

        // --- S5: IoT device settings ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S5,3,1,240,10#"),
                "accelSensitivity", 3);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S5,3,1,240,10#"),
                "s6UploadEnabled", 1);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S5,3,1,240,10#"),
                "heartbeatInterval", 240);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S5,3,1,240,10#"),
                "s6Interval", 10);

        // --- S6: Vehicle data (spec example) ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S6,80,3,221,0,372,372,0,28,460-00,0x27A6,220486467#"),
                Position.KEY_BATTERY_LEVEL, 80);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S6,80,3,221,0,372,372,0,28,460-00,0x27A6,220486467#"),
                "speedMode", 3);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S6,80,3,221,0,372,372,0,28,460-00,0x27A6,220486467#"),
                "speed", UnitsConverter.knotsFromKph(22.1));
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S6,80,3,221,0,372,372,0,28,460-00,0x27A6,220486467#"),
                Position.KEY_CHARGE, false);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S6,80,3,221,0,372,372,0,28,460-00,0x27A6,220486467#"),
                Position.KEY_POWER, 37.2);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S6,80,3,221,0,372,372,0,28,460-00,0x27A6,220486467#"),
                Position.KEY_BATTERY, 37.2);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S6,80,3,221,0,372,372,0,28,460-00,0x27A6,220486467#"),
                Position.KEY_LOCK, false);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S6,80,3,221,0,372,372,0,28,460-00,0x27A6,220486467#"),
                Position.KEY_RSSI, 28);
        // Spec example trip field is non-numeric (legacy cell-like); hex total → meters (10 m units)
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S6,80,3,221,0,372,372,0,28,460-00,0x27A6,220486467#"),
                Position.KEY_ODOMETER, 0x27A6L * 10L);
        // Numeric mileage (controller units × 10 → meters)
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S6,80,3,221,0,372,372,0,28,100,5000#"),
                Position.KEY_ODOMETER_TRIP, 1000L);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S6,80,3,221,0,372,372,0,28,100,5000#"),
                Position.KEY_ODOMETER, 50000L);

        // --- S7: Vehicle settings 1 ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S7,0,3,0,0#"),
                "headlight", 0);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S7,0,3,0,0#"),
                "speedMode", 3);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S7,0,3,0,0#"),
                Position.KEY_THROTTLE, 0);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S7,0,3,0,0#"),
                "taillight", 0);

        // --- S4: Vehicle settings 2 ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S4,0,0,0,0,0,15,20,25#"),
                "lowSpeedLimit", 15);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S4,0,0,0,0,0,15,20,25#"),
                "midSpeedLimit", 20);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S4,0,0,0,0,0,15,20,25#"),
                "highSpeedLimit", 25);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S4,0,0,0,0,0,15,20,25#"),
                Position.KEY_SPEED_LIMIT, UnitsConverter.knotsFromKph(25));

        // --- W0: Alarms ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,W0,1#"),
                Position.KEY_ALARM, Position.ALARM_MOVEMENT);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,W0,2#"),
                Position.KEY_ALARM, Position.ALARM_FALL_DOWN);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,W0,3#"),
                Position.KEY_ALARM, Position.ALARM_TAMPERING);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,W0,4#"),
                Position.KEY_ALARM, Position.ALARM_LOW_BATTERY);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,W0,6#"),
                "tipOverCleared", true);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,W0,7#"),
                "tamperCleared", true);

        // --- V0: Audio alert echo-back ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,V0,1#"),
                "audioAlertCode", 1);

        // --- D0: Valid GPS location (spec example) ---
        verifyPosition(decoder, text(
                "*SCOR,OM,123456789123456,D0,0,124458.00,A,2237.7514,N,11408.6214,E,6,0.21,151216,10,M,A#"),
                position("2016-12-15 12:44:58.000", true, 22.62919, 114.14369));
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,D0,0,124458.00,A,2237.7514,N,11408.6214,E,6,0.21,151216,10,M,A#"),
                Position.KEY_SATELLITES, 6);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,D0,0,124458.00,A,2237.7514,N,11408.6214,E,6,0.21,151216,10,M,A#"),
                Position.KEY_HDOP, 0.21);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,D0,0,124458.00,A,2237.7514,N,11408.6214,E,6,0.21,151216,10,M,A#"),
                "altitude", 10.0);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,D0,0,124458.00,A,2237.7514,N,11408.6214,E,6,0.21,151216,10,M,A#"),
                Position.KEY_EVENT, 0);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,D0,0,124458.00,A,2237.7514,N,11408.6214,E,6,0.21,151216,10,M,A#"),
                Position.KEY_TYPE, "D0");

        // --- D0: Invalid GPS (no fix) ---
        Object invalidGps = decoder.decode(null, null, text(
                "*SCOR,OM,123456789123456,D0,0,033724.00,V,,,,,,,120517,,,N#"));
        assertNotNull(invalidGps);
        assertFalse(((Position) invalidGps).getValid());

        // --- D0: Southern hemisphere + continuous tracking trigger ---
        verifyPosition(decoder, text(
                "*SCOR,OM,867584030387299,D0,1,012102.00,A,0608.00062,S,10659.70331,E,12,0.69,151118,30.3,M,A#"),
                position("2018-11-15 01:21:02.000", true, -6.133344, 106.995055));
        verifyAttribute(decoder, text(
                "*SCOR,OM,867584030387299,D0,1,012102.00,A,0608.00062,S,10659.70331,E,12,0.69,151118,30.3,M,A#"),
                Position.KEY_EVENT, 1);

        // Reset decoder for the rest of the tests with the original IMEI
        decoder = inject(new OmniEbikeProtocolDecoder(null));

        // Re-establish session with Q0
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,Q0,412,80,28#"),
                Position.KEY_POWER, 4.12);

        // --- D1: Tracking interval echo-back ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,D1,60#"),
                "trackingInterval", 60);

        // --- G0: Firmware version ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,G0,110,Jul 4 2018,1101,1101,1101,,,,#"),
                Position.KEY_VERSION_FW, "110");
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,G0,110,Jul 4 2018,1101,1101,1101,,,,#"),
                "firmwareBuildDate", "Jul 4 2018");
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,G0,110,Jul 4 2018,1101,1101,1101,,,,#"),
                "controllerVersion", "1101");

        // --- E0: Controller error code ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,E0,1#"),
                Position.KEY_ALARM, Position.ALARM_FAULT);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,E0,1#"),
                Position.KEY_STATUS, 1);

        // --- U0: Upgrade check (IoT-initiated) ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,U0,110,8A,1101#"),
                "upgradeIotFw", "110");
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,U0,110,8A,1101#"),
                "upgradeDeviceId", "8A");

        // --- U1: Upgrade packet request ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,U1,100,8A#"),
                "upgradePacketIdx", 100);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,U1,100,8A#"),
                "upgradeDeviceId", "8A");

        // --- U2: Upgrade result (success) ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,U2,8A,0#"),
                "upgradeDeviceId", "8A");
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,U2,8A,0#"),
                "upgradeResult", 0);

        // --- K0: BLE communication KEY ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,K0,OmniW4GX#"),
                "bleKey", "OmniW4GX");

        // --- S1: Event notification (IoT off) ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,S1,1#"),
                Position.KEY_EVENT, 1);

        // --- L5: External device (battery lock unlock success) ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,L5,1,0#"),
                "externalDeviceOp", 1);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,L5,1,0#"),
                "externalDeviceResult", 0);

        // --- L5: Status query (locked state) ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,L5,33,16#"),
                "externalDeviceOp", 33);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,L5,33,16#"),
                "externalDeviceResult", 16);

        // --- Z0: Controller customized data ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,Z0,1,5,A0B1C2D3E4F5#"),
                "controllerDataType", 1);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,Z0,1,5,A0B1C2D3E4F5#"),
                "controllerDataLength", 5);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,Z0,1,5,A0B1C2D3E4F5#"),
                "controllerData", "A0B1C2D3E4F5");

        // --- U5: HTTP upgrade echo-back ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,U5,0,10,http://fw.omni.com/v1.bin,0,,,,#"),
                "httpUpgradeType", 0);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,U5,0,10,http://fw.omni.com/v1.bin,0,,,,#"),
                "httpUpgradeUrl", "http://fw.omni.com/v1.bin");

        // --- I0: SIM card ICCID ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,I0,123456789AB123456789#"),
                Position.KEY_ICCID, "123456789AB123456789");

        // --- M0: Bluetooth MAC address ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,M0,12:34:56:78:90:AB#"),
                "bleMac", "12:34:56:78:90:AB");

        // --- V1: Device sound settings ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,V1,2,2,2,0#"),
                "alarmSound", 2);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,V1,2,2,2,0#"),
                "unlockSound", 2);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,V1,2,2,2,0#"),
                "lockSound", 2);

        // --- C0: RFID card unlock request (IoT-initiated) ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,C0,0,0,000000001A2B3C4D,0#"),
                "rfidRequest", 0);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,C0,0,0,000000001A2B3C4D,0#"),
                "rfidCardType", 0);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,C0,0,0,000000001A2B3C4D,0#"),
                Position.KEY_CARD, "000000001A2B3C4D");
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,C0,0,0,000000001A2B3C4D,0#"),
                Position.KEY_DRIVER_UNIQUE_ID, "000000001A2B3C4D");

        // --- C0: Accept doc typo *SCOS header and lock request ---
        verifyAttribute(decoder, text(
                "*SCOS,OM,123456789123456,C0,1,0,00000000AABBCCDD,0#"),
                "rfidRequest", 1);
        verifyAttribute(decoder, text(
                "*SCOS,OM,123456789123456,C0,1,0,00000000AABBCCDD,0#"),
                Position.KEY_CARD, "00000000AABBCCDD");
        verifyAttribute(decoder, text(
                "*SCOS,OM,123456789123456,C0,1,0,00000000AABBCCDD,0#"),
                Position.KEY_DRIVER_UNIQUE_ID, "00000000AABBCCDD");

        // --- B0: Beacon validation (beacon found) ---
        verifyAttribute(decoder, text(
                "*CMDR,OM,123456789123456,000000000000,B0,1,10,12:34:56:78:90:AB,1578386704,50,0,0,0#"),
                "beaconDetected", 1);
        verifyAttribute(decoder, text(
                "*CMDR,OM,123456789123456,000000000000,B0,1,10,12:34:56:78:90:AB,1578386704,50,0,0,0#"),
                "beaconMac", "12:34:56:78:90:AB");
        verifyAttribute(decoder, text(
                "*CMDR,OM,123456789123456,000000000000,B0,1,10,12:34:56:78:90:AB,1578386704,50,0,0,0#"),
                "beaconBattery", 50);

        // --- B0: Beacon validation (no beacon found) ---
        verifyAttribute(decoder, text(
                "*CMDR,OM,123456789123456,000000000000,B0,0,0,,0,0,0,0,0#"),
                "beaconDetected", 0);

        // --- SC: Charging station information ---
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,SC,ChgStation001,0,3,2,25,372,360,500,1,30,370,1000,0,0,0#"),
                "chargingStatus", 2);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,SC,ChgStation001,0,3,2,25,372,360,500,1,30,370,1000,0,0,0#"),
                "chargingStationImei", "ChgStation001");
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,SC,ChgStation001,0,3,2,25,372,360,500,1,30,370,1000,0,0,0#"),
                Position.PREFIX_TEMP + 1, 25);
        verifyAttribute(decoder, text(
                "*SCOR,OM,123456789123456,SC,ChgStation001,0,3,2,25,372,360,500,1,30,370,1000,0,0,0#"),
                "chargingRxSwitch", true);

        // --- SC: All-zero example (basic valid response) ---
        verifyAttributes(decoder, text(
                "*SCOR,OM,123456789123456,SC,0,0,0,0,0,0,0,0,0,0,0,0,0#"));

        // --- Null: unknown command type ---
        verifyNull(decoder, text(
                "*SCOR,OM,123456789123456,XX,somedata#"));
    }

}
