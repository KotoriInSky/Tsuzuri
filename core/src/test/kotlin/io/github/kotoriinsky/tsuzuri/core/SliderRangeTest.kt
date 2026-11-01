package io.github.kotoriinsky.tsuzuri.core

import kotlin.test.Test
import kotlin.test.assertTrue

class SliderRangeTest {

    private val sliders = Catalog.features.flatMap { it.prefs }.filterIsInstance<PrefSpec.Slider>()

    @Test
    fun `推荐范围落在上下限之内`() {
        for (s in sliders) {
            val r = s.recommended ?: continue
            assertTrue(r.start >= s.min && r.endInclusive <= s.max && r.start < r.endInclusive, "${s.key}: $r 不在 ${s.min}..${s.max}")
        }
    }

    @Test
    fun `默认值在上下限之内且落在刻度上`() {
        for (s in sliders) {
            assertTrue(s.default in s.min..s.max, "${s.key}: 默认 ${s.default} 不在 ${s.min}..${s.max}")
            if (s.steps == 0) continue
            val step = (s.max - s.min) / (s.steps + 1)
            val k = (s.default - s.min) / step
            assertTrue(kotlin.math.abs(k - Math.round(k)) < 1e-3, "${s.key}: 默认 ${s.default} 不在刻度上（步长 $step）")
        }
    }
}
