package com.github.jamsinclair.owcamera2

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelChildren

/**
 * Manages the debug preview overlay independently of Pebble messaging.
 * Continuously updates the preview display with camera frames converted to Pebble format.
 */
class DebugPreviewController(
    private val coroutineScope: CoroutineScope,
    private val imageConverter: PebbleImageConverter
) {
    companion object {
        private const val TAG = "DebugPreviewController"
        private const val UPDATE_INTERVAL_MS = 200L  // 5fps
    }

    private var debugOverlay: PebbleDebugPreviewOverlay? = null
    private var previewProvider: (() -> Bitmap?)? = null
    private var updateHandler: Handler? = null

    private var pebbleModel: PebbleModel = PebbleModel.BASALT
    private var pebbleFormat: Int = PebbleImageConverter.FORMAT_COLOR_4BIT
    private var ditheringAlgorithm: Int = PebbleImageConverter.DITHER_BAYER_2X2

    fun setDebugOverlay(overlay: PebbleDebugPreviewOverlay) {
        debugOverlay = overlay
    }

    fun setPreviewProvider(provider: () -> Bitmap?) {
        previewProvider = provider
    }

    fun setPreviewSettings(model: PebbleModel, format: Int, ditheringAlgorithm: Int) {
        pebbleModel = model
        pebbleFormat = format
        this.ditheringAlgorithm = ditheringAlgorithm
    }

    fun onResume() {
        if (!MyDebug.PEBBLE_DEBUG_PREVIEW) {
            return
        }

        if (debugOverlay == null || previewProvider == null) {
            return
        }

        Log.d(TAG, "Starting debug preview updates")

        updateHandler = Handler(Looper.getMainLooper())
        updateDebugPreview()
        scheduleNextUpdate()
    }

    fun onPause() {
        if (MyDebug.PEBBLE_DEBUG_PREVIEW) {
            Log.d(TAG, "Stopping debug preview updates")
        }

        updateHandler?.removeCallbacksAndMessages(null)
        updateHandler = null
    }

    fun destroy() {
        onPause()
        coroutineScope.coroutineContext.cancelChildren()
    }

    private fun scheduleNextUpdate() {
        updateHandler?.postDelayed({
            if (debugOverlay != null && previewProvider != null) {
                updateDebugPreview()
                scheduleNextUpdate()
            }
        }, UPDATE_INTERVAL_MS)
    }

    private fun updateDebugPreview() {
        val bitmap = previewProvider?.invoke() ?: return
        val overlay = debugOverlay ?: return

        if (!MyDebug.PEBBLE_DEBUG_PREVIEW) {
            return
        }

        coroutineScope.launch {
            try {
                val config = bitmap.config ?: Bitmap.Config.ARGB_8888
                val bitmapCopy = bitmap.copy(config, false)

                try {
                    when (pebbleFormat) {
                        PebbleImageConverter.FORMAT_COLOR_4BIT -> {
                            val colorImageData = imageConverter.convertBitmapToColorPixelData(bitmapCopy, pebbleModel, ditheringAlgorithm)
                            val croppedWidth = pebbleModel.width - pebbleModel.actionBarWidth
                            val quantizedIndices = imageConverter.unpackColorPixels(colorImageData.chunks, croppedWidth, pebbleModel.height)
                            overlay.updatePreviewColor(pebbleModel, colorImageData.palette, quantizedIndices, croppedWidth, pebbleModel.height, ditheringAlgorithm)
                        }
                        else -> {
                            val pixelData = imageConverter.convertBitmapToPixelData(bitmapCopy, pebbleModel, ditheringAlgorithm)
                            val croppedWidth = pebbleModel.width - pebbleModel.actionBarWidth
                            overlay.updatePreviewBW(pebbleModel, pixelData, croppedWidth, pebbleModel.height, ditheringAlgorithm)
                        }
                    }
                } finally {
                    bitmapCopy.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating debug preview", e)
            }
        }
    }
}
