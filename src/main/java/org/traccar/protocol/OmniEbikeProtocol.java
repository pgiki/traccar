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

import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import org.traccar.BaseProtocol;
import org.traccar.PipelineBuilder;
import org.traccar.TrackerServer;
import org.traccar.config.Config;
import org.traccar.model.Command;

import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;

public class OmniEbikeProtocol extends BaseProtocol {

    @Inject
    public OmniEbikeProtocol(Config config) {
        setSupportedDataCommands(
                Command.TYPE_CUSTOM,
                Command.TYPE_ENGINE_STOP,
                Command.TYPE_ENGINE_RESUME,
                Command.TYPE_POSITION_SINGLE,
                Command.TYPE_POSITION_PERIODIC,
                Command.TYPE_POSITION_STOP,
                Command.TYPE_GET_DEVICE_STATUS,
                Command.TYPE_GET_VERSION,
                Command.TYPE_ALARM_ARM,
                Command.TYPE_ALARM_DISARM,
                Command.TYPE_REBOOT_DEVICE);
        addServer(new TrackerServer(config, getName(), false) {
            @Override
            protected void addProtocolHandlers(PipelineBuilder pipeline, Config config) {
                pipeline.addLast(new LineBasedFrameDecoder(4096));
                pipeline.addLast(new StringEncoder(StandardCharsets.ISO_8859_1));
                pipeline.addLast(new StringDecoder(StandardCharsets.ISO_8859_1));
                pipeline.addLast(new OmniEbikeProtocolEncoder(OmniEbikeProtocol.this));
                pipeline.addLast(new OmniEbikeProtocolDecoder(OmniEbikeProtocol.this));
            }
        });
    }

}
