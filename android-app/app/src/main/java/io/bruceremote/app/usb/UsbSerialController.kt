package io.bruceremote.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class UsbSerialDeviceDescriptor(
    val deviceId: Int,
    val displayName: String,
    val vendorId: Int,
    val productId: Int,
    val driverName: String,
    val portCount: Int,
    val isCh9102: Boolean,
) {
    val vidPid: String
        get() = String.format(Locale.US, "%04X:%04X", vendorId, productId)

    override fun toString(): String = "$displayName · $vidPid"
}

sealed class UsbConnectionState {
    data object Disconnected : UsbConnectionState()
    data class WaitingForPermission(val device: UsbSerialDeviceDescriptor) : UsbConnectionState()
    data class Opening(val device: UsbSerialDeviceDescriptor) : UsbConnectionState()
    data class Connected(val device: UsbSerialDeviceDescriptor) : UsbConnectionState()
    data class Failed(val message: String) : UsbConnectionState()
}

/**
 * Owns Android USB permission, CDC/CH9102 probing, and the serial byte stream.
 *
 * Important: this class intentionally never calls setDTR() or setRTS(). On the
 * M5StickC Plus2 those CH9102 outputs are wired to ESP32 EN/GPIO0 and toggling
 * them can reset the device or enter its ROM bootloader.
 */
class UsbSerialController(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onDevicesChanged(devices: List<UsbSerialDeviceDescriptor>)
        fun onDeviceAttached(deviceId: Int)
        fun onConnectionStateChanged(state: UsbConnectionState)
        fun onSerialData(data: ByteArray)
        fun onSerialError(message: String)
    }

    private data class ProbedDevice(
        val device: UsbDevice,
        val driver: UsbSerialDriver,
        val descriptor: UsbSerialDeviceDescriptor,
    )

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val sessionLock = Any()

    private val customProber = UsbSerialProber(
        ProbeTable().apply {
            addProduct(WCH_VENDOR_ID, CH9102F_PRODUCT_ID, CdcAcmSerialDriver::class.java)
            addProduct(WCH_VENDOR_ID, CH9102X_PRODUCT_ID, CdcAcmSerialDriver::class.java)
        },
    )

    private var receiverRegistered = false
    private var pendingPermissionDeviceId: Int? = null
    private var probedDevices: Map<Int, ProbedDevice> = emptyMap()
    private var generation = 0

    @Volatile
    private var currentDeviceId: Int? = null

    @Volatile
    private var currentPort: UsbSerialPort? = null

    @Volatile
    private var currentConnection: UsbDeviceConnection? = null

    @Volatile
    private var ioManager: SerialInputOutputManager? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDeviceExtra()
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    if (device == null || pendingPermissionDeviceId != device.deviceId) return
                    pendingPermissionDeviceId = null
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        openDevice(device.deviceId)
                    } else {
                        listener.onConnectionStateChanged(
                            UsbConnectionState.Failed("USB permission denied"),
                        )
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    scanDevices()
                    device?.let { listener.onDeviceAttached(it.deviceId) }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device?.deviceId == currentDeviceId) {
                        disconnect("USB device detached")
                    }
                    scanDevices()
                }
            }
        }
    }

    fun start(initialIntent: Intent? = null) {
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(
                appContext,
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        scanDevices()
        handleUsbIntent(initialIntent)
    }

    fun stop() {
        suspendUsb()
        ioExecutor.shutdown()
    }

    /**
     * Gives one final short command time to leave SerialInputOutputManager's
     * asynchronous write buffer before tearing down an Activity-owned port.
     */
    fun stopAfterCommand(command: String) {
        if (!writeCommand(command)) {
            stop()
            return
        }
        mainHandler.postDelayed({ stop() }, FINAL_WRITE_DRAIN_MS)
    }

    /**
     * Releases the serial port and USB broadcasts without destroying the
     * controller. MainActivity uses this before handing USB ownership to the
     * firmware screen, then calls [start] again when that screen returns.
     */
    fun suspendUsb(onReleased: (() -> Unit)? = null) {
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(usbReceiver) }
            receiverRegistered = false
        }
        closeSession(reason = null, onReleased = onReleased)
    }

    fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val attached = intent.usbDeviceExtra() ?: return
        scanDevices()
        listener.onDeviceAttached(attached.deviceId)
    }

    fun scanDevices() {
        val found = usbManager.deviceList.values
            .mapNotNull(::probe)
            .sortedWith(
                compareByDescending<ProbedDevice> { it.descriptor.isCh9102 }
                    .thenBy { it.descriptor.displayName },
            )

        probedDevices = found.associateBy { it.device.deviceId }
        listener.onDevicesChanged(found.map { it.descriptor })
    }

    fun connect(deviceId: Int) {
        val probed = probedDevices[deviceId] ?: run {
            scanDevices()
            probedDevices[deviceId]
        }
        if (probed == null) {
            listener.onConnectionStateChanged(
                UsbConnectionState.Failed("The selected USB serial device is no longer available"),
            )
            return
        }

        if (currentDeviceId == deviceId && currentPort != null) return
        disconnect()

        if (!usbManager.hasPermission(probed.device)) {
            pendingPermissionDeviceId = deviceId
            listener.onConnectionStateChanged(
                UsbConnectionState.WaitingForPermission(probed.descriptor),
            )
            val permissionIntent = PendingIntent.getBroadcast(
                appContext,
                deviceId,
                Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            runCatching {
                usbManager.requestPermission(probed.device, permissionIntent)
            }.onFailure {
                pendingPermissionDeviceId = null
                listener.onConnectionStateChanged(
                    UsbConnectionState.Failed(it.message ?: "Unable to request USB permission"),
                )
            }
            return
        }

        openDevice(deviceId)
    }

    fun disconnect(reason: String? = null) {
        closeSession(reason = reason, onReleased = null)
    }

    private fun closeSession(
        reason: String?,
        onReleased: (() -> Unit)?,
    ) {
        val oldIo: SerialInputOutputManager?
        val oldPort: UsbSerialPort?
        val oldConnection: UsbDeviceConnection?
        synchronized(sessionLock) {
            generation += 1
            pendingPermissionDeviceId = null
            currentDeviceId = null
            oldIo = ioManager
            oldPort = currentPort
            oldConnection = currentConnection
            ioManager = null
            currentPort = null
            currentConnection = null
        }

        if (oldIo != null || oldPort != null || oldConnection != null) {
            ioExecutor.execute {
                runCatching { oldIo?.stop() }
                runCatching { oldPort?.close() }
                runCatching { oldConnection?.close() }
                onReleased?.let { callback -> mainHandler.post(callback) }
            }
        } else {
            onReleased?.let { callback -> mainHandler.post(callback) }
        }
        listener.onConnectionStateChanged(UsbConnectionState.Disconnected)
        reason?.let(listener::onSerialError)
    }

    fun writeCommand(command: String): Boolean {
        val manager = ioManager ?: return false
        return runCatching {
            manager.writeAsync("$command\n".toByteArray(StandardCharsets.UTF_8))
        }.onFailure {
            mainHandler.post {
                listener.onSerialError(it.message ?: "USB serial write failed")
            }
        }.isSuccess
    }

    private fun openDevice(deviceId: Int) {
        val probed = probedDevices[deviceId] ?: return
        val openGeneration: Int
        synchronized(sessionLock) {
            generation += 1
            openGeneration = generation
        }
        listener.onConnectionStateChanged(UsbConnectionState.Opening(probed.descriptor))

        ioExecutor.execute {
            var connection: UsbDeviceConnection? = null
            var port: UsbSerialPort? = null
            try {
                connection = usbManager.openDevice(probed.device)
                    ?: error("Android could not open the USB device")
                port = probed.driver.ports.firstOrNull()
                    ?: error("The USB serial driver exposes no ports")

                port.open(connection)
                port.setParameters(
                    BAUD_RATE,
                    UsbSerialPort.DATABITS_8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE,
                )

                // Do not call port.setDTR(...) or port.setRTS(...). See class comment.
                val manager = SerialInputOutputManager(
                    port,
                    object : SerialInputOutputManager.Listener {
                        override fun onNewData(data: ByteArray) {
                            mainHandler.post { listener.onSerialData(data) }
                        }

                        override fun onRunError(error: Exception) {
                            mainHandler.post {
                                handleRunError(openGeneration, error)
                            }
                        }
                    },
                ).apply {
                    setReadBufferSize(4096)
                    setReadTimeout(100)
                    setWriteTimeout(1_000)
                    start()
                }

                synchronized(sessionLock) {
                    if (generation != openGeneration) {
                        manager.stop()
                        port.close()
                        connection.close()
                        return@execute
                    }
                    currentDeviceId = deviceId
                    currentPort = port
                    currentConnection = connection
                    ioManager = manager
                }

                mainHandler.post {
                    listener.onConnectionStateChanged(
                        UsbConnectionState.Connected(probed.descriptor),
                    )
                }
            } catch (error: Exception) {
                runCatching { port?.close() }
                runCatching { connection?.close() }
                mainHandler.post {
                    synchronized(sessionLock) {
                        if (generation != openGeneration) return@post
                        currentDeviceId = null
                        currentPort = null
                        currentConnection = null
                        ioManager = null
                    }
                    listener.onConnectionStateChanged(
                        UsbConnectionState.Failed(
                            error.message ?: error.javaClass.simpleName,
                        ),
                    )
                }
            }
        }
    }

    private fun handleRunError(errorGeneration: Int, error: Exception) {
        synchronized(sessionLock) {
            if (generation != errorGeneration) return
        }
        val message = error.message ?: "USB serial connection stopped"
        disconnect()
        listener.onConnectionStateChanged(UsbConnectionState.Failed(message))
    }

    private fun probe(device: UsbDevice): ProbedDevice? {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: customProber.probeDevice(device)
            ?: if (device.looksLikeCdcAcm()) CdcAcmSerialDriver(device) else null
            ?: return null

        val isCh9102 = device.vendorId == WCH_VENDOR_ID &&
            device.productId in setOf(CH9102F_PRODUCT_ID, CH9102X_PRODUCT_ID)
        val productName = runCatching { device.productName }.getOrNull()
            ?.takeIf { it.isNotBlank() }
        val displayName = when {
            isCh9102 && productName != null -> "$productName (CH9102)"
            isCh9102 -> "WCH CH9102 USB serial"
            productName != null -> productName
            else -> "CDC ACM USB serial"
        }
        val driverName = driver.javaClass.simpleName.removeSuffix("SerialDriver")
        val descriptor = UsbSerialDeviceDescriptor(
            deviceId = device.deviceId,
            displayName = displayName,
            vendorId = device.vendorId,
            productId = device.productId,
            driverName = driverName,
            portCount = driver.ports.size,
            isCh9102 = isCh9102,
        )
        return ProbedDevice(device, driver, descriptor)
    }

    private fun UsbDevice.looksLikeCdcAcm(): Boolean {
        if (deviceClass == UsbConstants.USB_CLASS_COMM) {
            return true
        }
        for (index in 0 until interfaceCount) {
            val interfaceClass = getInterface(index).interfaceClass
            if (interfaceClass == UsbConstants.USB_CLASS_COMM ||
                interfaceClass == UsbConstants.USB_CLASS_CDC_DATA
            ) {
                return true
            }
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private companion object {
        const val ACTION_USB_PERMISSION = "io.bruceremote.app.USB_PERMISSION"
        const val BAUD_RATE = 115_200
        const val FINAL_WRITE_DRAIN_MS = 150L

        const val WCH_VENDOR_ID = 0x1A86
        const val CH9102F_PRODUCT_ID = 0x55D4
        const val CH9102X_PRODUCT_ID = 0x55D3
    }
}
