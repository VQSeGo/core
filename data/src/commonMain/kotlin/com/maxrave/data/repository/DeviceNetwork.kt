package com.maxrave.data.repository

/**
 * Calls [onChange] with whether the device has any network that can carry a request, now and on every
 * change. Android only; the desktop has no such signal and never calls it.
 */
expect fun watchDeviceNetwork(onChange: (hasNetwork: Boolean) -> Unit)
