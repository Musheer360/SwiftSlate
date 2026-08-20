package com.musheer360.swiftslate.service

import java.util.Locale

/**
 * A detached, bounded representation of accessibility content.
 *
 * The service converts framework nodes into this shape immediately and never keeps
 * AccessibilityNodeInfo instances while a provider request is in flight.
 */
data class ConversationNodeSnapshot(
    val text: String? = null,
    val contentDescription: String? = null,
    val viewIdResourceName: String? = null,
    val className: String? = null,
    val isEditable: Boolean = false,
    val isPassword: Boolean = false,
    val children: List<ConversationNodeSnapshot> = emptyList()
)

data class ConversationSnapshot(
    val text: String,
    val latestIncoming: String
)

/** Extension point for package-specific accessibility layouts added later. */
interface ConversationAdapter {
    fun supports(packageName: String): Boolean
    fun extract(root: ConversationNodeSnapshot): ConversationSnapshot?
}

/**
 * Extracts a small conversation window without making assumptions about a particular chat app.
 * Generic extraction only uses readable text and explicit ownership hints exposed by the tree;
 * it declines when no plausible incoming message can be identified.
 */
class ConversationContextExtractor(
    private val adapters: List<ConversationAdapter> = emptyList()
) {

    fun extract(root: ConversationNodeSnapshot, packageName: String): ConversationSnapshot? {
        adapters.firstOrNull { it.supports(packageName) }
            ?.extract(root)
            ?.let { return it }
        return extractGeneric(root)
    }

    private fun extractGeneric(root: ConversationNodeSnapshot): ConversationSnapshot? {
        val candidates = linkedMapOf<String, Candidate>()
        flatten(root).forEach { node ->
            val text = (node.text ?: node.contentDescription)?.trim() ?: return@forEach
            if (!isMessageCandidate(node, text)) return@forEach

            val metadata = listOfNotNull(
                node.contentDescription,
                node.viewIdResourceName,
                node.className
            ).joinToString(" ").lowercase(Locale.ROOT)
            val candidate = Candidate(
                text = text,
                incoming = containsAny(metadata, INCOMING_MARKERS),
                outgoing = containsAny(metadata, OUTGOING_MARKERS)
            )
            val key = text.lowercase(Locale.ROOT)
            val previous = candidates[key]
            candidates[key] = if (previous == null) candidate else previous.copy(
                incoming = previous.incoming || candidate.incoming,
                outgoing = previous.outgoing || candidate.outgoing
            )
        }

        val messages = candidates.values.toList()
        if (messages.isEmpty()) return null

        // An explicit incoming marker is the strongest generic signal. If an app exposes no
        // incoming marker, use the newest message that is not identified as authored by the user.
        val latestIncomingIndex = messages.indexOfLast { it.incoming && !it.outgoing }
        val anchorIndex = if (latestIncomingIndex >= 0) {
            latestIncomingIndex
        } else {
            messages.indexOfLast { !it.outgoing }
        }
        if (anchorIndex < 0) return null

        val selected = messages
            .subList((anchorIndex - MAX_NEARBY_MESSAGES + 1).coerceAtLeast(0), anchorIndex + 1)
            .map { candidate ->
                val role = when {
                    candidate.incoming -> "Incoming"
                    candidate.outgoing -> "You"
                    else -> "Message"
                }
                "$role: ${candidate.text.take(MAX_MESSAGE_CHARS)}"
            }
        val context = selected.joinToString("\n").take(MAX_CONTEXT_CHARS).trim()
        return if (context.isBlank()) null else ConversationSnapshot(
            text = "Reply to the latest incoming message.\n$context",
            latestIncoming = messages[anchorIndex].text.take(MAX_MESSAGE_CHARS)
        )
    }

    private fun flatten(root: ConversationNodeSnapshot): List<ConversationNodeSnapshot> {
        val result = ArrayList<ConversationNodeSnapshot>(MAX_NODE_COUNT)
        fun visit(node: ConversationNodeSnapshot, depth: Int) {
            if (result.size >= MAX_NODE_COUNT || depth > MAX_DEPTH) return
            result += node
            node.children.forEach { child -> visit(child, depth + 1) }
        }
        visit(root, 0)
        return result
    }

    private fun isMessageCandidate(node: ConversationNodeSnapshot, text: String): Boolean {
        if (node.isEditable || node.isPassword) return false
        if (text.length !in 2..MAX_MESSAGE_CHARS) return false
        val className = node.className.orEmpty()
        if (className.endsWith("Button") || className.endsWith("EditText") ||
            className.endsWith("CheckBox") || className.endsWith("Switch") ||
            className.endsWith("Spinner")) return false

        val normalized = text.lowercase(Locale.ROOT).replace("\u00a0", " ").trim()
        if (normalized in UI_TEXT) return false
        if (TIME_ONLY.matches(normalized) || PHONE_ONLY.matches(normalized)) return false
        return true
    }

    private fun containsAny(value: String, markers: List<String>): Boolean =
        markers.any { marker -> value.contains(marker) }

    private data class Candidate(
        val text: String,
        val incoming: Boolean,
        val outgoing: Boolean
    )

    private companion object {
        const val MAX_NODE_COUNT = 500
        const val MAX_DEPTH = 32
        const val MAX_NEARBY_MESSAGES = 5
        const val MAX_MESSAGE_CHARS = 600
        const val MAX_CONTEXT_CHARS = 3_000

        val INCOMING_MARKERS = listOf(
            "incoming", "received", "message from", "from sender", "sender"
        )
        val OUTGOING_MARKERS = listOf(
            "outgoing", "sent by you", "sent by me", "from you", "from me", "your message"
        )
        val UI_TEXT = setOf(
            "send", "attach", "camera", "gallery", "emoji", "back", "more", "menu",
            "reply", "forward", "copy", "share", "delete", "online", "typing", "delivered",
            "read", "call", "video call", "voice call"
        )
        val TIME_ONLY = Regex("^(?:[0-2]?\\d:[0-5]\\d(?:\\s*[ap]m)?|[0-5]?\\d\\s*(?:min|mins|minutes|hr|hrs|hours) ago)$")
        val PHONE_ONLY = Regex("^[+()\\d][+()\\d .-]{5,}$")
    }
}
