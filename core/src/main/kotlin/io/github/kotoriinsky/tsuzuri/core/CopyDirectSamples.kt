package io.github.kotoriinsky.tsuzuri.core

/**
 * 复制直达「试一下」的样例池（用户 2026-10-21：随机放几个测试用例，当彩蛋）。
 *
 * 2026-10-23 换成用户挑的 10 条（各平台一条，覆盖中外、图文 / 视频 / 音乐），并且**内容随包**：
 * 标题、第二行、封面都提前抓好放在 `samples/`（`samples.json` + 224px 方图），岛直接用，不联网 ——
 * 用户原话「直接保存内容，避免网络问题」（海外站在没代理的手机上本来就抓不到）。
 * 短链样例另存了跳转后的干净地址（[Sample.finalUrl]），点「打开」时直接用，不带分享者参数。
 *
 * `CopyDirectSamplesTest` 断言：每一条都被规则引擎认出、每一条都能按识别出的目标地址查回自己的内容。
 */
object CopyDirectSamples {

    /** 一条随包样例。[cover] 是 `samples/` 下的文件名，没有封面的平台显示 App 图标。 */
    class Sample(
        val key: String,
        val text: String,
        val title: String,
        val subtitle: String?,
        val cover: String?,
        val finalUrl: String?,
    )

    private const val DIR = "/io/github/kotoriinsky/tsuzuri/core/samples/"

    val SAMPLES: List<Sample> = runCatching {
        val raw = CopyDirectSamples::class.java.getResourceAsStream(DIR + "samples.json")
            ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@runCatching emptyList()
        (MiniJson.parse(raw) as List<*>).map { e ->
            val m = e as Map<*, *>
            Sample(
                m["key"] as String, m["text"] as String, m["title"] as String,
                m["subtitle"] as? String, m["cover"] as? String, m["final"] as? String,
            )
        }
    }.getOrDefault(emptyList())

    val ALL: List<String> = SAMPLES.map { it.text }

    /** 识别出的目标地址 → 随包内容。按规则引擎算出的目标对照，和识别侧走的是同一条路。 */
    private val byTarget: Map<String, Sample> by lazy {
        SAMPLES.mapNotNull { s -> PasscodeRules.match(s.text)?.target?.let { it to s } }.toMap()
    }

    fun preset(target: String): Sample? = byTarget[target]

    /** 封面原始字节（JPEG）；没有封面或读不到返回 null。 */
    fun coverBytes(sample: Sample): ByteArray? = sample.cover?.let { name ->
        CopyDirectSamples::class.java.getResourceAsStream(DIR + name)?.use { it.readBytes() }
    }

    /** 「识别所有链接」关着时也能弹的那几条（有平台规则的）。 */
    val PLATFORM_ONLY: List<String> by lazy {
        val strict = PasscodeRules.Options(genericUrl = false)
        ALL.filter { PasscodeRules.match(it, strict) != null }
    }

    /**
     * 随机抽一条，**不与上一条相同**：识别侧 60 秒内同一内容去重（`PasscodeIsland.isDuplicate`），
     * 连抽到同一条就会「点了没反应」。[genericUrl] 为 false 时只从 [PLATFORM_ONLY] 里抽 ——
     * 抽到 GitHub 这种普通链接不会弹，用户会以为功能坏了。
     */
    fun pick(
        previous: String?,
        random: kotlin.random.Random = kotlin.random.Random.Default,
        genericUrl: Boolean = true,
    ): String {
        val base = if (genericUrl) ALL else PLATFORM_ONLY.ifEmpty { ALL }
        val pool = base.filter { it != previous }.ifEmpty { base }
        return pool[random.nextInt(pool.size)]
    }
}
