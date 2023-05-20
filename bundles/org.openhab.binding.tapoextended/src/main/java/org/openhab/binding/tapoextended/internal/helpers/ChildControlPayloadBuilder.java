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
package org.openhab.binding.tapoextended.internal.helpers;

import static org.openhab.binding.tapoextended.internal.constants.TapoBindingSettings.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * PAYLOAD BUILDER
 * Generates payload for TapoHttp request
 *
 * @author Christian Wild - Initial contribution
 */
@NonNullByDefault
public class ChildControlPayloadBuilder {
    public String method = "";
    public @Nullable String childDeviceId = "";
    private JsonObject parameters = new JsonObject();

    /**
     * Set Command
     *
     * @param command command (method) to send
     */
    public void setCommand(String command) {
        this.method = command;
    }

    /**
     * Set Child Device Id
     *
     * @param deviceId device ID of the child device
     */
    public void setChildDeviceId(String deviceId) {
        this.childDeviceId = deviceId;
    }

    /**
     * Add Parameter
     *
     * @param name parameter name
     * @param value parameter value (typeOf Bool,Number or String)
     */
    public void addParameter(String name, Object value) {
        if (value instanceof Boolean) {
            this.parameters.addProperty(name, (Boolean) value);
        } else if (value instanceof Number) {
            this.parameters.addProperty(name, (Number) value);
        } else {
            this.parameters.addProperty(name, value.toString());
        }
    }

    /**
     * Get JSON Payload (STRING)
     *
     * @return String JSON-Payload
     */
    public String getPayload() {
        Gson gson = new Gson();
        JsonObject payload = getJsonPayload();
        return gson.toJson(payload);
    }

    /**
     * Get JSON Payload (JSON-Object)
     *
     * @return JsonObject JSON-Payload
     */
    public JsonObject getJsonPayload() {
        JsonObject payload = new JsonObject();
        JsonObject payloadParameters = new JsonObject();
        JsonObject childPayload = new JsonObject();
        long timeMils = System.currentTimeMillis();// * 1000;

        childPayload.addProperty("method", this.method);
        if (this.parameters.size() > 0) {
            childPayload.add("params", this.parameters);
        }

        payloadParameters.add("requestData", childPayload);
        payloadParameters.addProperty("device_id", this.childDeviceId);

        payload.addProperty("method", DEVICE_CMD_CONTROLCHILD);
        payload.add("params", payloadParameters);
        payload.addProperty("requestTimeMils", timeMils);

        return payload;
    }

    /**
     * Flush Parameters
     * remove all parameters
     */
    public void flushParameters(String command) {
        this.parameters = new JsonObject();
    }
}
