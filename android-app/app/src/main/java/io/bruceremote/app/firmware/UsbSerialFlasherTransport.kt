package io.bruceremote.app.firmware

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import io.bruceremote.flasher.SerialTransportBridge
import java.io.Closeable
import java.io.IOException
import java.util.Locale

data class FlasherUsbDevice(
    val deviceId: Int,
    val displayName: String,
    val vendorId: Int,
    val productId: Int,
    val driverName: String,
    val portCount: Int,
) {
    val vidPid: String
        get() = String.format(Locale.US, "%04X:%04X", vendorId, productId)

    override fun toString(): String = "$displayName · $vidPid"
}

/**
 * Exclusive, synchronous USB serial transport for the JNI flasher.
 *
 * This class never starts SerialInputOutputManager. Every byte received from the
 * port therefore belongs to esp-serial-flasher. Callers must open, use, and close
 * it on a worker thread.
 */
class UsbSerialFlasherTransport private constructor(
    private val usbManager: UsbManager,
    initialDevice: UsbDevice,
    initialDriver: UsbSerialDriver,
    private val stableSerialNumber: String?,
) : SerialTransportBridge, Closeable {
    private val lock = Any()
    private val vendorId = initialDevice.vendorId
    private val productId = initialDevice.productId

    private var device: UsbDevice = initialDevice
    private var connection: UsbDeviceConnection? = null
    private var port: UsbSerialPort? = null
    private var baudRate: Int = INITIAL_BAUD_RATE
    private var dtrState: Boolean? = null
    private var rtsState: Boolean? = null

    init {
        synchronized(lock) {
            openLocked(initialDevice, initialDriver)
        }
    }

    override fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeoutMillis: Int,
    ): Int = synchronized(lock) {
        checkRange(buffer, offset, length)
        if (length == 0) return@synchronized 0
        val activePort = requirePort()
        val outgoing = if (offset == 0 && length == buffer.size) {
            buffer
        } else {
            buffer.copyOfRange(offset, offset + length)
        }
        activePort.write(outgoing, timeoutMillis)
        length
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeoutMillis: Int,
    ): Int = synchronized(lock) {
        checkRange(buffer, offset, length)
        if (length == 0) return@synchronized 0
        val activePort = requirePort()
        if (offset == 0 && length == buffer.size) {
            activePort.read(buffer, timeoutMillis)
        } else {
            val incoming = ByteArray(length)
            val count = activePort.read(incoming, timeoutMillis)
            if (count > 0) {
                incoming.copyInto(buffer, destinationOffset = offset, endIndex = count)
            }
            count
        }
    }

    override fun setBaudRate(baudRate: Int): Boolean = synchronized(lock) {
        require(baudRate > 0) { "Baud rate must be positive" }
        requirePort().setParameters(
            baudRate,
            UsbSerialPort.DATABITS_8,
            UsbSerialPort.STOPBITS_1,
            UsbSerialPort.PARITY_NONE,
        )
        this.baudRate = baudRate
        true
    }

    override fun setControlLines(dtr: Boolean, rts: Boolean): Boolean =
        synchronized(lock) {
            val activePort = requirePort()

            // UsbSerialPort exposes separate requests. When both lines change,
            // assert the new line before deasserting the old one so a classic
            // two-transistor auto-reset circuit crosses (1,1), not (0,0).
            if (dtrState != dtr && dtr) activePort.setDTR(true)
            if (rtsState != rts && rts) activePort.setRTS(true)
            if (dtrState != dtr && !dtr) activePort.setDTR(false)
            if (rtsState != rts && !rts) activePort.setRTS(false)

            dtrState = dtr
            rtsState = rts
            true
        }

    override fun purgeInput(): Boolean = synchronized(lock) {
        val activePort = requirePort()
        try {
            activePort.purgeHwBuffers(false, true)
            true
        } catch (_: UnsupportedOperationException) {
            drainInputLocked(activePort)
        }
    }

    /**
     * The shipped Cardputer ADV profile deliberately uses manual bootloader
     * entry, so this is not used in the normal workflow. It is nevertheless
     * implemented defensively for a future USB-JTAG reset strategy.
     */
    override fun awaitReconnect(timeoutMillis: Int): Boolean {
        require(timeoutMillis > 0)
        synchronized(lock) {
            closeResourcesLocked()
        }

        val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadlineNanos) {
            val candidates = usbManager.deviceList.values.filter(::matchesOriginalDevice)
            val candidate = candidates.singleOrNull()
            if (candidate != null && usbManager.hasPermission(candidate)) {
                val driver = probe(candidate)?.driver
                if (driver != null) {
                    val reopened = runCatching {
                        synchronized(lock) {
                            device = candidate
                            openLocked(candidate, driver)
                        }
                    }.isSuccess
                    if (reopened) return true
                }
            }
            try {
                Thread.sleep(RECONNECT_POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    override fun close() {
        synchronized(lock) {
            closeResourcesLocked()
        }
    }

    private fun matchesOriginalDevice(candidate: UsbDevice): Boolean {
        if (candidate.vendorId != vendorId || candidate.productId != productId) return false
        val expectedSerial = stableSerialNumber ?: return true
        val actualSerial = runCatching { candidate.serialNumber }.getOrNull()
        return actualSerial == expectedSerial
    }

    private fun openLocked(device: UsbDevice, driver: UsbSerialDriver) {
        if (!usbManager.hasPermission(device)) {
            throw SecurityException("Android USB permission is not granted for the selected device")
        }
        val openedConnection = usbManager.openDevice(device)
            ?: throw IOException("Android could not open the selected USB device")
        val openedPort = driver.ports.firstOrNull()
            ?: run {
                openedConnection.close()
                throw IOException("The USB serial driver exposes no ports")
            }

        try {
            openedPort.open(openedConnection)
            openedPort.setParameters(
                baudRate,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE,
            )
            // Explicitly deassert DTR and RTS after open. The CH9102 on
            // M5StickC Plus2 wires DTR to ESP32 EN (reset); leaving it high
            // after open causes an immediate device reset.
            openedPort.setDTR(false)
            openedPort.setRTS(false)
        } catch (error: Exception) {
            runCatching { openedPort.close() }
            openedConnection.close()
            throw error
        }

        connection = openedConnection
        port = openedPort
        dtrState = null
        rtsState = null
    }

    private fun closeResourcesLocked() {
        val oldPort = port
        val oldConnection = connection
        port = null
        connection = null
        dtrState = null
        rtsState = null
        runCatching { oldPort?.close() }
        runCatching { oldConnection?.close() }
    }

    private fun requirePort(): UsbSerialPort =
        port ?: throw IOException("USB serial transport is closed")

    private fun drainInputLocked(activePort: UsbSerialPort): Boolean =
        try {
            val scratch = ByteArray(512)
            repeat(MAX_DRAIN_READS) {
                if (activePort.read(scratch, DRAIN_TIMEOUT_MILLIS) <= 0) return true
            }
            true
        } catch (_: IOException) {
            false
        }

    private fun checkRange(buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) {
            "Invalid byte range offset=$offset length=$length size=${buffer.size}"
        }
    }

    companion object {
        private const val INITIAL_BAUD_RATE = 115_200
        private const val RECONNECT_POLL_MILLIS = 50L
        private const val DRAIN_TIMEOUT_MILLIS = 5
        private const val MAX_DRAIN_READS = 32

        private const val WCH_VENDOR_ID = 0x1A86
        private const val CH9102F_PRODUCT_ID = 0x55D4
        private const val CH9102X_PRODUCT_ID = 0x55D3

        private val customProber = UsbSerialProber(
            ProbeTable().apply {
                addProduct(WCH_VENDOR_ID, CH9102F_PRODUCT_ID, CdcAcmSerialDriver::class.java)
                addProduct(WCH_VENDOR_ID, CH9102X_PRODUCT_ID, CdcAcmSerialDriver::class.java)
            },
        )

        fun enumerate(context: Context): List<FlasherUsbDevice> {
            val manager = context.applicationContext
                .getSystemService(Context.USB_SERVICE) as UsbManager
            return manager.deviceList.values
                .mapNotNull { probe(it)?.descriptor }
                .sortedWith(
                    compareBy<FlasherUsbDevice> { it.vendorId != WCH_VENDOR_ID }
                        .thenBy { it.displayName },
                )
        }

        fun open(context: Context, deviceId: Int): UsbSerialFlasherTransport {
            val manager = context.applicationContext
                .getSystemService(Context.USB_SERVICE) as UsbManager
            val device = manager.deviceList.values.firstOrNull { it.deviceId == deviceId }
                ?: throw IOException("The selected USB device is no longer attached")
            val probed = probe(device)
                ?: throw IOException("The selected USB device has no supported serial interface")
            val serialNumber = if (manager.hasPermission(device)) {
                runCatching { device.serialNumber }.getOrNull()
            } else {
                null
            }
            return UsbSerialFlasherTransport(
                usbManager = manager,
                initialDevice = device,
                initialDriver = probed.driver,
                stableSerialNumber = serialNumber,
            )
        }

        private data class Probed(
            val driver: UsbSerialDriver,
            val descriptor: FlasherUsbDevice,
        )

        private fun probe(device: UsbDevice): Probed? {
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                ?: customProber.probeDevice(device)
                ?: if (device.looksLikeCdcAcm()) {
                    runCatching { CdcAcmSerialDriver(device) }.getOrNull()
                } else {
                    null
                }
                ?: return null
            if (driver.ports.isEmpty()) return null

            val isCh9102 = device.vendorId == WCH_VENDOR_ID &&
                device.productId in setOf(CH9102F_PRODUCT_ID, CH9102X_PRODUCT_ID)
            val productName = runCatching { device.productName }.getOrNull()
                ?.takeIf(String::isNotBlank)
            val displayName = when {
                isCh9102 && productName != null -> "$productName (CH9102)"
                isCh9102 -> "WCH CH9102 USB serial"
                productName != null -> productName
                else -> "CDC ACM USB serial"
            }
            return Probed(
                driver = driver,
                descriptor = FlasherUsbDevice(
                    deviceId = device.deviceId,
                    displayName = displayName,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    driverName = driver.javaClass.simpleName.removeSuffix("SerialDriver"),
                    portCount = driver.ports.size,
                ),
            )
        }

        private fun UsbDevice.looksLikeCdcAcm(): Boolean {
            if (deviceClass == UsbConstants.USB_CLASS_COMM) return true
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
    }
}
