package net.sourceforge.opencamera

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.client.PebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import android.content.Context
import android.util.Log
import android.graphics.Bitmap

class PebbleHelper(private val coroutineScope: CoroutineScope, private val context: Context? = null) {
    companion object {
        private const val TAG = "PebbleHelper"
        private val WATCHAPP_UUID = UUID.fromString("c187457d-3067-4062-8b1a-f8fde467b545")
        private const val KEY_CAPTURE = 1
        private const val KEY_PICTURE_TAKEN = 2
        private const val KEY_PREVIEW_DATA = 3
        private const val KEY_REQUEST_NEXT_FRAME = 4
        private const val KEY_CAPTURE_ACK = 5
    }

    private val pebbleStatusToast = ToastBoxer()
    private var pebbleSender: PebbleSender? = null
    private val imageConverter = PebbleImageConverter(context)
    private val pixelCompressor = PebblePixelCompressor()
    private val messagePacker = PebbleMessagePacker()
    private var pebbleModel: PebbleModel = PebbleModel.BASALT
    private var pebbleFormat: Int = PebbleImageConverter.FORMAT_COLOR_4BIT
    private var pebbleDithering: Int = PebbleImageConverter.DITHER_BAYER_2X2

    // Pull-based chunk state management
    private var currentChunks: List<ByteArray>? = null
    private var currentFormat: Int = -1
    private var currentFrameJob: Job? = null

    // Queue-with-replacement for frame requests
    private data class PendingFrameRequest(
        val bitmap: Bitmap,
        val model: PebbleModel,
        val format: Int,
        val ditheringAlgorithm: Int,
        val rotationDegrees: Int
    )
    private var pendingFrameRequest: PendingFrameRequest? = null

    fun onClickPebbleWatchButton(main: MainActivity) {
        if (MyDebug.LOG) {
            Log.i(TAG, "Opening app on pebble")
        }

        main.preview.showToast(pebbleStatusToast, R.string.pebble_app_open)

        if (pebbleSender == null) {
            pebbleSender = DefaultPebbleSender(main)
        }

        coroutineScope.launch {
            try {
                val result = pebbleSender?.startAppOnTheWatch(WATCHAPP_UUID)
                if (MyDebug.LOG) {
                    Log.d(TAG, "App start result: $result")
                }
                if (result == null || result.isEmpty()) {
                    main.preview.showToast(pebbleStatusToast, R.string.pebble_app_open_failed)
                }
            } catch (e: Exception) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "Error starting app on watch", e)
                }
                main.preview.showToast(pebbleStatusToast, R.string.pebble_app_open_failed)
            }
        }
    }

    fun onResume(main: MainActivity) {
        if (pebbleSender == null) {
            pebbleSender = DefaultPebbleSender(main)
        }
    }

    fun onPause(main: MainActivity) {
        // Clean up sender if needed
        pebbleSender?.let {
            coroutineScope.launch {
                try {
                    it.close()
                } catch (e: Exception) {
                    if (MyDebug.LOG) {
                        Log.e(TAG, "Error closing sender", e)
                    }
                }
            }
        }
        pebbleSender = null
    }

    fun setPebbleSettings(model: PebbleModel, format: Int, ditheringAlgorithm: Int) {
        pebbleModel = model
        pebbleFormat = format
        pebbleDithering = ditheringAlgorithm
    }

    fun cancelCurrentFrame() {
        currentFrameJob?.cancel()
        // Don't clear currentChunks/currentFormat here - let them be overwritten by the new frame
        // This prevents losing chunks during rapid frame requests while chunks are still being pulled
        if (MyDebug.LOG) {
            Log.d(TAG, "Current frame job cancelled (chunks preserved for pull-based delivery)")
        }
    }

    fun queueOrSendFrame(bitmap: Bitmap, model: PebbleModel, format: Int, ditheringAlgorithm: Int, rotationDegrees: Int = 0) {
        if (currentFrameJob?.isActive == true) {
            if (MyDebug.LOG) {
                Log.d(TAG, "Frame job active, queueing new request instead of sending immediately")
            }
            // Recycle old pending bitmap if it exists to avoid memory leak
            pendingFrameRequest?.bitmap?.recycle()

            // Make a copy of the bitmap now, since the preview reuses bitmap instances
            val config = bitmap.config ?: Bitmap.Config.ARGB_8888
            val bitmapCopy = bitmap.copy(config, false)
            pendingFrameRequest = PendingFrameRequest(bitmapCopy, model, format, ditheringAlgorithm, rotationDegrees)
            return
        }

        // No frame in progress, send immediately
        sendPreviewFrame(bitmap, model, format, ditheringAlgorithm, rotationDegrees)
    }

    fun sendChunk(chunkNumber: Int, format: Int) {
        if (!MainActivity.isAppInForeground()) {
            if (MyDebug.LOG) {
                Log.d(TAG, "App is not in foreground, skipping chunk send")
            }
            return
        }

        val chunks = currentChunks
        if (chunks == null) {
            if (MyDebug.LOG) {
                Log.d(TAG, "No chunks available, ignoring chunk request $chunkNumber")
            }
            return
        }

        if (format != currentFormat) {
            if (MyDebug.LOG) {
                Log.d(TAG, "Format mismatch: requested=$format, current=$currentFormat (stale request, ignoring)")
            }
            return
        }

        val chunk = chunks.getOrNull(chunkNumber)
        if (chunk == null) {
            if (MyDebug.LOG) {
                Log.d(TAG, "Chunk $chunkNumber out of bounds (total chunks: ${chunks.size})")
            }
            return
        }

        coroutineScope.launch {
            try {
                if (pebbleSender != null) {
                    val dictionary: Map<UInt, PebbleDictionaryItem> = mapOf(
                        KEY_PREVIEW_DATA.toUInt() to PebbleDictionaryItem.ByteArray(chunk)
                    )
                    pebbleSender?.sendDataToPebble(WATCHAPP_UUID, dictionary)

                    if (MyDebug.LOG) {
                        Log.d(TAG, "[E2E] Sent message ${chunkNumber + 1}/${chunks.size}: ${chunk.size} bytes (first 20 bytes: ${chunk.take(20).joinToString(",") { "%02x".format(it) }})")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending chunk $chunkNumber", e)
            }
        }
    }

    fun onPictureTaken(main: MainActivity) {
        if (!MainActivity.isAppInForeground()) {
            if (MyDebug.LOG) {
                Log.d(TAG, "App is not in foreground, skipping picture taken notification")
            }
            return
        }

        if (pebbleSender == null) {
            pebbleSender = DefaultPebbleSender(main)
        }

        coroutineScope.launch {
            try {
                val dictionary = mapOf(
                    KEY_PICTURE_TAKEN.toUInt() to PebbleDictionaryItem.ByteArray(currentTimestampBytes())
                )
                val result = pebbleSender?.sendDataToPebble(WATCHAPP_UUID, dictionary)
                if (MyDebug.LOG) {
                    Log.d(TAG, "Picture taken notification sent: $result")
                }
            } catch (e: Exception) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "Error sending picture taken notification", e)
                }
            }
        }
    }

    fun sendPreviewFrame(bitmap: Bitmap, model: PebbleModel, format: Int, ditheringAlgorithm: Int, rotationDegrees: Int = 0) {
        // Only send preview frames if the app is in the foreground
        if (!MainActivity.isAppInForeground()) {
            if (MyDebug.LOG) {
                Log.d(TAG, "App is not in foreground, skipping preview frame")
            }
            return
        }

        val frameStartTime = System.currentTimeMillis()
        if (MyDebug.LOG) {
            Log.d(TAG, "[TIMING] PebbleHelper.sendPreviewFrame() called at $frameStartTime (watch request -> PebbleHelper)")
        }

        // Make a copy of the bitmap because the preview reuses bitmap instances
        // If we don't copy it, by the time the coroutine runs, the bitmap may have been replaced
        val config = bitmap.config ?: Bitmap.Config.ARGB_8888
        val bitmapCopy = bitmap.copy(config, false)

        currentFrameJob = coroutineScope.launch {
            try {
                when (format) {
                    PebbleImageConverter.FORMAT_COLOR_4BIT -> {
                        sendColorPreviewFrame(bitmapCopy, model, ditheringAlgorithm, frameStartTime, rotationDegrees)
                    }
                    else -> {
                        // B&W format - single message with header
                        val conversionStartTime = System.currentTimeMillis()
                        val pixelData = imageConverter.convertBitmapToPixelData(bitmapCopy, model, ditheringAlgorithm, rotationDegrees)
                        val conversionEndTime = System.currentTimeMillis()

                        if (MyDebug.LOG) {
                            Log.d(TAG, "[E2E] B&W conversion took ${conversionEndTime - conversionStartTime}ms")
                        }

                        if (pebbleSender != null) {
                            // Add header byte and timestamp for B&W format
                            // Header: [Bit 0=0] [Bits 1-2=reserved] [Bits 3-5=format] [Bit 6=multi-message] [Bit 7=reserved]
                            var headerByte = 0
                            headerByte = headerByte or ((PebbleImageConverter.FORMAT_BW_1BIT and 0x07) shl 3)  // Bits 3-5: format
                            // Skip the leading 0x00 from currentTimestampBytes() - we only need the 4 timestamp bytes
                            val fullTimestampBytes = currentTimestampBytes()
                            val timestampBytes = fullTimestampBytes.drop(1).toByteArray()
                            val message = byteArrayOf(headerByte.toByte()) + timestampBytes + pixelData

                            val dictionary: Map<UInt, PebbleDictionaryItem> = mapOf(
                                KEY_PREVIEW_DATA.toUInt() to PebbleDictionaryItem.ByteArray(message)
                            )

                            pebbleSender?.sendDataToPebble(WATCHAPP_UUID, dictionary)
                            val sendTime = System.currentTimeMillis()
                            if (MyDebug.LOG) {
                                Log.d(TAG, "[TIMING] B&W message sent. PebbleHelper processing: ${sendTime - frameStartTime}ms (entry->send)")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending preview frame", e)
            } finally {
                bitmapCopy.recycle()

                val pending = pendingFrameRequest
                if (pending != null) {
                    pendingFrameRequest = null
                    if (MyDebug.LOG) {
                        Log.d(TAG, "Frame finished, processing pending frame request")
                    }
                    // sendPreviewFrame makes its own bitmap copy, so we can recycle after the call
                    sendPreviewFrame(pending.bitmap, pending.model, pending.format, pending.ditheringAlgorithm, pending.rotationDegrees)
                    pending.bitmap.recycle()
                }
            }
        }
    }

    private suspend fun sendColorPreviewFrame(bitmap: Bitmap, model: PebbleModel, ditheringAlgorithm: Int, frameStartTime: Long, rotationDegrees: Int = 0) {
        val conversionStartTime = System.currentTimeMillis()
        val colorImageData = imageConverter.convertBitmapToColorPixelData(bitmap, model, ditheringAlgorithm, rotationDegrees)
        val conversionEndTime = System.currentTimeMillis()

        if (MyDebug.LOG) {
            Log.d(TAG, "[E2E] Bitmap conversion took ${conversionEndTime - conversionStartTime}ms")
        }

        // Compress the packed pixel data
        val compressionStartTime = System.currentTimeMillis()
        val packedPixels = colorImageData.chunks[0]  // Single chunk from converter
        // Max chunk size accounts for message packing overhead:
        // First message: 1 (header) + 4 (timestamp) + 16 (palette) = 21 bytes overhead
        // Continuation: 1 (header) + 4 (timestamp) = 5 bytes overhead
        // Use worst case (21) to ensure all packed messages fit within 8192 bytes
        val compressedChunks = pixelCompressor.compressPixelData(packedPixels, maxChunkSize = 8192 - 21)
        val compressionEndTime = System.currentTimeMillis()

        if (MyDebug.LOG) {
            Log.d(TAG, "[E2E] Compression took ${compressionEndTime - compressionStartTime}ms (${packedPixels.size} -> ${compressedChunks.sumOf { it.size }} bytes)")
            compressedChunks.forEachIndexed { index, chunk ->
                Log.d(TAG, "[E2E] Compressed chunk $index: ${chunk.size} bytes")
            }
        }

        // Pack into messages with headers and palette
        val packingStartTime = System.currentTimeMillis()
        val timestamp = System.currentTimeMillis() / 1000L
        val messages = messagePacker.packMessages(
            compressedChunks,
            PebbleImageConverter.FORMAT_COLOR_4BIT,
            colorImageData.palette,
            timestamp
        )
        val packingEndTime = System.currentTimeMillis()

        if (MyDebug.LOG) {
            Log.d(TAG, "[E2E] Message packing took ${packingEndTime - packingStartTime}ms (${messages.size} messages)")
        }

        // Store chunks for pull-based delivery
        currentChunks = messages
        currentFormat = PebbleImageConverter.FORMAT_COLOR_4BIT

        // Send only chunk 0 immediately (watch will request subsequent chunks via REQUEST_NEXT_CHUNK)
        val sendStartTime = System.currentTimeMillis()
        if (messages.isNotEmpty() && pebbleSender != null) {
            val firstMessage = messages[0]
            val dictionary: Map<UInt, PebbleDictionaryItem> = mapOf(
                KEY_PREVIEW_DATA.toUInt() to PebbleDictionaryItem.ByteArray(firstMessage)
            )

            pebbleSender?.sendDataToPebble(WATCHAPP_UUID, dictionary)

            if (MyDebug.LOG) {
                Log.d(TAG, "[TIMING] Sent color message 1/${messages.size}: total ${firstMessage.size} bytes (first 20 bytes: ${firstMessage.take(20).joinToString(",") { "%02x".format(it) }})")
                if (messages.size > 1) {
                    Log.d(TAG, "[TIMING] Watch will request remaining ${messages.size - 1} message(s) via REQUEST_NEXT_CHUNK")
                }
            }
        }
        val sendEndTime = System.currentTimeMillis()

        if (MyDebug.LOG) {
            Log.d(TAG, "[TIMING] Color frame - PebbleHelper processing: ${sendEndTime - frameStartTime}ms (entry->send)")
            Log.d(TAG, "[TIMING] Color frame - Breakdown: conversion=${conversionEndTime - conversionStartTime}ms, compression=${compressionEndTime - compressionStartTime}ms, packing=${packingEndTime - packingStartTime}ms, send=${sendEndTime - sendStartTime}ms")
        }
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
}
