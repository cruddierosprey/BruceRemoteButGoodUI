package io.bruceremote.app

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.bruceremote.app.firmware.FirmwareUpdateChecker
import io.bruceremote.app.protocol.BruceCommandQueue
import io.bruceremote.app.protocol.BruceDeviceIdentityParser
import io.bruceremote.app.protocol.BruceLineParser
import io.bruceremote.app.protocol.BruceMenuState
import io.bruceremote.app.protocol.BruceProtocol
import io.bruceremote.app.protocol.BruceTftStreamParser
import io.bruceremote.app.ui.BruceScreenView
import io.bruceremote.app.ui.MenuOptionAdapter
import io.bruceremote.app.usb.UsbConnectionState
import io.bruceremote.app.usb.UsbSerialController
import io.bruceremote.app.usb.UsbSerialDeviceDescriptor

class MainActivity :
    AppCompatActivity(),
    UsbSerialController.Listener,
    BruceLineParser.Listener,
    BruceCommandQueue.Listener {

    private lateinit var deviceSpinner: Spinner
    private lateinit var scanButton: Button
    private lateinit var connectButton: Button
    private lateinit var firmwareButton: Button
    private lateinit var mirrorButton: Button
    private lateinit var connectionStatus: TextView
    private lateinit var deviceInfo: TextView
    private lateinit var menuTitle: TextView
    private lateinit var menuList: ListView
    private lateinit var bruceScreen: BruceScreenView
    private lateinit var rawCommand: EditText
    private lateinit var sendButton: Button
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private lateinit var usbController: UsbSerialController
    private lateinit var lineParser: BruceLineParser
    private lateinit var tftStreamParser: BruceTftStreamParser
    private lateinit var commandQueue: BruceCommandQueue
    private lateinit var menuAdapter: MenuOptionAdapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val identityParser = BruceDeviceIdentityParser()
    private val logBuffer = StringBuilder()
    private val remoteControlViews = mutableListOf<View>()

    private var devices: List<UsbSerialDeviceDescriptor> = emptyList()
    private var connectionState: UsbConnectionState = UsbConnectionState.Disconnected
    private var connectedDescriptor: UsbSerialDeviceDescriptor? = null
    private var promptSeen = false
    private var firmwareHandoff = false
    private var mirrorActive = false
    private var mirrorPacketSeen = false
    private var pendingUsbRelease: PendingUsbRelease? = null

    private val bootstrapRunnable = Runnable {
        if (connectionState is UsbConnectionState.Connected) {
            // Patched Bruce answers these with structured data. Stock Bruce
            // simply reports an unknown command, then the legacy probes below
            // keep the app fully compatible without requiring custom firmware.
            commandQueue.enqueue(BruceProtocol.COMMAND_REMOTE_HELLO)
            commandQueue.enqueue(BruceProtocol.COMMAND_REMOTE_STATE)
            commandQueue.enqueue(BruceProtocol.COMMAND_INFO)
            commandQueue.enqueue(BruceProtocol.COMMAND_OPTIONS_JSON)
        }
    }

    private val mirrorHandshakeRunnable = Runnable {
        if (mirrorActive && !mirrorPacketSeen) {
            appendLog(
                "[Screen] No TFT packets arrived. Screen preview requires " +
                    "Bruce 1.12 or newer with the `display start` command.",
            )
            stopScreenPreview(sendCommand = false)
        }
    }

    private val mirrorReleaseFallbackRunnable = Runnable {
        if (pendingUsbRelease != null) {
            appendLog("[Screen] Closing USB after the display-stop drain window")
            performPendingUsbRelease()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        menuAdapter = MenuOptionAdapter(this)
        menuList.adapter = menuAdapter

        lineParser = BruceLineParser(this)
        tftStreamParser = BruceTftStreamParser(
            onTextData = lineParser::accept,
            onTftPacket = { packet ->
                if (mirrorActive) {
                    bruceScreen.applyPacket(packet)
                    if (!mirrorPacketSeen) {
                        mirrorPacketSeen = true
                        mainHandler.removeCallbacks(mirrorHandshakeRunnable)
                        appendLog(
                            "[Screen] Bruce TFT stream detected; " +
                                "rendering its compact drawing log",
                        )
                    }
                }
            },
        )
        commandQueue = BruceCommandQueue(
            writeLine = { usbController.writeCommand(it) },
            listener = this,
        )
        usbController = UsbSerialController(this, this)

        configureActions()
        setRemoteControlsEnabled(false)
        usbController.start(intent)

        // Check for firmware updates on GitHub (respects 24h interval)
        FirmwareUpdateChecker(this).checkOnStartup(lifecycleScope)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        usbController.handleUsbIntent(intent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(bootstrapRunnable)
        mainHandler.removeCallbacks(mirrorHandshakeRunnable)
        mainHandler.removeCallbacks(mirrorReleaseFallbackRunnable)
        val stopMirror =
            (mirrorActive || pendingUsbRelease != null) &&
                connectionState is UsbConnectionState.Connected
        commandQueue.reset()
        lineParser.flushPartial()
        tftStreamParser.reset()
        if (stopMirror) {
            usbController.stopAfterCommand(BruceProtocol.COMMAND_DISPLAY_STOP)
        } else {
            usbController.stop()
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (firmwareHandoff) {
            firmwareHandoff = false
            firmwareButton.isEnabled = true
            usbController.start()
        }
    }

    private fun bindViews() {
        deviceSpinner = findViewById(R.id.deviceSpinner)
        scanButton = findViewById(R.id.scanButton)
        connectButton = findViewById(R.id.connectButton)
        firmwareButton = findViewById(R.id.firmwareButton)
        mirrorButton = findViewById(R.id.mirrorButton)
        connectionStatus = findViewById(R.id.connectionStatus)
        deviceInfo = findViewById(R.id.deviceInfo)
        menuTitle = findViewById(R.id.menuTitle)
        menuList = findViewById(R.id.menuList)
        bruceScreen = findViewById(R.id.bruceScreen)
        rawCommand = findViewById(R.id.rawCommand)
        sendButton = findViewById(R.id.sendButton)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)

        remoteControlViews += listOf(
            findViewById(R.id.infoButton),
            findViewById(R.id.refreshButton),
            mirrorButton,
            findViewById(R.id.prevButton),
            findViewById(R.id.upButton),
            findViewById(R.id.nextButton),
            findViewById(R.id.escButton),
            findViewById(R.id.selectButton),
            findViewById(R.id.downButton),
            findViewById(R.id.prevPageButton),
            findViewById(R.id.nextPageButton),
            rawCommand,
            sendButton,
            menuList,
        )
    }

    private fun configureActions() {
        scanButton.setOnClickListener {
            usbController.scanDevices()
            appendLog("[USB] Scan requested")
        }

        connectButton.setOnClickListener {
            if (connectionState is UsbConnectionState.Connected ||
                connectionState is UsbConnectionState.Opening ||
                connectionState is UsbConnectionState.WaitingForPermission
            ) {
                requestUsbRelease(PendingUsbRelease.DISCONNECT)
            } else {
                selectedDevice()?.let { usbController.connect(it.deviceId) }
            }
        }

        firmwareButton.setOnClickListener {
            if (firmwareHandoff) return@setOnClickListener
            firmwareHandoff = true
            firmwareButton.isEnabled = false
            appendLog("[USB] Releasing runtime serial port for firmware mode")
            requestUsbRelease(PendingUsbRelease.FIRMWARE_INSTALLER)
        }

        findViewById<Button>(R.id.infoButton).setOnClickListener {
            commandQueue.enqueue(BruceProtocol.COMMAND_INFO)
        }
        findViewById<Button>(R.id.refreshButton).setOnClickListener {
            commandQueue.enqueue(BruceProtocol.COMMAND_OPTIONS_JSON)
        }
        mirrorButton.setOnClickListener {
            if (mirrorActive) stopScreenPreview() else startScreenPreview()
        }

        bindNavigationButton(R.id.prevButton, "prev")
        bindNavigationButton(R.id.upButton, "up")
        bindNavigationButton(R.id.nextButton, "next")
        bindNavigationButton(R.id.escButton, "esc")
        bindNavigationButton(R.id.selectButton, "select")
        bindNavigationButton(R.id.downButton, "down")
        bindNavigationButton(R.id.prevPageButton, "prevpage")
        bindNavigationButton(R.id.nextPageButton, "nextpage")

        menuList.setOnItemClickListener { _, _, position, _ ->
            val option = menuAdapter.getItem(position)
            commandQueue.enqueue(
                BruceProtocol.selectOption(option.number),
                refreshMenuAfter = true,
                timeoutMs = NAVIGATION_TIMEOUT_MS,
            )
        }

        sendButton.setOnClickListener { submitRawCommand() }
        rawCommand.setOnEditorActionListener { _, actionId, event ->
            val keyboardSend = actionId == EditorInfo.IME_ACTION_SEND
            val enterSend = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_UP
            if (keyboardSend || enterSend) {
                submitRawCommand()
                true
            } else {
                false
            }
        }

        findViewById<Button>(R.id.clearLogButton).setOnClickListener {
            logBuffer.clear()
            logText.text = ""
        }
    }

    private fun bindNavigationButton(buttonId: Int, direction: String) {
        findViewById<Button>(buttonId).setOnClickListener {
            commandQueue.enqueue(
                BruceProtocol.nav(direction),
                refreshMenuAfter = true,
                timeoutMs = NAVIGATION_TIMEOUT_MS,
            )
        }
    }

    private fun startScreenPreview() {
        if (connectionState !is UsbConnectionState.Connected) return
        mirrorActive = true
        mirrorPacketSeen = false
        bruceScreen.reset(getString(R.string.mirror_waiting))
        bruceScreen.visibility = View.VISIBLE
        mirrorButton.setText(R.string.mirror_stop)
        appendLog(
            "[Screen] Starting Bruce's experimental vector display stream. " +
                "Images and sprite animations may be approximate.",
        )
        commandQueue.enqueue(
            BruceProtocol.COMMAND_DISPLAY_START,
            timeoutMs = SCREEN_COMMAND_TIMEOUT_MS,
        )
    }

    private fun stopScreenPreview(sendCommand: Boolean = true) {
        mainHandler.removeCallbacks(mirrorHandshakeRunnable)
        if (sendCommand && connectionState is UsbConnectionState.Connected) {
            commandQueue.enqueue(
                BruceProtocol.COMMAND_DISPLAY_STOP,
                timeoutMs = SCREEN_COMMAND_TIMEOUT_MS,
            )
        }
        mirrorActive = false
        mirrorPacketSeen = false
        mirrorButton.setText(R.string.mirror_start)
        bruceScreen.visibility = View.GONE
        bruceScreen.reset()
    }

    private fun requestUsbRelease(action: PendingUsbRelease) {
        if (mirrorActive && connectionState is UsbConnectionState.Connected) {
            pendingUsbRelease = action
            commandQueue.reset()
            connectButton.isEnabled = false
            appendLog("[Screen] Stopping Bruce display logging before closing USB")
            stopScreenPreview(sendCommand = true)
            mainHandler.removeCallbacks(mirrorReleaseFallbackRunnable)
            mainHandler.postDelayed(
                mirrorReleaseFallbackRunnable,
                SCREEN_STOP_DRAIN_TIMEOUT_MS,
            )
            return
        }
        performUsbRelease(action)
    }

    private fun performPendingUsbRelease() {
        val action = pendingUsbRelease ?: return
        pendingUsbRelease = null
        mainHandler.removeCallbacks(mirrorReleaseFallbackRunnable)
        performUsbRelease(action)
    }

    private fun performUsbRelease(action: PendingUsbRelease) {
        when (action) {
            PendingUsbRelease.DISCONNECT -> usbController.disconnect()
            PendingUsbRelease.FIRMWARE_INSTALLER -> {
                usbController.suspendUsb {
                    if (!isFinishing && !isDestroyed) {
                        startActivity(Intent(this, FirmwareActivity::class.java))
                    }
                }
            }
        }
    }

    private fun submitRawCommand() {
        val command = rawCommand.text?.toString()?.trim().orEmpty()
        if (command.isBlank()) return
        commandQueue.enqueue(
            command = command,
            refreshMenuAfter = true,
            timeoutMs = RAW_COMMAND_TIMEOUT_MS,
        )
        rawCommand.text?.clear()
        rawCommand.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(rawCommand.windowToken, 0)
    }

    private fun selectedDevice(): UsbSerialDeviceDescriptor? {
        val position = deviceSpinner.selectedItemPosition
        return devices.getOrNull(position)
    }

    override fun onDevicesChanged(devices: List<UsbSerialDeviceDescriptor>) {
        val selectedId = selectedDevice()?.deviceId ?: connectedDescriptor?.deviceId
        this.devices = devices
        deviceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            devices,
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        val restoredPosition = devices.indexOfFirst { it.deviceId == selectedId }
        if (restoredPosition >= 0) deviceSpinner.setSelection(restoredPosition)

        deviceSpinner.isEnabled = devices.isNotEmpty() &&
            connectionState !is UsbConnectionState.Connected
        connectButton.isEnabled = devices.isNotEmpty() ||
            connectionState is UsbConnectionState.Connected

        if (devices.isEmpty() && connectionState is UsbConnectionState.Disconnected) {
            showStatus(getString(R.string.no_usb_devices), StatusTone.MUTED)
        }
    }

    override fun onDeviceAttached(deviceId: Int) {
        val index = devices.indexOfFirst { it.deviceId == deviceId }
        if (index < 0 || connectionState !is UsbConnectionState.Disconnected) return
        deviceSpinner.setSelection(index)
        usbController.connect(deviceId)
    }

    override fun onConnectionStateChanged(state: UsbConnectionState) {
        connectionState = state
        when (state) {
            UsbConnectionState.Disconnected -> {
                mainHandler.removeCallbacks(bootstrapRunnable)
                commandQueue.reset()
                lineParser.reset()
                tftStreamParser.reset()
                identityParser.reset()
                connectedDescriptor = null
                promptSeen = false
                connectButton.text = getString(R.string.connect)
                connectButton.isEnabled = devices.isNotEmpty()
                deviceSpinner.isEnabled = devices.isNotEmpty()
                showStatus(getString(R.string.status_disconnected), StatusTone.MUTED)
                deviceInfo.text = getString(R.string.device_info_waiting)
                menuTitle.text = getString(R.string.menu_title_waiting)
                menuAdapter.submitList(emptyList(), -1)
                stopScreenPreview(sendCommand = false)
                setRemoteControlsEnabled(false)
            }

            is UsbConnectionState.WaitingForPermission -> {
                connectedDescriptor = state.device
                connectButton.text = getString(R.string.disconnect)
                showStatus(getString(R.string.waiting_for_permission), StatusTone.WORKING)
                renderDeviceInfo()
                setRemoteControlsEnabled(false)
            }

            is UsbConnectionState.Opening -> {
                connectedDescriptor = state.device
                connectButton.text = getString(R.string.disconnect)
                showStatus(
                    getString(R.string.opening_device, state.device.displayName),
                    StatusTone.WORKING,
                )
                renderDeviceInfo()
                setRemoteControlsEnabled(false)
            }

            is UsbConnectionState.Connected -> {
                connectedDescriptor = state.device
                promptSeen = false
                lineParser.reset()
                tftStreamParser.reset()
                commandQueue.reset()
                identityParser.reset()
                connectButton.text = getString(R.string.disconnect)
                connectButton.isEnabled = true
                deviceSpinner.isEnabled = false
                showStatus(
                    getString(R.string.connected_to, state.device.displayName),
                    StatusTone.SUCCESS,
                )
                renderDeviceInfo()
                setRemoteControlsEnabled(true)
                appendLog(
                    "[USB] Connected ${state.device.vidPid} via " +
                        "${state.device.driverName}; 115200 8-N-1; DTR/RTS untouched",
                )
                mainHandler.removeCallbacks(bootstrapRunnable)
                mainHandler.postDelayed(bootstrapRunnable, BRUCE_BOOT_SETTLE_MS)
            }

            is UsbConnectionState.Failed -> {
                mainHandler.removeCallbacks(bootstrapRunnable)
                commandQueue.reset()
                tftStreamParser.reset()
                connectButton.text = getString(R.string.connect)
                connectButton.isEnabled = devices.isNotEmpty()
                deviceSpinner.isEnabled = devices.isNotEmpty()
                showStatus(
                    getString(R.string.connection_error, state.message),
                    StatusTone.ERROR,
                )
                stopScreenPreview(sendCommand = false)
                setRemoteControlsEnabled(false)
                appendLog("[USB] ${state.message}")
            }
        }
    }

    override fun onSerialData(data: ByteArray) {
        tftStreamParser.accept(data)
    }

    override fun onSerialError(message: String) {
        appendLog("[USB] $message")
    }

    override fun onLine(line: String) {
        val clean = ANSI_ESCAPE.replace(line, "")
        appendLog(clean)

        BruceProtocol.parseRemoteIdentity(clean)?.let {
            identityParser.setIdentity(it)
            renderDeviceInfo()
        }
        if (identityParser.consume(clean)) {
            renderDeviceInfo()
        }

        BruceProtocol.parseMenuState(clean)?.let(::renderMenu)
    }

    override fun onPrompt() {
        if (!promptSeen) {
            promptSeen = true
            appendLog("[Bruce] Prompt detected; runtime control ready")
        }
        commandQueue.onPrompt()
    }

    override fun onCommandSent(command: String) {
        appendLog("> $command")
        showStatus("Bruce is processing “$command”…", StatusTone.WORKING)
        if (command.equals(BruceProtocol.COMMAND_DISPLAY_START, ignoreCase = true)) {
            mainHandler.removeCallbacks(mirrorHandshakeRunnable)
            mainHandler.postDelayed(mirrorHandshakeRunnable, SCREEN_HANDSHAKE_TIMEOUT_MS)
        }
    }

    override fun onCommandReady(completedCommand: String?) {
        val descriptor = connectedDescriptor ?: return
        showStatus(
            "${descriptor.displayName} · Bruce ready",
            StatusTone.SUCCESS,
        )
        if (completedCommand.equals(
                BruceProtocol.COMMAND_DISPLAY_STOP,
                ignoreCase = true,
            ) && pendingUsbRelease != null
        ) {
            performPendingUsbRelease()
        }
    }

    override fun onCommandTimeout(command: String) {
        showStatus(
            getString(R.string.command_timed_out, command),
            StatusTone.ERROR,
        )
        appendLog("[Bruce] Timed out waiting for prompt after: $command")
        if (command.equals(BruceProtocol.COMMAND_DISPLAY_START, ignoreCase = true)) {
            appendLog(
                "[Screen] This Bruce build may not support `display start`, " +
                    "or the mixed serial stream could not be synchronized.",
            )
            stopScreenPreview(sendCommand = false)
        } else if (
            command.equals(BruceProtocol.COMMAND_DISPLAY_STOP, ignoreCase = true) &&
            pendingUsbRelease != null
        ) {
            performPendingUsbRelease()
        }
    }

    override fun onCommandWriteFailed(command: String) {
        showStatus("Could not write “$command”", StatusTone.ERROR)
        appendLog("[USB] Write failed: $command")
        when {
            command.equals(BruceProtocol.COMMAND_DISPLAY_START, ignoreCase = true) ->
                stopScreenPreview(sendCommand = false)

            command.equals(BruceProtocol.COMMAND_DISPLAY_STOP, ignoreCase = true) &&
                pendingUsbRelease != null -> performPendingUsbRelease()
        }
    }

    private fun renderMenu(state: BruceMenuState) {
        menuAdapter.submitList(state.options, state.activeIndex)
        val dimensions = if (state.width > 0 && state.height > 0) {
            " · ${state.width}×${state.height}"
        } else {
            ""
        }
        val count = if (state.options.isNotEmpty()) {
            val active = if (state.activeIndex >= 0) state.activeIndex + 1 else 0
            " · $active/${state.options.size}"
        } else {
            ""
        }
        menuTitle.text = "${state.title}$count$dimensions"

        if (state.activeIndex >= 0) {
            menuList.post {
                menuList.setSelection((state.activeIndex - 1).coerceAtLeast(0))
            }
        }
    }

    private fun renderDeviceInfo() {
        val descriptor = connectedDescriptor
        val usbLine = descriptor?.let {
            "${it.vidPid} · ${it.driverName} · ${it.portCount} port(s)"
        }.orEmpty()
        val identity = identityParser.identity.displayText("")
        deviceInfo.text = listOf(identity, usbLine)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .ifBlank { getString(R.string.device_info_waiting) }
    }

    private fun setRemoteControlsEnabled(enabled: Boolean) {
        remoteControlViews.forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.45f
        }
    }

    private fun showStatus(text: String, tone: StatusTone) {
        connectionStatus.text = text
        val color = when (tone) {
            StatusTone.MUTED -> R.color.bruce_text_muted
            StatusTone.WORKING -> R.color.bruce_secondary
            StatusTone.SUCCESS -> R.color.bruce_success
            StatusTone.ERROR -> R.color.bruce_error
        }
        connectionStatus.setTextColor(ContextCompat.getColor(this, color))
        connectButton.backgroundTintList = when (tone) {
            StatusTone.ERROR -> ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.bruce_error),
            )

            else -> null
        }
    }

    private fun appendLog(text: String) {
        if (text.isEmpty() && logBuffer.endsWith("\n")) return
        logBuffer.append(text).append('\n')
        if (logBuffer.length > MAX_LOG_CHARS) {
            val removeThrough = logBuffer.indexOf(
                "\n",
                logBuffer.length - MAX_LOG_CHARS,
            ).takeIf { it >= 0 } ?: (logBuffer.length - MAX_LOG_CHARS)
            logBuffer.delete(0, removeThrough + 1)
        }
        logText.text = logBuffer
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private enum class StatusTone {
        MUTED,
        WORKING,
        SUCCESS,
        ERROR,
    }

    private enum class PendingUsbRelease {
        DISCONNECT,
        FIRMWARE_INSTALLER,
    }

    private companion object {
        const val BRUCE_BOOT_SETTLE_MS = 1_200L
        const val NAVIGATION_TIMEOUT_MS = 8_000L
        const val RAW_COMMAND_TIMEOUT_MS = 60_000L
        const val SCREEN_COMMAND_TIMEOUT_MS = 30_000L
        const val SCREEN_HANDSHAKE_TIMEOUT_MS = 15_000L
        const val SCREEN_STOP_DRAIN_TIMEOUT_MS = 1_500L
        const val MAX_LOG_CHARS = 60_000

        val ANSI_ESCAPE = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")
    }
}
