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
import org.traccar.BaseProtocolEncoder;
import org.traccar.Protocol;
import org.traccar.model.Command;

public class OmniEbikeProtocolEncoder extends BaseProtocolEncoder {

    public OmniEbikeProtocolEncoder(Protocol protocol) {
        super(protocol);
    }

    private String formatCommand(Command command, String cmd) {
        return String.format("\u00ff\u00ff*SCOS,OM,%s,%s#\n", getUniqueId(command.getDeviceId()), cmd);
    }

    private void registerPendingCommand(Channel channel, long deviceId, String type) {
        if (channel != null) {
            OmniEbikeProtocolDecoder decoder = channel.pipeline().get(OmniEbikeProtocolDecoder.class);
            if (decoder != null) {
                decoder.setPendingCommand(deviceId, type);
            }
        }
    }

    @Override
    protected Object encodeCommand(Channel channel, Command command) {
        return switch (command.getType()) {
            case Command.TYPE_CUSTOM -> {
                String data = command.getString(Command.KEY_DATA);
                if (data != null && data.startsWith("R0")) {
                    registerPendingCommand(channel, command.getDeviceId(), command.getType());
                }
                yield formatCommand(command, data);
            }
            case Command.TYPE_ENGINE_STOP -> {
                registerPendingCommand(channel, command.getDeviceId(), command.getType());
                yield formatCommand(command, "R0,1,20,0," + System.currentTimeMillis() / 1000);
            }
            case Command.TYPE_ENGINE_RESUME -> {
                registerPendingCommand(channel, command.getDeviceId(), command.getType());
                yield formatCommand(command, "R0,0,20,0," + System.currentTimeMillis() / 1000);
            }
            case Command.TYPE_POSITION_SINGLE ->
                    formatCommand(command, "D0");
            case Command.TYPE_POSITION_PERIODIC ->
                    formatCommand(command, "D1," + command.getInteger(Command.KEY_FREQUENCY));
            case Command.TYPE_POSITION_STOP ->
                    formatCommand(command, "D1,0");
            case Command.TYPE_GET_DEVICE_STATUS ->
                    formatCommand(command, "S6");
            case Command.TYPE_GET_VERSION ->
                    formatCommand(command, "G0");
            case Command.TYPE_ALARM_ARM ->
                    formatCommand(command, "V0,81");
            case Command.TYPE_ALARM_DISARM ->
                    formatCommand(command, "V0,80");
            case Command.TYPE_REBOOT_DEVICE ->
                    formatCommand(command, "S1,2");
            default -> null;
        };
    }

}
