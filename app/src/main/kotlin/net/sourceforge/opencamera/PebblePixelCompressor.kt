package net.sourceforge.opencamera

import android.util.Log
import java.util.zip.Deflater

/**
 * Compresses Pebble pixel data with deflate and intelligently chunks if needed.
 * Only used for color images; B&W images are sent uncompressed.
 */
class PebblePixelCompressor {
    companion object {
        private const val TAG = "PebblePixelCompressor"
    }

    /**
     * Compresses pixel data with deflate, chunking if necessary.
     * Each chunk fits within maxChunkSize bytes, leaving room for message headers.
     *
     * @param pixelData Packed pixel data to compress
     * @param maxChunkSize Maximum size for each chunk (default 8191, accounting for 1-byte header)
     * @return List of compressed chunks, each fitting within maxChunkSize
     */
    fun compressPixelData(
        pixelData: ByteArray,
        maxChunkSize: Int = 8191
    ): List<ByteArray> {
        // Try compressing the entire data first
        val fullCompressed = deflateCompress(pixelData)

        if (fullCompressed.size <= maxChunkSize) {
            // Fits in single chunk
            Log.d(TAG, "Full data fits in single chunk: ${pixelData.size} bytes -> ${fullCompressed.size} bytes")
            return listOf(fullCompressed)
        }

        // Estimate compression ratio and split accordingly
        val compressionRatio = fullCompressed.size.toDouble() / pixelData.size.toDouble()
        val estimatedChunks = (compressionRatio * pixelData.size / maxChunkSize).toInt() + 1

        Log.d(TAG, "Data exceeds chunk size. Compression ratio: $compressionRatio. Estimated chunks: $estimatedChunks")

        // Split uncompressed data and compress each part
        return splitAndCompress(pixelData, estimatedChunks, maxChunkSize)
    }

    /**
     * Recursively splits and compresses data to fit within chunk size constraints.
     */
    private fun splitAndCompress(
        pixelData: ByteArray,
        numChunks: Int,
        maxChunkSize: Int
    ): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        val chunkSize = (pixelData.size + numChunks - 1) / numChunks  // Ceiling division

        var offset = 0

        while (offset < pixelData.size) {
            val end = minOf(offset + chunkSize, pixelData.size)
            val chunk = pixelData.sliceArray(offset until end)
            val compressed = deflateCompress(chunk)

            if (compressed.size > maxChunkSize) {
                // This chunk is still too large, need more chunks
                // Recursively split this portion further
                val subChunks = splitAndCompress(chunk, 2, maxChunkSize)
                chunks.addAll(subChunks)
            } else {
                chunks.add(compressed)
            }

            offset = end
        }

        Log.d(TAG, "Split into ${chunks.size} compressed chunks")
        return chunks
    }

    /**
     * Compresses data using raw deflate format (without zlib headers).
     * Output buffer sized to handle worst case where compressed data is larger than input.
     */
    private fun deflateCompress(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()

        // Deflate worst case: input size + 0.1% + 12 bytes overhead
        val outputSize = data.size + (data.size / 1000) + 100
        val output = ByteArray(outputSize)
        var totalCompressed = 0

        while (!deflater.finished()) {
            val count = deflater.deflate(output, totalCompressed, output.size - totalCompressed)
            totalCompressed += count
        }
        deflater.end()

        return output.copyOfRange(0, totalCompressed)
    }
}
