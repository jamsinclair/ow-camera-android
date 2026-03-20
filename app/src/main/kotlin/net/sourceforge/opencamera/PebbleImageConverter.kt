package net.sourceforge.opencamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.util.Log

enum class PebbleModel(val width: Int, val height: Int) {
    APLITE(144, 168),
    BASALT(144, 168),
    DIORITE(144, 168),
    FLINT(144, 168),
    CHALK(180, 180),
    EMERY(200, 228),
    UNKNOWN(144, 168);

    val aspectRatio: Float get() = width.toFloat() / height.toFloat()
    val actionBarWidth: Int get() = if (this == CHALK) PEBBLE_ACTION_BAR_WIDTH - PEBBLE_ACTION_BAR_CHALK_BUFFER else PEBBLE_ACTION_BAR_WIDTH

    companion object {
        const val PEBBLE_ACTION_BAR_WIDTH = 30
        const val PEBBLE_ACTION_BAR_CHALK_BUFFER = 2

        @JvmStatic
        fun fromString(modelStr: String): PebbleModel = when (modelStr) {
            "aplite"  -> APLITE
            "basalt"  -> BASALT
            "diorite" -> DIORITE
            "flint"   -> FLINT
            "chalk"   -> CHALK
            "emery"   -> EMERY
            else      -> BASALT
        }
    }
}


data class ColorImageData(
    val format: Int,
    val palette: IntArray,
    val chunks: List<ByteArray>
)

class PebbleImageConverter(private val context: Context? = null) {
    companion object {
        private const val TAG = "PebbleImageConverter"
        // Dithering algorithms
        const val DITHER_FLOYD_STEINBERG = 0
        const val DITHER_BAYER_2X2 = 1
        const val DITHER_BAYER_4X4 = 2
        const val DITHER_BAYER_8X8 = 3
        const val DITHER_ATKINSON = 4

        // Color format constants
        const val FORMAT_BW_1BIT = 0
        const val FORMAT_COLOR_4BIT = 3
    }

    /**
     * Converts an Android Bitmap to Pebble-compatible 4-bit color pixel data with multi-message support
     * Selects 16 most frequent Pebble colors from image, then dithers against actual Pebble palette for accurate error diffusion
     *
     * @param source The source bitmap from camera preview
     * @param model Target Pebble watch model
     * @param ditheringAlgorithm Dithering algorithm to use (default: Floyd-Steinberg)
     * @param rotationDegrees Camera display rotation to counter-rotate the image (default: 0)
     * @return ColorImageData with format, palette, and chunks
     */
    fun convertBitmapToColorPixelData(source: Bitmap, model: PebbleModel, ditheringAlgorithm: Int = DITHER_FLOYD_STEINBERG, rotationDegrees: Int = 0): ColorImageData {
        // 0. Rotate to counter display rotation if needed
        val rotated = rotateBitmap(source, rotationDegrees)

        // 1. Center crop to target aspect ratio
        val cropped = centerCrop(rotated, model.aspectRatio)

        // 2. Scale to target resolution
        val scaled = Bitmap.createScaledBitmap(
            cropped,
            model.width,
            model.height,
            true  // bilinear filtering
        )

        // 3. Crop right edge for action bar if needed
        val actionBarCropped = if (model.actionBarWidth > 0) {
            val croppedWidth = model.width - model.actionBarWidth
            Bitmap.createBitmap(
                scaled,
                0,
                0,
                croppedWidth,
                model.height
            )
        } else {
            scaled
        }

        // 4. Extract RGB pixels
        val width = actionBarCropped.width
        val height = actionBarCropped.height
        val totalPixels = width * height
        val pixels = IntArray(totalPixels)
        actionBarCropped.getPixels(pixels, 0, width, 0, 0, width, height)

        val t1 = System.currentTimeMillis()

        // 5. Select 16 most frequent Pebble colors from image (histogram-based)
        val pebblePaletteIndices = selectPebbleColorsFromImage(pixels, 16)

        val t2 = System.currentTimeMillis()

        // 6. Build RGB palette from selected Pebble colors for dithering
        val rgbPalette = pebblePaletteIndices.map { index ->
            PebbleColorPalette.PEBBLE_COLORS[index]
        }.toIntArray()

        // 7. Apply dithering against actual Pebble colors
        val quantizedIndices = when (ditheringAlgorithm) {
            DITHER_FLOYD_STEINBERG -> ditheringFloydSteinbergColor(pixels, width, height, rgbPalette)
            DITHER_ATKINSON -> ditheringAtkinsonColor(pixels, width, height, rgbPalette)
            DITHER_BAYER_2X2 -> ditheringBayerColor(pixels, width, height, rgbPalette, 2)
            DITHER_BAYER_4X4 -> ditheringBayerColor(pixels, width, height, rgbPalette, 4)
            DITHER_BAYER_8X8 -> ditheringBayerColor(pixels, width, height, rgbPalette, 8)
            else -> ditheringFloydSteinbergColor(pixels, width, height, rgbPalette)  // Default
        }

        val t3 = System.currentTimeMillis()

        // 8. Pack to 4-bit format (2 pixels per byte)
        val packedPixels = packPixelsTo4Bit(quantizedIndices)

        val t4 = System.currentTimeMillis()
        val uniqueColors = quantizedIndices.toSet().size
        Log.d(TAG, "4-bit conversion breakdown: palette=${t2-t1}ms, dither=${t3-t2}ms, pack=${t4-t3}ms, total=${t4-t1}ms, unique_colors=$uniqueColors/16")

        // 9. Return all packed pixels as single chunk (compression handles chunking)
        val chunks = listOf(packedPixels)

        // 10. Cleanup temporary bitmaps
        if (cropped != rotated) cropped.recycle()
        if (scaled != cropped) scaled.recycle()
        if (actionBarCropped != scaled) actionBarCropped.recycle()
        if (rotated != source) rotated.recycle()

        return ColorImageData(FORMAT_COLOR_4BIT, pebblePaletteIndices, chunks)
    }


/**
     * Converts an Android Bitmap to Pebble-compatible pixel data
     *
     * @param source The source bitmap from camera preview
     * @param model Target Pebble watch model
     * @param ditheringAlgorithm Dithering algorithm to use (default: Floyd-Steinberg)
     * @param rotationDegrees Camera display rotation to counter-rotate the image (default: 0)
     * @return ByteArray of pixel data in Pebble ARGB format
     */
    fun convertBitmapToPixelData(source: Bitmap, model: PebbleModel, ditheringAlgorithm: Int = DITHER_FLOYD_STEINBERG, rotationDegrees: Int = 0): ByteArray {
        // 0. Rotate to counter display rotation if needed
        val rotated = rotateBitmap(source, rotationDegrees)

        // 1. Center crop to target aspect ratio
        val cropped = centerCrop(rotated, model.aspectRatio)

        // 2. Scale to target resolution
        val scaled = Bitmap.createScaledBitmap(
            cropped,
            model.width,
            model.height,
            true  // bilinear filtering
        )

        // 3. Crop right edge for action bar if needed
        val actionBarCropped = if (model.actionBarWidth > 0) {
            val croppedWidth = model.width - model.actionBarWidth
            Bitmap.createBitmap(
                scaled,
                0,
                0,
                croppedWidth,
                model.height
            )
        } else {
            scaled
        }

        // 4. Convert pixels to 1-bit B&W with selected dithering algorithm
        val pixelData = convertToMonochrome(actionBarCropped, ditheringAlgorithm)

        // 5. Cleanup temporary bitmaps
        if (cropped != rotated) cropped.recycle()
        if (scaled != cropped) scaled.recycle()
        if (actionBarCropped != scaled) actionBarCropped.recycle()
        if (rotated != source) rotated.recycle()

        return pixelData
    }

    /**
     * Rotates bitmap by specified degrees, or returns source unchanged if degrees == 0.
     * The input degrees are negated internally to counter-rotate display rotation.
     */
    private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) {
            return source
        }

        val matrix = Matrix().apply {
            postRotate(-degrees.toFloat())
        }

        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true
        )
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
     * Calculate maximum error diffusion threshold based on palette size
     * Larger palettes can tolerate larger errors without creating harsh patterns
     * 16 colors (4-bit): ~80, 64 colors (6-bit): ~150
     */
    private fun getMaxErrorForPalette(paletteSize: Int): Int {
        return (40 + paletteSize * 1.7).toInt().coerceAtMost(180)
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

    /**
     * Selects N most frequent Pebble colors from image using histogram approach
     * Returns array of Pebble palette indices (0-63) sorted by frequency
     * Histogram approach ensures stable palette across frames (no flickering in video)
     *
     * @param pixels RGB pixel array from image
     * @param paletteSize Number of colors to select (typically 16 for 4-bit format)
     * @return IntArray of Pebble palette indices (0-63) sorted by frequency
     */
    private fun selectPebbleColorsFromImage(
        pixels: IntArray,
        paletteSize: Int
    ): IntArray {
        // Build histogram of 64 Pebble colors
        val histogram = IntArray(64)

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            // Always use fast RGB distance for histogram - perceptual accuracy not critical for counting
            val pebbleIndex = PebbleColorPalette.findNearestPebbleColor(r, g, b)

            histogram[pebbleIndex]++
        }

        // Select top N most-used colors
        return histogram.withIndex()
            .sortedByDescending { it.value }
            .take(paletteSize)
            .map { it.index }
            .toIntArray()
    }

    /**
     * Floyd-Steinberg dithering with color quantization
     */
    private fun ditheringFloydSteinbergColor(
        pixels: IntArray,
        width: Int,
        height: Int,
        palette: IntArray
    ): IntArray {
        val ditherLevel = 0.85
        val maxError = getMaxErrorForPalette(palette.size)
        val scaledDitherLevel = (1.0 - Math.pow(1.0 - ditherLevel, 2.0)) * (15.0 / 16.0)

        val quantizedIndices = IntArray(pixels.size)
        val workingPixels = pixels.copyOf()

        // Create RGB error buffers
        val errorR = IntArray(pixels.size)
        val errorG = IntArray(pixels.size)
        val errorB = IntArray(pixels.size)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x

                // Get current pixel with accumulated error
                val pixel = workingPixels[index]
                var r = (pixel shr 16) and 0xFF
                var g = (pixel shr 8) and 0xFF
                var b = pixel and 0xFF

                r = (r + errorR[index]).coerceIn(0, 255)
                g = (g + errorG[index]).coerceIn(0, 255)
                b = (b + errorB[index]).coerceIn(0, 255)

                // Find nearest palette color
                var nearestIndex = 0
                var minDist = Int.MAX_VALUE
                for (i in palette.indices) {
                    val pr = (palette[i] shr 16) and 0xFF
                    val pg = (palette[i] shr 8) and 0xFF
                    val pb = palette[i] and 0xFF
                    val dist = (r - pr) * (r - pr) + (g - pg) * (g - pg) + (b - pb) * (b - pb)
                    if (dist < minDist) {
                        minDist = dist
                        nearestIndex = i
                    }
                }

                quantizedIndices[index] = nearestIndex

                // Calculate error
                val paletteColor = palette[nearestIndex]
                val pr = (paletteColor shr 16) and 0xFF
                val pg = (paletteColor shr 8) and 0xFF
                val pb = paletteColor and 0xFF

                var errR = r - pr
                var errG = g - pg
                var errB = b - pb

                // Cap maximum error based on palette quality
                errR = errR.coerceIn(-maxError, maxError)
                errG = errG.coerceIn(-maxError, maxError)
                errB = errB.coerceIn(-maxError, maxError)

                // Apply non-linear dither level scaling and distribute error
                if (x + 1 < width) {
                    val nextIndex = index + 1
                    errorR[nextIndex] = (errorR[nextIndex] + (errR * 7 / 16 * scaledDitherLevel).toInt()).coerceIn(-255, 255)
                    errorG[nextIndex] = (errorG[nextIndex] + (errG * 7 / 16 * scaledDitherLevel).toInt()).coerceIn(-255, 255)
                    errorB[nextIndex] = (errorB[nextIndex] + (errB * 7 / 16 * scaledDitherLevel).toInt()).coerceIn(-255, 255)
                }

                if (y + 1 < height) {
                    if (x - 1 >= 0) {
                        val nextIndex = index + width - 1
                        errorR[nextIndex] = (errorR[nextIndex] + (errR * 3 / 16 * scaledDitherLevel).toInt()).coerceIn(-255, 255)
                        errorG[nextIndex] = (errorG[nextIndex] + (errG * 3 / 16 * scaledDitherLevel).toInt()).coerceIn(-255, 255)
                        errorB[nextIndex] = (errorB[nextIndex] + (errB * 3 / 16 * scaledDitherLevel).toInt()).coerceIn(-255, 255)
                    }
                    val nextIndex = index + width
                    errorR[nextIndex] = (errorR[nextIndex] + (errR * 5 / 16 * scaledDitherLevel).toInt()).coerceIn(-255, 255)
                    errorG[nextIndex] = (errorG[nextIndex] + (errG * 5 / 16 * scaledDitherLevel).toInt()).coerceIn(-255, 255)
                    errorB[nextIndex] = (errorB[nextIndex] + (errB * 5 / 16 * scaledDitherLevel).toInt()).coerceIn(-255, 255)

                    if (x + 1 < width) {
                        val nextIndex = index + width + 1
                        errorR[nextIndex] = (errorR[nextIndex] + (errR / 16 * scaledDitherLevel).toInt()).coerceIn(-255, 255)
                        errorG[nextIndex] = (errorG[nextIndex] + (errG / 16 * scaledDitherLevel).toInt()).coerceIn(-255, 255)
                        errorB[nextIndex] = (errorB[nextIndex] + (errB / 16 * scaledDitherLevel).toInt()).coerceIn(-255, 255)
                    }
                }
            }
        }

        return quantizedIndices
    }

    /**
     * Atkinson dithering with color quantization
     */
    private fun ditheringAtkinsonColor(
        pixels: IntArray,
        width: Int,
        height: Int,
        palette: IntArray
    ): IntArray {
        val ditherLevel = 0.85
        val maxError = getMaxErrorForPalette(palette.size)
        val scaledDitherLevel = (1.0 - Math.pow(1.0 - ditherLevel, 2.0)) * (15.0 / 16.0)

        val quantizedIndices = IntArray(pixels.size)
        val errorR = IntArray(pixels.size)
        val errorG = IntArray(pixels.size)
        val errorB = IntArray(pixels.size)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x

                // Get current pixel with error
                val pixel = pixels[index]
                var r = ((pixel shr 16) and 0xFF) + errorR[index]
                var g = ((pixel shr 8) and 0xFF) + errorG[index]
                var b = (pixel and 0xFF) + errorB[index]

                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)

                // Find nearest palette color
                var nearestIndex = 0
                var minDist = Int.MAX_VALUE
                for (i in palette.indices) {
                    val pr = (palette[i] shr 16) and 0xFF
                    val pg = (palette[i] shr 8) and 0xFF
                    val pb = palette[i] and 0xFF
                    val dist = (r - pr) * (r - pr) + (g - pg) * (g - pg) + (b - pb) * (b - pb)
                    if (dist < minDist) {
                        minDist = dist
                        nearestIndex = i
                    }
                }

                quantizedIndices[index] = nearestIndex

                // Calculate error
                val paletteColor = palette[nearestIndex]
                val pr = (paletteColor shr 16) and 0xFF
                val pg = (paletteColor shr 8) and 0xFF
                val pb = paletteColor and 0xFF

                var errR = (r - pr) / 8
                var errG = (g - pg) / 8
                var errB = (b - pb) / 8

                // Cap maximum error based on palette quality
                errR = errR.coerceIn(-maxError, maxError)
                errG = errG.coerceIn(-maxError, maxError)
                errB = errB.coerceIn(-maxError, maxError)

                // Distribute to 6 neighbors, 1/8 each, with scaled dither level
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
                        errorR[neighborIndex] = (errorR[neighborIndex] + (errR * scaledDitherLevel).toInt()).coerceIn(-255, 255)
                        errorG[neighborIndex] = (errorG[neighborIndex] + (errG * scaledDitherLevel).toInt()).coerceIn(-255, 255)
                        errorB[neighborIndex] = (errorB[neighborIndex] + (errB * scaledDitherLevel).toInt()).coerceIn(-255, 255)
                    }
                }
            }
        }

        return quantizedIndices
    }

    /**
     * Bayer ordered dithering with color quantization
     */
    private fun ditheringBayerColor(
        pixels: IntArray,
        width: Int,
        height: Int,
        palette: IntArray,
        matrixSize: Int
    ): IntArray {
        val quantizedIndices = IntArray(pixels.size)
        val matrix = getBayerMatrix(matrixSize)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val pixel = pixels[index]

                var r = (pixel shr 16) and 0xFF
                var g = (pixel shr 8) and 0xFF
                var b = pixel and 0xFF

                // Apply dithering threshold based on matrix
                val matrixValue = matrix[(y % matrixSize) * matrixSize + (x % matrixSize)]

                r = if (r > matrixValue) 255 else 0
                g = if (g > matrixValue) 255 else 0
                b = if (b > matrixValue) 255 else 0

                // Find nearest palette color
                var nearestIndex = 0
                var minDist = Int.MAX_VALUE
                for (i in palette.indices) {
                    val pr = (palette[i] shr 16) and 0xFF
                    val pg = (palette[i] shr 8) and 0xFF
                    val pb = palette[i] and 0xFF
                    val dist = (r - pr) * (r - pr) + (g - pg) * (g - pg) + (b - pb) * (b - pb)
                    if (dist < minDist) {
                        minDist = dist
                        nearestIndex = i
                    }
                }

                quantizedIndices[index] = nearestIndex
            }
        }

        return quantizedIndices
    }

    /**
     * Pack 4-bit palette indices into bytes (2 pixels per byte, MSB→LSB)
     */
    private fun packPixelsTo4Bit(indices: IntArray): ByteArray {
        val byteCount = (indices.size + 1) / 2
        val packed = ByteArray(byteCount)

        for (i in indices.indices) {
            val byteIndex = i / 2
            val bitShift = if (i % 2 == 0) 4 else 0  // First pixel high nibble (4), second pixel low nibble (0)
            packed[byteIndex] = (packed[byteIndex].toInt() or ((indices[i] and 0x0F) shl bitShift)).toByte()
        }

        return packed
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

    /**
     * Unpack 4-bit color palette indices from packed chunks for debug visualization
     */
    fun unpackColorPixels(chunks: List<ByteArray>, width: Int, height: Int): IntArray {
        val totalPixels = width * height
        val indices = IntArray(totalPixels)
        var pixelIndex = 0

        for (chunk in chunks) {
            for (byte in chunk) {
                if (pixelIndex >= totalPixels) break

                // Extract high nibble (first pixel)
                if (pixelIndex < totalPixels) {
                    indices[pixelIndex++] = ((byte.toInt() shr 4) and 0x0F)
                }

                // Extract low nibble (second pixel)
                if (pixelIndex < totalPixels) {
                    indices[pixelIndex++] = (byte.toInt() and 0x0F)
                }
            }
            if (pixelIndex >= totalPixels) break
        }

        return indices
    }

}
