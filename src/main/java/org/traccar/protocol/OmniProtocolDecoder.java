package org.traccar.protocol;

import java.util.Arrays;
import java.util.Date;

import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.BaseProtocolDecoder;
import org.traccar.NetworkMessage;
import org.traccar.Protocol;
import org.traccar.helper.DateBuilder;
import org.traccar.model.Command;
import org.traccar.model.Position;
import org.traccar.session.DeviceSession;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.net.SocketAddress;

public class OmniProtocolDecoder extends BaseProtocolDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(OmniProtocolDecoder.class);

    private String pendingCommand;

    public void setPendingCommand(String pendingCommand) {
        this.pendingCommand = pendingCommand;
    }

    public OmniProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    public static String joinWithComma(String[] array) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < array.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(array[i]);
        }
        return sb.toString();
    }

    public static String[] extractMessage(String input) {
        int startIndex = input.indexOf('*');
        int endIndex = input.indexOf('#');
        if (startIndex >= 0 && endIndex > 0) {
            input = input.substring(startIndex + 1, endIndex);
        }
        return input.split(","); // Split the string into array by ','
    }

    public static StringBuffer formatCommand(String[] values, String command) {
        String[] requiredData = Arrays.copyOfRange(values, 1, 3);
        String deviceData = joinWithComma(requiredData);
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyMMddHHmmss");
        LocalDateTime now = LocalDateTime.now();
        String reply = String.format("ÿÿ*CMDS,%s,%s,%s#<LF>\n", deviceData, dtf.format(now), command);
        return new StringBuffer(reply);
    }

    @Override
    protected Object decode(Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {
        /*
         * sample of valid sentences
         * ÿ*CMDR,OM,123456789123456,000000000000,L1,1234,1675415421,1#
         * CMDR,OM,123456789123456,200318123020,Q0,412,80#<LF>
         * CMDR,OM,123456789123456,200318123020,H0,0,412,28,80,0#<LF>
         */
        String sentence = (String) msg;
        LOGGER.info("Decoded message: " + sentence);
        String[] values = extractMessage(sentence);
        if (values.length <= 4) {
            return null;
        }
        int cursor = 5;
        String imei = values[2];
        String type = values[4];

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, imei);
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());
        getLastLocation(position, null);

        switch (type) {
            case "L0": /* Unlock Command 
                    Unlock result return 0->success[unlocked] 1->fail[still locked]
                    example *CMDR,OM,862205059172132,000000000000,L0,0,1234,1711146100#
                */ 
                boolean isSuccess = Integer.parseInt(values[cursor++]) == 0;
                position.set(Position.KEY_LOCK, !isSuccess);
                position.set(Position.KEY_DRIVER_UNIQUE_ID, values[cursor++]);
                position.set(Position.KEY_ALARM, isSuccess ? Position.ALARM_UNLOCK : Position.ALARM_LOCK);
                channel.write(new NetworkMessage(formatCommand(values, "Re,L0"), remoteAddress));
                break;
            case "L1": // Locked Command，send when the lock is locked
                position.set(Position.KEY_DRIVER_UNIQUE_ID, values[cursor++]);
                cursor++; // skip the timestamp
                position.set(Position.KEY_DRIVING_TIME, values[cursor++]);
                position.set(Position.KEY_LOCK, true);
                position.set(Position.KEY_ALARM, Position.ALARM_LOCK);
                channel.write(new NetworkMessage(formatCommand(values, "Re,L1"), remoteAddress));
                break;
            case "L3": // electric vehicle switch control
                position.set(Position.KEY_IGNITION, Integer.parseInt(values[cursor++]) > 1);
                break;
            case "L5": // TODO: external lock device control
                break;
            case "L6": // TODO: external lock device control, battery lock
                break;
            case "D0": /*  
                Reporting device location
                CMDR,OM,862205059172132,000000000000,D0,1,151644.000,A,0640.2063,S,03912.6205,E,10,0.83,,3.0,M,A#
               */
                cursor = 6;
                String timeString = values[cursor++]; //
                if (timeString.isEmpty()){
                    break;
                }
                // extra and set time
                int hour = Integer.parseInt(timeString.substring(0, 2));
                int minute = Integer.parseInt(timeString.substring(2, 4));
                int second = Integer.parseInt(timeString.substring(4, 6));
                // Create DateBuilder object
                DateBuilder dateBuilder = new DateBuilder(new Date()).setTime(hour, minute, second);
                position.setTime(dateBuilder.getDate());
                String isValid = values[cursor++];
                String latS = values[cursor++];
                boolean south = values[cursor++].equals("S");
                String lonS = values[cursor++];
                boolean west = values[cursor++].equals("W");
                String numSetelliteS = values[cursor++];
                String keyHoopS = values[cursor++];
                String altituteS = values[cursor++];

                if (!isValid.isEmpty()) {
                    position.setValid(isValid.equals("A"));
                }
                if (!latS.isEmpty() && !lonS.isEmpty()) {
                    double lat = Double.parseDouble(latS);
                    double lon = Double.parseDouble(lonS);

                    if (lat > 90 || lon > 180) {
                        int lonDegrees = (int) (lon * 0.01);
                        lon = (lon - lonDegrees * 100) / 60.0;
                        lon += lonDegrees;

                        int latDegrees = (int) (lat * 0.01);
                        lat = (lat - latDegrees * 100) / 60.0;
                        lat += latDegrees;
                    }
                    position.setLongitude(west ? -lon : lon);
                    position.setLatitude(south ? -lat : lat);
                }
                if (!numSetelliteS.isEmpty()) {
                    position.set(Position.KEY_SATELLITES, Integer.parseInt(numSetelliteS));
                }
                if (!keyHoopS.isEmpty()) {
                    position.set(Position.KEY_HDOP, Double.parseDouble(keyHoopS));
                }
                if (!altituteS.isEmpty()) {
                    position.setAltitude(Double.parseDouble(altituteS));
                }
                break;
            case "Q0": // battery level update
                position.set(Position.KEY_BATTERY, Integer.parseInt(values[cursor++]) * 0.01);
                position.set(Position.KEY_BATTERY_LEVEL, Integer.parseInt(values[cursor++]));
                break;
            case "H0":
                position.set(Position.KEY_LOCK, Integer.parseInt(values[cursor++]) == 1);
                position.set(Position.KEY_BATTERY, Integer.parseInt(values[cursor++]) * 0.01);
                position.set(Position.KEY_RSSI, Integer.parseInt(values[cursor++]));
                position.set(Position.KEY_STATUS, Integer.parseInt(values[cursor++]));
                position.set(Position.KEY_BATTERY_LEVEL, Integer.parseInt(values[cursor++]));
                break;
            case "S5":
                position.set(Position.KEY_BATTERY, Integer.parseInt(values[cursor++]) * 0.01);
                position.set(Position.KEY_RSSI, Integer.parseInt(values[cursor++]));
                position.set(Position.KEY_SATELLITES, Integer.parseInt(values[cursor++]));
                
                boolean isLocked = Integer.parseInt(values[cursor++]) == 1;
                
                position.set(Position.KEY_LOCK, isLocked);
                position.set(Position.KEY_ALARM, isLocked ? "locked" : "unlocked");
                // for fault messages
                switch (Integer.parseInt(values[cursor++])) {
                    case 1:
                        position.set(Position.KEY_ALARM, Position.ALARM_FATIGUE_DRIVING);
                        break;
                    case 2:
                        position.set(Position.KEY_ALARM, Position.ALARM_FALL_DOWN);
                        break;
                    case 3:
                        position.set(Position.KEY_ALARM, Position.ALARM_TAMPERING);
                        break;
                    case 6:
                    case 7:
                        position.set(Position.KEY_ALARM, Position.ALARM_MOVEMENT);
                        break;
                    default:
                        break;
                }
                position.set(Position.KEY_BATTERY_LEVEL, Integer.parseInt(values[cursor++]));
                break;
            case "W0": // (Alarm instruction) eg *CMDR,OM,123456789123456,200318123020,W0,1#<LF>
                /*
                 * 1: Illegal movement alarm
                 * 2: Falling down alarm
                 * 3: Illegal dismantling alarm
                 * 6: Clear the alarm for falling down (the electric car is lifted up)
                 * 7. Clear the alarm for illegal dismantling (connection recovery)
                 */
                switch (Integer.parseInt(values[cursor++])) {
                    case 1:
                        position.set(Position.KEY_ALARM, Position.ALARM_FATIGUE_DRIVING);
                        break;
                    case 2:
                        position.set(Position.KEY_ALARM, Position.ALARM_FALL_DOWN);
                        break;
                    case 3:
                        position.set(Position.KEY_ALARM, Position.ALARM_TAMPERING);
                        break;
                    case 6:
                    case 7:
                        position.set(Position.KEY_ALARM, Position.ALARM_MOVEMENT);
                        break;
                    default:
                        break;
                }
                channel.write(new NetworkMessage(formatCommand(values, "Re,W0"), remoteAddress));
                break;
            case "D1":
            case "S4":
            case "S6":
            case "S7":
            case "V0":
            case "G0":
            case "K0":
            case "I0":
            case "M0":
                position.set(Position.KEY_RESULT, sentence);
                break;
            default:
                break;
        }
        return !position.getAttributes().isEmpty() ? position : null;
    }
}
