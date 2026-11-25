package io.github.kotoriinsky.tsuzuri.ui.tile

import android.app.Activity
import android.app.BroadcastOptions
import android.content.Intent
import android.os.Bundle
import io.github.kotoriinsky.tsuzuri.core.AggPayment

/**
 * 「聚合支付」桌面快捷方式的跳板：不显示界面，给 SystemUI 发一条带身份的广播就结束
 * （SystemUI 只受理本模块 uid），由它用小窗打开最近用的那项。
 */
class AggPaymentShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
        sendBroadcast(Intent(AggPayment.ACTION_SHOW).setPackage(SYSTEM_UI), null, options)
        finish()
    }

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
    }
}
