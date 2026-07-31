package com.musheer360.swiftslate.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckWorkerTest {

    @Test
    fun isNewer_detectsHigherPatch() {
        assertTrue(UpdateCheckWorker.isNewer("1.0.50", "1.0.49"))
        assertTrue(UpdateCheckWorker.isNewer("1.0.100", "1.0.99"))
    }

    @Test
    fun isNewer_isFalseForSameOrOlder() {
        assertFalse(UpdateCheckWorker.isNewer("1.0.49", "1.0.49"))
        assertFalse(UpdateCheckWorker.isNewer("1.0.48", "1.0.49"))
        assertFalse(UpdateCheckWorker.isNewer("0.9.99", "1.0.0"))
    }

    /** "1.0.100" must not lose to "1.0.99" the way a string comparison would. */
    @Test
    fun isNewer_comparesNumericallyNotLexically() {
        assertTrue(UpdateCheckWorker.isNewer("1.0.9", "1.0.10") == false)
        assertTrue(UpdateCheckWorker.isNewer("1.0.10", "1.0.9"))
    }

    @Test
    fun isNewer_treatsMissingComponentsAsZero() {
        assertTrue(UpdateCheckWorker.isNewer("1.1", "1.0.9"))
        assertFalse(UpdateCheckWorker.isNewer("1.0", "1.0.0"))
        assertTrue(UpdateCheckWorker.isNewer("1.0.1", "1.0"))
    }

    @Test
    fun isNewer_majorAndMinorTakePrecedence() {
        assertTrue(UpdateCheckWorker.isNewer("2.0.0", "1.99.99"))
        assertFalse(UpdateCheckWorker.isNewer("1.99.99", "2.0.0"))
    }

    /** A dev build ("1.0-dev" -> "1.0") or garbage must never look newer than a release. */
    @Test
    fun isNewer_ignoresNonNumericComponents() {
        assertFalse(UpdateCheckWorker.isNewer("", "1.0.49"))
        assertFalse(UpdateCheckWorker.isNewer("abc", "1.0.49"))
        assertTrue(UpdateCheckWorker.isNewer("1.0.49", "1.0-dev"))
    }
}
