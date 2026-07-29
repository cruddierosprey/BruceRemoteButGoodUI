package io.bruceremote.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import io.bruceremote.app.protocol.BruceTftPacket
import io.bruceremote.app.protocol.BruceTftProtocol
import kotlin.math.min

/**
 * Replays Bruce's vector TFT log into an Android bitmap.
 *
 * This intentionally matches the existing Bruce WebUI navigator protocol. It
 * is a useful live preview for menus and text-heavy tools, but it cannot invent
 * pixels that stock Bruce never logs (notably some images and sprite pushes).
 */
class BruceScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private var screenBitmap = Bitmap.createBitmap(
        DEFAULT_WIDTH,
        DEFAULT_HEIGHT,
        Bitmap.Config.ARGB_8888,
    )
    private var screenCanvas = Canvas(screenBitmap)
    private var hasDrawing = false
    private var waitingMessage = "Start screen preview"
    private var rotation = 0

    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val bitmapPaint = Paint().apply {
        isFilterBitmap = false
    }
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textAlign = Paint.Align.CENTER
        textSize = 14f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    init {
        screenCanvas.drawColor(Color.BLACK)
        contentDescription = waitingMessage
    }

    fun reset(message: String = "Waiting for Bruce display data…") {
        replaceBitmap(DEFAULT_WIDTH, DEFAULT_HEIGHT)
        hasDrawing = false
        waitingMessage = message
        contentDescription = message
        invalidate()
    }

    fun applyPacket(packet: BruceTftPacket) {
        when (packet.function) {
            BruceTftProtocol.SCREEN_INFO -> {
                val width = packet.unsigned16(3)
                val height = packet.unsigned16(5)
                if (width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) {
                    rotation = packet.unsigned8(7)
                    if (screenBitmap.width != width || screenBitmap.height != height) {
                        replaceBitmap(width, height)
                    }
                    waitingMessage = "Waiting for the next Bruce redraw…"
                    contentDescription = "Bruce screen $width by $height, rotation $rotation"
                }
            }

            BruceTftProtocol.FILL_SCREEN -> {
                screenCanvas.drawColor(rgb565(packet.unsigned16(3)))
                markDrawn()
            }

            BruceTftProtocol.DRAW_RECT,
            BruceTftProtocol.FILL_RECT,
            -> {
                configurePaint(
                    packet.unsigned16(11),
                    packet.function == BruceTftProtocol.FILL_RECT,
                )
                screenCanvas.drawRect(
                    packet.signed16(3).toFloat(),
                    packet.signed16(5).toFloat(),
                    (packet.signed16(3) + packet.unsigned16(7)).toFloat(),
                    (packet.signed16(5) + packet.unsigned16(9)).toFloat(),
                    drawPaint,
                )
                markDrawn()
            }

            BruceTftProtocol.DRAW_ROUND_RECT,
            BruceTftProtocol.FILL_ROUND_RECT,
            -> {
                configurePaint(
                    packet.unsigned16(13),
                    packet.function == BruceTftProtocol.FILL_ROUND_RECT,
                )
                val x = packet.signed16(3).toFloat()
                val y = packet.signed16(5).toFloat()
                val radius = packet.unsigned16(11).toFloat()
                screenCanvas.drawRoundRect(
                    RectF(
                        x,
                        y,
                        x + packet.unsigned16(7),
                        y + packet.unsigned16(9),
                    ),
                    radius,
                    radius,
                    drawPaint,
                )
                markDrawn()
            }

            BruceTftProtocol.DRAW_CIRCLE,
            BruceTftProtocol.FILL_CIRCLE,
            -> {
                configurePaint(
                    packet.unsigned16(9),
                    packet.function == BruceTftProtocol.FILL_CIRCLE,
                )
                screenCanvas.drawCircle(
                    packet.signed16(3).toFloat(),
                    packet.signed16(5).toFloat(),
                    packet.unsigned16(7).toFloat(),
                    drawPaint,
                )
                markDrawn()
            }

            BruceTftProtocol.DRAW_TRIANGLE,
            BruceTftProtocol.FILL_TRIANGLE,
            -> {
                configurePaint(
                    packet.unsigned16(15),
                    packet.function == BruceTftProtocol.FILL_TRIANGLE,
                )
                val path = Path().apply {
                    moveTo(packet.signed16(3).toFloat(), packet.signed16(5).toFloat())
                    lineTo(packet.signed16(7).toFloat(), packet.signed16(9).toFloat())
                    lineTo(packet.signed16(11).toFloat(), packet.signed16(13).toFloat())
                    close()
                }
                screenCanvas.drawPath(path, drawPaint)
                markDrawn()
            }

            BruceTftProtocol.DRAW_ELLIPSE,
            BruceTftProtocol.FILL_ELLIPSE,
            -> {
                configurePaint(
                    packet.unsigned16(11),
                    packet.function == BruceTftProtocol.FILL_ELLIPSE,
                )
                val cx = packet.signed16(3).toFloat()
                val cy = packet.signed16(5).toFloat()
                val rx = packet.unsigned16(7).toFloat()
                val ry = packet.unsigned16(9).toFloat()
                screenCanvas.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), drawPaint)
                markDrawn()
            }

            BruceTftProtocol.DRAW_LINE -> {
                configurePaint(packet.unsigned16(11), fill = false)
                screenCanvas.drawLine(
                    packet.signed16(3).toFloat(),
                    packet.signed16(5).toFloat(),
                    packet.signed16(7).toFloat(),
                    packet.signed16(9).toFloat(),
                    drawPaint,
                )
                markDrawn()
            }

            BruceTftProtocol.DRAW_ARC -> {
                configurePaint(packet.unsigned16(15), fill = false)
                val outer = packet.unsigned16(7).toFloat()
                val inner = packet.unsigned16(9).toFloat()
                val radius = (outer + inner) / 2f
                drawPaint.strokeWidth = (outer - inner + 1f).coerceAtLeast(1f)
                val cx = packet.signed16(3).toFloat()
                val cy = packet.signed16(5).toFloat()
                val start = packet.unsigned16(11).toFloat() + 90f
                val sweep = angleSweep(
                    packet.unsigned16(11),
                    packet.unsigned16(13),
                )
                screenCanvas.drawArc(
                    RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                    start,
                    sweep,
                    false,
                    drawPaint,
                )
                markDrawn()
            }

            BruceTftProtocol.DRAW_WIDE_LINE -> {
                configurePaint(packet.unsigned16(13), fill = false)
                drawPaint.strokeWidth = packet.unsigned16(11).coerceAtLeast(1).toFloat()
                drawPaint.strokeCap = Paint.Cap.ROUND
                screenCanvas.drawLine(
                    packet.signed16(3).toFloat(),
                    packet.signed16(5).toFloat(),
                    packet.signed16(7).toFloat(),
                    packet.signed16(9).toFloat(),
                    drawPaint,
                )
                drawPaint.strokeCap = Paint.Cap.BUTT
                markDrawn()
            }

            BruceTftProtocol.DRAW_CENTRE_STRING,
            BruceTftProtocol.DRAW_RIGHT_STRING,
            BruceTftProtocol.DRAW_STRING,
            BruceTftProtocol.PRINT,
            -> {
                drawTextPacket(packet)
                markDrawn()
            }

            BruceTftProtocol.DRAW_IMAGE -> {
                // Stock serial mirroring supplies a filesystem path, not image
                // pixels. Preserve the backing screen and add a small marker so
                // the user knows why this region cannot be exact.
                val path = packet.utf8(12)
                val x = packet.signed16(3).toFloat()
                val y = packet.signed16(5).toFloat()
                configurePaint(0x7bef, fill = false)
                screenCanvas.drawRect(x, y, x + 54f, y + 18f, drawPaint)
                drawPaint.style = Paint.Style.FILL
                drawPaint.textSize = 7f
                drawPaint.typeface = Typeface.MONOSPACE
                screenCanvas.drawText(
                    path.substringAfterLast('/').take(8).ifBlank { "image" },
                    x + 2f,
                    y + 12f,
                    drawPaint,
                )
                markDrawn()
            }

            BruceTftProtocol.DRAW_PIXEL -> {
                configurePaint(packet.unsigned16(7), fill = true)
                screenCanvas.drawRect(
                    packet.signed16(3).toFloat(),
                    packet.signed16(5).toFloat(),
                    packet.signed16(3) + 1f,
                    packet.signed16(5) + 1f,
                    drawPaint,
                )
                markDrawn()
            }

            BruceTftProtocol.DRAW_FAST_V_LINE -> {
                configurePaint(packet.unsigned16(9), fill = true)
                val x = packet.signed16(3).toFloat()
                val y = packet.signed16(5).toFloat()
                screenCanvas.drawRect(x, y, x + 1f, y + packet.unsigned16(7), drawPaint)
                markDrawn()
            }

            BruceTftProtocol.DRAW_FAST_H_LINE -> {
                configurePaint(packet.unsigned16(9), fill = true)
                val x = packet.signed16(3).toFloat()
                val y = packet.signed16(5).toFloat()
                screenCanvas.drawRect(x, y, x + packet.unsigned16(7), y + 1f, drawPaint)
                markDrawn()
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val scale = min(
            width.toFloat() / screenBitmap.width,
            height.toFloat() / screenBitmap.height,
        )
        val renderedWidth = screenBitmap.width * scale
        val renderedHeight = screenBitmap.height * scale
        val left = (width - renderedWidth) / 2f
        val top = (height - renderedHeight) / 2f
        canvas.drawBitmap(
            screenBitmap,
            null,
            RectF(left, top, left + renderedWidth, top + renderedHeight),
            bitmapPaint,
        )

        if (!hasDrawing) {
            canvas.drawText(
                waitingMessage,
                width / 2f,
                height / 2f - (placeholderPaint.ascent() + placeholderPaint.descent()) / 2f,
                placeholderPaint,
            )
        }
    }

    private fun drawTextPacket(packet: BruceTftPacket) {
        val x = packet.signed16(3).toFloat()
        val top = packet.signed16(5).toFloat()
        val scale = packet.unsigned16(7).coerceIn(1, 8)
        val foreground = packet.unsigned16(9)
        val background = packet.unsigned16(11)
        val lines = packet.utf8(13)
            .replace("\\n", "\n")
            .split('\n')

        drawPaint.typeface = Typeface.MONOSPACE
        drawPaint.textSize = 8f * scale
        drawPaint.style = Paint.Style.FILL
        drawPaint.color = rgb565(foreground)
        drawPaint.strokeWidth = 1f
        drawPaint.textAlign = when (packet.function) {
            BruceTftProtocol.DRAW_CENTRE_STRING -> Paint.Align.CENTER
            BruceTftProtocol.DRAW_RIGHT_STRING -> Paint.Align.RIGHT
            else -> Paint.Align.LEFT
        }

        val charWidth = 6f * scale
        val lineHeight = 8f * scale
        val metrics = drawPaint.fontMetrics
        lines.forEachIndexed { index, line ->
            val y = top + index * lineHeight
            val textWidth = line.length * charWidth
            val left = when (drawPaint.textAlign) {
                Paint.Align.CENTER -> x - textWidth / 2f
                Paint.Align.RIGHT -> x - textWidth
                else -> x
            }
            if (background != foreground) {
                val oldColor = drawPaint.color
                drawPaint.color = rgb565(background)
                screenCanvas.drawRect(left, y, left + textWidth, y + lineHeight, drawPaint)
                drawPaint.color = oldColor
            }
            screenCanvas.drawText(line, x, y - metrics.top, drawPaint)
        }
    }

    private fun configurePaint(color565: Int, fill: Boolean) {
        drawPaint.color = rgb565(color565)
        drawPaint.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
        drawPaint.strokeWidth = 1f
        drawPaint.strokeCap = Paint.Cap.BUTT
        drawPaint.textAlign = Paint.Align.LEFT
    }

    private fun markDrawn() {
        hasDrawing = true
        contentDescription =
            "Live Bruce screen ${screenBitmap.width} by ${screenBitmap.height}, rotation $rotation"
    }

    private fun replaceBitmap(width: Int, height: Int) {
        screenBitmap.recycle()
        screenBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        screenCanvas = Canvas(screenBitmap)
        screenCanvas.drawColor(Color.BLACK)
    }

    private fun angleSweep(start: Int, end: Int): Float {
        val delta = end - start
        if (delta != 0 && delta % 360 == 0) {
            return if (delta > 0) 360f else -360f
        }
        val normalized = delta % 360
        return if (normalized >= 0) normalized.toFloat() else (normalized + 360).toFloat()
    }

    private fun rgb565(value: Int): Int {
        val red = ((value ushr 11) and 0x1f) * 255 / 31
        val green = ((value ushr 5) and 0x3f) * 255 / 63
        val blue = (value and 0x1f) * 255 / 31
        return Color.rgb(red, green, blue)
    }

    private companion object {
        const val DEFAULT_WIDTH = 240
        const val DEFAULT_HEIGHT = 135
        const val MAX_DIMENSION = 480
    }
}
