package com.github.jamsinclair.owcamera2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.view.View

/**
 * Debug overlay that displays the final Pebble image preview in native resolution.
 * Shows the exact pixel data as it would appear on the watch.
 */
class PebbleDebugPreviewOverlay(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    companion object {
        private const val TAG = "PebbleDebugPreviewOverlay"
    }

    private var previewBitmap: Bitmap? = null
    private var model: PebbleModel = PebbleModel.BASALT
    private var format: Int = PebbleImageConverter.FORMAT_BW_1BIT
    private var ditheringAlgorithm: Int = PebbleImageConverter.DITHER_FLOYD_STEINBERG
    private var palette: IntArray? = null

    private val paint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
    }

    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = false
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!MyDebug.LOG) {
            setMeasuredDimension(0, 0)
            return
        }

        // Use Pebble resolution at 2x size (stretch frames)
        val width = model.width * 2
        val height = model.height * 2

        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        if (!MyDebug.LOG || previewBitmap == null) {
            return
        }

        previewBitmap?.let {
            // Scale 2x for better visibility
            canvas.scale(2f, 2f)
            canvas.drawBitmap(it, 0f, 0f, paint)
            // Draw border so the overlay is visible
            canvas.drawRect(0f, 0f, it.width.toFloat(), it.height.toFloat(), borderPaint)
        }
    }

    /**
     * Update the preview display with color image data
     */
    fun updatePreviewColor(
        model: PebbleModel,
        palette: IntArray,
        quantizedIndices: IntArray,
        width: Int,
        height: Int,
        ditheringAlgorithm: Int
    ) {
        if (!MyDebug.LOG) return

        this.model = model
        this.format = PebbleImageConverter.FORMAT_COLOR_4BIT
        this.ditheringAlgorithm = ditheringAlgorithm
        this.palette = palette

        try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)

            // Convert palette indices to RGB colors
            for (i in quantizedIndices.indices) {
                val paletteIndex = quantizedIndices[i].coerceIn(0, palette.size - 1)
                val pebbleColorIndex = palette[paletteIndex]
                val rgbColor = PebbleColorPalette.PEBBLE_COLORS[pebbleColorIndex.coerceIn(0, 63)]
                // Add full alpha
                pixels[i] = 0xFF000000.toInt() or rgbColor
            }

            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            setPreviewBitmap(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating color preview", e)
        }
    }

    /**
     * Update the preview display with B&W image data
     */
    fun updatePreviewBW(
        model: PebbleModel,
        packedPixels: ByteArray,
        width: Int,
        height: Int,
        ditheringAlgorithm: Int
    ) {
        if (!MyDebug.LOG) return

        this.model = model
        this.format = PebbleImageConverter.FORMAT_BW_1BIT
        this.ditheringAlgorithm = ditheringAlgorithm
        this.palette = null

        try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            val pixels = IntArray(width * height)

            // Unpack 1-bit pixels (LSB-first, 8 pixels per byte)
            for (i in pixels.indices) {
                val byteIndex = i / 8
                val bitIndex = i % 8
                val bit = if (byteIndex < packedPixels.size) {
                    (packedPixels[byteIndex].toInt() shr bitIndex) and 0x01
                } else {
                    0
                }
                pixels[i] = if (bit == 1) Color.WHITE else Color.BLACK
            }

            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            setPreviewBitmap(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating B&W preview", e)
        }
    }

    private fun setPreviewBitmap(bitmap: Bitmap) {
        previewBitmap?.recycle()
        previewBitmap = bitmap
        requestLayout()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        previewBitmap?.recycle()
        previewBitmap = null
    }
}
