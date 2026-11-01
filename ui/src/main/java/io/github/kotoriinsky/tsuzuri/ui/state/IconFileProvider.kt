package io.github.kotoriinsky.tsuzuri.ui.state

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileNotFoundException

/**
 * 自定义图标的**唯一出口**：设置界面把处理好的小图写进模块 App 自己的 files 目录，
 * SystemUI 通过这个 provider 用 `contentResolver.openInputStream(uri)` 读出来。
 *
 * ## 为什么不再走框架的共享目录（这是本次修的那个 bug）
 *
 * 原来的链路是 `XposedService.openRemoteFile`（设置界面写）/ `XposedInterface.openRemoteFile`
 * （hook 读）。它在真机上**从来没有成功过一次**：设备日志里
 * `自定义图标共享目录：1 个文件（probe.png）`，那张 `probe.png` 是占位用的，
 * 用户选的图一个字节都没进去过。
 *
 * 那条通道的失败是**静默**的：
 *
 * ```
 * FrameworkBridge.current ?: return null     // 拿不到框架服务 → null，没有任何线索
 * svc.openRemoteFile(name)                   // 抛异常 → 被 catch 吞掉，日志只剩一个类名
 * ```
 *
 * 于是「用户选了图但扇子里没变」这件事在日志里查不出原因 —— 这正是要根治的病。
 * 换成 `content://` 之后，失败会带着**具体的异常类型 + 错误信息**上浮
 * （`FileNotFoundException` / `SecurityException` / 找不到 provider），两边都能看见。
 *
 * ## 为什么自己写一个，而不用 `androidx.core.content.FileProvider`
 *
 * 两件事 FileProvider 不做，而这里必须要做：
 *
 * 1. **调用方校验**（下面第三条）—— FileProvider 只按 `file_paths.xml` 校验路径；
 * 2. **路径白名单**（只放行本目录下、`.png` 结尾、名字里没有分隔符的文件）。
 *
 * 顺带，自己写不需要 `file_paths.xml` 那份 XML，也就少一处「声明与实际不符」的可能。
 *
 * ## 安全：provider 是 exported 的，所以必须自查调用方
 *
 * URI 是 `content://<authority>/icon/<文件名>`，**只实现读**：
 *
 *  - `openFile` 只接受 `mode == "r"`，其余一律拒绝；
 *  - `insert` / `update` / `delete` 一律不实现（返回 null / 0）—— 这个 provider
 *    不是数据源，只是一个**只读出口**，写永远只发生在模块 App 自己的进程里；
 *  - 调用方 uid 必须是 uid 0 / 1000 / `com.android.systemui` 的 uid / 本 App 自己
 *    （本机 SystemUI 的 sharedUserId 是 `android.uid.systemui`，**不是** 1000，
 *    所以必须按包名判，与 `FanShortcutCatalogProvider.isTrustedCaller` 同一套规则）。
 *
 * 文件名还会做**双重**校验：先按字符白名单剔一遍，再把 `canonicalPath` 比一遍，
 * 确认它确实落在图标目录里 —— 只靠第一层的话，`..` 之外的花样（符号链接等）挡不住。
 *
 * ## 失败要有多可见
 *
 * 每次拒绝/找不到都打一行 `Log.w`，内容包含**具体 URI 与原因**。判断「到底通没通」
 * 不需要猜：hook 侧还能用 [METHOD_PROBE] 问一次「目录里现在有几个文件、各多大」，
 * 那行日志与这里的是同一份事实（见 `IconOverrides.probeChannel`）。
 */
class IconFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /**
     * 唯一的读入口。
     *
     * `mode` 只接受 `"r"`：其余（`w` / `rw` / `wt`）一律当「文件不存在」拒绝 ——
     * 不能抛一个「不支持写」的消息，那等于告诉外界「这里可以试写」。
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val name = nameOf(uri) ?: return deny(uri, "URI 形状不对（只认 /$SEGMENT/<文件名>）")
        if (mode != "r") return deny(uri, "只读 provider，拒绝 mode=$mode")
        if (!isTrustedCaller(context)) return deny(uri, "非可信调用方 uid=${callingUidOrNull()}")
        val file = resolveFile(context, name) ?: return deny(uri, "文件名不合法或不在图标目录内")
        if (!file.isFile) return deny(uri, "文件不存在")
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also {
                Log.i(TAG, "读取 $name（${file.length()} 字节）")
            }
        } catch (t: Throwable) {
            return deny(uri, "打开文件失败：" + t.javaClass.simpleName + "：" + t.message)
        }
    }

    /**
     * 诊断用：这个文件现在多大。
     *
     * 只在可信调用方下返回一行；其余（含未知 URI）返回 null。
     * 与 `openFile` 一样，任何异常都不外抛 —— provider 崩了会带走整个 App 进程。
     */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (!isTrustedCaller(context)) {
            Log.w(TAG, "拒绝查询（非可信调用方 uid=${callingUidOrNull()}）：$uri")
            return null
        }
        val name = nameOf(uri) ?: return null
        val file = resolveFile(context, name) ?: return null
        return try {
            MatrixCursor(arrayOf(COLUMN_NAME, COLUMN_SIZE)).apply {
                addRow(arrayOf<Any?>(file.name, if (file.isFile) file.length() else -1L))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "查询失败：" + t.javaClass.simpleName)
            null
        }
    }

    /**
     * 诊断入口（[METHOD_PROBE]）：**目录里现在有几个文件、各多大**。
     *
     * 为什么值得永久留着：这条通道的故障现象只有「图标没变」，而它跨进程、
     * 跨 uid、还要过包可见性 —— 没有这行日志就只能靠猜。它与
     * `IconOverrides.probeChannel` 打的那行是同一份事实的两个视角
     *（一边在 SystemUI，一边在这里）。
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_PROBE) {
            Log.w(TAG, "未知方法：$method")
            return null
        }
        if (!isTrustedCaller(context)) {
            Log.w(TAG, "拒绝探测（非可信调用方 uid=${callingUidOrNull()}）")
            return null
        }
        val files = try {
            dir(context)?.listFiles()?.filter { it.isFile && it.name.endsWith(SUFFIX) }.orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "列目录失败：" + t.javaClass.simpleName + "：" + t.message)
            emptyList()
        }
        val detail = files.sortedBy { it.name }.take(MAX_REPORT).map { it.name + "=" + it.length() + "B" }
        Log.i(TAG, "图标目录：${files.size} 个文件" + detail.joinToString("、", prefix = "（", postfix = "）"))
        return Bundle().apply {
            putInt(EXTRA_COUNT, files.size)
            putStringArrayList(EXTRA_FILES, ArrayList(detail))
        }
    }

    /** 图标文件就是 PNG（`getType` 给它一个确定的值，方便排障时看 `dumpsys`）。 */
    override fun getType(uri: Uri): String? =
        if (nameOf(uri) != null) "image/png" else null

    // 不是数据源：写操作一概不提供，见类注释。
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    // ── 内部工具 ──────────────────────────────────────────────────────

    /** 统一的「说不」：打一行带原因的日志（**这是本次修 bug 的核心诉求**），再抛出去。 */
    private fun deny(uri: Uri, why: String): Nothing {
        Log.w(TAG, "拒绝读取 $uri：$why")
        throw FileNotFoundException("$uri：$why")
    }

    companion object {
        private const val TAG = "FP-IconFile"

        /**
         * authority。`ui/src/main/AndroidManifest.xml` 的 `<provider>` 与 hook 侧
         * `IconOverrides.AUTHORITY` 三处必须逐字一致（`:hook` 不能依赖 `:ui`，
         * 所以只能各写一份 —— 与 `IconOverrides.FIELD` 同一个理由）。
         */
        const val AUTHORITY = "io.github.kotoriinsky.tsuzuri.iconfile"

        /** URI 的路径段。hook 侧 `IconOverrides` 里有一份对应的常量。 */
        const val SEGMENT = "icon"

        /** 只放行这种后缀（能过滤掉目录里的临时文件 / `.nomedia` 之类）。 */
        const val SUFFIX = ".png"

        /** 探测方法名与返回键（hook 侧 `IconOverrides` 有对应常量）。 */
        const val METHOD_PROBE = "probe"
        const val EXTRA_COUNT = "count"
        const val EXTRA_FILES = "files"

        private const val COLUMN_NAME = "_display_name"
        private const val COLUMN_SIZE = "_size"

        /** 探测返回的条目上限（日志不该被一次探测刷屏）。 */
        private const val MAX_REPORT = 20

        /**
         * 允许读图标的包名（本机 SystemUI 的 sharedUserId 不是 system，只能按包名认）。
         *
         * `IconOverridesStore` 也读它：写盘之后逐个 `grantUriPermission`，见那里的注释。
         */
        val READER_PACKAGES = arrayOf(
            "com.android.systemui",
            "com.miui.personalassistant",
            "com.miui.home",
        )

        /**
         * 图标目录。**只在这里定义一次**：写入侧（`IconOverridesStore.importImage`）
         * 与读取侧（本 provider）必须落在同一个目录上，两处各拼一次字符串迟早会漂移。
         */
        fun dir(context: Context?): File? = context?.let { File(it.filesDir, "icon_overrides") }

        /** 某个文件的对外 URI（hook 侧 `IconOverrides` 拼的是同一个形状）。 */
        fun uriOf(name: String): Uri = Uri.parse("content://$AUTHORITY/$SEGMENT/$name")

        /**
         * 名字 → 文件。**两道校验**，任一道不过就返回 null：
         *
         * 1. 字符白名单：非空、不超长、`.png` 结尾、不含路径分隔符、不是 `.` / `..`；
         * 2. `canonicalPath` 必须真的在图标目录内 —— 挡掉第 1 条覆盖不到的情况
         *   （符号链接、以及未来哪天白名单被放宽）。
         */
        fun resolveFile(context: Context?, name: String?): File? {
            if (context == null || name == null) return null
            if (!isSafeName(name)) return null
            val root = dir(context) ?: return null
            return try {
                val f = File(root, name)
                val rootPath = root.canonicalPath
                val filePath = f.canonicalPath
                if (filePath == rootPath || !filePath.startsWith(rootPath + File.separator)) null else f
            } catch (t: Throwable) {
                Log.w(TAG, "解析文件名失败（$name）：" + t.javaClass.simpleName)
                null
            }
        }

        private fun isSafeName(name: String): Boolean {
            if (name.isEmpty() || name.length > 128) return false
            if (!name.endsWith(SUFFIX)) return false
            if (name == "." || name == "..") return false
            return name.none { it == '/' || it == '\\' || it == '\u0000' }
        }

        /**
         * URI → 文件名。只认 `/<SEGMENT>/<一段>` 这一种形状：
         * 多一段少一段都不认（形状不对时 `openFile` 会打一行带原因的日志）。
         */
        private fun nameOf(uri: Uri): String? {
            val segs = uri.pathSegments
            if (segs.size != 2 || segs[0] != SEGMENT) return null
            val last = segs[1]
            return last.takeIf { it.isNotEmpty() }
        }

        private fun callingUidOrNull(): Int = try {
            Binder.getCallingUid()
        } catch (_: Throwable) {
            -1
        }

        /**
         * 调用方可信吗。uid 0 / 1000 / 本 App 自己，或 uid 归属 [READER_PACKAGES]。
         *
         * 取不到 uid 一律当**不可信**（宁可读不到图，也不能把文件交出去）。
         */
        fun isTrustedCaller(context: Context?): Boolean {
            val uid = callingUidOrNull()
            if (uid < 0) return false
            if (uid == 0 || uid == Process.SYSTEM_UID || uid == Process.myUid()) return true
            val pkgs = try {
                context?.packageManager?.getPackagesForUid(uid)
            } catch (_: Throwable) {
                null
            }
            return pkgs?.any { p -> READER_PACKAGES.any { it == p } } == true
        }
    }
}
