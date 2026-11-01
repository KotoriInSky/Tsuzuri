package io.github.kotoriinsky.tsuzuri.hook.feature.screenrecognition

import android.os.SystemClock
import java.lang.reflect.Method

/**
 * 按**方法签名特征**在宿主（超级小爱）里定位混淆类，替代写死混淆名。
 *
 * ## 为什么
 *
 * 小爱每次发版混淆名都会漂（`bs1.u→es1.u`、`sr1.b→vr1.b`、`zr1.d→cs1.d`），
 * 而且**旧名可能还存在但已是别的类**（新版 `sr1.b` 就是）—— 按名字找不会 class not found，
 * 只会静默挂错或找不到方法。方法名本身没混淆，所以按「方法名 + 参数类型」认类。
 *
 * ## 做法
 *
 *  1. 快速路径：先试已知名（当前名、旧名），**必须通过签名校验**才采用；
 *  2. 快速路径没命中再全量扫：从宿主 BaseDexClassLoader 的 `pathList.dexElements[].dexFile`
 *     枚举类名，只看形如 `abc1.d` 的混淆短名（单段包 + 短类名），`Class.forName(…, false)`
 *     不触发初始化，按签名过滤；
 *  3. 一次扫描同时为所有规格收候选，结果缓存（按 ClassLoader）；
 *  4. 候选恰好 1 个才采用，0 个或多于 1 个都返回失败原因，由调用方降级 + 记日志。
 *
 * 全部走反射，任何一步失败都只是「找不到」，不抛到调用方。
 */
internal object RecognitionClassLocator {

    /** 一项要定位的类：用 [matches] 判定某个类是不是它。 */
    class Spec(
        val label: String,
        /** 已知名（快速路径），按顺序尝试，仍需签名校验。 */
        val knownNames: List<String>,
        val matches: (Class<*>) -> Method?,
    )

    /** 定位结果：[method] 非 null 即成功；否则 [reason] 说明原因（含候选数）。 */
    class Result(val cls: Class<*>?, val method: Method?, val reason: String, val via: String)

    /** 混淆短名：单段小写包（≤5 字符，可带数字）+ 1–3 位小写类名。 */
    private val OBFUSCATED_NAME = Regex("^[a-z][a-z0-9]{0,4}\\.[a-z]{1,3}$")

    internal fun isCandidateName(name: String): Boolean = OBFUSCATED_NAME.matches(name)

    private val cache = HashMap<ClassLoader, Map<String, Result>>()

    /** 最近一次全量扫描的统计（诊断用）。 */
    @Volatile
    var lastScanSummary: String = "未扫描"
        private set

    @Synchronized
    fun locate(cl: ClassLoader, specs: List<Spec>): Map<String, Result> {
        cache[cl]?.let { cached -> if (specs.all { it.label in cached }) return cached }
        val out = LinkedHashMap<String, Result>()
        val pending = mutableListOf<Spec>()
        for (spec in specs) {
            val fast = spec.knownNames.firstNotNullOfOrNull { name ->
                val c = runCatching { Class.forName(name, false, cl) }.getOrNull() ?: return@firstNotNullOfOrNull null
                val m = runCatching { spec.matches(c) }.getOrNull() ?: return@firstNotNullOfOrNull null
                Result(c, m, "ok", "known:$name")
            }
            if (fast != null) out[spec.label] = fast else pending += spec
        }
        if (pending.isNotEmpty()) out.putAll(scan(cl, pending))
        cache[cl] = (cache[cl] ?: emptyMap()) + out
        return out
    }

    private fun scan(cl: ClassLoader, specs: List<Spec>): Map<String, Result> {
        val t0 = SystemClock.elapsedRealtime()
        val names = try {
            enumerateClassNames(cl)
        } catch (t: Throwable) {
            lastScanSummary = "枚举类名失败: ${t.javaClass.simpleName}: ${t.message}"
            return specs.associate { it.label to Result(null, null, "无法枚举宿主 dex（${t.javaClass.simpleName}）", "scan") }
        }
        val hits = specs.associate { it.label to mutableListOf<Pair<Class<*>, Method>>() }
        var examined = 0
        for (name in names) {
            if (!isCandidateName(name)) continue
            val c = try { Class.forName(name, false, cl) } catch (_: Throwable) { continue }
            examined++
            for (spec in specs) {
                val m = try { spec.matches(c) } catch (_: Throwable) { null } ?: continue
                hits.getValue(spec.label) += c to m
            }
        }
        val ms = SystemClock.elapsedRealtime() - t0
        lastScanSummary = "扫描 ${names.size} 个类名、反射 $examined 个候选，耗时 ${ms}ms"
        return specs.associate { spec ->
            val h = hits.getValue(spec.label)
            spec.label to when (h.size) {
                1 -> Result(h[0].first, h[0].second, "ok", "scan")
                0 -> Result(null, null, "按签名找到 0 个候选", "scan")
                else -> Result(null, null, "按签名找到 ${h.size} 个候选（${h.joinToString { it.first.name }}），不敢挑", "scan")
            }
        }
    }

    /** 反射 BaseDexClassLoader.pathList.dexElements[].dexFile.entries()。 */
    private fun enumerateClassNames(cl: ClassLoader): List<String> {
        val pathList = findField(cl.javaClass, "pathList").get(cl)
            ?: error("pathList 为空")
        val elements = findField(pathList.javaClass, "dexElements").get(pathList) as Array<*>
        val names = ArrayList<String>(1 shl 16)
        for (e in elements) {
            if (e == null) continue
            val dexFile = runCatching { findField(e.javaClass, "dexFile").get(e) }.getOrNull() ?: continue
            @Suppress("UNCHECKED_CAST")
            val en = dexFile.javaClass.getMethod("entries").invoke(dexFile) as java.util.Enumeration<String>
            while (en.hasMoreElements()) names += en.nextElement()
        }
        if (names.isEmpty()) error("dexElements 里没有类名")
        return names
    }

    private fun findField(start: Class<*>, name: String): java.lang.reflect.Field {
        var c: Class<*>? = start
        while (c != null) {
            runCatching { return c.getDeclaredField(name).apply { isAccessible = true } }
            c = c.superclass
        }
        throw NoSuchFieldException(name)
    }

    // ---- 三个目标的签名规格（8.2.65 反编译核实）----

    private const val TIP_BUBBLE = "com.xiaomi.voiceassistant.screenrecognition.model.TipBubble"

    private fun Class<*>.declared(name: String, check: (Array<Class<*>>) -> Boolean): Method? =
        declaredMethods.singleOrNull { it.name == name && check(it.parameterTypes) }

    /** EntityViewHolder：`initEntity(TipBubble, boolean)`。 */
    val VIEW_HOLDER = Spec("EntityViewHolder.initEntity", listOf("es1.u", "bs1.u")) { c ->
        c.declared("initEntity") { p -> p.size == 2 && p[0].name == TIP_BUBBLE && p[1] == java.lang.Boolean.TYPE }
    }

    /** 气泡点击分发：`clickBubble(TipBubble)`。旧名 sr1.b 在新版是别的类，签名校验会挡掉。 */
    val CLICK_DISPATCH = Spec("BubbleFactory.clickBubble", listOf("vr1.b", "sr1.b")) { c ->
        c.declared("clickBubble") { p -> p.size == 1 && p[0].name == TIP_BUBBLE }
    }

    /** 结果组装：`createHybImageStructureResultFrom(*, Bitmap)`。 */
    val STRUCTURE = Spec("StructureFactory.createHybImageStructureResultFrom", listOf("cs1.d", "zr1.d")) { c ->
        c.declared("createHybImageStructureResultFrom") { p ->
            p.size == 2 && p[1].name == "android.graphics.Bitmap"
        }
    }
}
