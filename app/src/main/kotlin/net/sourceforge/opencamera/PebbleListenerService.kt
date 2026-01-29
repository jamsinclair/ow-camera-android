package net.sourceforge.opencamera

import java.util.UUID
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.WatchIdentifier
import io.rebble.pebblekit2.common.model.ReceiveResult
import android.content.Intent
import android.util.Log

class PebbleListenerService : BasePebbleListenerService() {
    companion object {
        private const val TAG = "PebbleListenerService"
        private const val KEY_CAPTURE = 1
        private const val KEY_REQUEST_NEXT_FRAME = 4  // Format: [modelId, format, ditheringAlgorithm]
        private const val KEY_TOGGLE_CAMERA = 5

        // Image format options
        const val FORMAT_BW_1BIT = 0
        const val FORMAT_COLOR_FUTURE = 1

        // Dithering algorithm options (for B/W images)
        const val DITHER_FLOYD_STEINBERG = 0
        const val DITHER_BAYER_2X2 = 1
        const val DITHER_BAYER_4X4 = 2
        const val DITHER_BAYER_8X8 = 3
        const val DITHER_ATKINSON = 4
    }

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier
    ): ReceiveResult {
        if (MyDebug.LOG) {
            Log.d(TAG, "Message received from watch app: $watchappUUID")
            Log.d(TAG, "Dictionary keys: ${data.keys}")
        }

        val captureItem = data[KEY_CAPTURE.toUInt()]
        if (captureItem != null) {
            try {
                val timerDuration = when (captureItem) {
                    is PebbleDictionaryItem.UInt8 -> captureItem.value.toInt()
                    is PebbleDictionaryItem.UInt16 -> captureItem.value.toInt()
                    is PebbleDictionaryItem.UInt32 -> captureItem.value.toInt()
                    is PebbleDictionaryItem.Int8 -> captureItem.value.toInt()
                    is PebbleDictionaryItem.Int16 -> captureItem.value.toInt()
                    is PebbleDictionaryItem.Int32 -> captureItem.value
                    else -> 0
                }

                if (MyDebug.LOG) {
                    Log.d(TAG, "App Message Received (KEY_CAPTURE): $timerDuration")
                }

                // Send broadcast to MainActivity with the timer duration
                val intent = Intent(MainActivity.ACTION_PEBBLE_CAPTURE).apply {
                    putExtra(MainActivity.EXTRA_TIMER_DURATION, timerDuration)
                    setPackage(baseContext.packageName)
                }
                if (MyDebug.LOG) {
                    Log.d(TAG, "Sending broadcast with action: ${intent.action}, timerDuration: $timerDuration")
                }
                sendBroadcast(intent)

            } catch (e: Exception) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "Error processing capture message", e)
                }
            }
        }

        // Handle REQUEST_NEXT_FRAME with format and dithering options
        val requestFrameItem = data[KEY_REQUEST_NEXT_FRAME.toUInt()]
        if (requestFrameItem != null) {
            try {
                val request = parseFrameRequest(requestFrameItem)
                if (MyDebug.LOG) {
                    Log.d(TAG, "REQUEST_NEXT_FRAME: model=${request.modelStr}, format=${request.format}, dither=${request.ditheringAlgorithm}")
                }

                val intent = Intent(MainActivity.ACTION_PEBBLE_REQUEST_FRAME).apply {
                    putExtra(MainActivity.EXTRA_PEBBLE_MODEL, request.modelStr)
                    putExtra(MainActivity.EXTRA_PEBBLE_FORMAT, request.format)
                    putExtra(MainActivity.EXTRA_PEBBLE_DITHERING, request.ditheringAlgorithm)
                    setPackage(baseContext.packageName)
                }
                sendBroadcast(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing REQUEST_NEXT_FRAME", e)
            }
        }

        // Handle TOGGLE_CAMERA
        val toggleCameraItem = data[KEY_TOGGLE_CAMERA.toUInt()]
        if (toggleCameraItem != null) {
            try {
                if (MyDebug.LOG) {
                    Log.d(TAG, "TOGGLE_CAMERA received")
                }

                val intent = Intent(MainActivity.ACTION_PEBBLE_TOGGLE_CAMERA).apply {
                    setPackage(baseContext.packageName)
                }
                sendBroadcast(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing TOGGLE_CAMERA message", e)
            }
        } else if (MyDebug.LOG && captureItem == null && requestFrameItem == null) {
            Log.d(TAG, "Message received but no recognized keys found (capture, frame request, or toggle camera)")
        }

        return ReceiveResult.Ack
    }

    /**
     * Parse frame request with format and dithering options.
     * Format: Byte array [modelId, format, ditheringAlgorithm]
     * Fields are optional - missing bytes use defaults
     */
    private fun parseFrameRequest(item: PebbleDictionaryItem): FrameRequest {
        val bytes = when (item) {
            is PebbleDictionaryItem.ByteArray -> item.value
            else -> byteArrayOf(1, FORMAT_BW_1BIT.toByte(), DITHER_FLOYD_STEINBERG.toByte())
        }

        if (MyDebug.LOG) {
            Log.d(TAG, "parseFrameRequest - raw bytes: ${bytes.joinToString(",") { it.toString() }}")
        }

        val modelId = if (bytes.isNotEmpty()) bytes[0].toInt() else 1  // default: basalt
        val format = if (bytes.size > 1) bytes[1].toInt() else FORMAT_BW_1BIT
        val dithering = if (bytes.size > 2) bytes[2].toInt() else DITHER_FLOYD_STEINBERG

        if (MyDebug.LOG) {
            Log.d(TAG, "parseFrameRequest - parsed: modelId=$modelId, format=$format, dithering=$dithering")
        }

        return FrameRequest(
            modelStr = modelIdToString(modelId),
            format = format,
            ditheringAlgorithm = dithering
        )
    }

    private fun modelIdToString(modelId: Int): String {
        return when (modelId) {
            0 -> "aplite"
            1 -> "basalt"
            2 -> "diorite"
            3 -> "flint"
            4 -> "chalk"
            5 -> "emery"
            else -> "basalt"
        }
    }

    /**
     * Data class for parsed frame request with format and dithering options
     */
    private data class FrameRequest(
        val modelStr: String,
        val format: Int,
        val ditheringAlgorithm: Int
    )
}
