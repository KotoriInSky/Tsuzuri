package io.github.kotoriinsky.tsuzuri.hook.feature.sideindex

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.IconOverrideEntries
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.Prefs
import io.github.kotoriinsky.tsuzuri.hook.feature.smallwindow.IconPackResolver

/**
 * 侧滑索引面板的**单项图标覆盖**（本批 3️⃣ 的后半）：用户给某一个应用单独指定图标。
 *
 * ## 与扇形那一套的关系：**同一份格式、同一个通道**，不是第二套概念
 *
 * 键 `<side_index>.icon_overrides`，值与扇形逐字同格式
 * （`<包名> ␁ pack ␁ <包名>/<drawable>` / `<包名> ␁ file ␁ <文件名> ␂ <指纹>`），
 * 解析用的是 `core` 里那份**可测的**实现 [IconOverrideEntries] ——
 * 本类只负责「读配置 → 解析 → 缓存」这三件在 SystemUI 里必须小心的事。
 *
 * 图片那条通道也是同一条：设置界面把用户从相册选的图解码裁剪成 192×192 的 PNG，
 * 放进**模块 App 自己的** `filesDir/icon_overrides/`，再经
 * `content://io.github.kotoriinsky.tsuzuri.iconfile/icon/<文件名>` 暴露出来
 * （理由写在 `ui/state/IconOverridesStore.kt` 的类注释里：SystemUI 既没有那个 URI 的
 * grant 也不是那个 uid，直接读用户的图必然失败）。**这一版不再走框架的共享目录**
 * —— 那条路在本项目里从来没有成功过一次。
 *
 * ## 优先级（与扇形那条链同序，别在别处再判一遍）
 *
 * ```
 * 单项覆盖（图片 / 图标包）  ← 本类
 * 整体图标包                 ← [SideIndexIconPack]
 * 应用自带图标
 * ```
 *
 * 单项覆盖**与整体图标包不是并列关系**：只要这一项有覆盖，整体图标包就不参与
 * （[SideIndexOverlay.bindCell] 里那三行的顺序就是这条优先级）。
 *
 * ## 缓存：按**签名**失效，不是按「配置变了」
 *
 * 触发一次配置重读的原因可能跟图标毫无关系（用户正在拖某个滑杆），所以缓存不能挂在
 * 「读完配置」这个动作上 —— 那会让一次滑杆拖动把几张 PNG 重新解码一遍（主线程 + 磁盘 IO）。
 * 缓存挂在 [Signatures.signature] 上：**只有覆盖内容真的变了**才重新解析。
 * 这也正是指纹（`␂` 后那一段）存在的理由：同一个应用换了一张新图，文件名不变、
 * 只有指纹变 —— 没有它，缓存永远不会失效，用户看到的是旧图。
 *
 * ## 线程
 *
 * - [read] 在**任何线程**都能调（一次配置读 + 一次纯字符串解析）；
 * - [Set.drawable] 只在**主线程**调（[SideIndexOverlay.bindCell]），内部只做查表，
 *   未命中时才解一次（每个「签名 + 包名」只解一次，之后命中缓存）；
 * - [prewarm] 用来把首次解码挪到后台线程 —— 不排它也不会错，只是第一次绑定时
 *   多花一次解码的时间（几百微秒到几毫秒，只在用户**配过覆盖**的那些应用上）。
 */
internal object SideIndexIconOverride {

    private const val TAG = "FP-SideIndex"

    /** 键后缀。与 `ui/state/IconOverridesStore.key(featureKey)` 同源。 */
    const val PREF = "icon_overrides"

    /**
     * 读图的通道。**必须与 `ui/state/IconFileProvider` 的三个常量逐字一致**
     *（`:hook` 不能依赖 `:ui`，所以只能各写一份 —— `smallwindow/IconOverrides.kt`
     * 里的三个常量也是同一个理由各自写的）。
     */
    private const val AUTHORITY = "io.github.kotoriinsky.tsuzuri.iconfile"
    private const val SEGMENT = "icon"

    /** 解码尺寸上限（有人手工往目录里塞了张大图也不会把 SystemUI 的内存打爆）。 */
    private const val MAX_PX = 512

    /** 解码缓存上限。覆盖条目是用户一条条加的，正常在个位数，256 只是给「异常情况下一直涨」一个终点。 */
    private const val CACHE_MAX = 256

    /**
     * 一次读取的结果。
     *
     * [signature] 是**原始条目**拼出来的（与 `IconOverrides.Set` 用同一条算法），
     * 它有两个消费者：[SideIndexConfig.windowDiffersFrom]（决定要不要换代浮层）
     * 与解码缓存的键。
     */
    class Set internal constructor(
        private val entries: Map<String, IconOverrideEntries.Entry>,
        val signature: String,
    ) {
        val size: Int get() = entries.size

        /** 这一项有没有覆盖。上层据此决定「还要不要去看整体图标包」。 */
        fun has(key: String): Boolean = entries.containsKey(key)

        /** 覆盖了哪些目标（预热用）。顺序无所谓。 */
        fun keys(): List<String> = entries.keys.toList()

        /** 覆盖存的是哪个 kind（安装日志/诊断用；没有覆盖时 null）。 */
        fun kindOf(key: String): String? = entries[key]?.kind

        /**
         * 按**签名**判等。
         *
         * 为什么必须显式写：`SideIndexConfig` 是 data class，而 `applyConfig` 的第一道
         * 闸门就是 `next == prev`。不写的话这个字段退化成引用相等 —— 每一次配置重读都
         * 「不相等」，于是那道闸门永远过不去（后面每一项的逐一比较仍然是对的，
         * 不会做多余的事，但「什么都没改」这件事就没法在那一行被看出来）。
         *
         * 签名是条目集合的**完整**函数（排序后拼原文），所以签名相同 = 内容相同。
         */
        override fun equals(other: Any?): Boolean =
            other is Set && other.signature == signature

        override fun hashCode(): Int = signature.hashCode()

        /**
         * 解析出这一项的图标。**主线程**（[SideIndexOverlay.bindCell]）。
         *
         * @return null = 没覆盖 / 解析失败 —— 调用方继续走整体图标包那条链
         */
        fun drawable(context: Context, key: String): Drawable? {
            val e = entries[key] ?: return null
            val cacheKey = signature + '\u0000' + key
            synchronized(cacheLock) {
                if (cache.containsKey(cacheKey)) return cache[cacheKey]
            }
            val d = try {
                when (e.kind) {
                    IconOverrideEntries.KIND_FILE -> decodeFile(context, IconOverrideEntries.fileName(e.value))
                    IconOverrideEntries.KIND_PACK -> drawableFromPack(context, e.value)
                    else -> null
                }
            } catch (t: Throwable) {
                // 这里跑在 SystemUI 的主线程上，异常逃出去就是黑屏。最坏情况是「这一项用自带图标」
                Log.w(TAG, "单项图标覆盖解析失败（$key, ${e.kind}）：" + t.javaClass.simpleName)
                null
            }
            synchronized(cacheLock) {
                // 签名没变才写回（变了说明用户刚改过，这一份结果已经过期）
                if (cache.size >= CACHE_MAX) cache.clear()
                cache[cacheKey] = d
            }
            return d
        }
    }

    /** 空覆盖（没配过 / 读失败）—— 读配置失败一律回退「不套用」（规范 §9）。 */
    val EMPTY = Set(emptyMap(), "")

    private val cacheLock = Any()

    /**
     * 解码缓存。**进程级、按签名分区**：同一个应用在同一份覆盖内容下只解一次。
     *
     * 值可以是 null（= 解析过、结论是「用不了」），所以查询必须用 `containsKey`
     * 区分「缓存过 null」与「没缓存」—— 否则每次绑定都会重试一次注定失败的 IO。
     */
    private val cache = HashMap<String, Drawable?>(64)

    /**
     * 读配置。**任何线程**。
     *
     * 任何异常都吃掉：调用点可能在后台构建线程上，异常带走的是 SystemUI。
     */
    fun read(prefs: Prefs): Set {
        val raw = try {
            prefs.rawStringSet(Keys.pref(SideIndex.spec.key, PREF))
        } catch (t: Throwable) {
            Log.w(TAG, "读单项图标覆盖失败，这一轮不套用：" + t.javaClass.simpleName)
            return EMPTY
        }
        if (raw.isEmpty()) return EMPTY
        return Set(IconOverrideEntries.decode(raw), IconOverrideEntries.signature(raw))
    }

    /**
     * 把首次解码挪到后台线程。**任何线程可调**（它只排一个短命 daemon）。
     *
     * 为什么不排也不会错：这只影响「第一次滑到那个字母时要不要在主线程上解一次图」。
     * 排了之后，用户第一次看到那个格子时缓存已经就绪。
     *
     * 只在**签名变化**时排一次（同一个签名反复排等于每次配置变化都读一遍盘）。
     */
    fun prewarm(context: Context, set: Set) {
        if (set.size == 0 || set.signature == warmedSignature) return
        warmedSignature = set.signature
        val app = context.applicationContext ?: context
        val thread = Thread({
            try {
                // 直接把每个覆盖解析一遍：成功的进缓存，失败的也进（值 null），
                // 于是主线程那一次只会命中缓存、不会再碰磁盘。
                for (key in set.keys()) set.drawable(app, key)
                Log.i(TAG, "单项图标覆盖已预热：${set.size} 条（${set.signature.take(48)}）")
            } catch (t: Throwable) {
                // 后台线程上抛出去同样会带走进程（SystemUI），必须自己吃掉
                Log.w(TAG, "单项图标覆盖预热失败（面板图标照常，退回自带图标）", t)
            }
        }, "fp-sideindex-iconoverride")
        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY
        thread.start()
    }

    /** 已经排过预热的签名（见 [prewarm]）。 */
    @Volatile
    private var warmedSignature: String? = null

    /** 从模块 App 的图标目录读一张 PNG。**失败只记日志**，退回自带图标。 */
    private fun decodeFile(context: Context, name: String): Drawable? {
        if (name.isEmpty()) return null
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
            // 带上异常类型与信息：这一条通道跨进程、跨 uid，没有这一行就只能靠猜
            Log.w(
                TAG,
                "读自定义图标失败（$name，$uri）：" + t.javaClass.simpleName + "：" + t.message,
            )
            null
        }
    }

    /** 从图标包里取一个**指定名字**的 drawable（与整体图标包无关，见类注释的优先级）。 */
    private fun drawableFromPack(context: Context, value: String): Drawable? {
        val ref = IconOverrideEntries.packRef(value) ?: return null
        val pack = IconPackResolver.packFor(context, ref.first) ?: return null
        return pack.drawableByName(ref.second)
    }

    private fun clamp(b: android.graphics.Bitmap): android.graphics.Bitmap =
        if (b.width <= MAX_PX && b.height <= MAX_PX) b
        else android.graphics.Bitmap.createScaledBitmap(b, MAX_PX, MAX_PX, true)
}
