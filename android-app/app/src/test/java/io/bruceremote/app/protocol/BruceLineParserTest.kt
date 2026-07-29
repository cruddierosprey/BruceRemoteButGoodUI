package io.bruceremote.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class BruceLineParserTest {
    @Test
    fun parsesLinesAndPromptAcrossUsbChunks() {
        val lines = mutableListOf<String>()
        var prompts = 0
        val parser = BruceLineParser(
            object : BruceLineParser.Listener {
                override fun onLine(line: String) {
                    lines += line
                }

                override fun onPrompt() {
                    prompts += 1
                }
            },
        )

        parser.accept("Bruce v1.10\r\n#".toByteArray())
        parser.accept(" ".toByteArray())

        assertEquals(listOf("Bruce v1.10"), lines)
        assertEquals(1, prompts)
    }

    @Test
    fun preservesUtf8WhenCodePointIsSplitAcrossChunks() {
        val lines = mutableListOf<String>()
        val parser = BruceLineParser(
            object : BruceLineParser.Listener {
                override fun onLine(line: String) {
                    lines += line
                }

                override fun onPrompt() = Unit
            },
        )
        val bytes = "Café\n".toByteArray(Charsets.UTF_8)

        parser.accept(bytes.copyOfRange(0, 4))
        parser.accept(bytes.copyOfRange(4, bytes.size))

        assertEquals(listOf("Café"), lines)
    }
}
