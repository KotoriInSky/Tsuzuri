package io.github.kotoriinsky.tsuzuri.core

/**
 * 聚合支付的跨进程契约：模块 App 的桌面快捷方式发 [ACTION_SHOW]，SystemUI 侧 `AggregatePayment`
 * 受理后用小窗打开最近用的那项（Flyme 形态，见 docs/101）。
 */
object AggPayment {

    /** 桌面快捷方式 → SystemUI：呼出付款小窗。接收端按发送方 uid 校验是不是本模块。 */
    const val ACTION_SHOW = "io.github.kotoriinsky.tsuzuri.action.AGG_PAYMENT_SHOW"

    /** 模块 App 的静态快捷方式 id。 */
    const val SHORTCUT_ID = "agg_payment"
}
