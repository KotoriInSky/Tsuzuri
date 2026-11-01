package io.github.kotoriinsky.tsuzuri.hook.feature.contextsuggestion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.BroadcastOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Catalog
import io.github.kotoriinsky.tsuzuri.hook.Feature
import io.github.kotoriinsky.tsuzuri.hook.HookContext
import io.github.kotoriinsky.tsuzuri.hook.InstallReport
import io.github.kotoriinsky.tsuzuri.hook.feature.gesture.SystemUiEnv
import org.json.JSONObject
import org.json.JSONArray
import kotlin.math.cos
import kotlin.math.sin

/** 常亮启用后由 SystemUI 显示状态；小爱胶囊仍只负责建议。 */
internal object ScreenOnStatus : Feature(Catalog.require(Catalog.CONTEXT_SCREEN_ON)) {
    private const val CHANNEL = "tsuzuri_screen_on"
    private const val STATUS_ID = 0x545301
    private const val ISLAND_ID = 0x545302
    private const val ACTION_CLOSE = "io.github.kotoriinsky.tsuzuri.action.SCREEN_ON_CLOSE"
    private const val CLOSE_KEY = "miui.focus.action_screen_on_close"
    private const val PIC_SUN = "screen_icon"
    private const val PIC_CLOSE = "screen_close"
    private const val MODULE_PKG = "io.github.kotoriinsky.tsuzuri"
    private const val ICON_NAME = "tsuzuri_screen_on"

    /** 小爱胶囊 pkg_drawable 的取值（「包名:资源名」），与岛共用同一张图。 */
    const val ICON_DRAWABLE = "$MODULE_PKG:$ICON_NAME"
    private var receiver: BroadcastReceiver? = null
    private var closeReceiver: BroadcastReceiver? = null
    private var context: Context? = null

    override fun supportsProcess(processName: String, isSystemServer: Boolean) =
        !isSystemServer && processName == "com.android.systemui"

    override fun install(ctx: HookContext): InstallReport {
        if (receiver != null) return InstallReport(1)
        val host = SystemUiEnv.context() ?: return InstallReport(0, notes = listOf("系统界面尚未就绪"))
        val engineUid = runCatching { host.packageManager.getApplicationInfo("com.xiaomi.aicr", 0).uid }.getOrNull()
            ?: return InstallReport(0, notes = listOf("小爱引擎不可用"))
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (sentFromUid != engineUid) return
                runCatching { if (intent.getBooleanExtra(ScreenOnSuggestion.EXTRA_ACTIVE, false)) show(context) else clear(context) }
                    .onFailure { ctx.log(Log.WARN, "常亮状态通知失败：${it.javaClass.simpleName}") }
            }
        }
        host.registerReceiver(r, IntentFilter(ScreenOnSuggestion.ACTION_SCREEN_STATUS), Context.RECEIVER_EXPORTED)
        receiver = r
        context = host
        val close = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching {
                    context.sendBroadcast(Intent(ScreenOnSuggestion.ACTION_SCREEN_STOP).setPackage("com.xiaomi.aicr"),
                        null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
                }.onFailure { ctx.log(Log.WARN, "常亮关闭请求失败：${it.javaClass.simpleName}") }
            }
        }
        runCatching { host.registerReceiver(close, IntentFilter(ACTION_CLOSE), Context.RECEIVER_NOT_EXPORTED) }.getOrElse {
            uninstall()
            return InstallReport(0, notes = listOf("常亮关闭接收器注册失败：${it.javaClass.simpleName}"))
        }
        closeReceiver = close
        return InstallReport(1, notes = listOf("常亮状态接收器=1"))
    }

    private fun show(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "临时屏幕常亮", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
        })
        // 只发超级岛，不再另发一条普通通知（用户 2026-10-27）；旧版本留下的那条顺手清掉。
        manager.cancel(STATUS_ID)
        val icon = moduleIcon(context) ?: drawnSun()
        val closeIcon = Icon.createWithBitmap(drawnClose())
        val title = "屏幕常亮"
        val closeIntent = Intent(ACTION_CLOSE).setPackage(context.packageName)
        val close = PendingIntent.getBroadcast(context, ISLAND_ID, closeIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // 展开态：模板 15「新图文 + 按钮组件 1」——左图标 + 标题，关闭是右侧圆形按钮，不再占底栏。
        val picture = JSONObject().put("type", 1).put("pic", PIC_SUN).put("picDark", PIC_SUN)
        val param = JSONObject().put("protocol", 1).put("updatable", true).put("enableFloat", true)
            .put("timeout", Int.MAX_VALUE).put("isShowNotification", false)
            .put("islandFirstFloat", true).put("business", "tsuzuri_screen_on")
            .put("notifyId", "com.android.systemui.$CHANNEL").put("content", title).put("ticker", title)
            .put("aodTitle", title).put("tickerPic", PIC_SUN).put("tickerPicDark", PIC_SUN)
            .put("aodPic", PIC_SUN)
            .put("param_island", JSONObject().put("islandPriority", 1).put("islandTimeout", Int.MAX_VALUE).put("islandProperty", 2)
                .put("bigIslandArea", JSONObject().put("imageTextInfoLeft", JSONObject().put("type", 1)
                    .put("picInfo", picture).put("textInfo", JSONObject().put("title", "")))
                    .put("textInfo", JSONObject().put("title", "常亮")))
                .put("smallIslandArea", JSONObject().put("picInfo", picture)))
            .put("iconTextInfo", JSONObject().put("animIconInfo", JSONObject().put("type", 0)
                .put("src", PIC_SUN).put("srcDark", PIC_SUN))
                .put("title", title).put("content", "已开启"))
            // 按钮组件 1 的 type 2 = 文字按钮（仅 1 枚）：右侧直接写「关闭」，不用图标（用户 2026-10-27）
            .put("actions", JSONArray().put(JSONObject().put("type", 2).put("action", CLOSE_KEY)
                .put("actionTitle", "关闭")
                .put("actionIntentType", 2).put("actionIntent", closeIntent.toUri(Intent.URI_INTENT_SCHEME))))
        val island = Notification.Builder(context, CHANNEL).setSmallIcon(icon).setContentTitle(title)
            .setOngoing(true).setAutoCancel(false)
        island.extras.putBoolean("show_notification", false)
        island.extras.putString("miui.focus.param", JSONObject().put("param_v2", param).toString())
        island.extras.putBundle("miui.focus.pics", Bundle().apply {
            putParcelable(PIC_SUN, icon)
            putParcelable(PIC_CLOSE, closeIcon)
        })
        island.extras.putBundle("miui.focus.actions", Bundle().apply {
            putParcelable(CLOSE_KEY, Notification.Action.Builder(closeIcon, "关闭", close).build())
        })
        manager.notify(ISLAND_ID, island.build())
    }

    /** 模块 App 里的太阳矢量图（与胶囊同一张）；模块资源取不到时退回现画的。 */
    private fun moduleIcon(context: Context): Icon? = runCatching {
        val res = context.packageManager.getResourcesForApplication(MODULE_PKG)
        @Suppress("DiscouragedApi")
        val id = res.getIdentifier(ICON_NAME, "drawable", MODULE_PKG)
        if (id == 0) return@runCatching null
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        res.getDrawable(id, null).apply { setBounds(0, 0, 96, 96) }.draw(Canvas(bitmap))
        Icon.createWithBitmap(bitmap)
    }.getOrNull()

    private fun drawnSun(): Icon {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 197, 49); strokeWidth = 8f; strokeCap = Paint.Cap.ROUND }
        canvas.drawCircle(48f, 48f, 19f, paint)
        repeat(8) { i ->
            val angle = Math.PI * i / 4
            canvas.drawLine((48 + 30 * cos(angle)).toFloat(), (48 + 30 * sin(angle)).toFloat(),
                (48 + 38 * cos(angle)).toFloat(), (48 + 38 * sin(angle)).toFloat(), paint)
        }
        return Icon.createWithBitmap(bitmap)
    }

    /**
     * 右侧圆形按钮的图标：划掉的太阳（「关闭常亮」），不用 ×（用户 2026-10-27）。
     * 白色描边，按钮底色由系统组件画；斜线两侧挖出缝隙，深浅底上都能看清是「划掉」。
     */
    private fun drawnClose(): Bitmap {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
        }
        canvas.drawCircle(48f, 48f, 14f, paint)
        repeat(8) { i ->
            val angle = Math.PI * i / 4
            canvas.drawLine((48 + 24 * cos(angle)).toFloat(), (48 + 24 * sin(angle)).toFloat(),
                (48 + 32 * cos(angle)).toFloat(), (48 + 32 * sin(angle)).toFloat(), paint)
        }
        val gap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 17f; strokeCap = Paint.Cap.ROUND
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }
        canvas.drawLine(18f, 18f, 78f, 78f, gap)
        canvas.drawLine(18f, 18f, 78f, 78f, paint)
        return bitmap
    }

    private fun clear(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(STATUS_ID)
        manager.cancel(ISLAND_ID)
    }

    override fun uninstall() {
        val host = context
        receiver?.let { if (host != null) runCatching { host.unregisterReceiver(it) } }
        closeReceiver?.let { if (host != null) runCatching { host.unregisterReceiver(it) } }
        if (host != null) clear(host)
        receiver = null
        closeReceiver = null
        context = null
        super.uninstall()
    }
}
