package com.github.jamsinclair.owcamera2

import android.util.Log

/**
 * Packs pixel data into Pebble-compatible messages with headers and palette.
 * Handles both single and multi-message scenarios for color and B&W formats.
 */
class PebbleMessagePacker {
    companion object {
        private const val TAG = "PebbleMessagePacker"
        private const val MAX_MESSAGE_SIZE = 8192
    }

    /**
     * Packs compressed pixel chunks into complete messages.
     *
     * @param compressedChunks List of compressed pixel data chunks
     * @param format Format type (FORMAT_BW_1BIT, FORMAT_COLOR_4BIT)
     * @param palette Pebble color palette indices (16 entries for 4-bit, null for B&W)
     * @param timestamp Unix timestamp in seconds for staleness validation
     * @return List of complete message ByteArrays, each ready to send
     * @throws IllegalArgumentException if palette doesn't match format
     */
    fun packMessages(
        compressedChunks: List<ByteArray>,
        format: Int,
        palette: IntArray? = null,
        timestamp: Long = System.currentTimeMillis() / 1000L
    ): List<ByteArray> {
        if (compressedChunks.isEmpty()) {
            Log.w(TAG, "No chunks to pack")
            return emptyList()
        }

        validatePaletteForFormat(format, palette)

        val isMultiMessage = compressedChunks.size > 1
        val messages = mutableListOf<ByteArray>()

        for ((index, chunk) in compressedChunks.withIndex()) {
            val isFirstMessage = index == 0
            val isLastMessage = index == compressedChunks.size - 1

            val message = if (isFirstMessage) {
                packFirstMessage(chunk, format, palette, isMultiMessage, timestamp)
            } else {
                packContinuationMessage(chunk, index - 1, isLastMessage, timestamp)
            }

            messages.add(message)
        }

        Log.d(TAG, "Packed ${compressedChunks.size} chunks into ${messages.size} messages")
        return messages
    }

    /**
     * Packs the first message with header, timestamp, palette, and initial chunk.
     */
    private fun packFirstMessage(
        pixelChunk: ByteArray,
        format: Int,
        palette: IntArray?,
        isMultiMessage: Boolean,
        timestamp: Long
    ): ByteArray {
        val headerByte = createHeaderByte(format, isMultiMessage)
        val timestampBytes = timestampToBytes(timestamp)
        val paletteBytes = palette?.let { createPaletteBytes(it) } ?: byteArrayOf()

        val totalSize = 1 + timestampBytes.size + paletteBytes.size + pixelChunk.size
        if (totalSize > MAX_MESSAGE_SIZE) {
            Log.w(TAG, "First message exceeds max size: $totalSize bytes")
        }

        return byteArrayOf(headerByte) + timestampBytes + paletteBytes + pixelChunk
    }

    /**
     * Packs continuation messages with continuation header, timestamp, and chunk.
     */
    private fun packContinuationMessage(
        pixelChunk: ByteArray,
        chunkNumber: Int,
        isLastMessage: Boolean,
        timestamp: Long
    ): ByteArray {
        val continuationByte = createContinuationByte(chunkNumber, isLastMessage)
        val timestampBytes = timestampToBytes(timestamp)

        val totalSize = 1 + timestampBytes.size + pixelChunk.size
        if (totalSize > MAX_MESSAGE_SIZE) {
            Log.w(TAG, "Continuation message $chunkNumber exceeds max size: $totalSize bytes")
        }

        return byteArrayOf(continuationByte) + timestampBytes + pixelChunk
    }

    /**
     * Creates the header byte for the first message.
     * Bit layout:
     *   Bit 0: Always 0 (distinguishes from continuation messages)
     *   Bits 1-2: Reserved
     *   Bits 3-5: Format (0 = B&W, 3 = 4-bit color)
     *   Bit 6: Multi-message flag (1 = multiple messages, 0 = single message)
     *   Bit 7: Reserved
     */
    private fun createHeaderByte(format: Int, isMultiMessage: Boolean): Byte {
        var byte = 0  // Bit 0: always 0
        byte = byte or ((format and 0x07) shl 3)  // Bits 3-5: format
        if (isMultiMessage) {
            byte = byte or (1 shl 6)  // Bit 6: multi-message flag
        }
        return byte.toByte()
    }

    /**
     * Creates the continuation byte for subsequent messages.
     * Bit layout:
     *   Bit 0: Always 1 (continuation marker)
     *   Bits 1-2: Reserved
     *   Bits 3-5: Chunk number (0-7)
     *   Bit 6: Last chunk flag (1 = final chunk, 0 = more chunks)
     *   Bit 7: Reserved
     */
    private fun createContinuationByte(chunkNumber: Int, isLastMessage: Boolean): Byte {
        var byte = 0x01  // Bit 0: continuation marker
        byte = byte or ((chunkNumber and 0x07) shl 3)  // Bits 3-5: chunk number
        if (isLastMessage) {
            byte = byte or (1 shl 6)  // Bit 6: last chunk flag
        }
        return byte.toByte()
    }

    /**
     * Converts palette indices to Pebble GColor8 bytes.
     * For 4-bit format: 16 palette entries (1 byte each in GColor8 format)
     *
     * GColor8 format: AARRGGBB where each channel is 2 bits
     * AA = 11 (opaque)
     * RR, GG, BB = top 2 bits of each 8-bit channel
     */
    private fun createPaletteBytes(palette: IntArray): ByteArray {
        return palette.map { paletteIndex ->
            val rgbColor = PebbleColorPalette.PEBBLE_COLORS[paletteIndex]
            val gcolor8 = rgbToGColor8(rgbColor)
            if (MyDebug.LOG) {
                Log.d(TAG, "[E2E] Palette[$paletteIndex]: RGB 0x%06X -> GColor8 0x%02X".format(rgbColor, gcolor8.toInt() and 0xFF))
            }
            gcolor8
        }.toByteArray()
    }

    /**
     * Converts 24-bit RGB (0xRRGGBB) to Pebble GColor8 format (AARRGGBB).
     * Each color channel is quantized to 2 bits (00, 01, 10, 11).
     */
    private fun rgbToGColor8(rgb: Int): Byte {
        // Extract 8-bit RGB components
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF

        // Convert to 2-bit components (top 2 bits of each channel)
        val r2bit = (r shr 6) and 0x3
        val g2bit = (g shr 6) and 0x3
        val b2bit = (b shr 6) and 0x3

        // Pack into GColor8: AARRGGBB
        // AA = 11 (opaque)
        val gcolor8 = (0x3 shl 6) or (r2bit shl 4) or (g2bit shl 2) or b2bit

        return gcolor8.toByte()
    }

    /**
     * Validates that the palette matches the format requirements.
     * @throws IllegalArgumentException if palette doesn't match format
     */
    private fun validatePaletteForFormat(format: Int, palette: IntArray?) {
        when (format) {
            PebbleImageConverter.FORMAT_BW_1BIT -> {
                if (palette != null) {
                    throw IllegalArgumentException("B&W format should not have a palette, but got ${palette.size} entries")
                }
            }
            PebbleImageConverter.FORMAT_COLOR_4BIT -> {
                if (palette == null || palette.size != 16) {
                    throw IllegalArgumentException("4-bit color format requires exactly 16 palette entries, got ${palette?.size}")
                }
            }
            else -> {
                throw IllegalArgumentException("Unsupported format: $format")
            }
        }
    }

    /**
     * Converts a unix timestamp (seconds) to a 4-byte little-endian byte array.
     */
    private fun timestampToBytes(ts: Long): ByteArray = byteArrayOf(
        (ts and 0xFF).toByte(),
        ((ts shr 8) and 0xFF).toByte(),
        ((ts shr 16) and 0xFF).toByte(),
        ((ts shr 24) and 0xFF).toByte()
    )
}
