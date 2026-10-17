package org.camylla.proof

import org.junit.Assert.assertEquals
import org.junit.Test

class DesignSystemTest {
    @Test
    fun `mobile palette matches Camylla dark workspace tokens`() {
        assertEquals(0xFF18181B.toInt(), MobileUi.BACKGROUND)
        assertEquals(0xFF222225.toInt(), MobileUi.SURFACE)
        assertEquals(0xFF2B2B2F.toInt(), MobileUi.SURFACE_RAISED)
        assertEquals(0xFFF2F2F3.toInt(), MobileUi.TEXT)
        assertEquals(0xFFA1A1AA.toInt(), MobileUi.MUTED)
        assertEquals(0xFF59A6FF.toInt(), MobileUi.BLUE)
        assertEquals(0xFF4DD49B.toInt(), MobileUi.GREEN)
        assertEquals(0xFFFF6B75.toInt(), MobileUi.RED)
    }
}
