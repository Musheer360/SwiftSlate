package com.musheer360.swiftslate.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextTest {

    private val extractor = ConversationContextExtractor()

    @Test
    fun extract_prefersLatestIncomingAndIncludesNearbyMessages() {
        val root = ConversationNodeSnapshot(
            children = listOf(
                node("Earlier", viewId = "incoming_message"),
                node("My response", viewId = "outgoing_message"),
                node("Latest from Sam", contentDescription = "Message from Sam"),
                node("Send", className = "android.widget.Button"),
                ConversationNodeSnapshot(text = "draft", isEditable = true)
            )
        )

        val result = extractor.extract(root, "com.example.chat")

        assertEquals("Latest from Sam", result?.latestIncoming)
        assertTrue(result?.text?.contains("Incoming: Earlier") == true)
        assertTrue(result?.text?.contains("You: My response") == true)
        assertTrue(result?.text?.contains("Incoming: Latest from Sam") == true)
        assertFalse(result?.text?.contains("Send") == true)
        assertFalse(result?.text?.contains("draft") == true)
    }

    @Test
    fun extract_withoutOwnershipMarkers_usesNewestNonOutgoingMessage() {
        val root = ConversationNodeSnapshot(
            children = listOf(
                node("First visible message"),
                node("My message", viewId = "outgoing_message"),
                node("Newest visible message")
            )
        )

        val result = extractor.extract(root, "com.example.chat")

        assertEquals("Newest visible message", result?.latestIncoming)
    }

    @Test
    fun extract_preservesRepeatedSiblingMessages_butRemovesAncestorDuplicate() {
        val repeated = ConversationNodeSnapshot(
            text = "OK",
            viewIdResourceName = "incoming_message",
            className = "android.widget.TextView",
            children = listOf(
                ConversationNodeSnapshot(
                    text = "OK",
                    viewIdResourceName = "incoming_message",
                    className = "android.widget.TextView"
                )
            )
        )
        val root = ConversationNodeSnapshot(
            children = listOf(
                repeated,
                node("Sure", viewId = "outgoing_message"),
                node("OK", viewId = "incoming_message")
            )
        )

        val result = extractor.extract(root, "com.example.chat")
        val okLines = result?.text?.lines()?.count { it == "Incoming: OK" }

        assertEquals("OK", result?.latestIncoming)
        assertEquals(2, okLines)
    }

    @Test
    fun extract_withOnlyOutgoingMessages_returnsNull() {
        val root = ConversationNodeSnapshot(
            children = listOf(
                node("Mine one", viewId = "outgoing_message"),
                node("Mine two", contentDescription = "Sent by you")
            )
        )

        assertNull(extractor.extract(root, "com.example.chat"))
    }

    @Test
    fun extract_ignoresControlsTimestampsAndPhoneNumbers() {
        val root = ConversationNodeSnapshot(
            children = listOf(
                node("Send", className = "android.widget.Button"),
                node("10:45"),
                node("+1 555 123 4567"),
                node("Incoming message", viewId = "incoming_message")
            )
        )

        val result = extractor.extract(root, "com.example.chat")

        assertEquals("Incoming message", result?.latestIncoming)
        assertFalse(result?.text?.contains("10:45") == true)
        assertFalse(result?.text?.contains("555") == true)
    }

    @Test
    fun extract_usesPackageAdapterBeforeGenericFallback() {
        val adapter = object : ConversationAdapter {
            override fun supports(packageName: String): Boolean = packageName == "com.example.chat"

            override fun extract(root: ConversationNodeSnapshot): ConversationSnapshot =
                ConversationSnapshot("adapter context", "adapter message")
        }
        val adapted = ConversationContextExtractor(listOf(adapter))

        val result = adapted.extract(ConversationNodeSnapshot(), "com.example.chat")

        assertEquals("adapter context", result?.text)
        assertEquals("adapter message", result?.latestIncoming)
    }

    private fun node(
        text: String,
        viewId: String? = null,
        contentDescription: String? = null,
        className: String? = "android.widget.TextView"
    ) = ConversationNodeSnapshot(
        text = text,
        viewIdResourceName = viewId,
        contentDescription = contentDescription,
        className = className
    )
}
