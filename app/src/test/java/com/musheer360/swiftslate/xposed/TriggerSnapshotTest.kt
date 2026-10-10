package com.musheer360.swiftslate.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The keyboard hook decides locally, with no IPC, whether a keystroke completed a trigger. */
@RunWith(RobolectricTestRunner::class)
class TriggerSnapshotTest {
    private val json = """
        {"v":1,"token":"abc","prefix":"?","triggers":[
          {"t":"?replace","c":1},{"t":"?paste","c":1},{"t":"?fix","c":0},{"t":"?x","c":0},{"t":"?undo","c":0}
        ]}
    """.trimIndent()

    private fun snapshot() = TriggerSnapshot.parse(json)!!

    @Test fun matchesTrailingTrigger() {
        assertEquals("?fix", snapshot().match("hello world ?fix")!!.trigger)
    }

    @Test fun onlyClipboardCommandsNeedTheClipboard() {
        assertTrue(snapshot().match("hello ?paste")!!.needsClip)
        assertFalse(snapshot().match("hello ?fix")!!.needsClip)
    }

    @Test fun ignoresPlainText() {
        assertNull(snapshot().match("hello world"))
        assertNull(snapshot().match("what is this?"))
        assertNull(snapshot().match("a?xx"))
    }

    @Test fun translateAcceptsTwoToFiveAlphanumericCharacters() {
        assertEquals("?translate:fr", snapshot().match("hi ?translate:fr")!!.trigger)
        assertNull(snapshot().match("hi ?translate:f"))
        assertNull(snapshot().match("hi ?translate:abcdef"))
        assertNull(snapshot().match("hi ?translate:zh-CN"))
    }

    @Test fun rejectsMissingOrMalformedSnapshots() {
        assertNull(TriggerSnapshot.parse(null))
        assertNull(TriggerSnapshot.parse(""))
        assertNull(TriggerSnapshot.parse("not json"))
        assertNull(TriggerSnapshot.parse("""{"token":"","triggers":[{"t":"?a"}]}"""))
        assertNotNull(TriggerSnapshot.parse(json))
    }
}
