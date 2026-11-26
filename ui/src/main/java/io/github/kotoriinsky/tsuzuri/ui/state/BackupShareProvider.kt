package io.github.kotoriinsky.tsuzuri.ui.state

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * 备份文件分享出口（`ACTION_SEND` 临时分享 `.zip` 备份包）。
 *
 * 声明为 `exported="false"` + `grantUriPermissions="true"`：只有我们通过系统分享 Intent
 * 临时授予读权限的目标应用能读到指定文件，其余外部进程一律无法访问。
 */
class BackupShareProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("只读出口：$uri")
        val file = resolveFile(context, nameOf(uri)) ?: throw FileNotFoundException("找不到备份文件：$uri")
        if (!file.isFile) throw FileNotFoundException("备份文件不存在：$uri")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val name = nameOf(uri) ?: return null
        val file = resolveFile(context, name) ?: return null
        if (!file.isFile) return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            addRow(
                cols.map<String, Any?> { col ->
                    when (col) {
                        OpenableColumns.DISPLAY_NAME -> name
                        OpenableColumns.SIZE -> file.length()
                        else -> null
                    }
                }.toTypedArray(),
            )
        }
    }

    override fun getType(uri: Uri): String? =
        if (nameOf(uri) != null) "application/zip" else null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "io.github.kotoriinsky.tsuzuri.backupshare"
        private const val SEGMENT = "backup"
        private const val PREFIX = "tsuzuri-backup-"
        private const val SUFFIX = ".zip"
        private const val STALE_MS = 60L * 60L * 1000L

        fun shareDir(context: Context): File = File(context.cacheDir, "backup_share")

        /** 在缓存目录准备一个临时备份文件并返回可分享的 content:// URI。 */
        fun prepareFile(context: Context, fileName: String): Pair<File, Uri> {
            require(isSafeName(fileName)) { "非法分享文件名：$fileName" }
            val dir = shareDir(context)
            if (!dir.isDirectory) dir.mkdirs()
            cleanupStale(dir)
            val file = File(dir, fileName)
            val uri = Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(SEGMENT)
                .appendPath(fileName)
                .build()
            return file to uri
        }

        private fun cleanupStale(dir: File) {
            val cutoff = System.currentTimeMillis() - STALE_MS
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) runCatching { f.delete() }
            }
        }

        private fun nameOf(uri: Uri): String? {
            val segs = uri.pathSegments
            if (segs.size != 2 || segs[0] != SEGMENT) return null
            return segs[1].takeIf(::isSafeName)
        }

        private fun isSafeName(name: String): Boolean {
            if (name.length > 96 || !name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) return false
            return name.none { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() }
        }

        private fun resolveFile(context: Context?, name: String?): File? {
            if (context == null || name == null || !isSafeName(name)) return null
            val root = shareDir(context)
            return runCatching {
                val f = File(root, name)
                val rootPath = root.canonicalPath
                val filePath = f.canonicalPath
                if (filePath == rootPath || !filePath.startsWith(rootPath + File.separator)) null else f
            }.getOrNull()
        }
    }
}
