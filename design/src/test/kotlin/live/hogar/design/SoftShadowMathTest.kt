package live.hogar.design

import org.junit.Assert.assertEquals
import org.junit.Test

class SoftShadowMathTest {
    @Test fun bakedAtQuarterResolutionWithPadding() {
        val l = SoftShadowMath.layout(290, 174, blurPx = 44, q = 4)
        assertEquals(88, l.padPx)
        assertEquals(117, l.bmpW) // (290 + 176) / 4 = 116,5 -> 117
        assertEquals(88, l.bmpH)  // (174 + 176) / 4 = 87,5 -> 88
    }

    @Test fun tinyInputsStayValid() {
        val l = SoftShadowMath.layout(1, 1, blurPx = 0, q = 4)
        assertEquals(1, l.bmpW)
        assertEquals(1, l.bmpH)
    }
}
