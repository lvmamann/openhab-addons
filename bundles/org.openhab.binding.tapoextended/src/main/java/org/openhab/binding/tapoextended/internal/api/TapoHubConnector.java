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
package org.openhab.binding.tapoextended.internal.api;

import static org.openhab.binding.tapoextended.internal.constants.TapoBindingSettings.*;
import static org.openhab.binding.tapoextended.internal.constants.TapoErrorConstants.*;
import static org.openhab.binding.tapoextended.internal.constants.TapoThingConstants.*;
import static org.openhab.binding.tapoextended.internal.helpers.TapoUtils.*;

import java.net.InetAddress;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.api.ContentResponse;
import org.openhab.binding.tapoextended.internal.device.TapoHubDevice;
import org.openhab.binding.tapoextended.internal.device.TapoHubHandler;
import org.openhab.binding.tapoextended.internal.helpers.ChildControlPayloadBuilder;
import org.openhab.binding.tapoextended.internal.helpers.PayloadBuilder;
import org.openhab.binding.tapoextended.internal.helpers.TapoErrorHandler;
import org.openhab.binding.tapoextended.internal.structures.TapoDeviceInfo;
import org.openhab.binding.tapoextended.internal.structures.TapoEventData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Handler class for TAPO Smart Home device connections.
 * This class uses asynchronous HttpClient-Requests
 *
 * @author Christian Wild - Initial contribution
 */
@NonNullByDefault
public class TapoHubConnector extends TapoHubHttpApi {
    private final Logger logger = LoggerFactory.getLogger(TapoHubConnector.class);
    private @Nullable final TapoHubDevice device;
    private TapoDeviceInfo deviceInfo;
    private Gson gson;
    private long lastLogin = 0L;
    private long lastQuery = 0L;

    /**
     * INIT CLASS
     *
     * @param config TapoExtendedConfiguration class
     */
    public TapoHubConnector(TapoHubHandler bridgeThingHandler) {
        super(bridgeThingHandler);
        this.gson = new Gson();
        device = null;
        this.deviceInfo = new TapoDeviceInfo();
    }

    public TapoHubConnector(TapoHubDevice device, TapoHubHandler bridgeThingHandler) {
        super(bridgeThingHandler);
        this.gson = new Gson();
        this.device = device;
        this.deviceInfo = new TapoDeviceInfo();
    }

    /***********************************
     *
     * LOGIN FUNCTIONS
     *
     ************************************/
    /**
     * login
     *
     * @return true if success
     */
    public boolean login() {
        if (this.pingDevice()) {
            logger.trace("({}) sending login to url '{}'", uid, deviceURL);

            long now = System.currentTimeMillis();
            if (now > this.lastLogin + TAPO_LOGIN_MIN_GAP_MS) {
                this.lastLogin = now;
                unsetToken();
                unsetCookie();

                /* create ssl-handschake (cookie) */
                String cookie = createHandshake();
                if (!cookie.isBlank()) {
                    setCookie(cookie);
                    String token = queryToken();
                    setToken(token);
                }
            } else {
                logger.trace("({}) not done cause of min_gap '{}'", uid, TAPO_LOGIN_MIN_GAP_MS);
            }
            return this.loggedIn();
        } else {
            logger.debug("({}) no ping while login '{}'", uid, this.ipAddress);
            handleError(new TapoErrorHandler(ERR_DEVICE_OFFLINE, "no ping while login"));
            return false;
        }
    }

    /***********************************
     *
     * HANDLE RESPONSES
     *
     ************************************/

    /**
     * handle error
     * 
     * @param te TapoErrorHandler
     */
    @Override
    protected void handleError(TapoErrorHandler tapoError) {
        this.bridge.setError(tapoError);
    }

    /**
     * get Json from response
     * 
     * @param responseBody
     * @return JsonObject with result
     */
    private JsonObject getJsonFromResponse(String responseBody) {
        JsonObject jsonObject = gson.fromJson(responseBody, JsonObject.class);
        /* get errocode (0=success) */
        if (jsonObject != null) {
            Integer errorCode = jsonObjectToInt(jsonObject, "error_code");
            if (errorCode == 0) {
                /* decrypt response */
                jsonObject = gson.fromJson(responseBody, JsonObject.class);
                logger.trace("({}) received result: {}", uid, responseBody);
                if (jsonObject != null) {
                    /* return result if set / else request was successfull */
                    if (jsonObject.has("result")) {
                        return jsonObject.getAsJsonObject("result");
                    } else {
                        return jsonObject;
                    }
                }
            } else {
                /* return errorcode from device */
                TapoErrorHandler te = new TapoErrorHandler(errorCode, "device answers with errorcode");
                logger.debug("({}) device answers with errorcode {} - {}", uid, errorCode, te.getMessage());
                handleError(te);
                return jsonObject;
            }
        }
        logger.debug("({}) sendPayload exception {}", uid, responseBody);
        handleError(new TapoErrorHandler(ERR_HTTP_RESPONSE));
        return new JsonObject();
    }

    /***********************************
     *
     * GET RESULTS
     *
     ************************************/

    /**
     * Check if device is online
     * 
     * @return true if device is online
     */
    public Boolean isOnline() {
        return isOnline(false);
    }

    /**
     * Check if device is online
     * 
     * @param raiseError if true
     * @return true if device is online
     */
    public Boolean isOnline(Boolean raiseError) {
        if (pingDevice()) {
            return true;
        } else {
            logger.trace("({})  device is offline (no ping)", uid);
            if (raiseError) {
                handleError(new TapoErrorHandler(ERR_DEVICE_OFFLINE));
            }
            logout();
            return false;
        }
    }

    /**
     * IP-Adress
     * 
     * @return String ipAdress
     */
    public String getIP() {
        return this.ipAddress;
    }

    /**
     * PING IP Adress
     * 
     * @return true if ping successfull
     */
    public Boolean pingDevice() {
        try {
            InetAddress address = InetAddress.getByName(this.ipAddress);
            return address.isReachable(TAPO_PING_TIMEOUT_MS);
        } catch (Exception e) {
            logger.debug("({}) InetAdress throws: {}", uid, e.getMessage());
            return false;
        }
    }

    /**
     * Query Info from Device adn refresh deviceInfo
     */
    public void queryChildInfo() {
        queryChildInfo(false);
    }

    /**
     * Query Info from Child Device and refresh deviceInfo
     *
     * @param ignoreGap ignore gap to last query. query anyway
     */
    public void queryChildInfo(boolean ignoreGap) {
        String deviceId = this.device.getDeviceId();

        logger.trace("({}) DeviceConnetor_queryInfo for device_id: {} from '{}'", uid, deviceId, deviceURL);
        long now = System.currentTimeMillis();
        if (ignoreGap || now > this.lastQuery + TAPO_SEND_MIN_GAP_MS) {
            this.lastQuery = now;

            /* create payload */
            ChildControlPayloadBuilder plBuilder = new ChildControlPayloadBuilder();
            plBuilder.method = DEVICE_CMD_GETINFO;
            plBuilder.childDeviceId = deviceId;
            String payload = plBuilder.getPayload();

            ContentResponse response = sendSecurePasstrhroug(payload);
            if (response != null) {
                String responseBody = decryptResponse(response.getContentAsString());
                getChildInfoFromResponse(responseBody);
            }
        } else {
            logger.debug("({}) command not sent becauso of min_gap: {}", uid, now + " <- " + lastQuery);
        }
    }

    protected void getChildInfoFromResponse(String responseBody) {
        JsonObject jsnResult = getJsonFromResponse(responseBody);
        JsonObject childResult = jsnResult.getAsJsonObject("responseData").getAsJsonObject("result");
        if (childResult.has(DEVICE_PROPERTY_ID)) {
            this.deviceInfo = new TapoDeviceInfo(childResult);
            this.device.setDeviceInfo(deviceInfo);
        } else {
            this.deviceInfo = new TapoDeviceInfo();
            this.device.handleConnectionState();
        }
        this.device.responsePasstrough(responseBody);
    }

    /**
     * Query status from Device and refresh deviceEvent
     */
    public void queryChildStatus() {
        queryChildStatus(false);
    }

    /**
     * Query status from Child Device and refresh deviceEvent
     *
     * @param ignoreGap ignore gap to last query. query anyway
     */
    public void queryChildStatus(boolean ignoreGap) {
        String deviceId = this.device.getDeviceId();

        logger.trace("({}) DeviceConnetor_queryStatus for device_id: {} from '{}'", uid, deviceId, deviceURL);
        long now = System.currentTimeMillis();
        if (ignoreGap || now > this.lastQuery + TAPO_SEND_MIN_GAP_MS) {
            this.lastQuery = now;

            /* create payload */
            ChildControlPayloadBuilder plBuilder = new ChildControlPayloadBuilder();
            plBuilder.method = DEVICE_CMD_GETTRIGGERLOGS;
            plBuilder.addParameter("page_size", 1);
            plBuilder.addParameter("start_id", 0);
            plBuilder.addParameter("device_id", (deviceId != null) ? deviceId : "");
            plBuilder.childDeviceId = deviceId;
            String payload = plBuilder.getPayload();

            ContentResponse response = sendSecurePasstrhroug(payload);
            if (response != null) {
                String responseBody = decryptResponse(response.getContentAsString());
                getChildStatusFromResponse(responseBody);
            }
        } else {
            logger.debug("({}) command not sent becauso of min_gap: {}", uid, now + " <- " + lastQuery);
        }
    }

    protected void getChildStatusFromResponse(String responseBody) {
        JsonObject jsnResult = getJsonFromResponse(responseBody);
        JsonObject childResult = jsnResult.getAsJsonObject("responseData").getAsJsonObject("result");
        if (childResult.has("logs")) {
            JsonArray logs = childResult.getAsJsonArray("logs");
            JsonObject eventObject = logs.get(0).getAsJsonObject();
            this.device.setEventData(new TapoEventData(eventObject));
        } else {
            this.device.handleConnectionState();
        }
        this.device.responsePasstrough(responseBody);
    }

    /**
     *
     * @return JsonArray with deviceList
     */
    public JsonArray getDeviceList() {
        /* create payload */
        PayloadBuilder plBuilder = new PayloadBuilder();
        plBuilder.method = DEVICE_CMD_GETCHILDREN;
        String payload = plBuilder.getPayload();

        ContentResponse response = sendSecurePasstrhroug(payload);

        if (response != null) {
            return getDeviceListFromResponse(response);
        }
        return new JsonArray();
    }

    /**
     * get DeviceList from Contenresponse
     *
     * @param response
     * @return
     */
    private JsonArray getDeviceListFromResponse(ContentResponse response) {
        /* work with response */
        if (response.getStatus() == 200) {
            String rBody = decryptResponse(response.getContentAsString());
            JsonObject jsonObject = gson.fromJson(rBody, JsonObject.class);

            if (jsonObject != null) {
                /* get errocode (0=success) */
                Integer errorCode = jsonObject.get("error_code").getAsInt();
                if (errorCode == 0) {
                    JsonObject result = jsonObject.getAsJsonObject("result");
                    return result.getAsJsonArray("child_device_list");
                } else {
                    /* return errorcode from device */
                    handleError(new TapoErrorHandler(errorCode, "device answers with errorcode"));
                    logger.trace("cloud returns error: '{}'", rBody);
                }
            } else {
                logger.trace("enexpected json-response '{}'", rBody);
            }
        } else {
            logger.trace("response error '{}'", response.getContentAsString());
        }
        return new JsonArray();
    }

    @Nullable
    protected ContentResponse sendSecurePasstrhroug(String payload) {
        /* encrypt payload */
        logger.trace("({}) encrypting payload '{}'", uid, payload);
        String encryptedPayload = encryptPayload(payload);

        /* create secured payload */
        PayloadBuilder plBuilder = new PayloadBuilder();
        plBuilder.method = "securePassthrough";
        plBuilder.addParameter("request", encryptedPayload);
        String securePassthroughPayload = plBuilder.getPayload();

        return sendRequest(deviceURL, securePassthroughPayload);
    }
}
