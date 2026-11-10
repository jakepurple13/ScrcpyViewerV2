package com.programmersbox.scrcpyviewer.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * An sRGB color from OKLCH: [l] lightness 0..1, [c] chroma (about 0..0.37), [h] hue in degrees.
 * Colors outside sRGB keep their lightness and hue and lose chroma until they fit, so a vivid seed
 * never clips into a different hue.
 */
fun oklch(l: Float, c: Float, h: Float): Color {
    val lightness = l.coerceIn(0f, 1f)
    val chroma = c.coerceAtLeast(0f)
    linearRgb(lightness, chroma, h).takeIf { it.inGamut() }?.let { return it.toColor() }
    // Binary search the largest chroma that stays in gamut.
    var lo = 0f
    var hi = chroma
    repeat(20) {
        val mid = (lo + hi) / 2
        if (linearRgb(lightness, mid, h).inGamut()) lo = mid else hi = mid
    }
    return linearRgb(lightness, lo, h).toColor()
}

private fun linearRgb(l: Float, c: Float, h: Float): FloatArray {
    val rad = Math.toRadians(h.toDouble())
    val a = c * cos(rad).toFloat()
    val b = c * sin(rad).toFloat()
    val l1 = (l + 0.3963377774f * a + 0.2158037573f * b).cube()
    val m1 = (l - 0.1055613458f * a - 0.0638541728f * b).cube()
    val s1 = (l - 0.0894841775f * a - 1.2914855480f * b).cube()
    return floatArrayOf(
        4.0767416621f * l1 - 3.3077115913f * m1 + 0.2309699292f * s1,
        -1.2684380046f * l1 + 2.6097574011f * m1 - 0.3413193965f * s1,
        -0.0041960863f * l1 - 0.7034186147f * m1 + 1.7076147010f * s1,
    )
}

private fun Float.cube() = this * this * this

private const val EPS = 1e-4f
private fun FloatArray.inGamut() = all { it >= -EPS && it <= 1f + EPS }

private fun FloatArray.toColor() = Color(encode(this[0]), encode(this[1]), encode(this[2]))

private fun encode(x: Float): Float {
    val v = x.coerceIn(0f, 1f)
    return if (v <= 0.0031308f) 12.92f * v else (1.055f * v.toDouble().pow(1 / 2.4).toFloat() - 0.055f)
}
