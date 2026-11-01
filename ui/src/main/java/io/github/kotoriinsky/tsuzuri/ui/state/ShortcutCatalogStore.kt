package io.github.kotoriinsky.tsuzuri.ui.state

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 后端（SystemUI 里的 hook）→ 前端的**唯一一条反向通道**。
 *
 * ## 为什么需要它（不是设计偏好，是被两条硬限制夹出来的）
 *
 * 1. **设置界面查不到别人的快捷方式。** 本机 `services.jar` 反编译：
 *    `LauncherAppsService.ensureShortcutPermission` 要求调用方有 `ACCESS_SHORTCUTS`
 *    或是**当前桌面**（`ShortcutService.hasShortcutHostPermissionInner` 就是
 *    `defaultLauncher.equals(packageName)`），模块的设置界面两者都不是 →
 *    必然 `SecurityException("Caller can't access shortcut information")`。
 *    所以「有哪些快捷方式可以导入」只能在 SystemUI 里枚举。
 * 2. **后端写不了配置。** 项目的配置通道是框架的 `getRemotePreferences()`，
 *    它是**只读**的，实测如此：
 *    hook 侧 `SharedPreferences.edit()` 抛 `UnsupportedOperationException`
 *    （设备日志：`FP-Fan: 发布快捷方式目录失败：UnsupportedOperationException`）。
 *
 * 于是只剩一条路：**后端把目录交给前端**。参考实现用的是同一个办法 ——
 * HyperGesture 的 `xposed/ShortcutHostRuntime.java:340-384` 在 SystemUI/MiuiHome 里
 * 枚举，然后 `ContentResolver.call(ConfigContract.URI, "report_shortcuts", …)` 写进
 * 模块 App 自己的 ContentProvider；它同样有调用方校验
 *（"Ignored shortcut host request from unauthorized sender"）。这里照它的形态做。
 *
 * ## 安全（Provider 是 exported 的，必须自查调用方）
 *
 * 只有 `call("publish_catalog")` 一个方法，且要求调用方是：
 * uid 0 / uid 1000，或 uid 归属 `com.android.systemui`（本机 SystemUI 的
 * sharedUserId 是 `android.uid.systemui/10226`，**不是** 1000，所以必须按包名判，
 * 不能只判 system uid）。其余方法（query/insert/update/delete）一律不实现 ——
 * 这个 Provider 不是数据源，只是一个投递口。
 *
 * 文件是 App 私有 SharedPreferences（**不是**远程配置）：这份目录是**派生数据**
 *（后端算出来的），不是用户的选择。用户的选择仍然走统一配置通道
 *（`<特性>.apps`，`PrefSpec.AppList`），这一点没有变。
 */
class FanShortcutCatalogProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_PUBLISH && method != METHOD_PUBLISH_ICONS) {
            Log.w(TAG, "未知方法：$method")
            return null
        }
        if (!isTrustedCaller(context)) {
            // 别的应用能撞到这个 Provider（exported），但拿不到任何东西、也写不进来
            Log.w(TAG, "拒绝非宿主调用方的目录发布请求")
            return null
        }
        if (method == METHOD_PUBLISH_ICONS) {
            val n = writeIcons(context ?: return null, extras ?: return null)
            Log.i(TAG, "收到快捷方式图标：$n 张")
            return Bundle().apply { putBoolean(EXTRA_RESULT, true) }
        }
        val entries = extras?.getStringArrayList(EXTRA_ENTRIES) ?: return null
        val n = write(context ?: return null, entries.toHashSet())
        Log.i(TAG, "收到快捷方式目录：${entries.size} 条（写入 ${n} 条）")
        return Bundle().apply { putBoolean(EXTRA_RESULT, true) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val TAG = "FP-ShortcutCatalog"

        /** authority 由 `ui/src/main/AndroidManifest.xml` 的 `<provider>` 声明，两边必须一致。 */
        const val AUTHORITY = "io.github.kotoriinsky.tsuzuri.shortcutcatalog"

        const val METHOD_PUBLISH = "publish_catalog"
        const val EXTRA_ENTRIES = "entries"
        const val EXTRA_RESULT = "ok"

        /** 清单里快捷方式的图标（hook 侧 `FanShortcuts.publishIcons`，键与前缀两边必须一致）。 */
        const val METHOD_PUBLISH_ICONS = "publish_icons"
        const val EXTRA_ICON_KEYS = "icon_keys"
        const val EXTRA_ICON_PREFIX = "icon:"

        /** 单张上限：与 hook 侧一致，超出的当作坏数据丢掉。 */
        private const val ICON_MAX_BYTES = 32 * 1024

        /** 允许发布目录的包名（本机 SystemUI 的 sharedUserId 不是 system，只能按包名认）。 */
        private val TRUSTED_PACKAGES = arrayOf(
            "com.android.systemui",
            "com.miui.home",
            "com.miui.personalassistant",
        )

        /** hook 侧 `FanShortcuts.publish` 用的 URI。 */
        fun uri(): Uri = Uri.parse("content://$AUTHORITY")

        private fun isTrustedCaller(context: Context?): Boolean {
            val uid = try {
                Binder.getCallingUid()
            } catch (_: Throwable) {
                return false
            }
            if (uid == 0 || uid == Process.SYSTEM_UID) return true
            val ctx = context ?: return false
            val pkgs = try {
                ctx.packageManager?.getPackagesForUid(uid)
            } catch (_: Throwable) {
                null
            }
            return pkgs?.any { p -> TRUSTED_PACKAGES.any { it == p } } == true
        }

        /** 写本地存储，返回可用的条数。异常绝不外抛（Provider 崩了会带走整个 App 进程）。 */
        private fun write(context: Context, entries: Set<String>): Int {
            val clean = entries.filter { it.indexOf(FIELD) > 0 }.toHashSet()
            return try {
                prefs(context).edit().putStringSet(KEY_ENTRIES, clean).apply()
                clean.size
            } catch (t: Throwable) {
                Log.w(TAG, "写目录失败：" + t.javaClass.simpleName)
                0
            }
        }

        /**
         * 图标写成 App 私有目录下的小文件（不进 SharedPreferences：PNG 塞进 XML 会让每次读配置都变慢）。
         * 这一批没有的键对应的旧文件删掉 —— 快捷方式从清单移除后，它的图（可能是联系人头像）不留在本机。
         */
        private fun writeIcons(context: Context, extras: Bundle): Int {
            val keys = extras.getStringArrayList(EXTRA_ICON_KEYS) ?: return 0
            return try {
                val dir = ShortcutIconStore.dir(context).apply { mkdirs() }
                val keep = HashSet<String>()
                for (key in keys) {
                    val bytes = extras.getByteArray(EXTRA_ICON_PREFIX + key) ?: continue
                    if (bytes.isEmpty() || bytes.size > ICON_MAX_BYTES) continue
                    val name = ShortcutIconStore.fileName(key)
                    val tmp = java.io.File(dir, "$name.part")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(java.io.File(dir, name))) tmp.delete() else keep.add(name)
                }
                dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
                prefs(context).edit().putLong(KEY_ICONS_REV, System.currentTimeMillis()).apply()
                keep.size
            } catch (t: Throwable) {
                Log.w(TAG, "写快捷方式图标失败：" + t.javaClass.simpleName)
                0
            }
        }

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }
}

/**
 * 前端读目录的地方。**与 Provider 同一个进程、同一个文件**，所以这里读到的就是
 * 后端投递过来的那份（[FanShortcutCatalogProvider.write]）。
 *
 * 目录可能在后端重载时被重写（用户改配置 → SystemUI 重算），所以这里带一个
 * 变更订阅：Provider 写文件会触发 `OnSharedPreferenceChangeListener`，
 * [observe] 读它来触发 Compose 重绘 —— 没有它，界面会一直显示「暂时读不到」，
 * 而值其实早就到了（本项目在 [ConfigStore] 里踩过同一个坑）。
 */
object ShortcutCatalogStore {

    @Volatile
    private var listening = false

    private var revision by mutableIntStateOf(0)

    internal val revisionValue: Int get() = revision

    /** 读一次原始条目（[ShortcutCatalog] 的解析在 [readShortcutCatalog] 里）。 */
    fun raw(context: Context): Set<String> = try {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getStringSet(KEY_ENTRIES, emptySet()) ?: emptySet()
    } catch (_: Throwable) {
        emptySet()
    }

    /** Composable 里用：订阅变化 + 返回当前值。 */
    @Composable
    fun observe(context: Context): Set<String> {
        ensureListener(context)
        @Suppress("UNUSED_EXPRESSION")
        revision
        return raw(context)
    }

    private fun ensureListener(context: Context) {
        if (listening) return
        listening = true
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener { _, key ->
                    // 只认键，不看值：值可能来自 Provider 的另一次写入，重读一次最省心
                    if (key == KEY_ENTRIES || key == KEY_ICONS_REV || key == null) revision++
                }
        } catch (t: Throwable) {
            Log.w("FP-ShortcutCatalog", "注册目录监听失败，界面可能不会自动刷新：" + t.javaClass.simpleName)
        }
    }
}

/** 与 Provider 一致的内部键名。 */
private const val FILE = "shortcut_catalog"
private const val KEY_ENTRIES = "entries"
private const val KEY_ICONS_REV = "icons_rev"

/**
 * 清单里快捷方式**自己的**图标（由 SystemUI 画好送来，见 [FanShortcutCatalogProvider.writeIcons]）。
 * 没有 = 还没送到 / 超限没送 / 快捷方式本身没图标，界面退回宿主应用图标。
 */
object ShortcutIconStore {
    fun dir(context: Context): java.io.File = java.io.File(context.filesDir, "shortcut_icons")

    /** `包名/id` → 文件名。id 里可能有 `/` 等字符，用哈希避开文件名问题。 */
    fun fileName(key: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) } + ".png"

    /** 后台线程调。 */
    fun load(context: Context, key: String): android.graphics.Bitmap? = try {
        val f = java.io.File(dir(context), fileName(key))
        if (f.isFile) android.graphics.BitmapFactory.decodeFile(f.path) else null
    } catch (_: Throwable) {
        null
    }

    /** 送来新的一批时变化，Composable 里拿它当 key 触发重新加载。 */
    @Composable
    fun revision(context: Context): Int {
        ShortcutCatalogStore.observe(context)
        return ShortcutCatalogStore.revisionValue
    }
}

/**
 * 目录项内部的分隔符（控制字符，正常标签里不会出现）。
 * 必须与 hook 侧 `FanShortcuts.FIELD`、以及 `ShortcutCatalog.kt` 的解析一致。
 */
private const val FIELD = '\u0001'
