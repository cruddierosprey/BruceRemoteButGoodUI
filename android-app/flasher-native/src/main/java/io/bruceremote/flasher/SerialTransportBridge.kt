package io.bruceremote.flasher

/**
 * Synchronous byte-stream and control-line access used by the native flasher.
 *
 * The implementation must be safe to call from a background thread. Return the
 * number of bytes transferred, zero on timeout, or a negative value on failure.
 * A thrown exception is propagated out of the active flasher operation.
 *
 * The application must grant this bridge exclusive access to the serial port:
 * asynchronous readers consume SLIP replies and make flashing fail.
 */
interface SerialTransportBridge {
    /**
     * Writes up to [length] bytes starting at [offset].
     */
    fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeoutMillis: Int,
    ): Int

    /**
     * Reads up to [length] bytes into [buffer] starting at [offset].
     */
    fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeoutMillis: Int,
    ): Int

    /**
     * Reconfigures the current serial port. USB Serial/JTAG implementations may
     * treat this as a successful no-op because its byte stream is not baud-bound.
     */
    fun setBaudRate(baudRate: Int): Boolean

    /**
     * Changes both modem-control lines as one logical operation.
     *
     * [dtr] and [rts] are asserted-state booleans, matching UsbSerialPort's
     * setDTR/setRTS convention.
     */
    fun setControlLines(dtr: Boolean, rts: Boolean): Boolean

    /**
     * Discards unread boot logs and stale protocol bytes.
     */
    fun purgeInput(): Boolean

    /**
     * Handles USB Serial/JTAG re-enumeration after reset.
     *
     * Wait up to [timeoutMillis], reopen the newly attached device, and atomically
     * replace the connection used by all other methods. Classic UART bridges can
     * simply return true.
     */
    fun awaitReconnect(timeoutMillis: Int): Boolean
}

