package io.github.kotoriinsky.tsuzuri.hook.portalprobe

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

/** 只显示自有红色封面与绿色测试片段，不访问用户媒体。 */
class PortalPreviewActivity : Activity() {
    companion object {
        val ready = java.util.concurrent.CountDownLatch(1)
        @Volatile var current: PortalPreviewActivity? = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        current = this
        ready.countDown()
    }
}
