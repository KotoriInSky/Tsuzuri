package io.github.kotoriinsky.tsuzuri.ui.state

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import io.github.kotoriinsky.tsuzuri.core.Catalog

/** 宿主只上报安装结果，不接收任何用户配置或内容；校验真实调用 UID 与作用域。 */
class HookStatusProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "report" || extras == null) return null
        val ctx = context ?: return null
        val spec = Catalog.find(arg.orEmpty()) ?: return null
        val callers = ctx.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty().toSet()
        if (spec.scope.none { it.packageName in callers }) throw SecurityException("调用进程不在特性作用域中")
        val count = extras.getInt("hooked", -1)
        if (count < 0) return null
        val missing = extras.getStringArrayList("missing").orEmpty().take(30).map { it.take(160) }.toSet()
        ctx.getSharedPreferences("runtime_hook_status", 0).edit()
            .putInt("${spec.key}.pid", Binder.getCallingPid())
            .putInt("${spec.key}.hooked", count)
            .putStringSet("${spec.key}.missing", missing)
            .putLong("${spec.key}.time", System.currentTimeMillis()).apply()
        return Bundle().apply { putBoolean("accepted", true) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
