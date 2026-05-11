package com.musheer360.swiftslate.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketException

class ApiClientUtilsTest {

    @Test
    fun isTransientNetwork_socketException_returnsTrue() {
        assertTrue(SocketException("Software caused connection abort").isTransientNetwork())
    }

    @Test
    fun isTransientNetwork_nonNetworkException_returnsFalse() {
        assertFalse(IllegalStateException("boom").isTransientNetwork())
    }
}
