package com.nuvio.tv.core.cloudstorage

import com.nuvio.tv.core.cloudstorage.LocalCloudStreamServer.Companion.isPrivateOrLocal
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Provider cookies and tokens never go to the TV's own network, whatever DNS answers. */
class PrivateAddressTest {
    @Test
    fun localAndPrivateAddressesAreRefused() {
        listOf("127.0.0.1", "10.0.0.5", "192.168.1.10", "172.16.3.4", "169.254.1.1", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1")
            .forEach { assertTrue(InetAddress.getByName(it).isPrivateOrLocal(), it) }
    }

    @Test
    fun publicAddressesAreAllowed() {
        listOf("8.8.8.8", "142.250.1.1", "2001:4860:4860::8888").forEach { assertFalse(InetAddress.getByName(it).isPrivateOrLocal(), it) }
    }
}
