package io.github.kotoriinsky.tsuzuri.ui.state

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import io.github.kotoriinsky.tsuzuri.core.PortalMediaContract
import java.io.File
import java.io.FileNotFoundException
import java.security.SecureRandom

/** 原媒体的私有暂存出口。分享原件只读；编辑器只能写独立的高熵编辑副本。 */
class PortalMediaProvider : ContentProvider() {
    override fun onCreate() = true
    private fun root(): File = File(requireNotNull(context).filesDir, "portal_media")
    private fun trusted(): Boolean {
        val uid = Binder.getCallingUid()
        return uid == Process.myUid() || uid == 0 || uid == Process.SYSTEM_UID ||
            context?.packageManager?.getPackagesForUid(uid).orEmpty().contains("com.miui.contentextension")
    }
    private fun file(uri: Uri): File {
        val segments = uri.pathSegments
        if (uri.authority != PortalMediaContract.AUTHORITY || segments.size != 2 ||
            !PortalMediaContract.safeSegment(segments[0]) || !PortalMediaContract.safeToken(segments[1]))
            throw FileNotFoundException("媒体令牌无效")
        val directory = directory(segments[0])
        return File(directory, segments[1]).also {
            if (it.canonicalFile.parentFile != directory.canonicalFile) throw FileNotFoundException("媒体路径无效")
        }
    }
    private fun directory(segment: String) =
        if (segment == PortalMediaContract.SEGMENT) root() else File(root(), PortalMediaContract.EDIT_SEGMENT)

    private fun writable(uri: Uri): Boolean {
        if (trusted()) return true
        if (uri.pathSegments.firstOrNull() != PortalMediaContract.EDIT_SEGMENT) return false
        val ctx = context ?: return false
        val uid = Binder.getCallingUid()
        if (PortalMediaContract.isDirectEditWriter(PortalMediaContract.EDIT_SEGMENT,
                ctx.packageManager.getPackagesForUid(uid).orEmpty().toList())) return true
        return ctx.checkUriPermission(uri, Binder.getCallingPid(), uid, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (!trusted() || uri.authority != PortalMediaContract.AUTHORITY ||
            uri.pathSegments.size != 1 || !PortalMediaContract.safeSegment(uri.pathSegments[0])) return null
        val format = PortalMediaContract.Format.entries.singleOrNull { it.extension == values?.getAsString("extension") }
            ?: return null
        val directory = directory(uri.pathSegments[0]).apply { mkdirs() }
        val expiry = System.currentTimeMillis() - 60 * 60 * 1000L
        listOf(root(), directory(PortalMediaContract.EDIT_SEGMENT)).forEach { dir ->
            dir.listFiles()?.filter { it.isFile && it.lastModified() < expiry }?.forEach { it.delete() }
        }
        val bytes = ByteArray(18).also(random::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it) } + "." + format.extension
        val out = File(directory, token)
        if (!out.createNewFile()) return null
        return uri.buildUpon().appendPath(token).build()
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val target = file(uri).takeIf { it.isFile } ?: throw FileNotFoundException("媒体已过期")
        if (mode !in listOf("r", "w", "wt", "rw", "rwt") || (mode != "r" && !writable(uri)))
            throw FileNotFoundException("没有媒体写权限")
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode))
    }
    override fun getType(uri: Uri): String? = runCatching { PortalMediaContract.tokenMime(file(uri).name) }.getOrNull()
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val file = runCatching { file(uri) }.getOrNull()?.takeIf { it.isFile } ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map<String, Any?> { if (it == OpenableColumns.DISPLAY_NAME) file.name else if (it == OpenableColumns.SIZE) file.length() else null }.toTypedArray())
        }
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        if (trusted() && runCatching { file(uri).delete() }.getOrDefault(false)) 1 else 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    companion object { private val random = SecureRandom() }
}
