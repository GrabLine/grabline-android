package dev.grabline.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceholderTest {
    @Test
    fun applicationIdIsStable() {
        assertEquals("dev.grabline.app", BuildConfig.APPLICATION_ID)
    }
}
