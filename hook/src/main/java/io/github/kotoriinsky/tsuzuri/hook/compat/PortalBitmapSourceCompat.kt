package io.github.kotoriinsky.tsuzuri.hook.compat

import android.graphics.Bitmap
import android.os.Bundle

/** 只在当前控件明确下发的字段中选底图；借用来源引用，由消费端复制后持有。 */
object PortalBitmapSourceCompat {
    @Suppress("DEPRECATION")
    fun pick(bundle: Bundle): Bitmap? =
        (bundle.getStringArray("apkParamList").orEmpty().asSequence() + sequenceOf("viewBitmap"))
            .distinct().filter(bundle::containsKey).mapNotNull { bundle.get(it) as? Bitmap }
            .filter { !it.isRecycled && it.width.toLong() * it.height <= 40_000_000 }
            .maxByOrNull { it.width.toLong() * it.height }
}
