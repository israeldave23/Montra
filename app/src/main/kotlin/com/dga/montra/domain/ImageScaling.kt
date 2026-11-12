package com.dga.montra.domain

/**
 * Decode-time downscaling for picked images.
 *
 * A gallery photo can easily be 12 MP; decoding it whole just to draw a 48dp circle would use tens of
 * megabytes. [sampleSizeFor] picks the largest power-of-two subsample that still leaves at least
 * [maxDimension] pixels on the long edge, which is what `BitmapFactory.Options.inSampleSize` expects.
 */
object ImageScaling {

    fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
        if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
        val longest = maxOf(width, height)
        var sample = 1
        while (longest / (sample * 2) >= maxDimension) {
            sample *= 2
        }
        return sample
    }

    /** True when the image already fits and needs no second, exact resize after decoding. */
    fun fitsWithin(width: Int, height: Int, maxDimension: Int): Boolean =
        width in 1..maxDimension && height in 1..maxDimension

    /** Long edge after an exact resize, preserving aspect ratio; never upscales. */
    fun scaledDimension(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        require(maxDimension > 0) { "Max dimension must be positive" }
        val longest = maxOf(width, height)
        if (longest <= maxDimension) return width to height
        val scale = maxDimension.toDouble() / longest
        val scaledWidth = Math.max(1, Math.round(width * scale).toInt())
        val scaledHeight = Math.max(1, Math.round(height * scale).toInt())
        return scaledWidth to scaledHeight
    }
}
