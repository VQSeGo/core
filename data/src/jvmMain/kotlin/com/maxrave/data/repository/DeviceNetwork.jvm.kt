package com.maxrave.data.repository

/**
 * Not watched on the desktop: the JVM has no network-change signal, and a proxy or TUN VPN in front of
 * the link leaves no reliable "no network" to read. NetworkGate's few seconds after the first failed
 * request is what the desktop has.
 */
actual fun watchDeviceNetwork(onChange: (hasNetwork: Boolean) -> Unit) = Unit
