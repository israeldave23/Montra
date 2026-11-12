package com.dga.montra.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImageScalingTest {

    @Test
    fun `sample size is the largest power of two that still covers the target`() {
        assertEquals(1, ImageScaling.sampleSizeFor(100, 100, 512))
        assertEquals(1, ImageScaling.sampleSizeFor(512, 512, 512))
        assertEquals(2, ImageScaling.sampleSizeFor(1024, 1024, 512))
        assertEquals(4, ImageScaling.sampleSizeFor(4000, 3000, 512))
        assertEquals(8, ImageScaling.sampleSizeFor(4096, 3072, 512))
    }

    @Test
    fun `sample size never over-shrinks`() {
        // 700 / 2 = 350 < 512, so the first sample would already be too small.
        assertEquals(1, ImageScaling.sampleSizeFor(700, 700, 512))
        // 1000 / 2 = 500 < 512, so again stay at 1.
        assertEquals(1, ImageScaling.sampleSizeFor(1000, 1000, 512))
        assertEquals(2, ImageScaling.sampleSizeFor(1025, 1025, 512))
    }

    @Test
    fun `invalid dimensions fall back to no subsampling`() {
        assertEquals(1, ImageScaling.sampleSizeFor(0, 0, 512))
        assertEquals(1, ImageScaling.sampleSizeFor(-4, 900, 512))
        assertEquals(1, ImageScaling.sampleSizeFor(900, 900, 0))
    }

    @Test
    fun `fitsWithin is only true when both edges are inside the limit`() {
        assertTrue(ImageScaling.fitsWithin(512, 400, 512))
        assertFalse(ImageScaling.fitsWithin(513, 400, 512))
        assertFalse(ImageScaling.fitsWithin(400, 0, 512))
    }

    @Test
    fun `exact resize preserves the aspect ratio and never upscales`() {
        assertEquals(512 to 384, ImageScaling.scaledDimension(4000, 3000, 512))
        assertEquals(384 to 512, ImageScaling.scaledDimension(3000, 4000, 512))
        assertEquals(100 to 50, ImageScaling.scaledDimension(100, 50, 512))
        assertEquals(512 to 1, ImageScaling.scaledDimension(4000, 2, 512))
    }

    @Test
    fun `scaled dimension rejects nonsense input`() {
        assertTrue(runCatching { ImageScaling.scaledDimension(0, 10, 512) }.isFailure)
        assertTrue(runCatching { ImageScaling.scaledDimension(10, 10, 0) }.isFailure)
    }
}
