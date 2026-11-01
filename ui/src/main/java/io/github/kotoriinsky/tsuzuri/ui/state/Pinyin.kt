package io.github.kotoriinsky.tsuzuri.ui.state

import android.icu.text.Transliterator
import android.util.Log
import io.github.kotoriinsky.tsuzuri.core.LetterIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 显示名 → 拼音首字母（设置界面这一侧）。
 *
 * ## 取法与 hook 侧逐行对齐
 *
 * 分组用 [firstLetter]（A–Z，数字、符号、其它一律归 `#`），组内排序用 [sortKey]
 * （照 Flyme `SlideAppsSorter.AppComparator` 的逐字符映射）。这两件事 hook 侧也有一份，
 * 在 `sideindex/AppIndex.kt` 的「拼音首字母」一节里。
 *
 * **两边必须给出同一个字母**：设置页里点 `C` 跳到的那一组，在屏索引条上也得是 `C` 那一格，
 * 否则同一个应用在界面里归 `A`、在索引条上归 `B`，而这种不一致既没有日志也不会崩，
 * 只能靠人肉比对才发现。所以这里是**照抄**，不是各写一套。
 *
 * 与那一侧不能共用代码的原因写在 [LetterIndex] 的类注释里：跑在不同进程，且契约层
 * 不允许出现 `android.*`（它要能在纯 JVM 上单测）。
 *
 * ## 线程
 *
 * ICU 的 `Transliterator` **不是线程安全的**（内部有游标状态），所以整块逻辑用一把锁圈起来。
 * 而且**只允许在 IO 线程调**（一个名字里每个汉字一次转换，一百多个应用就是几百次）——
 * 唯一的入口是 [PinyinIndex.build]，它自己切到 [Dispatchers.IO]。
 * 界面里**不要**在 composition 里调这个对象。
 */
internal object Pinyin {

    private const val TAG = "FP-AppPicker"

    /**
     * ICU 转换器 ID 的降级链，**直接从 Appindex 抄的**（同 hook 侧那一串）：
     * `Han-Latin` 做汉字→拼音，`/Names` 用姓名读音规则处理多音字的常见歧义，
     * `Latin-ASCII` 把带声调的拉丁字母折成 ASCII（`Ǎ` → `A`）。
     *
     * 后两级是降级：某些 ROM 的 ICU 数据被裁剪过，不一定有 `/Names` 变体。
     * 全拿不到时所有汉字归 `#`（和 Appindex 在表缺失时的行为一致）。
     */
    private val TRANSLITERATOR_IDS = listOf(
        "Han-Latin/Names; Latin-ASCII",
        "Han-Latin; Latin-ASCII",
        "Han-Latin",
    )

    /**
     * Flyme `SlideAppsSorter.AppComparator.ORDERED_SHARP`：不是字母的字符统一映射成 `'N'`，
     * 这样「1号店」这类名字在组内也有确定的次序（不会因为字符集不同而忽前忽后）。
     */
    private const val ORDERED_SHARP = 'N'

    /** 一把锁圈住转换器**和**两个缓存：转换器本身有状态，缓存也在锁下改。 */
    private val lock = Any()

    /**
     * 缓存的转换器。构建一次要解析上百条规则，是毫秒级开销，
     * 绝不能每个应用名建一个（Appindex 也是 `once`）。
     */
    @Volatile
    private var hanToLatin: Transliterator? = null

    /** 一次性诊断标记：平台一个可用的汉字转换器都没有时只提示一次。 */
    @Volatile
    private var warnedNoTransliterator = false

    /**
     * 单个 code point → 首字母。
     *
     * 为什么值得缓存：[sortKey] 要**逐字**求首字母，而汉字在应用名里的复用率极高
     * （「微信」「微信输入法」「微信读书」共用一个「微」），缓存之后 ICU 的实际调用次数
     * 只和**不同字的个数**有关（几百），与清单长度无关。
     */
    private val byCodePoint = HashMap<Int, String>(256)

    /**
     * 取首字母。逐行对应 Appindex 的 `PinyinUtils.getFirstLetter`。
     *
     * **不打包 `pinyin_table.bin` 兜底**（Appindex 最后还有那一层）：那张表只覆盖
     * U+4E00–U+9FA5，且在 Appindex 自己那边也是可选资产（`PinyinUtils.init` 是
     * try/catch 的失败路径）。ICU 拿不到就归 `#`，正是 Appindex 在表缺失时的实际行为。
     */
    internal fun firstLetter(name: String): String {
        if (name.isEmpty()) return LetterIndex.OTHER
        val first = name[0]
        if (first in 'A'..'Z' || first in 'a'..'z') return first.uppercaseChar().toString()
        // 数字与其余 ASCII（标点、空格）归 `#`，这一步不碰 ICU
        if (first.isDigit() || first.code < 128) return LetterIndex.OTHER
        // 只按第一个 **code point** 判：emoji 开头的名字（代理对）走 ICU 也是 `#`，
        // 不会把代理对拆坏（`name[0]` 是半个代理对）
        return letterOfCodePoint(name.codePointAt(0))
    }

    /**
     * 组内排序键。逐行对应 Flyme `SlideAppsSorter.AppComparator.getLetterUpperCase`：
     * 空格跳过、`A-Z`/`a-z` 取大写、其它 ASCII（数字、标点）映射成 `'N'`、
     * 非 ASCII 取拼音首字母（拿不到就 `'N'`）。
     *
     * 于是「爱奇艺」的键是 `AQY`、「Amazon」是 `AMAZON` —— 逐字符比，A 组里
     * `'M' < 'Q'`，Amazon 在前。这和「把整个名字转成一串拼音再比」的结果**不一样**
     * （整串比是 `AIQIYI` vs `AMAZON`，`'I' < 'M'`），所以必须逐字符映射。
     *
     * `ch.code` 是 UTF-16 码元而不是 code point —— 这一处**故意与 Appindex 保持一致**
     * （那边也是 `for (ch in label)` 之后传 `ch.code`）：非 BMP 字符（emoji）走
     * ICU 只会拿到 `#`，两边同样地把它当 `'N'`，次序才不会分岔。
     */
    internal fun sortKey(name: String): String {
        val sb = StringBuilder(name.length + 4)
        for (ch in name) {
            if (ch == ' ') continue
            if (ch in 'A'..'Z' || ch in 'a'..'z') {
                sb.append(ch.uppercaseChar())
                continue
            }
            if (ch.code < 128) {
                sb.append(ORDERED_SHARP)
                continue
            }
            val letter = letterOfCodePoint(ch.code)
            sb.append(if (letter.length == 1 && letter != LetterIndex.OTHER) letter[0] else ORDERED_SHARP)
        }
        return sb.toString()
    }

    private fun letterOfCodePoint(codePoint: Int): String = synchronized(lock) {
        byCodePoint[codePoint]?.let { return it }
        val letter = transliterate(codePoint)
        byCodePoint[codePoint] = letter
        letter
    }

    /** 只允许在 [lock] 里调（转换器有游标状态）。 */
    private fun transliterate(codePoint: Int): String {
        val t = transliterator() ?: return LetterIndex.OTHER
        return try {
            val source = String(Character.toChars(codePoint))
            // 转换结果可能带声调、也可能是多段（`zhāng`），所以只挑第一个拉丁字母
            for (c in t.transliterate(source)) {
                if (c in 'A'..'Z' || c in 'a'..'z') return c.uppercaseChar().toString()
            }
            LetterIndex.OTHER
        } catch (_: Throwable) {
            LetterIndex.OTHER
        }
    }

    private fun transliterator(): Transliterator? {
        hanToLatin?.let { return it }
        synchronized(lock) {
            hanToLatin?.let { return it }
            for (id in TRANSLITERATOR_IDS) {
                val t = try {
                    Transliterator.getInstance(id)
                } catch (_: Throwable) {
                    null
                }
                if (t != null) {
                    hanToLatin = t
                    return t
                }
            }
            // 一个都没有：所有汉字都会归 `#`。这在真机上必须**看得见**，
            // 否则只会表现为「中文应用全挤在 # 里」，很容易被当成「拼音就是没做」
            if (!warnedNoTransliterator) {
                warnedNoTransliterator = true
                Log.w(
                    TAG,
                    "平台没有可用的汉字转拼音转换器（已试 $TRANSLITERATOR_IDS），汉字将归入 #",
                )
            }
            return null
        }
    }
}

/**
 * 一份清单的「拼音序 + 首字母表」。
 *
 * 为什么要把排序和首字母**一起**算好再交给界面：
 *  1. 两者都依赖 ICU（见 [Pinyin] 的线程说明），界面里算就是主线程上几百次转换；
 *  2. 界面拿到的是**同一个快照**，分组与排序不可能对不上 —— 分开算就可能出现
 *     「这一项按排序键在 A 组，但首字母表说它在 B 组」这种自相矛盾的清单。
 *
 * 不可变，构建后随便跨线程读。
 */
internal class PinyinIndex private constructor(
    /** 已按拼音排好的清单。 */
    val apps: List<InstalledApp>,
    private val letters: Map<String, String>,
) {

    /**
     * 显示名 → 索引条上的槽位（`A`–`Z` 或 `#`）。
     *
     * 查不到时返回 `#` 而不是抛异常：一个列表项没有槽位，就会在界面上**消失**
     * （分组是唯一的数据源），那是比放错组严重得多的问题。
     */
    fun letterOf(label: String): String = letters[label] ?: LetterIndex.OTHER

    companion object {

        /**
         * 排序 + 取首字母。内部切到 [Dispatchers.IO]，所以调用方在协程里写
         * `val index = PinyinIndex.build(apps)` 就够了，不要在 composition 里调。
         *
         * 排序是**拼音序**（[Pinyin.sortKey]），不是名称序：名称序（`CASE_INSENSITIVE`）下
         * 同一个字母分组里会混进别的字母开头的应用（「爱奇艺」会夹在 `A` 组的名称序里），
         * 索引条跳过去的落点看起来就是随机的。
         *
         * 注意这里**不改** [loadAppCatalog] 的排序：那一份是「清单的默认次序」，
         * 别的入口（快捷方式导入等）也用得到，拼音序只是这一页的展示需要。
         */
        suspend fun build(apps: List<InstalledApp>): PinyinIndex = withContext(Dispatchers.IO) {
            peek(apps)?.let { return@withContext it }
            if (apps.isEmpty()) return@withContext PinyinIndex(emptyList(), emptyMap())

            // 首字母按**显示名**去重后算一次：同名必然同组，重复算没有意义
            val letters = HashMap<String, String>(apps.size * 2)
            for (app in apps) {
                if (letters.containsKey(app.label)) continue
                letters[app.label] = Pinyin.firstLetter(app.label)
            }

            // 排序键先算好再排（比较器里现算会为每次比较走一遍查表）
            val keyed = ArrayList<Pair<InstalledApp, String>>(apps.size)
            for (app in apps) keyed.add(app to Pinyin.sortKey(app.label))
            // 稳定排序：键相同的项保持传进来的次序（那是按名称排的），
            // 于是「同一个拼音键」内部也是确定的，不会每次打开都换一个样子
            keyed.sortWith(compareBy { it.second })

            PinyinIndex(keyed.map { it.first }, letters).also { last = apps to it }
        }

        /** 最近一份索引：应用清单有进程内缓存，同一份清单不用每次进页都重算（用户 2026-10-31：加载卡）。 */
        @Volatile
        private var last: Pair<List<InstalledApp>, PinyinIndex>? = null

        /** 已算好就立刻给，页面第一帧就能画分组列表。 */
        fun peek(apps: List<InstalledApp>): PinyinIndex? = last?.takeIf { it.first === apps }?.second
    }
}
