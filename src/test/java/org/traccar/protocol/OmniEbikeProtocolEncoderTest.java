package org.traccar.protocol;

import org.junit.jupiter.api.Test;
import org.traccar.ProtocolTest;
import org.traccar.model.Command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OmniEbikeProtocolEncoderTest extends ProtocolTest {

    private static final String IMEI = "123456789012345";

    private String expected(String cmd) {
        return "\u00ff\u00ff*SCOS,OM," + IMEI + "," + cmd + "#\n";
    }

    @Test
    public void testEncodePositionSingle() throws Exception {
        var encoder = inject(new OmniEbikeProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_POSITION_SINGLE);

        assertEquals(expected("D0"), encoder.encodeCommand(null, command));
    }

    @Test
    public void testEncodePositionPeriodic() throws Exception {
        var encoder = inject(new OmniEbikeProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_POSITION_PERIODIC);
        command.set(Command.KEY_FREQUENCY, 60);

        assertEquals(expected("D1,60"), encoder.encodeCommand(null, command));
    }

    @Test
    public void testEncodePositionStop() throws Exception {
        var encoder = inject(new OmniEbikeProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_POSITION_STOP);

        assertEquals(expected("D1,0"), encoder.encodeCommand(null, command));
    }

    @Test
    public void testEncodeGetDeviceStatus() throws Exception {
        var encoder = inject(new OmniEbikeProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_GET_DEVICE_STATUS);

        assertEquals(expected("S6"), encoder.encodeCommand(null, command));
    }

    @Test
    public void testEncodeGetVersion() throws Exception {
        var encoder = inject(new OmniEbikeProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_GET_VERSION);

        assertEquals(expected("G0"), encoder.encodeCommand(null, command));
    }

    @Test
    public void testEncodeAlarmArm() throws Exception {
        var encoder = inject(new OmniEbikeProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_ALARM_ARM);

        assertEquals(expected("V0,81"), encoder.encodeCommand(null, command));
    }

    @Test
    public void testEncodeAlarmDisarm() throws Exception {
        var encoder = inject(new OmniEbikeProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_ALARM_DISARM);

        assertEquals(expected("V0,80"), encoder.encodeCommand(null, command));
    }

    @Test
    public void testEncodeRebootDevice() throws Exception {
        var encoder = inject(new OmniEbikeProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_REBOOT_DEVICE);

        assertEquals(expected("S1,2"), encoder.encodeCommand(null, command));
    }

    @Test
    public void testEncodeCustom() throws Exception {
        var encoder = inject(new OmniEbikeProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_CUSTOM);
        command.set(Command.KEY_DATA, "L5,1");

        assertEquals(expected("L5,1"), encoder.encodeCommand(null, command));
    }

    @Test
    public void testEncodeCustomK0() throws Exception {
        var encoder = inject(new OmniEbikeProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_CUSTOM);
        command.set(Command.KEY_DATA, "K0,0");

        assertEquals(expected("K0,0"), encoder.encodeCommand(null, command));
    }

    @Test
    public void testEncodeEngineStop() throws Exception {
        var encoder = inject(new OmniEbikeProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_ENGINE_STOP);

        long before = System.currentTimeMillis() / 1000;
        Object result = encoder.encodeCommand(null, command);
        long after = System.currentTimeMillis() / 1000;

        assertInstanceOf(String.class, result);
        String encoded = (String) result;

        assertTrue(encoded.startsWith("\u00ff\u00ff*SCOS,OM," + IMEI + ",R0,1,20,0,"),
                "Expected lock command prefix, got: " + encoded);
        assertTrue(encoded.endsWith("#\n"),
                "Expected command suffix, got: " + encoded);

        // Verify the embedded timestamp is a valid epoch second
        String[] parts = encoded.replace("#\n", "").split(",");
        long timestamp = Long.parseLong(parts[parts.length - 1]);
        assertTrue(timestamp >= before && timestamp <= after + 1,
                "Timestamp " + timestamp + " should be between " + before + " and " + (after + 1));
        assertTrue(timestamp > 1577836800L, "Timestamp should be after 2020-01-01");
    }

    @Test
    public void testEncodeEngineResume() throws Exception {
        var encoder = inject(new OmniEbikeProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_ENGINE_RESUME);

        long before = System.currentTimeMillis() / 1000;
        Object result = encoder.encodeCommand(null, command);
        long after = System.currentTimeMillis() / 1000;

        assertInstanceOf(String.class, result);
        String encoded = (String) result;

        assertTrue(encoded.startsWith("\u00ff\u00ff*SCOS,OM," + IMEI + ",R0,0,20,0,"),
                "Expected unlock command prefix, got: " + encoded);
        assertTrue(encoded.endsWith("#\n"),
                "Expected command suffix, got: " + encoded);

        String[] parts = encoded.replace("#\n", "").split(",");
        long timestamp = Long.parseLong(parts[parts.length - 1]);
        assertTrue(timestamp >= before && timestamp <= after + 1,
                "Timestamp " + timestamp + " should be between " + before + " and " + (after + 1));
    }

    @Test
    public void testEncodeUnknownReturnsNull() throws Exception {
        var encoder = inject(new OmniEbikeProtocolEncoder(null));

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_POWER_OFF);

        assertNull(encoder.encodeCommand(null, command));
    }

}
