/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
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
package org.traccar.handler.events;

import jakarta.inject.Inject;
import org.traccar.model.Event;
import org.traccar.model.Position;

public class CardEventHandler extends BaseEventHandler {

    @Inject
    public CardEventHandler() {
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        String card = position.getString(Position.KEY_CARD);
        if (card == null || card.isEmpty()) {
            return;
        }

        Event event = new Event(Event.TYPE_CARD_READ, position);
        event.set(Position.KEY_CARD, card);
        String driverUniqueId = position.getString(Position.KEY_DRIVER_UNIQUE_ID);
        event.set(Position.KEY_DRIVER_UNIQUE_ID,
                driverUniqueId != null && !driverUniqueId.isEmpty() ? driverUniqueId : card);
        if (position.hasAttribute("rfidRequest")) {
            event.set("rfidRequest", position.getInteger("rfidRequest"));
        }
        if (position.hasAttribute("rfidCardType")) {
            event.set("rfidCardType", position.getInteger("rfidCardType"));
        }
        callback.eventDetected(event);
    }

}
