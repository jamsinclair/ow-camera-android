package com.github.jamsinclair.owcamera2

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pebble fixed 64-color palette definition and utilities
 *
 * Uses the actual Pebble GColor palette with 64 named colors.
 * Maps to Pebble watch color space (6-bit RGB with specific named values).
 * Reference: Pebble GColor enum
 */
object PebbleColorPalette {

    // Complete 64-color Pebble GColor palette in RGB format (0xRRGGBB)
    // Order matches Pebble GColor enum values (0-63)
    val PEBBLE_COLORS = intArrayOf(
        0x000000,  // 0: GColorBlack
        0x0000FF,  // 1: GColorOxfordBlue
        0x5555FF,  // 2: GColorBabyBlueEyes
        0x55FFFF,  // 3: GColorCeleste
        0x00AA00,  // 4: GColorGreen
        0x00FF00,  // 5: GColorMalachite
        0x55FF55,  // 6: GColorMediumSpringGreen
        0x55FFAA,  // 7: GColorCyan
        0x0055AA,  // 8: GColorBlueMoon
        0x5500FF,  // 9: GColorBlueViolet
        0x0000AA,  // 10: GColorCobaltBlue
        0x5555AA,  // 11: GColorBluePol
        0x555555,  // 12: GColorDarkGray
        0xAAAAAA,  // 13: GColorLightGray
        0xFFFFFF,  // 14: GColorWhite
        0xFF0000,  // 15: GColorRed
        0xAA0055,  // 16: GColorBulgarianRose
        0xAA0000,  // 17: GColorImperialPurple
        0xFF00AA,  // 18: GColorIndigo
        0x5500AA,  // 19: GColorElectricBlue
        0x005500,  // 20: GColorArmyGreen
        0x00AA55,  // 21: GColorDarkGreen
        0x005555,  // 22: GColorMidnightGreen
        0x0055FF,  // 23: GColorCadetBlue
        0x55AAFF,  // 24: GColorPictonBlue
        0xFF0055,  // 25: GColorBrilliantRose
        0xFF00FF,  // 26: GColorShockingPink
        0xFF55FF,  // 27: GColorFashionMagenta
        0xFF00AA,  // 28: GColorMagenta
        0xFF0055,  // 29: GColorFolly
        0xFF5500,  // 30: GColorOrange
        0xAA5500,  // 31: GColorRejectedOrange
        0xFFAA00,  // 32: GColorSunsetOrange
        0xFF0000,  // 33: GColorChromatic (fallback to red)
        0xFF0000,  // 34: GColorRed
        0xFFAA55,  // 35: GColorRajah
        0xFFAA00,  // 36: GColorMelon
        0xFF55FF,  // 37: GColorRichBrilliantLavender
        0xAA55FF,  // 38: GColorLavender
        0xAA00FF,  // 39: GColorPlum
        0xAA00AA,  // 40: GColorPurple
        0xFF00FF,  // 41: GColorViolet
        0xAA5555,  // 42: GColorTumbleweed
        0xFF0000,  // 43: GColorScarlet
        0xAA0000,  // 44: GColorCrimson
        0x550000,  // 45: GColorChestnut
        0x555500,  // 46: GColorBrown
        0x00AA00,  // 47: GColorJaegerGreen
        0x00AA55,  // 48: GColorDarkMossGreen
        0x555555,  // 49: GColorDesaturated (fallback to gray)
        0x555555,  // 50: GColorDarkGray (duplicate)
        0xFFFFFF,  // 51: GColorGhostWhite
        0x550000,  // 52: GColorCafeNoir
        0xFFAA55,  // 53: GColorBeige
        0xFFAA00,  // 54: GColorBisque
        0xFFFFAA,  // 55: GColorCream
        0xFFFF55,  // 56: GColorPastelYellow
        0xFFFF00,  // 57: GColorYellow
        0xAAFF00,  // 58: GColorSpringBud
        0x55FF00,  // 59: GColorInchworm
        0x00FF00,  // 60: GColorLimeGreen
        0x55FFAA,  // 61: GColorMintGreen
        0x00AAAA,  // 62: GColorTeal
        0x55FFFF   // 63: GColorAquamarine
    )

    /**
     * Converts 8-bit RGB values to nearest Pebble palette index (0-63)
     * Uses Euclidean distance in RGB space to find closest color
     */
    fun findNearestPebbleColor(r: Int, g: Int, b: Int): Int {
        var closestIndex = 0
        var minDistance = Int.MAX_VALUE

        for (i in PEBBLE_COLORS.indices) {
            val paletteColor = PEBBLE_COLORS[i]
            val pr = (paletteColor shr 16) and 0xFF
            val pg = (paletteColor shr 8) and 0xFF
            val pb = paletteColor and 0xFF

            // Euclidean distance
            val distance = (r - pr) * (r - pr) +
                          (g - pg) * (g - pg) +
                          (b - pb) * (b - pb)

            if (distance < minDistance) {
                minDistance = distance
                closestIndex = i
            }
        }

        return closestIndex
    }

    /**
     * Converts 8-bit RGB values to nearest Pebble palette index using perceptual color distance (CIELAB)
     * More accurate to human color perception than Euclidean RGB distance
     */
    fun findNearestPebbleColorPerceptual(r: Int, g: Int, b: Int): Int {
        val targetLab = rgbToLab(r, g, b)
        var closestIndex = 0
        var minDistance = Float.MAX_VALUE

        for (i in PEBBLE_COLORS.indices) {
            val paletteColor = PEBBLE_COLORS[i]
            val pr = (paletteColor shr 16) and 0xFF
            val pg = (paletteColor shr 8) and 0xFF
            val pb = paletteColor and 0xFF

            val paletteLab = rgbToLab(pr, pg, pb)
            val distance = cieDeltaE(targetLab, paletteLab)

            if (distance < minDistance) {
                minDistance = distance
                closestIndex = i
            }
        }

        return closestIndex
    }

    /**
     * Convert RGB (0-255) to CIELAB color space
     */
    private fun rgbToLab(r: Int, g: Int, b: Int): FloatArray {
        // Normalize RGB to 0-1
        var rNorm = r / 255f
        var gNorm = g / 255f
        var bNorm = b / 255f

        // Apply gamma correction (sRGB to linear RGB)
        rNorm = if (rNorm > 0.04045f) ((rNorm + 0.055f) / 1.055f).pow(2.4f) else rNorm / 12.92f
        gNorm = if (gNorm > 0.04045f) ((gNorm + 0.055f) / 1.055f).pow(2.4f) else gNorm / 12.92f
        bNorm = if (bNorm > 0.04045f) ((bNorm + 0.055f) / 1.055f).pow(2.4f) else bNorm / 12.92f

        // Convert to XYZ (using D65 illuminant)
        val x = rNorm * 0.4124f + gNorm * 0.3576f + bNorm * 0.1805f
        val y = rNorm * 0.2126f + gNorm * 0.7152f + bNorm * 0.0722f
        val z = rNorm * 0.0193f + gNorm * 0.1192f + bNorm * 0.9505f

        // Normalize by D65 reference white
        val xn = x / 0.95047f
        val yn = y / 1.00000f
        val zn = z / 1.08883f

        // Convert to LAB
        val fx = if (xn > 0.008856f) xn.pow(1f / 3f) else (7.787f * xn) + (16f / 116f)
        val fy = if (yn > 0.008856f) yn.pow(1f / 3f) else (7.787f * yn) + (16f / 116f)
        val fz = if (zn > 0.008856f) zn.pow(1f / 3f) else (7.787f * zn) + (16f / 116f)

        val l = (116f * fy) - 16f
        val a = 500f * (fx - fy)
        val labB = 200f * (fy - fz)

        return floatArrayOf(l, a, labB)
    }

    /**
     * Calculate CIE ΔE (Delta E CIE76) - perceptual color difference
     * Ranges from 0 (identical) to 100+ (very different)
     */
    private fun cieDeltaE(lab1: FloatArray, lab2: FloatArray): Float {
        val dL = lab1[0] - lab2[0]
        val da = lab1[1] - lab2[1]
        val db = lab1[2] - lab2[2]
        return kotlin.math.sqrt(dL * dL + da * da + db * db)
    }
}
