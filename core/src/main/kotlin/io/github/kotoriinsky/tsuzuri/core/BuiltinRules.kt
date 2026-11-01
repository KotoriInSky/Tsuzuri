package io.github.kotoriinsky.tsuzuri.core

/**
 * APK 内置的口令规则：表格部分（平台、域名、能用模板表达的口令规则）从
 * `passcode_rules.json` 读，与订阅规则同一格式、同一校验器。特例（QQ 音乐歌曲、闲鱼短链、
 * 百度网盘 scheme、`contentCore`、`categoryFor`）仍在 [PasscodeRules] 的代码里。
 *
 * 读不到或整份解析失败时 [loaded] 为 false、各表为空：识别退化成「只有通用网页」，但不会让宿主进程
 * （system_server / SystemUI / 复制直达）在类初始化时崩掉。安装日志里带 `rules=` 计数，排障时看它。
 * 单测钉住：文件随包、整份通过 CI 级校验、样例全过（BuiltinRulesTest）。
 */
object BuiltinRules {

    private const val RESOURCE = "/io/github/kotoriinsky/tsuzuri/core/passcode_rules.json"

    /** 校验结果（LOAD 模式：有错的单条已被跳过）；读不到时为 null。 */
    val checked: RuleSetCheck.Checked? = load()

    val loaded: Boolean get() = checked != null

    /** 安装日志用的一行摘要：`platforms/hosts/passcodes`，读不到时 `missing`。 */
    val summary: String
        get() = checked?.let { "${it.platforms.size}/${it.hosts.size}/${it.passcodes.size}" } ?: "missing"

    private fun load(): RuleSetCheck.Checked? = try {
        val text = BuiltinRules::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
        text?.let(RuleSet::parse)?.doc?.let { RuleSetCheck.check(it, RuleSetCheck.Mode.LOAD) }
    } catch (_: Throwable) {
        null
    }
}
