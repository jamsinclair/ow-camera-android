package net.sourceforge.opencamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PebbleModel(val width: Int, val height: Int) {
    APLITE(144, 168),
    BASALT(144, 168),
    DIORITE(144, 168),
    FLINT(144, 168),
    CHALK(180, 180),
    EMERY(200, 228),
    UNKNOWN(144, 168);

    val aspectRatio: Float get() = width.toFloat() / height.toFloat()
}


class PebbleImageConverter(private val context: Context? = null) {
    companion object {
        private const val TAG = "PebbleImageConverter"
        // Height in pixels reserved for UI elements (timer bar) at bottom of Pebble screen
        // Set to 0 to disable and use full screen height
        private const val PEBBLE_TIMER_BAR_HEIGHT = 30
        // Dithering algorithms
        const val DITHER_FLOYD_STEINBERG = 0
        const val DITHER_BAYER_2X2 = 1
        const val DITHER_BAYER_4X4 = 2
        const val DITHER_BAYER_8X8 = 3
        const val DITHER_ATKINSON = 4
    }

    /**
     * Converts an Android Bitmap to Pebble-compatible pixel data
     *
     * @param source The source bitmap from camera preview
     * @param model Target Pebble watch model
     * @param ditheringAlgorithm Dithering algorithm to use (default: Floyd-Steinberg)
     * @return ByteArray of pixel data in Pebble ARGB format
     */
    fun convertBitmapToPixelData(source: Bitmap, model: PebbleModel, ditheringAlgorithm: Int = DITHER_FLOYD_STEINBERG): ByteArray {
        // 1. Center crop to target aspect ratio
        val cropped = centerCrop(source, model.aspectRatio)

        // 2. Scale to target resolution
        val scaled = Bitmap.createScaledBitmap(
            cropped,
            model.width,
            model.height,
            true  // bilinear filtering
        )

        // 3. Crop bottom for timer bar if needed
        val timerBarCropped = if (PEBBLE_TIMER_BAR_HEIGHT > 0) {
            val croppedHeight = model.height - PEBBLE_TIMER_BAR_HEIGHT
            Bitmap.createBitmap(
                scaled,
                0,
                0,
                model.width,
                croppedHeight
            )
        } else {
            scaled
        }

        // 4. Convert pixels to 1-bit B&W with selected dithering algorithm
        val pixelData = convertToMonochrome(timerBarCropped, ditheringAlgorithm)

        // 5. Cleanup temporary bitmaps
        if (cropped != source) cropped.recycle()
        if (scaled != cropped) scaled.recycle()
        if (timerBarCropped != scaled) timerBarCropped.recycle()

        return pixelData
    }

    /**
     * Center crops bitmap to target aspect ratio
     */
    private fun centerCrop(source: Bitmap, targetAspect: Float): Bitmap {
        val sourceWidth = source.width
        val sourceHeight = source.height
        val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()

        val (cropWidth, cropHeight) = if (sourceAspect > targetAspect) {
            // Source is wider - crop width
            val newWidth = (sourceHeight * targetAspect).toInt()
            Pair(newWidth, sourceHeight)
        } else {
            // Source is taller - crop height
            val newHeight = (sourceWidth / targetAspect).toInt()
            Pair(sourceWidth, newHeight)
        }

        val x = (sourceWidth - cropWidth) / 2
        val y = (sourceHeight - cropHeight) / 2

        return if (x == 0 && y == 0 && cropWidth == sourceWidth && cropHeight == sourceHeight) {
            // No cropping needed
            source
        } else {
            Bitmap.createBitmap(source, x, y, cropWidth, cropHeight)
        }
    }
    private fun rgbToGrayscale(r: Int, g: Int, b: Int): Int {
        // ITU-R BT.709 luma coefficients
        return (0.2126 * r + 0.7152 * g + 0.0722 * b).toInt()
    }
    /**
     * Floyd-Steinberg dithering - error diffusion algorithm
     */
    private fun ditheringFloydSteinberg(grayValues: IntArray, width: Int, height: Int): BooleanArray {
        val bwPixels = BooleanArray(grayValues.size)
        val workingValues = grayValues.copyOf()  // Make a mutable copy

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val oldValue = workingValues[index].coerceIn(0, 255)
                val newValue = if (oldValue > 127) 255 else 0
                bwPixels[index] = newValue == 255  // true = white, false = black

                val error = oldValue - newValue

                // Distribute error to neighboring pixels with Floyd-Steinberg weights
                if (x + 1 < width) {
                    workingValues[index + 1] = (workingValues[index + 1] + error * 7 / 16).coerceIn(0, 255)
                }
                if (y + 1 < height) {
                    if (x - 1 >= 0) {
                        workingValues[index + width - 1] = (workingValues[index + width - 1] + error * 3 / 16).coerceIn(0, 255)
                    }
                    workingValues[index + width] = (workingValues[index + width] + error * 5 / 16).coerceIn(0, 255)
                    if (x + 1 < width) {
                        workingValues[index + width + 1] = (workingValues[index + width + 1] + error * 1 / 16).coerceIn(0, 255)
                    }
                }
            }
        }

        return bwPixels
    }

    /**
     * Bayer matrix dithering - ordered dithering
     */
    private fun ditheringBayer(grayValues: IntArray, width: Int, height: Int, matrixSize: Int): BooleanArray {
        val bwPixels = BooleanArray(grayValues.size)
        val matrix = getBayerMatrix(matrixSize)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val grayValue = grayValues[index]
                val matrixValue = matrix[(y % matrixSize) * matrixSize + (x % matrixSize)]
                bwPixels[index] = grayValue > matrixValue
            }
        }

        return bwPixels
    }

    /**
     * Atkinson dithering - simplified error diffusion
     */
    private fun ditheringAtkinson(grayValues: IntArray, width: Int, height: Int): BooleanArray {
        val bwPixels = BooleanArray(grayValues.size)
        val workingValues = grayValues.copyOf()

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val oldValue = workingValues[index].coerceIn(0, 255)
                val newValue = if (oldValue > 127) 255 else 0
                bwPixels[index] = newValue == 255

                val error = (oldValue - newValue) / 8

                // Distribute 1/8 of error to each of 8 neighboring pixels
                val neighbors = listOf(
                    x + 1 to y,
                    x + 2 to y,
                    x - 1 to (y + 1),
                    x to (y + 1),
                    x + 1 to (y + 1),
                    x to (y + 2)
                )

                for ((nx, ny) in neighbors) {
                    if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                        val neighborIndex = ny * width + nx
                        workingValues[neighborIndex] = (workingValues[neighborIndex] + error).coerceIn(0, 255)
                    }
                }
            }
        }

        return bwPixels
    }

    /**
     * Get Bayer dithering matrix of specified size
     */
    private fun getBayerMatrix(size: Int): IntArray {
        return when (size) {
            2 -> intArrayOf(
                0, 128,
                192, 64
            )
            4 -> intArrayOf(
                0, 128, 32, 160,
                192, 64, 224, 96,
                48, 176, 16, 144,
                240, 112, 208, 80
            )
            8 -> intArrayOf(
                0, 32, 8, 40, 2, 34, 10, 42,
                48, 16, 56, 24, 50, 18, 58, 26,
                12, 44, 4, 36, 14, 46, 6, 38,
                60, 28, 52, 20, 62, 30, 54, 22,
                3, 35, 11, 43, 1, 33, 9, 41,
                51, 19, 59, 27, 49, 17, 57, 25,
                15, 47, 7, 39, 13, 45, 5, 37,
                63, 31, 55, 23, 61, 29, 53, 21
            )
            else -> intArrayOf(0, 128, 192, 64)  // Default to 2x2
        }
    }

    private fun packPixelsToBW(pixels: BooleanArray): ByteArray {
        val byteCount = (pixels.size + 7) / 8
        val packed = ByteArray(byteCount)

        for (i in pixels.indices) {
            if (pixels[i]) {
                val byteIndex = i / 8
                val bitIndex = i % 8  // LSB first (little-endian)
                packed[byteIndex] = (packed[byteIndex].toInt() or (1 shl bitIndex)).toByte()
            }
        }

        return packed
    }
    private fun convertToMonochrome(bitmap: Bitmap, ditheringAlgorithm: Int = DITHER_FLOYD_STEINBERG): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val totalPixels = width * height
        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Convert to grayscale first
        val grayValues = IntArray(totalPixels)
        for (i in 0 until totalPixels) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            grayValues[i] = rgbToGrayscale(r, g, b)
        }

        // Apply selected dithering algorithm
        val bwPixels = when (ditheringAlgorithm) {
            DITHER_FLOYD_STEINBERG -> ditheringFloydSteinberg(grayValues, width, height)
            DITHER_BAYER_2X2 -> ditheringBayer(grayValues, width, height, 2)
            DITHER_BAYER_4X4 -> ditheringBayer(grayValues, width, height, 4)
            DITHER_BAYER_8X8 -> ditheringBayer(grayValues, width, height, 8)
            DITHER_ATKINSON -> ditheringAtkinson(grayValues, width, height)
            else -> ditheringFloydSteinberg(grayValues, width, height)  // Default to Floyd-Steinberg
        }

        val packed = packPixelsToBW(bwPixels)

        return packed
    }

}
