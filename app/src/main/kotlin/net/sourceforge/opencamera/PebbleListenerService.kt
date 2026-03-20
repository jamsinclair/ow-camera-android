package net.sourceforge.opencamera

import java.util.UUID
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.client.DefaultPebbleSender
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
        private const val KEY_REQUEST_NEXT_FRAME = 4  // Format: [Byte 0: instance+flags, modelId, format, ditheringAlgorithm]
        private const val KEY_TOGGLE_CAMERA = 6
        private const val KEY_REQUEST_NEXT_CHUNK = 7  // Format: [Byte 0: instance+flags, chunkNumber, format]
        private const val TIMESTAMP_STALENESS_THRESHOLD_SECONDS = 3L

        // Image format options
        const val FORMAT_BW_1BIT = 0
        const val FORMAT_COLOR_4BIT = 3  // 4-bit palette (16 colors)

        // Dithering algorithm options (for B/W images)
        const val DITHER_FLOYD_STEINBERG = 0
        const val DITHER_BAYER_2X2 = 1
        const val DITHER_BAYER_4X4 = 2
        const val DITHER_BAYER_8X8 = 3
        const val DITHER_ATKINSON = 4

        const val ACTION_PEBBLE_REQUEST_CHUNK = "net.sourceforge.opencamera.PEBBLE_REQUEST_CHUNK"
        const val EXTRA_CHUNK_NUMBER = "chunk_number"
        const val EXTRA_CHUNK_FORMAT = "chunk_format"
        const val ACTION_PEBBLE_APP_CLOSED = "net.sourceforge.opencamera.PEBBLE_APP_CLOSED"
    }

    private fun extractMessageBytes(data: PebbleDictionary): ByteArray? {
        // Check each known message key and extract bytes if present and valid
        val keys = data.keys

        if (keys.contains(KEY_CAPTURE.toUInt())) {
            val item = data[KEY_CAPTURE.toUInt()]
            if (item is PebbleDictionaryItem.ByteArray && item.value.isNotEmpty()) {
                if (MyDebug.LOG) {
                    Log.d(TAG, "Extracting message bytes from KEY_CAPTURE (${item.value.size} bytes)")
                }
                return item.value
            }
        }

        if (keys.contains(KEY_REQUEST_NEXT_FRAME.toUInt())) {
            val item = data[KEY_REQUEST_NEXT_FRAME.toUInt()]
            if (item is PebbleDictionaryItem.ByteArray && item.value.isNotEmpty()) {
                if (MyDebug.LOG) {
                    Log.d(TAG, "Extracting message bytes from KEY_REQUEST_NEXT_FRAME (${item.value.size} bytes)")
                }
                return item.value
            }
        }

        if (keys.contains(KEY_TOGGLE_CAMERA.toUInt())) {
            val item = data[KEY_TOGGLE_CAMERA.toUInt()]
            if (item is PebbleDictionaryItem.ByteArray && item.value.isNotEmpty()) {
                if (MyDebug.LOG) {
                    Log.d(TAG, "Extracting message bytes from KEY_TOGGLE_CAMERA (${item.value.size} bytes)")
                }
                return item.value
            }
        }

        if (keys.contains(KEY_REQUEST_NEXT_CHUNK.toUInt())) {
            val item = data[KEY_REQUEST_NEXT_CHUNK.toUInt()]
            if (item is PebbleDictionaryItem.ByteArray && item.value.isNotEmpty()) {
                if (MyDebug.LOG) {
                    Log.d(TAG, "Extracting message bytes from KEY_REQUEST_NEXT_CHUNK (${item.value.size} bytes)")
                }
                return item.value
            }
        }

        Log.w(TAG, "No valid message bytes found in dictionary (keys: $keys)")
        return null
    }

    private fun isTimestampFresh(bytes: ByteArray): Boolean {
        if (MyDebug.LOG) {
            Log.d(TAG, "isTimestampFresh called with ${bytes.size} bytes")
        }
        if (bytes.size < 5) {
            if (MyDebug.LOG) {
                Log.w(TAG, "Timestamp validation failed: insufficient bytes (need 5, got ${bytes.size})")
            }
            return false
        }
        val ts = ((bytes[1].toLong() and 0xFF) or
                  ((bytes[2].toLong() and 0xFF) shl 8) or
                  ((bytes[3].toLong() and 0xFF) shl 16) or
                  ((bytes[4].toLong() and 0xFF) shl 24))
        val now = System.currentTimeMillis() / 1000L
        val timeDiff = Math.abs(now - ts)
        val isFresh = timeDiff <= TIMESTAMP_STALENESS_THRESHOLD_SECONDS
        if (MyDebug.LOG) {
            Log.d(TAG, "Timestamp validation: msg_ts=$ts, now=$now, diff=${timeDiff}s, fresh=$isFresh, threshold=${TIMESTAMP_STALENESS_THRESHOLD_SECONDS}s")
        }
        return isFresh
    }

    private fun currentTimestampBytes(): ByteArray {
        val ts = System.currentTimeMillis() / 1000L
        return byteArrayOf(
            0x00,
            (ts and 0xFF).toByte(),
            ((ts shr 8) and 0xFF).toByte(),
            ((ts shr 16) and 0xFF).toByte(),
            ((ts shr 24) and 0xFF).toByte()
        )
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

        // Validate timestamp for all incoming messages
        // All message types have timestamp in bytes 1-4
        val messageBytes = extractMessageBytes(data)
        if (messageBytes != null && !isTimestampFresh(messageBytes)) {
            Log.w(TAG, "Stale message received, ignoring")
            return ReceiveResult.Ack
        } else if (messageBytes == null) {
            Log.d(TAG, "Invalid message received, ignoring")
            return ReceiveResult.Ack  // ACK immediately for stale messages to prevent retries
        }

        val captureItem = data[KEY_CAPTURE.toUInt()]
        if (captureItem != null) {
            try {
                // Parse capture message: 8 bytes
                // Byte 0: Header byte (reserved)
                // Bytes 1-4: Timestamp in seconds (32-bit little endian)
                // Bytes 5-7: Timer duration in seconds (24-bit little endian)
                val bytes = when (captureItem) {
                    is PebbleDictionaryItem.ByteArray -> captureItem.value
                    else -> byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
                }

                // Parse 24-bit little endian timer duration from bytes 5-7
                val timerDuration = if (bytes.size >= 8) {
                    ((bytes[5].toInt() and 0xFF) or
                     ((bytes[6].toInt() and 0xFF) shl 8) or
                     ((bytes[7].toInt() and 0xFF) shl 16))
                } else {
                    0
                }

                if (MyDebug.LOG) {
                    Log.d(TAG, "App Message Received (KEY_CAPTURE): timerDuration=$timerDuration")
                }

                // Send ACK back to watch immediately
                sendCaptureAck()

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
                val requestStartTime = System.currentTimeMillis()
                // Only process frame requests if the app is in the foreground
                if (!MainActivity.isAppInForeground()) {
                    if (MyDebug.LOG) {
                        Log.d(TAG, "App is not in foreground, skipping REQUEST_NEXT_FRAME")
                    }
                } else {
                    val request = parseFrameRequest(requestFrameItem)
                    if (MyDebug.LOG) {
                        Log.d(TAG, "[TIMING] REQUEST_NEXT_FRAME received at $requestStartTime: model=${request.modelStr}, format=${request.format}, dither=${request.ditheringAlgorithm}")
                    }

                    val intent = Intent(MainActivity.ACTION_PEBBLE_REQUEST_FRAME).apply {
                        putExtra(MainActivity.EXTRA_PEBBLE_MODEL, request.modelStr)
                        putExtra(MainActivity.EXTRA_PEBBLE_FORMAT, request.format)
                        putExtra(MainActivity.EXTRA_PEBBLE_DITHERING, request.ditheringAlgorithm)
                        putExtra("request_timestamp", requestStartTime)
                        setPackage(baseContext.packageName)
                    }
                    sendBroadcast(intent)
                    if (MyDebug.LOG) {
                        Log.d(TAG, "[TIMING] REQUEST_NEXT_FRAME broadcast sent at ${System.currentTimeMillis()} (service handling took ${System.currentTimeMillis() - requestStartTime}ms)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing REQUEST_NEXT_FRAME", e)
            }
        } else if (MyDebug.LOG && data[KEY_CAPTURE.toUInt()] == null && data[KEY_TOGGLE_CAMERA.toUInt()] == null) {
            Log.d(TAG, "No recognized keys found")
        }

        // Handle TOGGLE_CAMERA
        val toggleCameraItem = data[KEY_TOGGLE_CAMERA.toUInt()]
        if (toggleCameraItem != null) {
            try {
                // Only process toggle camera if the app is in the foreground
                if (!MainActivity.isAppInForeground()) {
                    if (MyDebug.LOG) {
                        Log.d(TAG, "App is not in foreground, skipping TOGGLE_CAMERA")
                    }
                } else {
                    if (MyDebug.LOG) {
                        Log.d(TAG, "TOGGLE_CAMERA received")
                    }

                    val intent = Intent(MainActivity.ACTION_PEBBLE_TOGGLE_CAMERA).apply {
                        setPackage(baseContext.packageName)
                    }
                    sendBroadcast(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing TOGGLE_CAMERA message", e)
            }
        }

        // Handle REQUEST_NEXT_CHUNK (pull-based chunk delivery)
        val requestChunkItem = data[KEY_REQUEST_NEXT_CHUNK.toUInt()]
        if (requestChunkItem != null) {
            try {
                // Only process chunk requests if the app is in the foreground
                if (!MainActivity.isAppInForeground()) {
                    if (MyDebug.LOG) {
                        Log.d(TAG, "App is not in foreground, skipping REQUEST_NEXT_CHUNK")
                    }
                } else {
                    val bytes = when (requestChunkItem) {
                        is PebbleDictionaryItem.ByteArray -> requestChunkItem.value
                        else -> byteArrayOf(0, 0, 0, 0, 0, 0, 0)
                    }

                    // Byte 0: Header (reserved)
                    // Bytes 1-4: Timestamp
                    // Byte 5: Chunk number
                    // Byte 6: Format
                    val chunkNumber = if (bytes.size > 5) bytes[5].toInt() else 0
                    val format = if (bytes.size > 6) bytes[6].toInt() else FORMAT_BW_1BIT

                    if (MyDebug.LOG) {
                        Log.d(TAG, "REQUEST_NEXT_CHUNK: chunkNumber=$chunkNumber, format=$format")
                    }

                    val intent = Intent(ACTION_PEBBLE_REQUEST_CHUNK).apply {
                        putExtra(EXTRA_CHUNK_NUMBER, chunkNumber)
                        putExtra(EXTRA_CHUNK_FORMAT, format)
                        setPackage(baseContext.packageName)
                    }
                    sendBroadcast(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing REQUEST_NEXT_CHUNK", e)
            }
        }

        return ReceiveResult.Ack
    }

    private suspend fun sendCaptureAck() {
        // Only send ACK if the app is in the foreground and in camera mode
        if (!MainActivity.isAppInForeground()) {
            if (MyDebug.LOG) {
                Log.d(TAG, "App is not in foreground, skipping capture ACK")
            }
            return
        }

        if (!MainActivity.isInCameraMode()) {
            if (MyDebug.LOG) {
                Log.d(TAG, "App is not in camera mode, skipping capture ACK")
            }
            return
        }

        try {
            val sender = DefaultPebbleSender(baseContext)
            val watchappUUID = UUID.fromString("c187457d-3067-4062-8b1a-f8fde467b545")
            val key = 5  // KEY_CAPTURE_ACK
            val dictionary: Map<UInt, PebbleDictionaryItem> = mapOf(
                key.toUInt() to PebbleDictionaryItem.ByteArray(currentTimestampBytes())
            )
            sender.sendDataToPebble(watchappUUID, dictionary)
            if (MyDebug.LOG) {
                Log.d(TAG, "Capture ACK sent to watch")
            }
        } catch (e: Exception) {
            if (MyDebug.LOG) {
                Log.e(TAG, "Error sending capture ACK", e)
            }
        }
    }

    /**
     * Parse frame request with format and dithering options.
     * Format: Byte array [Byte 0: header, Bytes 1-4: timestamp, Byte 5: modelId, Byte 6: format, Byte 7: ditheringAlgorithm]
     * Fields are optional - missing bytes use defaults
     */
    private fun parseFrameRequest(item: PebbleDictionaryItem): FrameRequest {
        val bytes = when (item) {
            is PebbleDictionaryItem.ByteArray -> item.value
            else -> byteArrayOf(0, 0, 0, 0, 0, 1, FORMAT_BW_1BIT.toByte(), DITHER_FLOYD_STEINBERG.toByte())
        }

        if (MyDebug.LOG) {
            Log.d(TAG, "parseFrameRequest - raw bytes: ${bytes.joinToString(",") { it.toString() }}")
        }

        // Byte 5: Model enum (0-5)
        val modelId = if (bytes.size > 5) bytes[5].toInt() else 1  // default: basalt
        // Byte 6: Format (0 or 3)
        val format = if (bytes.size > 6) bytes[6].toInt() else FORMAT_BW_1BIT
        // Byte 7: Dithering algorithm
        val dithering = if (bytes.size > 7) bytes[7].toInt() else DITHER_FLOYD_STEINBERG

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

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        if (MyDebug.LOG) {
            Log.d(TAG, "Pebble app closed, cancelling in-flight requests")
        }

        // Notify MainActivity to cancel in-flight requests and stop sending data
        val intent = Intent(ACTION_PEBBLE_APP_CLOSED).apply {
            setPackage(baseContext.packageName)
        }
        sendBroadcast(intent)
    }
}
