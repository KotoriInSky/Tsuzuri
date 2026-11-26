package io.github.kotoriinsky.tsuzuri.ui.ocr

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * 仅用于将主应用缓存的独立 OCR 组件安装包交给系统安装器（non-exported + grantUriPermissions）。
 */
class OcrPluginInstallProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only provider")
        val ctx = context ?: throw FileNotFoundException("no context")
        val file = File(File(ctx.cacheDir, "ocr_plugin"), "ocr-runtime.apk")
        if (!file.isFile) throw FileNotFoundException("apk not found")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        val APK_URI: Uri = Uri.parse("content://io.github.kotoriinsky.tsuzuri.ocrinstall/ocr-runtime.apk")
    }
}
