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

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The {@link TapoHubConfiguration} class contains fields mapping hub configuration parameters.
 *
 * @author Lucas de Mamann - Initial contribution
 */

@NonNullByDefault
public final class TapoHubConfiguration {
    /* THING CONFIGUTATION PROPERTYS */
    public static final String CONFIG_DEVICE_IP = "ipAddress";
    public static final String CONFIG_DISCOVERY_INTERVAL = "discoveryInterval";

    /* DEFAULT & FIXED CONFIGURATIONS */
    public static final Integer CONFIG_FIXED_INTERVAL = 1440;

    /* thing configuration parameter. */
    public String ipAddress = "";
    public int reconnectInterval = CONFIG_FIXED_INTERVAL;
    public int discoveryInterval = 60;
}
