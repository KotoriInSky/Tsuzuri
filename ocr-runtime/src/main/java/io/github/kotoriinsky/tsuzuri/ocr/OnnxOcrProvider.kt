package io.github.kotoriinsky.tsuzuri.ocr

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.OnnxOcr

/**
 * 独立 OCR 推理组件跨进程服务出口（[OnnxOcr.AUTHORITY]）。
 *
 * 白名单鉴权：仅受理主模块 `io.github.kotoriinsky.tsuzuri`、`com.miui.contentextension`、
 * 本组件自身、root 与 system_server 调用。
 */
class OnnxOcrProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val out = Bundle()
        val ctx = context ?: return out.apply { putBoolean(OnnxOcr.OUT_OK, false) }
        if (!isAuthorizedCaller(ctx)) {
            Log.w(TAG, "拒绝未授权 OCR 组件调用 uid=${Binder.getCallingUid()}")
            return out.apply {
                putBoolean(OnnxOcr.OUT_OK, false)
                putString(OnnxOcr.OUT_ERROR, "unauthorized")
            }
        }
        return when (method) {
            OnnxOcr.METHOD_STATUS -> {
                val tier = OnnxOcr.Tier.of(extras?.getString(OnnxOcr.EXTRA_TIER))
                out.apply {
                    putBoolean(OnnxOcr.OUT_OK, true)
                    putBoolean(OnnxOcr.OUT_READY, OnnxOcrModelStore.isInstalled(ctx, tier))
                }
            }
            OnnxOcr.METHOD_QUERY_STATE -> handleQueryState(ctx, out)
            OnnxOcr.METHOD_START_DOWNLOAD -> {
                val tier = OnnxOcr.Tier.of(extras?.getString(OnnxOcr.EXTRA_TIER))
                val mirror = OnnxOcr.Mirror.of(extras?.getString(OnnxOcr.EXTRA_MIRROR))
                val customMirror = extras?.getString(OnnxOcr.EXTRA_CUSTOM_MIRROR)
                val started = OnnxOcrModelStore.startDownload(ctx, tier, mirror, customMirror)
                out.apply { putBoolean(OnnxOcr.OUT_OK, started) }
            }
            OnnxOcr.METHOD_CANCEL_DOWNLOAD -> {
                OnnxOcrModelStore.cancelDownload()
                out.apply { putBoolean(OnnxOcr.OUT_OK, true) }
            }
            OnnxOcr.METHOD_DELETE_TIER -> {
                val tier = OnnxOcr.Tier.of(extras?.getString(OnnxOcr.EXTRA_TIER))
                OnnxOcrModelStore.deleteTier(ctx, tier)
                out.apply { putBoolean(OnnxOcr.OUT_OK, true) }
            }
            OnnxOcr.METHOD_RELEASE -> {
                OnnxOcrEngine.release()
                out.apply { putBoolean(OnnxOcr.OUT_OK, true) }
            }
            OnnxOcr.METHOD_RECOGNIZE -> handleRecognize(ctx, extras, out)
            else -> out.apply {
                putBoolean(OnnxOcr.OUT_OK, false)
                putString(OnnxOcr.OUT_ERROR, "unknown method: $method")
            }
        }
    }

    private fun handleQueryState(ctx: Context, out: Bundle): Bundle {
        val installed = ArrayList<String>()
        val bytesArray = LongArray(OnnxOcr.Tier.entries.size)
        OnnxOcr.Tier.entries.forEachIndexed { idx, tier ->
            if (OnnxOcrModelStore.isInstalled(ctx, tier)) {
                installed.add(tier.id)
                bytesArray[idx] = OnnxOcrModelStore.installedBytes(ctx, tier)
            }
        }
        val snap = OnnxOcrModelStore.snapshot()
        val versionName = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull().orEmpty()
        return out.apply {
            putBoolean(OnnxOcr.OUT_OK, true)
            putString(OnnxOcr.OUT_VERSION, versionName)
            putStringArrayList(OnnxOcr.OUT_INSTALLED_TIERS, installed)
            putLongArray(OnnxOcr.OUT_TIER_BYTES, bytesArray)
            putString(OnnxOcr.OUT_DL_STATE, snap.state)
            putString(OnnxOcr.OUT_DL_TIER, snap.tierId)
            putString(OnnxOcr.OUT_DL_FILE, snap.fileLabel)
            putLong(OnnxOcr.OUT_DL_BYTES, snap.downloadedBytes)
            putLong(OnnxOcr.OUT_DL_TOTAL, snap.totalBytes)
            putString(OnnxOcr.OUT_DL_MESSAGE, snap.message)
        }
    }

    private fun handleRecognize(ctx: Context, extras: Bundle?, out: Bundle): Bundle {
        val tier = OnnxOcr.Tier.of(extras?.getString(OnnxOcr.EXTRA_TIER))
        val backend = OnnxOcr.Backend.of(extras?.getString(OnnxOcr.EXTRA_BACKEND))
        @Suppress("DEPRECATION")
        val pfd = extras?.getParcelable<ParcelFileDescriptor>(OnnxOcr.EXTRA_PFD)
            ?: return out.apply {
                putBoolean(OnnxOcr.OUT_OK, false)
                putString(OnnxOcr.OUT_ERROR, "missing image pfd")
            }
        if (!OnnxOcrModelStore.isInstalled(ctx, tier)) {
            runCatching { pfd.close() }
            return out.apply {
                putBoolean(OnnxOcr.OUT_OK, false)
                putString(OnnxOcr.OUT_ERROR, "model_not_installed:${tier.id}")
            }
        }
        val bitmap = try {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { BitmapFactory.decodeStream(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "解码 OCR 输入图片失败", t)
            null
        } ?: return out.apply {
            putBoolean(OnnxOcr.OUT_OK, false)
            putString(OnnxOcr.OUT_ERROR, "decode_failed")
        }

        return try {
            val res = OnnxOcrEngine.recognize(ctx, bitmap, tier, backend)
            if (res == null) {
                out.apply {
                    putBoolean(OnnxOcr.OUT_OK, false)
                    putString(OnnxOcr.OUT_ERROR, "engine_null")
                }
            } else {
                val n = res.glyphs.size
                val texts = ArrayList<String>(n)
                val lines = IntArray(n)
                val boxes = FloatArray(n * 4)
                res.glyphs.forEachIndexed { i, g ->
                    texts.add(g.text)
                    lines[i] = g.line
                    boxes[i * 4] = g.box.left
                    boxes[i * 4 + 1] = g.box.top
                    boxes[i * 4 + 2] = g.box.right
                    boxes[i * 4 + 3] = g.box.bottom
                }
                out.apply {
                    putBoolean(OnnxOcr.OUT_OK, true)
                    putString(OnnxOcr.OUT_ENGINE, res.engineLabel)
                    putLong(OnnxOcr.OUT_ELAPSED_MS, res.elapsedMs)
                    putStringArrayList(OnnxOcr.OUT_TEXTS, texts)
                    putIntArray(OnnxOcr.OUT_LINES, lines)
                    putFloatArray(OnnxOcr.OUT_BOXES, boxes)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "PP-OCRv6 推理失败", t)
            out.apply {
                putBoolean(OnnxOcr.OUT_OK, false)
                putString(OnnxOcr.OUT_ERROR, t.message ?: t.javaClass.simpleName)
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun isAuthorizedCaller(ctx: Context): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid() || uid == 0 || uid == Process.SYSTEM_UID) return true
        val pkgs = runCatching { ctx.packageManager.getPackagesForUid(uid) }.getOrNull().orEmpty()
        return pkgs.any { it == HOST_PACKAGE || it == CONTENT_EXTENSION_PACKAGE }
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    private companion object {
        const val TAG = "FP-OnnxOcrProvider"
        const val HOST_PACKAGE = "io.github.kotoriinsky.tsuzuri"
        const val CONTENT_EXTENSION_PACKAGE = "com.miui.contentextension"
    }
}
