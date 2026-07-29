package io.bruceremote.app.protocol

import org.json.JSONException
import org.json.JSONObject

data class BruceMenuOption(
    val number: Int,
    val label: String,
)

data class BruceMenuState(
    val width: Int,
    val height: Int,
    val type: String,
    val title: String,
    val options: List<BruceMenuOption>,
    val activeIndex: Int,
)

data class BruceDeviceIdentity(
    val version: String? = null,
    val board: String? = null,
    val sdk: String? = null,
    val macAddress: String? = null,
    val wifi: String? = null,
    val ipAddress: String? = null,
) {
    fun displayText(fallback: String): String {
        val heading = listOfNotNull(
            board?.takeIf { it.isNotBlank() },
            version?.takeIf { it.isNotBlank() }?.let { "Bruce $it" },
        ).joinToString(" · ")

        val details = listOfNotNull(
            sdk?.takeIf { it.isNotBlank() }?.let { "SDK $it" },
            macAddress?.takeIf { it.isNotBlank() }?.let { "MAC $it" },
            wifi?.takeIf { it.isNotBlank() },
            ipAddress?.takeIf { it.isNotBlank() }?.let { "IP $it" },
        ).joinToString(" · ")

        return listOf(heading, details)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .ifBlank { fallback }
    }
}

class BruceDeviceIdentityParser {
    var identity = BruceDeviceIdentity()
        private set

    fun reset() {
        identity = BruceDeviceIdentity()
    }

    fun setIdentity(value: BruceDeviceIdentity) {
        identity = value
    }

    /**
     * Consumes one Bruce `info` output line.
     *
     * Returns true when a recognized identity/status field changed.
     */
    fun consume(line: String): Boolean {
        val trimmed = line.trim()
        val updated = when {
            trimmed.startsWith("Bruce v", ignoreCase = true) ->
                identity.copy(version = trimmed.substringAfter("Bruce v").trim())

            trimmed.startsWith("Device:", ignoreCase = true) ->
                identity.copy(board = trimmed.substringAfter(':').trim())

            trimmed.startsWith("SDK:", ignoreCase = true) ->
                identity.copy(sdk = trimmed.substringAfter(':').trim())

            trimmed.startsWith("MAC addr:", ignoreCase = true) ->
                identity.copy(macAddress = trimmed.substringAfter(':').trim())

            trimmed.startsWith("Wifi:", ignoreCase = true) ->
                identity.copy(wifi = "Wi-Fi ${trimmed.substringAfter(':').trim()}")

            trimmed.startsWith("Ip:", ignoreCase = true) ->
                identity.copy(ipAddress = trimmed.substringAfter(':').trim())

            else -> return false
        }
        val changed = updated != identity
        identity = updated
        return changed
    }
}

object BruceProtocol {
    const val COMMAND_REMOTE_HELLO = "remote hello"
    const val COMMAND_REMOTE_STATE = "remote state"
    const val COMMAND_INFO = "info"
    const val COMMAND_OPTIONS_JSON = "optionsJSON"
    const val COMMAND_DISPLAY_START = "display start"
    const val COMMAND_DISPLAY_STOP = "display stop"

    fun nav(direction: String): String = "nav $direction"

    fun selectOption(number: Int): String = "options $number"

    /**
     * Bruce currently emits the menu as one JSON line. Locating the outermost
     * braces tolerates optional terminal prefixes while still rejecting other
     * JSON-shaped log data that does not carry the menu fields.
     */
    fun parseMenuState(line: String): BruceMenuState? {
        val firstBrace = line.indexOf('{')
        val lastBrace = line.lastIndexOf('}')
        if (firstBrace < 0 || lastBrace <= firstBrace) return null

        return try {
            val root = JSONObject(line.substring(firstBrace, lastBrace + 1))
            if (root.optString("protocol") == "bruce-remote/1" &&
                root.optString("operation") == "state" &&
                root.optBoolean("ok", false)
            ) {
                return parseRemoteMenuState(root.getJSONObject("state"))
            }
            if (!root.has("options") || !root.has("menu")) return null

            val array = root.getJSONArray("options")
            val options = buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        BruceMenuOption(
                            number = item.optInt("n", index),
                            label = item.optString("label", "Option ${index + 1}"),
                        ),
                    )
                }
            }
            val requestedActive = root.optInt("active", -1)
            val active = requestedActive.takeIf { it in options.indices } ?: -1

            BruceMenuState(
                width = root.optInt("width", 0),
                height = root.optInt("height", 0),
                type = root.optString("menu", "regular_menu"),
                title = root.optString("menu_title", "").ifBlank { "Bruce menu" },
                options = options,
                activeIndex = active,
            )
        } catch (_: JSONException) {
            null
        }
    }

    fun parseRemoteIdentity(line: String): BruceDeviceIdentity? {
        if (!line.startsWith("@BRUCE_REMOTE/1 ")) return null
        val firstBrace = line.indexOf('{')
        val lastBrace = line.lastIndexOf('}')
        if (firstBrace < 0 || lastBrace <= firstBrace) return null
        return try {
            val root = JSONObject(line.substring(firstBrace, lastBrace + 1))
            if (root.optString("protocol") != "bruce-remote/1" ||
                root.optString("operation") != "hello" ||
                !root.optBoolean("ok", false)
            ) {
                return null
            }
            BruceDeviceIdentity(
                version = root.optString("firmware_version").takeIf { it.isNotBlank() },
                board = root.optString("device").takeIf { it.isNotBlank() },
                sdk = root.optString("chip").takeIf { it.isNotBlank() },
            )
        } catch (_: JSONException) {
            null
        }
    }

    private fun parseRemoteMenuState(state: JSONObject): BruceMenuState {
        val array = state.getJSONArray("items")
        val options = buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    BruceMenuOption(
                        number = item.optInt("index", index),
                        label = item.optString("label", "Option ${index + 1}"),
                    ),
                )
            }
        }
        val requestedActive = state.optInt("active", -1)
        val active = requestedActive.takeIf { it in options.indices } ?: -1
        val menuType = when (state.optInt("type", -1)) {
            0 -> "main_menu"
            1 -> "sub_menu"
            else -> "regular_menu"
        }
        return BruceMenuState(
            width = state.optInt("width", 0),
            height = state.optInt("height", 0),
            type = menuType,
            title = state.optString("title", "").ifBlank { "Bruce menu" },
            options = options,
            activeIndex = active,
        )
    }
}
