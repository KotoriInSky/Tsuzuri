package io.github.kotoriinsky.tsuzuri.ui.state

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.PortalTempImageContract
import java.io.File
import java.io.FileNotFoundException
import java.security.SecureRandom

/**
 * 传送门编辑/分享的私有临时图片出口。
 *
 * 图片写在模块 App 的 filesDir，不进入 MediaStore；外部组件只能通过拿到的
 * 高熵临时 URI 读取或编辑，Provider 不提供目录枚举。分享 URI 本身是只读能力令牌，
 * 因为宿主进程不拥有 Provider，无法通过 chooser 转授系统 URI 权限；写入仍要求可信调用方。
 */
class PortalTempImageProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        cleanupStale()
        return true
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val segment = segmentOf(uri) ?: return null
        if (!isTrustedWriter()) return null
        val token = newToken()
        val file = resolveFile(segment, token) ?: return null
        return try {
            file.parentFile?.mkdirs()
            if (!file.createNewFile()) return null
            uri(segment, token)
        } catch (t: Throwable) {
            Log.w(TAG, "创建临时图失败", t)
            runCatching { file.delete() }
            null
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val segment = segmentOf(uri) ?: throw missing(uri, "URI 形状不对")
        val token = tokenOf(uri) ?: throw missing(uri, "文件名不合法")
        val file = resolveFile(segment, token) ?: throw missing(uri, "路径不合法")
        val write = mode == "w" || mode == "wt" || mode == "rw" || mode == "rwt"
        val required = if (write) android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        else android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (!isAllowed(uri, required)) throw missing(uri, "没有 URI 授权")
        val access = if (write) ParcelFileDescriptor.MODE_READ_WRITE else ParcelFileDescriptor.MODE_READ_ONLY
        return try {
            ParcelFileDescriptor.open(file, access)
        } catch (t: Throwable) {
            throw missing(uri, "打开失败：${t.javaClass.simpleName}")
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val segment = segmentOf(uri) ?: return 0
        val token = tokenOf(uri) ?: return 0
        if (!isAllowed(uri, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) return 0
        return if (resolveFile(segment, token)?.delete() == true) 1 else 0
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val segment = segmentOf(uri) ?: return null
        val token = tokenOf(uri) ?: return null
        if (!isAllowed(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)) return null
        val file = resolveFile(segment, token) ?: return null
        if (!file.isFile) return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map<String, Any?> { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> token
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            }.toTypedArray())
        }
    }

    override fun getType(uri: Uri): String? =
        if (segmentOf(uri) != null && tokenOf(uri) != null) "image/png" else null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun shutdown() {
        cleanupStale()
        super.shutdown()
    }

    private fun segmentOf(uri: Uri): String? =
        PortalTempImageContract.segmentOf(uri.pathSegments)

    private fun tokenOf(uri: Uri): String? {
        val segments = uri.pathSegments
        if (segments.size != 2 || !PortalTempImageContract.isSafeSegment(segments[0])) return null
        return segments[1].takeIf(PortalTempImageContract::isSafeToken)
    }

    private fun resolveFile(segment: String, token: String): File? {
        val root = rootDir(context) ?: return null
        if (!PortalTempImageContract.isSafeSegment(segment) ||
            !PortalTempImageContract.isSafeToken(token)
        ) return null
        return try {
            val file = File(File(root, segment), token)
            val rootPath = root.canonicalPath
            val filePath = file.canonicalPath
            if (!filePath.startsWith(rootPath + File.separator)) null else file
        } catch (_: Throwable) {
            null
        }
    }

    private fun isTrustedWriter(): Boolean {
        val uid = callingUid()
        if (uid == Process.myUid() || uid == 0 || uid == Process.SYSTEM_UID) return true
        val packages = try {
            context?.packageManager?.getPackagesForUid(uid).orEmpty()
        } catch (_: Throwable) {
            emptyArray()
        }
        return packages.any { it == "com.miui.contentextension" }
    }

    private fun isAllowed(uri: Uri, mode: Int): Boolean {
        val uid = callingUid()
        if (uid == Process.myUid() || uid == 0 || uid == Process.SYSTEM_UID) return true
        if (isTrustedWriter() && mode and android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
            return true
        }
        val segment = segmentOf(uri)
        if (segment != null &&
            PortalTempImageContract.isDirectAccessCaller(segment, callingPackages(uid))
        ) return true
        if (segment != null &&
            PortalTempImageContract.isTokenCapabilityAllowed(
                segment,
                write = mode and android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0,
            )
        ) return true
        val ctx = context ?: return false
        return ctx.checkUriPermission(uri, Binder.getCallingPid(), uid, mode) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun callingPackages(uid: Int): List<String> = try {
        context?.packageManager?.getPackagesForUid(uid)?.toList().orEmpty()
    } catch (_: Throwable) {
        emptyList()
    }

    private fun callingUid(): Int = try {
        Binder.getCallingUid()
    } catch (_: Throwable) {
        -1
    }

    private fun newToken(): String {
        val bytes = ByteArray(18)
        secureRandom.nextBytes(bytes)
        return buildString(bytes.size * 2 + 4) {
            bytes.forEach { append("%02x".format(it)) }
            append(PortalTempImageContract.PNG_SUFFIX)
        }
    }

    private fun cleanupStale() {
        val root = rootDir(context) ?: return
        val cutoff = System.currentTimeMillis() - STALE_MS
        runCatching {
            root.listFiles()?.forEach { segment ->
                if (!segment.isDirectory) return@forEach
                segment.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(PortalTempImageContract.PNG_SUFFIX) &&
                        file.lastModified() < cutoff
                    ) file.delete()
                }
            }
        }.onFailure { Log.w(TAG, "清理临时图失败", it) }
    }

    private fun missing(uri: Uri, reason: String): FileNotFoundException =
        FileNotFoundException("$uri：$reason")

    companion object {
        private const val TAG = "FP-PortalTemp"
        private const val STALE_MS = 30 * 60 * 1000L
        private val secureRandom = SecureRandom()

        fun rootDir(context: Context?): File? =
            context?.let { File(it.filesDir, "portal_temp") }

        fun uri(segment: String, token: String): Uri =
            Uri.parse(PortalTempImageContract.uri(segment, token))
    }

}
