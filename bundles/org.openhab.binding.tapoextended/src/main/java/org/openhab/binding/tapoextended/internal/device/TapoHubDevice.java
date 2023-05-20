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
package org.openhab.binding.tapoextended.internal.device;

import static org.openhab.binding.tapoextended.internal.constants.TapoBindingSettings.*;
import static org.openhab.binding.tapoextended.internal.constants.TapoErrorConstants.*;
import static org.openhab.binding.tapoextended.internal.constants.TapoThingConstants.*;
import static org.openhab.binding.tapoextended.internal.helpers.TapoUtils.*;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.tapoextended.internal.api.TapoHubConnector;
import org.openhab.binding.tapoextended.internal.helpers.TapoErrorHandler;
import org.openhab.binding.tapoextended.internal.structures.TapoDeviceConfiguration;
import org.openhab.binding.tapoextended.internal.structures.TapoDeviceInfo;
import org.openhab.binding.tapoextended.internal.structures.TapoEventData;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.BridgeHandler;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract class as base for TAPO-Device device implementations.
 *
 * @author Lucas de Mamann - Initial contribution
 */
@NonNullByDefault
public abstract class TapoHubDevice extends BaseThingHandler {
    private final Logger logger = LoggerFactory.getLogger(TapoHubDevice.class);
    protected final TapoErrorHandler deviceError = new TapoErrorHandler();
    protected final String uid;
    protected @Nullable final String deviceId;
    protected TapoDeviceConfiguration config = new TapoDeviceConfiguration();
    protected TapoDeviceInfo deviceInfo;
    protected @Nullable ScheduledFuture<?> startupJob;
    protected @Nullable ScheduledFuture<?> pollingJob;
    protected @NonNullByDefault({}) TapoHubConnector connector;
    protected @NonNullByDefault({}) TapoHubHandler bridge;

    /**
     * Constructor
     *
     * @param thing Thing object representing device
     */
    protected TapoHubDevice(Thing thing) {
        super(thing);
        this.deviceInfo = new TapoDeviceInfo();
        this.uid = getThing().getUID().getAsString();
        this.deviceId = getThing().getProperties().get("deviceId");
    }

    /***********************************
     *
     * INIT AND SETTINGS
     *
     ************************************/

    /**
     * INITIALIZE DEVICE
     */
    @Override
    public void initialize() {
        try {
            this.config = getConfigAs(TapoDeviceConfiguration.class);
            Bridge bridgeThing = getBridge();
            if (bridgeThing != null) {
                BridgeHandler bridgeHandler = bridgeThing.getHandler();
                if (bridgeHandler != null) {
                    this.bridge = (TapoHubHandler) bridgeHandler;
                    this.connector = new TapoHubConnector(this, bridge);
                }
            }
        } catch (Exception e) {
            logger.debug("({}) configuration error : {}", uid, e.getMessage());
        }
        TapoErrorHandler configError = checkSettings();
        if (!configError.hasError()) {
            activateDevice();
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, configError.getMessage());
        }

        // get initial status
        queryDeviceInfo();
        queryDeviceStatus();
    }

    /**
     * DISPOSE
     */
    @Override
    public void dispose() {
        try {
            stopScheduler(this.startupJob);
            stopScheduler(this.pollingJob);
            connector.logout();
        } catch (Exception e) {
            // handle exception
        }
        super.dispose();
    }

    /**
     * ACTIVATE DEVICE
     */
    private void activateDevice() {
        // set the thing status to UNKNOWN temporarily and let the background task decide for the real status.
        updateStatus(ThingStatus.UNKNOWN);

        // background initialization (delay it a little bit):
        this.startupJob = scheduler.schedule(this::delayedStartUp, 2000, TimeUnit.MILLISECONDS);
    }

    /**
     * CHECK SETTINGS
     * 
     * @return TapoErrorHandler with configuration-errors
     */
    protected TapoErrorHandler checkSettings() {
        TapoErrorHandler configErr = new TapoErrorHandler();

        /* check bridge */
        if (bridge == null || !(bridge instanceof TapoHubHandler)) {
            configErr.raiseError(ERR_NO_BRIDGE);
            return configErr;
        }
        /* check credentials */
        if (!bridge.getCredentials().areSet()) {
            configErr.raiseError(ERR_CONF_CREDENTIALS);
            return configErr;
        }
        return configErr;
    }

    /**
     * Checks if the response object contains errors and if so throws an {@link IOException} when an error code was set.
     *
     * @throws IOException if an error code was set in the response object
     */
    protected void checkErrors() throws IOException {
        final Integer errorCode = deviceError.getCode();

        if (errorCode != 0) {
            throw new IOException("Error (" + errorCode + "): " + deviceError.getMessage());
        }
    }

    /***********************************
     *
     * SCHEDULER
     *
     ************************************/
    /**
     * delayed OneTime StartupJob
     */
    private void delayedStartUp() {
        connect();
        startPollingScheduler();
    }

    /**
     * Start scheduler
     */
    protected void startPollingScheduler() {
        int pollingInterval = this.config.pollingInterval;
        TimeUnit timeUnit = TimeUnit.SECONDS;

        if (pollingInterval > 0) {
            logger.debug("({}) startScheduler: create job with interval : {} {}", uid, pollingInterval, timeUnit);
            this.pollingJob = scheduler.scheduleWithFixedDelay(this::pollingSchedulerAction, pollingInterval,
                    pollingInterval, timeUnit);
        } else {
            logger.debug("({}) scheduler disabled with config '0'", uid);
            stopScheduler(this.pollingJob);
        }
    }

    /**
     * Stop scheduler
     * 
     * @param scheduler ScheduledFeature<?> which schould be stopped
     */
    protected void stopScheduler(@Nullable ScheduledFuture<?> scheduler) {
        if (scheduler != null) {
            scheduler.cancel(true);
            scheduler = null;
        }
    }

    /**
     * Scheduler Action
     */
    protected void pollingSchedulerAction() {
        logger.trace("({}) schedulerAction", uid);
        queryDeviceStatus();
    }

    /***********************************
     *
     * ERROR HANDLER
     *
     ************************************/
    /**
     * return device Error
     * 
     * @return
     */
    public TapoErrorHandler getError() {
        return this.deviceError;
    }

    /**
     * set device error
     * 
     * @param tapoError TapoErrorHandler-Object
     */
    public void setError(TapoErrorHandler tapoError) {
        this.deviceError.set(tapoError);
        handleConnectionState();
    }

    /***********************************
     *
     * THING
     *
     ************************************/

    /***
     * Check if ThingType is model
     * 
     * @param model
     * @return
     */
    protected Boolean isThingModel(String model) {
        try {
            ThingTypeUID foundType = new ThingTypeUID(BINDING_ID, model);
            ThingTypeUID expectedType = getThing().getThingTypeUID();
            return expectedType.equals(foundType);
        } catch (Exception e) {
            logger.warn("({}) verify thing model throws : {}", uid, e.getMessage());
            return false;
        }
    }

    /**
     * CHECK IF RECEIVED DATA ARE FROM THE EXPECTED DEVICE
     * Compare MAC-Adress
     * 
     * @param deviceInfo
     * @return true if is the expected device
     */
    protected Boolean isExpectedThing(TapoDeviceInfo deviceInfo) {
        try {
            String expectedThingUID = getThing().getProperties().get(DEVICE_REPRESENTATION_PROPERTY);
            String foundThingUID = deviceInfo.getRepresentationProperty();
            String foundModel = deviceInfo.getModel();
            if (expectedThingUID == null || expectedThingUID.isBlank()) {
                return isThingModel(foundModel);
            }
            /* sometimes received mac was with and sometimes without "-" from device */
            expectedThingUID = unformatMac(expectedThingUID);
            foundThingUID = unformatMac(foundThingUID);
            return expectedThingUID.equals(foundThingUID);
        } catch (Exception e) {
            logger.warn("({}) verify thing model throws : {}", uid, e.getMessage());
            return false;
        }
    }

    /**
     * Return ThingUID
     */
    public ThingUID getThingUID() {
        return getThing().getUID();
    }

    /***********************************
     *
     * DEVICE PROPERTIES
     *
     ************************************/

    /**
     * query device Properties
     */
    public void queryDeviceInfo() {
        queryDeviceInfo(false);
    }

    /**
     * query device Properties
     * 
     * @param ignoreGap ignore gap to last query. query anyway (force)
     */
    public void queryDeviceInfo(boolean ignoreGap) {
        deviceError.reset();
        if (connector.loggedIn()) {
            connector.queryChildInfo(ignoreGap);
        } else {
            logger.debug("({}) tried to query DeviceInfo but not loggedIn", uid);
            connect();
        }
    }

    /**
     * query device status
     */
    public void queryDeviceStatus() {
        queryDeviceStatus(false);
    }

    /**
     * query device status
     *
     * @param ignoreGap ignore gap to last query. query anyway (force)
     */
    public void queryDeviceStatus(boolean ignoreGap) {
        deviceError.reset();
        if (connector.loggedIn()) {
            connector.queryChildStatus(ignoreGap);
        } else {
            logger.debug("({}) tried to query DeviceStatus but not loggedIn", uid);
            connect();
        }
    }

    /**
     * SET DEVICE INFOs to device
     * 
     * @param deviceInfo
     */
    public void setDeviceInfo(TapoDeviceInfo deviceInfo) {
        this.deviceInfo = deviceInfo;
        if (isExpectedThing(deviceInfo)) {
            devicePropertiesChanged(deviceInfo);
            handleConnectionState();
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "found type:'" + deviceInfo.getModel() + "' with mac:'" + deviceInfo.getRepresentationProperty()
                            + "'. Check IP-Address");
        }
    }

    /**
     * Set Device EventData to device
     * 
     * @param eventData
     */
    public void setEventData(TapoEventData eventData) {
        publishState(getChannelID(CHANNEL_GROUP_BUTTON, CHANNEL_EVENT),
                getStringType(eventData.getLastEvent().toString()));
        publishState(getChannelID(CHANNEL_GROUP_BUTTON, CHANNEL_EVENT_DETAILS),
                getStringType(eventData.getLastEventDetail().toString()));
        publishState(getChannelID(CHANNEL_GROUP_BUTTON, CHANNEL_TIMESTAMP),
                getDecimalType(eventData.getLastEventTimestamp()));
    }

    /**
     * Handle full responsebody received from connector
     * 
     * @param responseBody
     */
    public void responsePasstrough(String responseBody) {
    }

    /**
     * UPDATE PROPERTIES
     * 
     * If only one property must be changed, there is also a convenient method
     * updateProperty(String name, String value).
     * 
     * @param TapoDeviceInfo
     */
    protected void devicePropertiesChanged(TapoDeviceInfo deviceInfo) {
        /* device properties */
        Map<String, String> properties = editProperties();
        properties.put(Thing.PROPERTY_MAC_ADDRESS, deviceInfo.getMAC());
        properties.put(Thing.PROPERTY_FIRMWARE_VERSION, deviceInfo.getFirmwareVersion());
        properties.put(Thing.PROPERTY_HARDWARE_VERSION, deviceInfo.getHardwareVersion());
        properties.put(Thing.PROPERTY_MODEL_ID, deviceInfo.getModel());
        properties.put(Thing.PROPERTY_SERIAL_NUMBER, deviceInfo.getSerial());
        updateProperties(properties);
    }

    /**
     * update channel state
     * 
     * @param channelID
     * @param value
     */
    public void publishState(String channelID, State value) {
        updateState(channelID, value);
    }

    /***********************************
     *
     * CONNECTION
     *
     ************************************/

    /**
     * Connect (login) to device
     * 
     */
    public Boolean connect() {
        deviceError.reset();
        Boolean loginSuccess = false;

        try {
            loginSuccess = connector.login();
            if (loginSuccess) {
                connector.queryChildInfo();
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, deviceError.getMessage());
            }
        } catch (Exception e) {
            updateStatus(ThingStatus.UNKNOWN);
        }
        return loginSuccess;
    }

    /**
     * disconnect device
     */
    public void disconnect() {
        connector.logout();
    }

    /**
     * handle device state by connector error
     */
    public void handleConnectionState() {
        ThingStatus deviceState = getThing().getStatus();
        Integer errorCode = deviceError.getCode();

        if (errorCode == 0) {
            if (deviceState != ThingStatus.ONLINE) {
                updateStatus(ThingStatus.ONLINE);
            }
        } else if (LIST_REAUTH_ERRORS.contains(errorCode)) {
            connect();
        } else if (LIST_COMMUNICATION_ERRORS.contains(errorCode)) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, deviceError.getMessage());
            disconnect();
        } else if (LIST_CONFIGURATION_ERRORS.contains(errorCode)) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, deviceError.getMessage());
        } else {
            updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, deviceError.getMessage());
        }
    }

    /**
     * Return IP-Address of device
     */
    public String getIpAddress() {
        return this.config.ipAddress;
    }

    @Nullable
    public String getDeviceId() {
        return this.deviceId;
    }

    /***********************************
     *
     * CHANNELS
     *
     ************************************/
    /**
     * Get ChannelID including group
     * 
     * @param group String channel-group
     * @param channel String channel-name
     * @return String channelID
     */
    protected String getChannelID(String group, String channel) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();
        if (CHANNEL_GROUP_THING_SET.contains(thingTypeUID) && group.length() > 0) {
            return group + "#" + channel;
        }
        return channel;
    }

    /**
     * Get Channel from ChannelID
     * 
     * @param channelID String channelID
     * @return String channel-name
     */
    protected String getChannelFromID(ChannelUID channelID) {
        String channel = channelID.getIdWithoutGroup();
        channel = channel.replace(CHANNEL_GROUP_ACTUATOR + "#", "");
        channel = channel.replace(CHANNEL_GROUP_DEVICE + "#", "");
        channel = channel.replace(CHANNEL_GROUP_EFFECTS + "#", "");
        channel = channel.replace(CHANNEL_GROUP_ENERGY + "#", "");
        channel = channel.replace(CHANNEL_GROUP_BUTTON + "#", "");
        return channel;
    }
}
