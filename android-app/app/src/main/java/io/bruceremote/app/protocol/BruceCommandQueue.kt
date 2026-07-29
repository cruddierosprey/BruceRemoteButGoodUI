package io.bruceremote.app.protocol

import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque

/**
 * Serializes CLI writes around Bruce's "# " prompt.
 *
 * This prevents a fast sequence of UI taps from merging commands while Bruce
 * is still executing a foreground operation.
 */
class BruceCommandQueue(
    private val writeLine: (String) -> Boolean,
    private val listener: Listener,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    interface Listener {
        fun onCommandSent(command: String)
        fun onCommandReady(completedCommand: String?)
        fun onCommandTimeout(command: String)
        fun onCommandWriteFailed(command: String)
    }

    private data class Request(
        val command: String,
        val refreshMenuAfter: Boolean,
        val timeoutMs: Long,
    )

    private val pending = ArrayDeque<Request>()
    private var active: Request? = null

    private val timeoutRunnable = Runnable {
        val timedOut = active ?: return@Runnable
        active = null
        pending.clear()
        listener.onCommandTimeout(timedOut.command)
    }

    fun enqueue(
        command: String,
        refreshMenuAfter: Boolean = false,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) {
        val sanitized = command
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
        if (sanitized.isEmpty()) return

        pending.addLast(
            Request(
                command = sanitized,
                refreshMenuAfter = refreshMenuAfter,
                timeoutMs = timeoutMs,
            ),
        )
        pump()
    }

    fun onPrompt() {
        handler.removeCallbacks(timeoutRunnable)
        val completed = active
        active = null

        if (completed?.refreshMenuAfter == true &&
            !completed.command.equals(BruceProtocol.COMMAND_OPTIONS_JSON, ignoreCase = true)
        ) {
            pending.addFirst(
                Request(
                    command = BruceProtocol.COMMAND_OPTIONS_JSON,
                    refreshMenuAfter = false,
                    timeoutMs = DEFAULT_TIMEOUT_MS,
                ),
            )
        }

        listener.onCommandReady(completed?.command)
        pump()
    }

    fun reset() {
        handler.removeCallbacks(timeoutRunnable)
        pending.clear()
        active = null
    }

    private fun pump() {
        if (active != null || pending.isEmpty()) return
        val request = pending.removeFirst()
        if (!writeLine(request.command)) {
            listener.onCommandWriteFailed(request.command)
            pump()
            return
        }

        active = request
        listener.onCommandSent(request.command)
        handler.postDelayed(timeoutRunnable, request.timeoutMs)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}
