package io.github.kotoriinsky.tsuzuri.capture.engine

/**
 * 全局识别向来源应用要「某块屏幕区域里那张图的源位图」的通道约定。
 *
 * 发送方：传送门进程（`com.miui.contentextension`，全局识别窗口所在）。
 * 接收方：来源应用进程里的影子抓取器（`:capture` 的 `SourceImageServer`）。
 *
 * 请求是指定包名的广播，要求发送方持有 [SENDER_PERMISSION]（系统签名权限，第三方应用拿不到），
 * extras 里带屏幕矩形与一个回调 Binder。应答经回调 Binder 单向发回：成功时附一个管道读端，
 * 接收方往写端写 PNG；失败时附原因。两端都只读，不改来源应用的任何对象。
 */
object SourceImageContract {
    const val ACTION = "io.github.kotoriinsky.tsuzuri.action.SOURCE_IMAGE"

    /** 传送门持有、第三方应用拿不到的签名权限，用作广播的发送方门槛。 */
    const val SENDER_PERMISSION = "android.permission.INTERNAL_SYSTEM_WINDOW"

    /** IntArray(4)：left, top, right, bottom，屏幕坐标。 */
    const val EXTRA_RECT = "rect"

    /** Bundle.getBinder：应答用的回调。 */
    const val EXTRA_CALLBACK = "callback"

    const val DESCRIPTOR = "io.github.kotoriinsky.tsuzuri.ISourceImageCallback"

    /**
     * 成功：int 宽、int 高、String 来源说明、FileDescriptor 管道读端。
     * 管道里是一个完整的图片文件：控件位图、视频帧编码成 PNG；网页图片是原文件（JPEG / WebP 等），
     * 接收方用 `BitmapFactory.decodeStream` 统一解码。
     */
    const val TX_RESULT = 1

    /** 失败：String 原因。 */
    const val TX_FAIL = 2

    /**
     * 请求种类（int）。缺省为 [MODE_FETCH]：取 [EXTRA_RECT] 那张图的源位图。
     * [MODE_LIST]：列出本进程屏幕上所有「真画着位图」的控件位置（与 Flyme 识屏一样按内容判断，不看类名）。
     */
    const val EXTRA_MODE = "mode"
    const val MODE_FETCH = 0
    const val MODE_LIST = 1

    /** [MODE_LIST] 的最小边长（int，像素）：比它小的控件不列。 */
    const val EXTRA_MIN_SIDE = "minSide"

    /** 列表应答：int 个数 n，IntArray(4n) 屏幕矩形，String 说明（耗时、是否因超时截断）。 */
    const val TX_LIST = 3
}
