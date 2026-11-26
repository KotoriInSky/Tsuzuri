package io.github.kotoriinsky.tsuzuri.hook.feature.globalrecognition

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.ScreenTranslation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 传送门进程内的静默文本翻译客户端：直连小爱翻译 SDK 服务
 * `com.xiaomi.aiasst.vision/.sdk.TranslateSdkService`（传送门已获授 `MI_AI_TRANSLATE` 权限，无包名白名单）。
 *
 * 全程纯 Binder AIDL 静默调用，**不弹任何系统浮窗或 Activity**，直接拿回译文供全局识别窗内展示与原位覆盖。
 * 所有事务号与 `AiTranslateResult` Parcel 顺序均取自本机 HyperOS 4 真机反编译实证。
 */
internal object SilentTranslateClient {

    private const val TAG = "FP-SilentTrans"
    private const val SERVICE_PKG = "com.xiaomi.aiasst.vision"
    private const val SERVICE_ACTION = "com.xiaomi.aiasst.vision.ACTION_TRANSLATE_SDK_SERVICE"
    private const val IFACE_DESCRIPTOR = "com.xiaomi.aiasst.vision.sdk.ITranslateSdkInterface"
    private const val CB_DESCRIPTOR = "com.xiaomi.aiasst.vision.sdk.ITranslateSdkCallback"

    // ITranslateSdkInterface.Stub 事务号
    private const val TX_DO_FUNC = 2
    private const val TX_SET_CALLBACK = 4
    private const val TX_CHECK_CTA = 6
    private const val TX_START_TEXT_TRANSLATE = 8
    private const val TX_RELEASE = 13

    // ITranslateSdkCallback.Stub 事务号
    private const val CB_ON_TEXT_TRANSLATE_RESULT = 4
    private const val CB_ON_ERROR = 13

    private const val BIND_TIMEOUT_MS = 2500L
    private const val RESULT_TIMEOUT_MS = 6500L

    private val main = Handler(Looper.getMainLooper())

    sealed class Outcome {
        data class Success(
            val translated: String,
            val srcLang: String,
            val destLang: String,
        ) : Outcome()

        data class NeedCta(val reason: String) : Outcome()
        data class Failure(val reason: String) : Outcome()
    }

    /**
     * 在后台线程完成一次静默文本翻译，[onDone] 恒在主线程回调。
     */
    fun translateAsync(
        context: Context,
        rawText: String,
        overrideDest: String? = null,
        log: (Int, String) -> Unit = { _, _ -> },
        onDone: (Outcome) -> Unit,
    ) {
        val text = ScreenTranslation.prepareText(rawText)
        if (text.isEmpty()) {
            main.post { onDone(Outcome.Failure("没有可翻译的文字")) }
            return
        }
        val pair = ScreenTranslation.guessLangPair(text, overrideDest)
        val appCtx = context.applicationContext ?: context
        Thread({
            val outcome = try {
                translateBlocking(appCtx, text, pair.src, pair.dest, log)
            } catch (t: Throwable) {
                log(Log.WARN, "[$TAG] 静默翻译异常：$t")
                Outcome.Failure("翻译服务暂不可用")
            }
            main.post { onDone(outcome) }
        }, "fp-gr-translate").start()
    }

    private fun translateBlocking(
        context: Context,
        text: String,
        srcLang: String,
        destLang: String,
        log: (Int, String) -> Unit,
    ): Outcome {
        val binderRef = AtomicReference<IBinder?>()
        val bindLatch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binderRef.set(service)
                bindLatch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binderRef.set(null)
            }

            override fun onNullBinding(name: ComponentName?) {
                bindLatch.countDown()
            }
        }
        val callerPkg = context.packageName.ifBlank { "com.miui.contentextension" }
        val intent = Intent(SERVICE_ACTION)
            .setPackage(SERVICE_PKG)
            .putExtra("from", callerPkg)
        val bound = try {
            context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            log(Log.WARN, "[$TAG] 绑定翻译服务失败：$t")
            false
        }
        if (!bound) return Outcome.Failure("无法连接系统翻译服务")

        return try {
            if (!bindLatch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return Outcome.Failure("连接系统翻译服务超时")
            }
            val remote = binderRef.get() ?: return Outcome.Failure("系统翻译服务未返回接口")

            // 1. 注册调用方客户端包名（doFunc(0, pkg)）
            doFunc(remote, 0, callerPkg)

            // 2. 校验小爱翻译隐私协议（CTA）
            if (!checkCta(remote)) {
                log(Log.INFO, "[$TAG] 小爱翻译 CTA 未同意，提示回退系统词典")
                return Outcome.NeedCta("未授权系统翻译协议")
            }

            // 3. 挂载回调 Binder 并发起翻译
            val resultLatch = CountDownLatch(1)
            val outcomeRef = AtomicReference<Outcome?>()
            val targetDialogId = AtomicReference<String?>()

            val callback = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code in 1..16777215) {
                        data.enforceInterface(CB_DESCRIPTOR)
                    }
                    if (code == INTERFACE_TRANSACTION) {
                        reply?.writeString(CB_DESCRIPTOR)
                        return true
                    }
                    when (code) {
                        CB_ON_TEXT_TRANSLATE_RESULT -> {
                            val parsed = if (data.readInt() != 0) readTranslateResult(data) else null
                            reply?.writeNoException()
                            reply?.writeInt(0) // inout 参数无需回写修改
                            if (parsed != null) {
                                val expected = targetDialogId.get()
                                if (expected.isNullOrEmpty() || parsed.dialogId == expected) {
                                    val dest = parsed.destStr?.trim().orEmpty()
                                    if (dest.isNotEmpty() && parsed.isFinal) {
                                        outcomeRef.compareAndSet(
                                            null,
                                            Outcome.Success(
                                                translated = dest,
                                                srcLang = parsed.srcLang?.ifBlank { srcLang } ?: srcLang,
                                                destLang = parsed.destLang?.ifBlank { destLang } ?: destLang,
                                            ),
                                        )
                                        resultLatch.countDown()
                                    } else if (dest.isNotEmpty() && outcomeRef.get() == null) {
                                        // 部分通道只有非 final 包时先暂存，若后续有 final 会被覆盖
                                        outcomeRef.set(
                                            Outcome.Success(
                                                translated = dest,
                                                srcLang = parsed.srcLang?.ifBlank { srcLang } ?: srcLang,
                                                destLang = parsed.destLang?.ifBlank { destLang } ?: destLang,
                                            ),
                                        )
                                    }
                                }
                            }
                            return true
                        }
                        CB_ON_ERROR -> {
                            val did = data.readString()
                            val errCode = data.readInt()
                            val errMsg = data.readString().orEmpty()
                            reply?.writeNoException()
                            log(Log.WARN, "[$TAG] 翻译回调错误 dialog=$did code=$errCode msg=$errMsg")
                            val out = if (errCode == 2000) Outcome.NeedCta(errMsg) else Outcome.Failure("翻译失败（$errCode）")
                            outcomeRef.compareAndSet(null, out)
                            resultLatch.countDown()
                            return true
                        }
                        else -> {
                            reply?.writeNoException()
                            return true
                        }
                    }
                }
            }

            setCallback(remote, callback)
            val dialogId = startTextTranslate(remote, text, srcLang, destLang)
            targetDialogId.set(dialogId)
            if (dialogId.isBlank()) {
                return Outcome.Failure("系统翻译未受理请求")
            }

            resultLatch.await(RESULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val finalOutcome = outcomeRef.get() ?: Outcome.Failure("翻译超时，请检查网络")
            runCatching { release(remote) }
            finalOutcome
        } finally {
            runCatching { context.unbindService(conn) }
        }
    }

    private data class ParsedResult(
        val dialogId: String?,
        val srcStr: String?,
        val destStr: String?,
        val srcLang: String?,
        val destLang: String?,
        val isFinal: Boolean,
    )

    /** 按 `com.xiaomi.aiasst.vision.sdk.AiTranslateResult.readFromParcel` 实测字段顺序读取。 */
    private fun readTranslateResult(p: Parcel): ParsedResult {
        val dialogId = p.readString()
        p.readInt() // segId
        val srcStr = p.readString()
        val destStr = p.readString()
        val srcLang = p.readString()
        val destLang = p.readString()
        p.readInt() // speakId
        p.readString() // timestamp
        p.readLong() // segTimestamp
        val isFinal = p.readByte() != 0.toByte()
        p.readByte() // isSensitiveWordTriggered
        p.readString() // vendor
        return ParsedResult(dialogId, srcStr, destStr, srcLang, destLang, isFinal)
    }

    private fun doFunc(remote: IBinder, type: Int, params: String): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE_DESCRIPTOR)
            data.writeInt(type)
            data.writeString(params)
            remote.transact(TX_DO_FUNC, data, reply, 0)
            reply.readException()
            reply.readString().orEmpty()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun setCallback(remote: IBinder, cb: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IFACE_DESCRIPTOR)
            data.writeStrongBinder(cb)
            remote.transact(TX_SET_CALLBACK, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun checkCta(remote: IBinder): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE_DESCRIPTOR)
            remote.transact(TX_CHECK_CTA, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun startTextTranslate(remote: IBinder, text: String, srcLang: String, destLang: String): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE_DESCRIPTOR)
            data.writeInt(1) // startMode = 1（重置引擎状态并开始本次文本翻译）
            data.writeString(text)
            data.writeString(srcLang)
            data.writeString(destLang)
            remote.transact(TX_START_TEXT_TRANSLATE, data, reply, 0)
            reply.readException()
            reply.readString().orEmpty()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun release(remote: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IFACE_DESCRIPTOR)
            remote.transact(TX_RELEASE, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
