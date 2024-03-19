package org.traccar.protocol;

import io.netty.channel.Channel;
import org.traccar.BaseProtocolEncoder;
import org.traccar.Protocol;
import org.traccar.model.Command;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;

public class OmniProtocolEncoder extends BaseProtocolEncoder {

    public OmniProtocolEncoder(Protocol protocol) {
        super(protocol);
    }

    private byte[] getSendOrder(String order) {
        return addByte(new byte[] { (byte) 0xFF, (byte) 0xFF }, order.getBytes());
    }

    private byte[] addByte(byte[] b1, byte[] b2) {
        byte[] b = new byte[b1.length + b2.length];
        System.arraycopy(b1, 0, b, 0, b1.length);
        System.arraycopy(b2, 0, b, b1.length, b2.length);
        return b;
    }

    private String formatCommand(Command command, String content) {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyMMddHHmmss");
        LocalDateTime now = LocalDateTime.now();
        return String.format("\uFFFF*CMDS,OM,%s,%s,%s#<LF>",
                getUniqueId(command.getDeviceId()),
                dtf.format(now),
                content);
    }

    @Override
    protected Object encodeCommand(Channel channel, Command command) {

        switch (command.getType()) {
            case Command.TYPE_CUSTOM:
                return formatCommand(command, command.getString(Command.KEY_DATA));
            case Command.TYPE_POSITION_SINGLE:
                return formatCommand(command, "D0");
            case Command.TYPE_POSITION_PERIODIC:
                return formatCommand(command, "D1," + command.getInteger(Command.KEY_FREQUENCY));
            case Command.TYPE_ENGINE_STOP:
            case Command.TYPE_ALARM_DISARM:
                if (channel != null) {
                    OmniProtocolDecoder decoder = channel.pipeline().get(OmniProtocolDecoder.class);
                    if (decoder != null) {
                        decoder.setPendingCommand(command.getType());
                    }
                }
                return formatCommand(command, "L0,0,1234," + System.currentTimeMillis() / 1000);
            default:
                return null;
        }
    }
}