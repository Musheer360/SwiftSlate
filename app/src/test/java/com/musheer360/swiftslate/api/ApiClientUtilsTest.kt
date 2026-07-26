package com.musheer360.swiftslate.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiClientUtilsTest {

    @Test
    fun testIsModelRefusal_detects_ai_refusals() {
        assertTrue(ApiClientUtils.isModelRefusal("I'm sorry, but I can't help with that."))
        assertTrue(ApiClientUtils.isModelRefusal("I cannot fulfill the request to make the text vulgar or add abusive slurs."))
        assertTrue(ApiClientUtils.isModelRefusal("As an AI, I am unable to generate illegal explosives instructions."))
        assertTrue(ApiClientUtils.isModelRefusal("I cannot comply with that request."))
        assertTrue(ApiClientUtils.isModelRefusal("This response violates safety guidelines."))
    }

    @Test
    fun testIsModelRefusal_allows_legitimate_user_text() {
        assertFalse(ApiClientUtils.isModelRefusal("I am sorry I cannot fulfill your order today. Please contact support."))
        assertFalse(ApiClientUtils.isModelRefusal("Translate to Spanish: I'm sorry but I can't make it to the party."))
        assertFalse(ApiClientUtils.isModelRefusal("Fix grammar: He said I cannot fulfill my promises."))
        assertFalse(ApiClientUtils.isModelRefusal("Dear John, I am unable to attend the meeting tomorrow."))
    }
}
