package com.musheer360.swiftslate.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs under Robolectric because [ApiClientUtils.tryExtractStructuredText] uses
 * org.json, which is an unimplemented stub in the plain JVM unit-test classpath
 * (every call throws, so a parse test would pass vacuously).
 */
@RunWith(RobolectricTestRunner::class)
class ApiClientUtilsTest {

    // --- isModelRefusal ---

    @Test
    fun testIsModelRefusal_detects_ai_refusals() {
        assertTrue(ApiClientUtils.isModelRefusal("I'm sorry, but I can't help with that."))
        assertTrue(ApiClientUtils.isModelRefusal("I cannot fulfill the request to make the text vulgar or add abusive slurs."))
        assertTrue(ApiClientUtils.isModelRefusal("As an AI, I am unable to generate illegal explosives instructions."))
        assertTrue(ApiClientUtils.isModelRefusal("I cannot comply with that request."))
        assertTrue(ApiClientUtils.isModelRefusal("This response violates safety guidelines."))
        assertTrue(ApiClientUtils.isModelRefusal("As an AI language model, I don't have opinions."))
        assertTrue(ApiClientUtils.isModelRefusal("I\u2019m unable to help with that \u2014 try something else."))
    }

    @Test
    fun testIsModelRefusal_allows_legitimate_user_text() {
        assertFalse(ApiClientUtils.isModelRefusal("I am sorry I cannot fulfill your order today. Please contact support."))
        assertFalse(ApiClientUtils.isModelRefusal("Translate to Spanish: I'm sorry but I can't make it to the party."))
        assertFalse(ApiClientUtils.isModelRefusal("Fix grammar: He said I cannot fulfill my promises."))
        assertFalse(ApiClientUtils.isModelRefusal("Dear John, I am unable to attend the meeting tomorrow."))
    }

    /**
     * Regression: these were all classified as refusals, so a correct transformation
     * was thrown away and the user was told their text had been blocked.
     */
    @Test
    fun testIsModelRefusal_doesNotFlagOrdinaryProse() {
        assertFalse(ApiClientUtils.isModelRefusal("Please review the attached workplace safety guidelines before Monday."))
        assertFalse(ApiClientUtils.isModelRefusal("The contractor violates our policy on late deliveries every single quarter."))
        assertFalse(ApiClientUtils.isModelRefusal("As an AI engineer I built three pipelines last year."))
        assertFalse(ApiClientUtils.isModelRefusal("Our safety policy needs an update before the audit."))
        assertFalse(ApiClientUtils.isModelRefusal("he said that the new rule violates safety rules at the plant"))
        assertFalse(ApiClientUtils.isModelRefusal("Our team aims to be helpful and harmless in every interaction."))
        assertFalse(ApiClientUtils.isModelRefusal("As an assistant manager, I approve the timesheets each Friday."))
    }

    /** A refusal replaces the transformation; it never trails a valid one. */
    @Test
    fun testIsModelRefusal_ignoresMatchesPastTheHead() {
        val longPrefix = "The quarterly report is attached for your review. ".repeat(10)
        assertFalse(ApiClientUtils.isModelRefusal(longPrefix + "I cannot comply with that request."))
    }

    @Test
    fun testIsModelRefusal_blankIsNotARefusal() {
        assertFalse(ApiClientUtils.isModelRefusal(""))
        assertFalse(ApiClientUtils.isModelRefusal("   \n  "))
    }

    // --- stripMarkdownFences ---

    @Test
    fun stripMarkdownFences_removesFencesAndLanguageTag() {
        assertEquals("hello world", ApiClientUtils.stripMarkdownFences("```\nhello world\n```"))
        assertEquals("hello world", ApiClientUtils.stripMarkdownFences("```text\nhello world\n```"))
    }

    @Test
    fun stripMarkdownFences_handlesLeadingWhitespaceAndTrailingNewlines() {
        assertEquals("hello", ApiClientUtils.stripMarkdownFences("   ```\nhello\n```\n\n"))
    }

    @Test
    fun stripMarkdownFences_leavesUnfencedTextAlone() {
        assertEquals("no fences here", ApiClientUtils.stripMarkdownFences("  no fences here  "))
        assertEquals("a ``` in the middle", ApiClientUtils.stripMarkdownFences("a ``` in the middle"))
    }

    @Test
    fun stripMarkdownFences_neverReturnsBlank() {
        assertEquals("```", ApiClientUtils.stripMarkdownFences("```"))
        assertEquals("```\n```", ApiClientUtils.stripMarkdownFences("```\n```"))
    }

    @Test
    fun stripMarkdownFences_preservesInnerNewlines() {
        assertEquals("line1\nline2", ApiClientUtils.stripMarkdownFences("```\nline1\nline2\n```"))
    }

    // --- tryExtractStructuredText ---

    @Test
    fun tryExtractStructuredText_extractsTextField() {
        val (text, parseFailed) = ApiClientUtils.tryExtractStructuredText("""{"text":"hello"}""")
        assertEquals("hello", text)
        assertFalse(parseFailed)
    }

    @Test
    fun tryExtractStructuredText_parsedButUnusableIsNotAParseFailure() {
        val (text, parseFailed) = ApiClientUtils.tryExtractStructuredText("""{"text":""}""")
        assertNull(text)
        assertFalse(parseFailed)
    }

    @Test
    fun tryExtractStructuredText_notJsonReportsParseFailure() {
        val (text, parseFailed) = ApiClientUtils.tryExtractStructuredText("just plain text")
        assertNull(text)
        assertTrue(parseFailed)
    }

    @Test
    fun tryExtractStructuredText_truncatedJsonReportsParseFailure() {
        val (text, parseFailed) = ApiClientUtils.tryExtractStructuredText("""{"text":"unterminated""")
        assertNull(text)
        assertTrue(parseFailed)
    }

    // --- redactSecrets ---

    @Test
    fun redactSecrets_masksProviderEchoedKeys() {
        assertEquals(
            "Incorrect API key provided: ***",
            ApiClientUtils.redactSecrets("Incorrect API key provided: sk-abc123DEF456ghi")
        )
        assertEquals("bad key ***", ApiClientUtils.redactSecrets("bad key gsk_ZZZZZZZZZZZZZZZZ"))
        assertEquals("key ***", ApiClientUtils.redactSecrets("key AIzaSyAbCdEfGhIjKlMn"))
    }

    @Test
    fun redactSecrets_leavesOrdinaryMessagesIntact() {
        assertEquals("Model not found.", ApiClientUtils.redactSecrets("Model not found."))
    }

    // --- wrapUserText ---

    @Test
    fun wrapUserText_fencesInputForBothProviders() {
        assertEquals("<input>\nhello\n</input>", ApiClientUtils.wrapUserText("hello"))
    }

    // --- extractApiErrorMessage / extractSigninUrl ---

    @Test
    fun extractApiErrorMessage_readsNestedOpenAIShape() {
        assertEquals(
            "Invalid API key",
            ApiClientUtils.extractApiErrorMessage("""{"error":{"message":"Invalid API key","type":"invalid_request_error"}}""")
        )
    }

    @Test
    fun extractApiErrorMessage_readsFlatStringShape() {
        // Ollama and friends return {"error":"<string>"}, not the nested OpenAI shape.
        assertEquals("unauthorized", ApiClientUtils.extractApiErrorMessage("""{"error":"unauthorized","signin_url":"https://x"}"""))
        assertEquals("you are not currently signed in", ApiClientUtils.extractApiErrorMessage("""{"error":"you are not currently signed in"}"""))
    }

    @Test
    fun extractApiErrorMessage_nonJsonAndBlankReturnEmpty() {
        assertEquals("", ApiClientUtils.extractApiErrorMessage(""))
        assertEquals("", ApiClientUtils.extractApiErrorMessage("not json"))
        assertEquals("", ApiClientUtils.extractApiErrorMessage("""{"something":"else"}"""))
    }

    @Test
    fun extractSigninUrl_readsTheFieldAndIgnoresOthers() {
        assertEquals(
            "https://ollama.com/signin/token",
            ApiClientUtils.extractSigninUrl("""{"error":"unauthorized","signin_url":"https://ollama.com/signin/token"}""")
        )
        assertNull(ApiClientUtils.extractSigninUrl("""{"error":{"message":"Invalid API key"}}"""))
        assertNull(ApiClientUtils.extractSigninUrl(""))
        assertNull(ApiClientUtils.extractSigninUrl("not json"))
    }

}
