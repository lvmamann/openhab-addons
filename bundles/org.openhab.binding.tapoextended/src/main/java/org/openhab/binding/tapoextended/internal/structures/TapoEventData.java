/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.tapoextended.internal.structures;

import static org.openhab.binding.tapoextended.internal.constants.TapoThingConstants.*;
import static org.openhab.binding.tapoextended.internal.helpers.TapoUtils.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.tapoextended.internal.constants.TapoHubEventDetails;
import org.openhab.binding.tapoextended.internal.constants.TapoHubEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

/**
 * Tapo-Event-Monitor Structure Class
 *
 * @author Lucas de Mamann - Initial contribution
 */
@NonNullByDefault
public class TapoEventData {
    private final Logger logger = LoggerFactory.getLogger(TapoEventData.class);
    private TapoHubEvents eventName = TapoHubEvents.unknown;
    private TapoHubEventDetails eventDetails = TapoHubEventDetails.NONE;
    private Integer timestamp = 0;

    private JsonObject jsonObject = new JsonObject();

    /**
     * INIT
     */
    public TapoEventData() {
        setData();
    }

    /**
     * Init DeviceInfo with new Data;
     * 
     * @param jso JsonObject new Data
     */
    public TapoEventData(JsonObject jso) {
        setData(jso);
    }

    /**
     * Set Data (new JsonObject)
     * 
     * @param jso JsonObject new Data
     */
    public TapoEventData setData(JsonObject jso) {
        /* create empty jsonObject to set efault values if has no data */
        if (jso.has(DEVICE_PROPERTY_EVENT)) {
            this.jsonObject = jso;
        } else {
            jsonObject = new JsonObject();
        }
        setData();
        return this;
    }

    private void setData() {
        this.eventName = TapoHubEvents.valueOf(jsonObject.get(DEVICE_PROPERTY_EVENT).getAsString());
        if (jsonObject.has("params")) {
            JsonObject parameters = jsonObject.getAsJsonObject("params");
            if (parameters.has(DEVICE_PROPERTY_EVENT_ROTATION_DETAIL)) {
                Integer rotationDegrees = jsonObjectToInt(parameters, DEVICE_PROPERTY_EVENT_ROTATION_DETAIL);
                if (rotationDegrees < 0) {
                    this.eventDetails = TapoHubEventDetails.ANTICLOCKWISE;
                } else {
                    this.eventDetails = TapoHubEventDetails.CLOCKWISE;
                }
            }
        } else {
            this.eventDetails = TapoHubEventDetails.NONE;
        }
        this.timestamp = jsonObjectToInt(jsonObject, DEVICE_PROPERTY_TIMESTAMP);
    }

    /***********************************
     *
     * GET VALUES
     *
     ************************************/

    public TapoHubEvents getLastEvent() {
        return this.eventName;
    }

    public TapoHubEventDetails getLastEventDetail() {
        return this.eventDetails;
    }

    public Integer getLastEventTimestamp() {
        return this.timestamp;
    }
}
