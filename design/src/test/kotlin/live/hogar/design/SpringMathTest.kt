package live.hogar.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class SpringMathTest {
    @Test fun stiffnessIsAngularFrequencySquared() {
        val k = SpringMath.stiffness(0.5f)
        val w = (2 * PI / 0.5).toFloat()
        assertEquals(w * w, k, 0.01f)
        assertEquals(157.91f, k, 0.05f)
    }

    @Test fun defaultSpringMatchesSwiftUI() {
        // .spring(response 0.55, dampingFraction 0.825)
        assertEquals(130.5f, HogarSpring(0.55f, 0.825f).stiffness, 0.1f)
        assertEquals(0.55f, Hogar.Motion.Default.response, 0f)
        assertEquals(0.825f, Hogar.Motion.Default.dampingFraction, 0f)
    }

    @Test fun bounceMapsToDamping() {
        assertEquals(1f, SpringMath.dampingFromBounce(0f), 1e-6f)      // .smooth
        assertEquals(0.85f, SpringMath.dampingFromBounce(0.15f), 1e-6f) // .snappy
        assertEquals(0.70f, SpringMath.dampingFromBounce(0.30f), 1e-6f) // .bouncy
        assertEquals(2f, SpringMath.dampingFromBounce(-0.5f), 1e-6f) // negativo: sobreamortiguado
        assertTrue(SpringMath.dampingFromBounce(-0.5f) > 1f)
    }

    @Test fun presetsUseAppleValues() {
        assertEquals(1f, Hogar.Motion.Smooth.dampingFraction, 0f)
        assertEquals(0.85f, Hogar.Motion.Snappy.dampingFraction, 0f)
        assertEquals(0.70f, Hogar.Motion.Bouncy.dampingFraction, 0f)
        assertEquals(HogarSpring.fromBounce(0.5f, 0.15f), Hogar.Motion.Snappy)
    }

    @Test fun specUsesConvertedStiffness() {
        val s = Hogar.Motion.Snappy.spec<Float>()
        assertEquals(0.85f, s.dampingRatio, 1e-6f)
        assertEquals(Hogar.Motion.Snappy.stiffness, s.stiffness, 1e-3f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun responseMustBePositive() { SpringMath.stiffness(0f) }
}
