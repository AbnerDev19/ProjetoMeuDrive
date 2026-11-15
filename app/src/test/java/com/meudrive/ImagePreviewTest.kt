package com.meudrive

import org.junit.Assert.*
import org.junit.Test

class ImagePreviewTest {
    @Test fun boundsRequestedPreviewSizeAndSamplingBeforeDecoding() {
        assertEquals(64, ImagePreview.edge(-100, false))
        assertEquals(1280, ImagePreview.edge(Int.MAX_VALUE, false))
        assertEquals(320, ImagePreview.edge(null, false))
        assertEquals(1280, ImagePreview.edge(64, true))
        // A 256 megapixel source decodes to about 1 megapixel for a 320px tile.
        assertEquals(32, ImagePreview.sample(16000, 16000, 320))
        assertEquals(1, ImagePreview.sample(100, 80, 320))
        assertTrue(40000 / ImagePreview.sample(40000, 20000, 1280) <= 2560)
    }
}
