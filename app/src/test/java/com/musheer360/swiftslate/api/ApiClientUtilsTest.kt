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

    @Test
    fun shouldRetryTransientNetwork_allowsUpToThreeRetries() {
        val error = SocketException("Software caused connection abort")
        assertTrue(shouldRetryTransientNetwork(error, retryCount = 0))
        assertTrue(shouldRetryTransientNetwork(error, retryCount = 1))
        assertTrue(shouldRetryTransientNetwork(error, retryCount = 2))
        assertFalse(shouldRetryTransientNetwork(error, retryCount = 3))
    }

    @Test
    fun shouldRetryTransientNetwork_nonTransientError_returnsFalse() {
        assertFalse(shouldRetryTransientNetwork(IllegalArgumentException("bad"), retryCount = 0))
    }
}
