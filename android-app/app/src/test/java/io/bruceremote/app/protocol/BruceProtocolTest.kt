package io.bruceremote.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BruceProtocolTest {
    @Test
    fun parsesCurrentBruceOptionsJson() {
        val line = """
            {"width":240,"height":135,"menu":"main_menu","menu_title":"Main","options":[{"n":0,"label":"WiFi"},{"n":1,"label":"RF"}],"active":1}
        """.trimIndent()

        val parsed = BruceProtocol.parseMenuState(line)
        assertNotNull(parsed)
        val state = parsed!!

        assertEquals(240, state.width)
        assertEquals("Main", state.title)
        assertEquals(2, state.options.size)
        assertEquals("RF", state.options[1].label)
        assertEquals(1, state.activeIndex)
    }

    @Test
    fun rejectsNonMenuJson() {
        assertNull(BruceProtocol.parseMenuState("""{"status":"ok"}"""))
    }

    @Test
    fun parsesPatchedBruceRemoteState() {
        val line = """
            @BRUCE_REMOTE/1 {"protocol":"bruce-remote/1","ok":true,"operation":"state","state":{"revision":42,"width":240,"height":135,"type":1,"title":"Infrared","items":[{"index":0,"label":"TV-B-Gone","enabled":true,"selected":false,"hovered":false},{"index":1,"label":"Back","enabled":true,"selected":false,"hovered":true}],"active":1}}
        """.trimIndent()

        val state = BruceProtocol.parseMenuState(line)!!
        assertEquals("Infrared", state.title)
        assertEquals("sub_menu", state.type)
        assertEquals("Back", state.options[1].label)
        assertEquals(1, state.activeIndex)
    }

    @Test
    fun parsesPatchedBruceRemoteIdentity() {
        val line = """
            @BRUCE_REMOTE/1 {"protocol":"bruce-remote/1","ok":true,"operation":"hello","firmware":"Bruce","firmware_version":"dev","build":"Homebrew","device":"M5StickC Plus2","chip":"ESP32-PICO-V3-02","flash_bytes":8388608,"baud":115200}
        """.trimIndent()

        val identity = BruceProtocol.parseRemoteIdentity(line)!!
        assertEquals("dev", identity.version)
        assertEquals("M5StickC Plus2", identity.board)
        assertEquals("ESP32-PICO-V3-02", identity.sdk)
    }
}
