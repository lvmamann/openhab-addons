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

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.binding.tapoextended.internal.TapoHubDiscoveryService;
import org.openhab.binding.tapoextended.internal.api.TapoHubConnector;
import org.openhab.binding.tapoextended.internal.helpers.TapoCredentials;
import org.openhab.binding.tapoextended.internal.helpers.TapoErrorHandler;
import org.openhab.binding.tapoextended.internal.structures.TapoBridgeConfiguration;
import org.openhab.binding.tapoextended.internal.structures.TapoHubConfiguration;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.BridgeHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;

/**
 * The {@link TapoHubHandler} is responsible for handling hub devices.
 *
 * @author Lucas Vilela - Initial contribution
 */
@NonNullByDefault
public class TapoHubHandler extends BaseBridgeHandler {
    private final Logger logger = LoggerFactory.getLogger(TapoHubHandler.class);
    private final TapoErrorHandler bridgeError = new TapoErrorHandler();
    private final HttpClient httpClient;
    private TapoHubConfiguration config = new TapoHubConfiguration();
    private TapoBridgeConfiguration parentConfig = new TapoBridgeConfiguration();
    private @Nullable ScheduledFuture<?> startupJob;
    private @Nullable ScheduledFuture<?> pollingJob;
    private @Nullable ScheduledFuture<?> discoveryJob;
    private @NonNullByDefault({}) TapoHubConnector connector;
    private @NonNullByDefault({}) TapoHubDiscoveryService discoveryService;
    protected @NonNullByDefault({}) TapoBridgeHandler parentBridge;
    private TapoCredentials credentials;

    private String uid;

    public TapoHubHandler(Bridge bridge, HttpClient httpClient) {
        super(bridge);
        Thing thing = getThing();
        this.credentials = new TapoCredentials();
        this.uid = thing.getUID().toString();
        this.httpClient = httpClient;
    }

    /***********************************
     *
     * BRIDGE INITIALIZATION
     *
     ************************************/
    @Override
    /**
     * INIT BRIDGE
     * set credentials and login cloud
     */
    public void initialize() {
        Bridge bridgeThing = getBridge();
        if (bridgeThing != null) {
            BridgeHandler bridgeHandler = bridgeThing.getHandler();
            if (bridgeHandler != null) {
                this.parentBridge = (TapoBridgeHandler) bridgeHandler;
                ;
                this.parentConfig = this.parentBridge.getBridgeConfig();
            }
        }

        this.config = getConfigAs(TapoHubConfiguration.class);
        this.credentials = new TapoCredentials(parentConfig.username, parentConfig.password);
        this.connector = new TapoHubConnector(this);
        activateBridge();
    }

    /**
     * ACTIVATE BRIDGE
     */
    private void activateBridge() {
        // set the thing status to UNKNOWN temporarily and let the background task decide for the real status.
        updateStatus(ThingStatus.UNKNOWN);

        // background initialization (delay it a little bit):
        this.startupJob = scheduler.schedule(this::delayedStartUp, 1000, TimeUnit.MILLISECONDS);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("{} Bridge doesn't handle command: {}", this.uid, command);
    }

    @Override
    public void dispose() {
        stopScheduler(this.startupJob);
        stopScheduler(this.pollingJob);
        stopScheduler(this.discoveryJob);
        super.dispose();
    }

    /**
     * ACTIVATE DISCOVERY SERVICE
     */
    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Collections.singleton(TapoHubDiscoveryService.class);
    }

    /**
     * Set DiscoveryService
     * 
     * @param discoveryService
     */
    public void setDiscoveryService(TapoHubDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
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
        loginCloud();
        startCloudScheduler();
        startDiscoveryScheduler();
    }

    /**
     * Start CloudLogin Scheduler
     */
    protected void startCloudScheduler() {
        int pollingInterval = config.reconnectInterval;
        TimeUnit timeUnit = TimeUnit.MINUTES;
        if (pollingInterval > 0) {
            logger.debug("{} starting cloudScheduler with interval {} {}", this.uid, pollingInterval, timeUnit);

            this.pollingJob = scheduler.scheduleWithFixedDelay(this::loginCloud, pollingInterval, pollingInterval,
                    timeUnit);
        } else {
            logger.debug("({}) cloudScheduler disabled with config '0'", uid);
            stopScheduler(this.pollingJob);
        }
    }

    /**
     * Start DeviceDiscovery Scheduler
     */
    protected void startDiscoveryScheduler() {
        int pollingInterval = config.discoveryInterval;
        TimeUnit timeUnit = TimeUnit.MINUTES;
        if (pollingInterval > 0) {
            logger.debug("{} starting discoveryScheduler with interval {} {}", this.uid, pollingInterval, timeUnit);

            this.discoveryJob = scheduler.scheduleWithFixedDelay(this::discoverDevices, 0, pollingInterval, timeUnit);
        } else {
            logger.debug("({}) discoveryScheduler disabled with config '0'", uid);
            stopScheduler(this.discoveryJob);
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
        return this.bridgeError;
    }

    /**
     * set device error
     * 
     * @param tapoError TapoErrorHandler-Object
     */
    public void setError(TapoErrorHandler tapoError) {
        this.bridgeError.set(tapoError);
    }

    /***********************************
     *
     * BRIDGE COMMUNICATIONS
     *
     ************************************/

    /**
     * Login to Cloud
     * 
     * @return
     */
    public boolean loginCloud() {
        bridgeError.reset(); // reset ErrorHandler
        if (!parentConfig.username.isBlank() && !parentConfig.password.isBlank()) {
            logger.debug("{} login with user {}", this.uid, parentConfig.username);
            if (connector.login()) {
                updateStatus(ThingStatus.ONLINE);
                return true;
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, bridgeError.getMessage());
            }
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "credentials not set");
        }
        return false;
    }

    /***********************************
     *
     * DEVICE DISCOVERY
     *
     ************************************/

    /**
     * START DEVICE DISCOVERY
     */
    public void discoverDevices() {
        this.discoveryService.startScan();
    }

    /**
     * GET DEVICELIST CONNECTED TO BRIDGE
     * 
     * @return devicelist
     */
    public JsonArray getDeviceList() {
        JsonArray deviceList = new JsonArray();

        logger.trace("{} discover devicelist from cloud", this.uid);
        deviceList = getDeviceListCloud();

        return deviceList;
    }

    /**
     * GET DEVICELIST FROM CLOUD
     * returns all devices stored in cloud
     * 
     * @return deviceList from cloud
     */
    private JsonArray getDeviceListCloud() {
        logger.trace("{} getDeviceList from cloud", this.uid);
        bridgeError.reset(); // reset ErrorHandler
        JsonArray deviceList = new JsonArray();
        if (loginCloud()) {
            deviceList = this.connector.getDeviceList();
        }

        return deviceList;
    }

    /***********************************
     *
     * BRIDGE GETTERS
     *
     ************************************/

    public TapoCredentials getCredentials() {
        return this.credentials;
    }

    public HttpClient getHttpClient() {
        return this.httpClient;
    }

    public ThingUID getUID() {
        return getThing().getUID();
    }

    public TapoHubConfiguration getBridgeConfig() {
        return this.config;
    }

    public String getIpAddress() {
        return this.config.ipAddress;
    }
}
