package io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.Prefs
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「单项图标覆盖」：用户给**某一项**（某个应用、或某条快捷方式）单独指定的图标。
 *
 * ## 优先级（用户要求，写死在这里，别在别处再判一遍）
 *
 * ```
 * 1. 单项自定义（图片文件）   ← 本文件 kind=FILE
 * 2. 单项图标包（从图标包里挑一个 drawable） ← 本文件 kind=PACK
 * 3. 整体图标包               ← FanTargets 的 pack（IconPackResolver）
 * 4. 应用自带图标 / 快捷方式自带图标
 * ```
 *
 * ## 存哪、怎么存
 *
 * 键 `<特性>.icon_overrides`，类型 `StringSet`（与 `apps` 同一个特性前缀）。
 * 每条一个条目，字段用控制字符 `\u0001` 分隔：
 *
 * ```
 * <targetKey> ␁ pack ␁ <图标包包名>/<drawable 名>
 * <targetKey> ␁ file ␁ <文件名> ␂ <内容指纹>
 * ```
 *
 * `targetKey` 与扇形清单用的是**同一套键**（应用 = `包名`，快捷方式 = `包名/id`），
 * 所以覆盖可以精确落到「微信」和「微信·扫一扫」中的任意一个。
 *
 * 指纹那一段**只用来判断「要不要丢掉解码缓存」**（[Set.signature] 比的是原始条目），
 * 解析不出来（老配置没有它）也照常按 `截到 ␂ 之前` 当文件名 —— 最坏情况是
 * 那一条显示成默认图标，而不是扇子画不出来。
 *
 * ## 图片是怎么从设置界面到这里的（**这条链路换过一次，原因写在下面**）
 *
 * 扇子跑在 **SystemUI 进程**里，而用户从相册选出来的图是**另一个 App 的私有数据 /
 * 一个 content:// URI**。SystemUI 既没有那个 URI 的 grant（授权给的是设置界面进程），
 * 也不是那个 uid —— 直接读必然失败（跨 uid + scoped storage）。所以设置界面先把图
 * **解码、裁剪、缩放、重新编码成 192×192 的 PNG**，放进**模块 App 自己的**
 * `filesDir/icon_overrides/`，再由同一个 App 的 `IconFileProvider`
 * （`content://<authority>/icon/<文件名>`）暴露出来；这里用
 * `contentResolver.openInputStream(uri)` 读。
 *
 * ### 为什么不再用框架的共享目录（`XposedInterface.openRemoteFile`）
 *
 * 那条通道在真机上**从来没有成功过一次** —— 设备日志里
 * `自定义图标共享目录：1 个文件（probe.png）`，那张 `probe.png` 是占位用的，
 * 用户选的图一个字节都没进去过。而且它的失败是**静默**的：设置界面侧拿不到框架服务
 * 就 `return null`、抛异常就被 catch 吞掉，日志里只剩一行类名，查不到原因。
 *
 * 换成 content:// 之后，失败会带着**具体异常类型 + 错误信息**上浮（见 [decodeFile]），
 * 而且能用 [probeChannel] 问一句「目录里现在有几个文件」—— 那行日志就是
 * 「写入侧到底有没有成功」的答案（与模块 App 进程里 `FP-IconFile` 的日志是同一份事实）。
 *
 * ## 线程
 *
 * 只在**后台合成线程**调（[FanTargets.Pool.composePages]）：要读配置、要读文件、
 * 要解码 PNG。绘制路径（`FanView.onDraw`）只拿到已经装好的位图。
 * 解码结果按「文件名 + 覆盖签名」缓存，用户改一次图标才重新解一次。
 */
internal object IconOverrides {

    private const val TAG = "FP-Fan"

    private const val FIELD = '\u0001'

    /**
     * 值里的指纹分隔符。**必须与 ui 侧 `IconOverridesStore.STAMP` 一致**。
     *
     * 它的作用只有一件事：让「同一个 target 换了张新图」也会改变配置值，
     * 从而让 [Set.signature] 变化、[FanTargets.Pool] 丢掉解码缓存
     *（文件名对同一个 target 是稳定的，光看文件名分不出新旧）。
     */
    private const val STAMP = '\u0002'

    /** kind 取值。 */
    const val KIND_PACK = "pack"
    const val KIND_FILE = "file"

    /** 覆盖存在哪个键上（与 hook 侧其它参数同一个特性前缀）。 */
    private val KEY: String = Keys.pref(
        io.github.kotoriinsky.tsuzuri.core.Catalog.CORNER_SMALL_WINDOW,
        "icon_overrides",
    )

    /**
     * 读图标的通道。**必须与 ui 侧 `IconFileProvider` 的三个常量逐字一致**
     *（`:hook` 不能依赖 `:ui`，所以只能各写一份 —— 与 [FIELD] 同一个理由）。
     */
    private const val AUTHORITY = "io.github.kotoriinsky.tsuzuri.iconfile"
    private const val SEGMENT = "icon"
    private const val METHOD_PROBE = "probe"
    private const val EXTRA_COUNT = "count"
    private const val EXTRA_FILES = "files"

    /** 一条覆盖。 */
    class Entry(val kind: String, val value: String)

    /**
     * 一次读取的结果 + 解码缓存。
     *
     * 为什么要一个「结果对象」而不是一个全局 Map：用户改一次覆盖就会换一份签名，
     * 缓存必须跟着整体失效（否则会出现「旧图标还留在扇子里」这种最难解释的现象）。
     */
    class Set(
        private val entries: Map<String, Entry>,
        /** 原始条目拼起来的签名，用来判断要不要丢弃解码缓存。 */
        val signature: String,
    ) {
        val size: Int get() = entries.size

        /** 这一项有没有覆盖（有的话上层就不去查整体图标包了）。 */
        fun has(key: String): Boolean = entries.containsKey(key)

        /**
         * 解析出这一项的图标。
         *
         * @param module  hook 侧的框架入口，**现在不再需要**（读图走 content://）；
         *                保留形参是为了不动 [FanTargets] 的调用点（那边的注释还在提它）
         * @param context 用来拿 `contentResolver`（读图）与 `Resources`（包成 Drawable）
         * @return null = 没覆盖 / 解析失败（调用方继续走整体图标包那条链）
         */
        fun drawable(
            module: XposedInterface?,
            context: Context,
            key: String,
            cache: MutableMap<String, Drawable?>,
        ): Drawable? {
            val e = entries[key] ?: return null
            cache[key]?.let { return it }
            val d = try {
                when (e.kind) {
                    KIND_FILE -> decodeFile(module, context, e.value)
                    KIND_PACK -> drawableFromPack(context, e.value)
                    else -> null
                }
            } catch (t: Throwable) {
                Log.w(TAG, "单项图标覆盖解析失败（$key, ${e.kind}）：" + t.javaClass.simpleName)
                null
            }
            cache[key] = d
            return d
        }
    }

    /** 空覆盖（没配过 / 读失败）—— 读配置失败一律回退默认值（规范 §9）。 */
    val EMPTY = Set(emptyMap(), "")

    /**
     * 读配置。**后台线程调**。
     *
     * 任何异常都吃掉：跑在系统进程的后台线程上，异常会带走 SystemUI。
     */
    fun read(prefs: Prefs): Set {
        val raw = try {
            prefs.rawStringSet(KEY)
        } catch (t: Throwable) {
            Log.w(TAG, "读单项图标覆盖失败，这一轮不套用：" + t.javaClass.simpleName)
            return EMPTY
        }
        if (raw.isEmpty()) return EMPTY

        val map = HashMap<String, Entry>(raw.size * 2)
        for (s in raw) {
            val a = s.indexOf(FIELD)
            if (a <= 0) continue
            val b = s.indexOf(FIELD, a + 1)
            if (b <= a) continue
            val key = s.substring(0, a)
            val kind = s.substring(a + 1, b)
            val value = s.substring(b + 1)
            if (key.isEmpty() || value.isEmpty()) continue
            map[key] = Entry(kind, value)
        }
        // 签名用排序后的原文：集合的迭代顺序不保证，直接用 raw.toString() 会误判「变了」
        // 指纹那一段也在原文里 —— 这正是「同一个 target 换新图」能被察觉的原因
        return Set(map, raw.sorted().joinToString("|"))
    }

    // ── 两种来源 ──────────────────────────────────────────────────────

    /**
     * 从模块 App 的图标目录读一张 PNG（`content://<authority>/icon/<文件名>`）。
     *
     * 只读：那个 provider 不实现任何写方法（它不是一个可以往里塞东西的地方）。
     *
     * 失败**必须留下能查的痕迹**：这里打的那行带上异常类型与错误信息，
     * 再问一次 [probeChannel]「目录里现在到底有几个文件」——
     * 「文件没写进去」和「写进去了但读不到（权限 / 进程没起来）」是两种病因，
     * 这两行日志把它们分开。
     *
     * @param value 配置里的值（`文件名 ␂ 指纹`）；指纹在这里被截掉，只取文件名
     */
    private fun decodeFile(
        @Suppress("UNUSED_PARAMETER") module: XposedInterface?,
        context: Context,
        value: String,
    ): Drawable? {
        val name = value.substringBefore(STAMP)
        val uri = Uri.parse("content://$AUTHORITY/$SEGMENT/$name")
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bmp = BitmapFactory.decodeStream(stream)
                if (bmp == null) {
                    Log.w(TAG, "自定义图标解码为 null（$name）：文件不是能解的图片？")
                    null
                } else {
                    BitmapDrawable(context.resources, clamp(bmp))
                }
            }
        } catch (t: Throwable) {
            // 这一行是本次修改的重点：以前这里只剩「null」，现在有异常类型 + 错误信息 + 通道探测
            Log.w(
                TAG,
                "读自定义图标失败（$name，$uri）：" + t.javaClass.simpleName + "：" + t.message,
            )
            probeChannel(context, force = true)
            null
        }
    }

    /**
     * 从图标包里取一个**指定名字**的 drawable（与整体图标包无关：覆盖里存的是
     * `包名/drawable名`，所以用户换了整体图标包，单项覆盖照样生效）。
     */
    private fun drawableFromPack(context: Context, value: String): Drawable? {
        val i = value.indexOf('/')
        if (i <= 0 || i == value.length - 1) return null
        val packPkg = value.substring(0, i)
        val name = value.substring(i + 1)
        val pack = IconPackResolver.packFor(context, packPkg) ?: return null
        return pack.drawableByName(name)
    }

    /**
     * 诊断：问一次模块 App「你的图标目录里现在有几个文件」。
     *
     * 为什么值得永久留着：这条通道（App 写 / SystemUI 读）跨进程、跨 uid，
     * 出问题时现象只有「用户选的自定义图标不显示」，没有日志就只能靠猜。
     * 这一行与模块 App 进程里 `FP-IconFile` 打的那行是**同一份事实**：
     *
     *  - `N=0` → 写入侧就没成功（问题在设置界面）；
     *  - `N>0` 但读不到 → 通道本身有问题（权限 / provider 没起来）；
     *  - 连 `call` 都抛异常 → provider 根本没法访问，异常信息里有原因。
     *
     * @param force true = 每次失败都问一次（错误路径上不该省这一次 Binder 调用）；
     *              false = 每个进程只问一次（正常路径上别刷屏）
     */
    private fun probeChannel(context: Context, force: Boolean) {
        if (!force && !probedOnce.compareAndSet(false, true)) return
        val uri = Uri.parse("content://$AUTHORITY")
        try {
            val b = context.contentResolver.call(uri, METHOD_PROBE, null, null)
            if (b == null) {
                Log.w(TAG, "图标目录探测：provider 拒绝或不存在（$uri）")
                return
            }
            val n = b.getInt(EXTRA_COUNT, -1)
            val files = b.getStringArrayList(EXTRA_FILES).orEmpty()
            Log.i(
                TAG,
                "自定义图标目录：$n 个文件" + files.joinToString("、", prefix = "（", postfix = "）"),
            )
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "图标目录探测失败（$uri）：" + t.javaClass.simpleName + "：" + t.message +
                    "（模块 App 可能被强行停止，或 provider 没进 APK）",
            )
        }
    }

    /** 每个进程只自动探测一次（[probeChannel] 的 force=false 分支）。 */
    private val probedOnce = AtomicBoolean(false)

    /**
     * 诊断入口（[FanTargets] 在扫描时调一次）。
     *
     * 这里**只打一行「通道换成什么了」**：真正的目录内容是模块 App 进程的事实，
     * 而这里拿不到 `Context`（签名 `probe(module)` 被别处调用，不能加参数），
     * 所以由 [probeChannel] 在真正读图时去问 —— 那才是需要答案的时刻。
     */
    fun probe(@Suppress("UNUSED_PARAMETER") module: XposedInterface?) {
        Log.i(
            TAG,
            "自定义图标通道：content://$AUTHORITY/$SEGMENT/" +
                "（框架共享目录已停用；目录内容在真正读图时探测）",
        )
    }

    /** 解码尺寸上限：目录里存的应该已经是 192 左右的小图，这里只兜住异常大的文件。 */
    private const val MAX_PX = 512

    /** 给 App 侧用的常量（那边拼文件名）。放这里是为了两边只有一处定义。 */
    const val SUFFIX = ".png"

    /** 位图解码的兜底尺寸检查（有人手工往目录里塞了张 4000×4000 的图也不会把内存打爆）。 */
    fun clamp(b: Bitmap): Bitmap =
        if (b.width <= MAX_PX && b.height <= MAX_PX) b
        else Bitmap.createScaledBitmap(b, MAX_PX, MAX_PX, true)
}
