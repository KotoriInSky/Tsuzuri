package io.github.kotoriinsky.tsuzuri.core

/**
 * 快递岛方图位的**承运商徽标**（2026-10-22 用户：「快递平台能不能提供专门重绘的图标」）。
 *
 * 以前快递岛的图标是「负责打开的 App」—— 圆通、中通、国际件都走菜鸟小程序，岛上显示的是**支付宝**，
 * 看不出是哪家快递。现在方图放承运商徽标，右上角小标仍是负责打开的 App（与网页的站点图标同一布局）。
 *
 * **自绘，不照描官方 logo**：每家一个**原创几何图形**（[Glyph]）+ 一组印象色，运行时用 Canvas 画，
 * 不带任何图片 / 字体资源。第一版只是「色块 + 一个字」，用户 2026-10-22 反馈「放个字上去也不行」，
 * 改成图形为主：图形描述的是「这家快递在名字或业务上的意象」（顺 = 顺势的箭羽、圆 = 环线、
 * 德邦 = 大件货箱……），不是任何承运商的标志形状。每个图形与官方标志的区别见
 * `docs/artwork/express-badges/README.md`。颜色只求好认，不声称是官方标准色。
 *
 * 图形用 24×24 网格描述（与 Material 图标同一网格），几何数据放在 core 里，
 * hook 侧的 `ExpressBadgePainter` 和浏览器预览 `preview.html` 读的是**同一份**数据
 * （单测核对预览里嵌的 JSON 与这里逐字一致），预览与真机不会各画各的。
 *
 * 键是 [PasscodeIsland.carrierName] 的返回值；认不出承运商（null，常见于纯数字单号）给通用 [GENERIC]。
 */
object ExpressBadge {

    /**
     * @param text 承运商简称（一个字或短字母）。新版绘制**不画字**，保留给无障碍描述与旧调用方。
     * @param background 底色（纯色，扁平风格不做渐变）；[foreground] 图形主色；[accent] 图形点缀色。
     */
    data class Badge(
        val text: String,
        val background: String,
        val foreground: String = "#FFFFFF",
        val accent: String = foreground,
        val glyph: Glyph = Glyph.TAG,
    )

    // ── 图形描述 ─────────────────────────────────────────────────────────

    /** 图形部件用什么颜色画：主色 / 点缀色 / 镂空（擦掉**之前**画过的部件，露出底色渐变）。 */
    enum class Role { FG, ACCENT, CUT }

    /** 部件的几何形状，坐标都在 24×24 网格里。 */
    sealed interface Shape {
        /** SVG path 语法的子集：只用绝对坐标的 M / L / H / V / C / Q / Z（见 [parsePath]）。 */
        data class Path(val d: String) : Shape
        data class Circle(val cx: Float, val cy: Float, val r: Float) : Shape
        data class Rect(val x: Float, val y: Float, val w: Float, val h: Float, val rx: Float = 0f) : Shape
    }

    /**
     * 一个部件。
     *  - [fill] = true：填充；同时 [stroke] > 0 时再描一圈同色圆角边（= 圆角化的填充，FILL_AND_STROKE）。
     *  - [fill] = false：只描边，宽 [stroke]，圆头圆角。
     *  - [dot] > 0：描边改成圆点虚线，点距 [dot]（只对描边有效）。
     *  - [alpha]：整个部件的不透明度（填充与描边作为一个整体合成，不会在交界处叠深）。
     */
    data class Mark(
        val shape: Shape,
        val role: Role = Role.FG,
        val fill: Boolean = true,
        val stroke: Float = 0f,
        val alpha: Float = 1f,
        val dot: Float = 0f,
    )

    private fun fill(shape: Shape, role: Role = Role.FG, alpha: Float = 1f, round: Float = 0f) =
        Mark(shape, role, fill = true, stroke = round, alpha = alpha)

    private fun line(shape: Shape, width: Float, role: Role = Role.FG, alpha: Float = 1f, dot: Float = 0f) =
        Mark(shape, role, fill = false, stroke = width, alpha = alpha, dot = dot)

    private fun p(d: String) = Shape.Path(d)
    private fun c(cx: Float, cy: Float, r: Float) = Shape.Circle(cx, cy, r)
    private fun r(x: Float, y: Float, w: Float, h: Float, rx: Float = 0f) = Shape.Rect(x, y, w, h, rx)

    /** 统一线宽（24 网格单位）：主线 [LINE]、细节 [THIN]、填充块的圆角化描边 [ROUND]、圆点航迹点距 [DOT]。 */
    private const val LINE = 2.2f
    private const val THIN = 1.4f
    private const val ROUND = 1.2f
    private const val DOT = 2.4f

    /**
     * 图形库（扁平风格：纯色、无渐变、无投影；线宽只有 [LINE] / [THIN] 两档）。
     * 全部是原创的通用意象（箭羽、环线、闪电、货箱、库房、信封、邮票、信箱、纸箱、地球、
     * 航线、传送带、云、星芒、行李牌），**没有一个是照着任何承运商的标志画的**。
     */
    enum class Glyph(val marks: List<Mark>) {
        /** 三道递进的箭羽「›››」：顺势而行。 */
        CHEVRONS(listOf(
            line(p("M4.5 6 L9.5 12 L4.5 18"), LINE, alpha = 0.35f),
            line(p("M9.5 6 L14.5 12 L9.5 18"), LINE, alpha = 0.65f),
            line(p("M14.5 6 L19.5 12 L14.5 18"), LINE, Role.ACCENT),
        )),

        /** 环线：一圈淡轨道 + 四分之三圈实线 + 线头一颗点，像包裹绕城一周回到手上。 */
        ORBIT(listOf(
            line(c(12f, 12f, 7.5f), LINE, alpha = 0.3f),
            line(p("M12 4.5 C16.142 4.5 19.5 7.858 19.5 12 C19.5 16.142 16.142 19.5 12 19.5 C7.858 19.5 4.5 16.142 4.5 12"), LINE),
            fill(c(4.5f, 12f, 2.6f), Role.ACCENT),
            fill(c(12f, 12f, 2f)),
        )),

        /** 折线闪电 + 两颗火花：「极」速。（山峰 + 太阳的方案像「图片」占位图标，弃用） */
        BOLT(listOf(
            fill(p("M13.6 2.8 L5.6 13.4 L11.4 13.4 L10.2 21.2 L18.4 10 L12.6 10 Z"), round = ROUND),
            fill(c(18.6f, 17.6f, 1.7f), Role.ACCENT),
            fill(c(5.6f, 6.4f, 1.1f), Role.ACCENT),
        )),

        /** 等轴测货箱：顶面点缀色、两侧面两档明度，面与面之间留缝，表示大件 / 重货。 */
        CUBE(listOf(
            fill(p("M12 3.75 L19.5 8 L12 12.25 L4.5 8 Z"), Role.ACCENT, round = ROUND),
            fill(p("M4.5 8.9 L11.4 12.8 L11.4 20.6 L4.5 16.7 Z"), round = ROUND),
            fill(p("M19.5 8.9 L19.5 16.7 L12.6 20.6 L12.6 12.8 Z"), alpha = 0.65f, round = ROUND),
        )),

        /** 宽檐库房 + 卷帘门：仓配一体。 */
        WAREHOUSE(listOf(
            fill(p("M2.8 10.6 L12 5 L21.2 10.6 L21.2 19.6 L2.8 19.6 Z"), round = ROUND),
            fill(r(6.8f, 12f, 10.4f, 9f, 0.8f), Role.CUT),
            line(p("M8.6 14.2 L15.4 14.2"), THIN, Role.ACCENT),
            line(p("M8.6 16.6 L15.4 16.6"), THIN, Role.ACCENT),
            line(p("M8.6 19 L15.4 19"), THIN, Role.ACCENT),
        )),

        /** 信封 + 封口一枚圆形火漆：邮政。 */
        ENVELOPE(listOf(
            fill(r(3f, 6f, 18f, 13f, 2.4f)),
            line(p("M4.8 7.8 L12 13 L19.2 7.8"), THIN, Role.CUT),
            fill(c(12f, 13.4f, 2.2f), Role.ACCENT),
        )),

        /**
         * 竖版齿孔邮票：密齿孔 + 内框 + 一枚圆形邮戳与一道波浪戳线。
         * 齿孔要密而小 —— 方形 + 每边三个大孔的第一版在小尺寸下像「设置」齿轮；
         * 实心圆 + 横线的第二版像「头像」，都弃用。
         */
        STAMP(listOf(
            fill(r(5.5f, 4f, 13f, 16f, 0.6f)),
            *listOf(5.75f, 8.25f, 10.75f, 13.25f, 15.75f, 18.25f).flatMap { y ->
                listOf(fill(c(5.5f, y, 0.85f), Role.CUT), fill(c(18.5f, y, 0.85f), Role.CUT))
            }.toTypedArray(),
            *listOf(8.25f, 10.75f, 13.25f, 15.75f).flatMap { x ->
                listOf(fill(c(x, 4f, 0.85f), Role.CUT), fill(c(x, 20f, 0.85f), Role.CUT))
            }.toTypedArray(),
            line(c(12f, 10.6f, 2.5f), THIN, Role.ACCENT),
            line(p("M8.8 15 C10 14.1 11 15.9 12.2 15 C13.4 14.1 14.4 15.9 15.4 15"), THIN, Role.ACCENT),
        )),

        /** 拱顶信箱 + 竖起的小旗：有信到了。 */
        MAILBOX(listOf(
            fill(p("M4 20 L4 12 C4 8.686 6.686 6 10 6 L14 6 C17.314 6 20 8.686 20 12 L20 20 Z"), round = ROUND),
            fill(r(7f, 11.3f, 7f, THIN, 0.7f), Role.CUT),
            fill(r(15.6f, 3f, 1.8f, 11f, 0.9f), Role.CUT),
            fill(r(16f, 3.4f, 1f, 10f, 0.5f), Role.ACCENT),
            fill(r(16f, 3.4f, 5f, 3.4f, 0.8f), Role.ACCENT),
        )),

        /** 正面纸箱：箱盖 + 箱身 + 一条竖向封箱带。 */
        PARCEL(listOf(
            fill(r(3f, 4.5f, 18f, 5f, 1.6f)),
            fill(r(4.5f, 10.7f, 15f, 9.3f, 1.6f), alpha = 0.8f),
            fill(r(10.3f, 4.5f, 3.4f, 15.5f, 0.6f), Role.ACCENT),
            fill(r(6.5f, 15.2f, 2.6f, 2.6f, 0.5f), Role.CUT),
        )),

        /** 经纬地球：外圈 + 一条经线 + 赤道，右上一颗卫星点。 */
        GLOBE(listOf(
            line(c(12f, 12.5f, 7.6f), LINE),
            line(p("M12 4.9 C14.1 4.9 15.7 8.3 15.7 12.5 C15.7 16.7 14.1 20.1 12 20.1 C9.9 20.1 8.3 16.7 8.3 12.5 C8.3 8.3 9.9 4.9 12 4.9 Z"), THIN),
            line(p("M4.4 12.5 L19.6 12.5"), THIN),
            fill(c(19.6f, 4.6f, 1.9f), Role.ACCENT),
        )),

        /** 航线：起点圆环 → 圆点虚线 → 终点定位点。 */
        ROUTE(listOf(
            line(c(6f, 18f, 2.4f), LINE),
            line(p("M8.3 15.9 C12.5 12.5 7.5 8.5 13.6 7"), LINE, dot = DOT),
            fill(c(17.8f, 6.2f, 3.3f), Role.ACCENT),
            fill(c(17.8f, 6.2f, 1.2f), Role.CUT),
        )),

        /** 传送带上的纸箱：分拣中心。 */
        CONVEYOR(listOf(
            fill(r(7f, 4f, 10f, 9.2f, 1.4f)),
            line(p("M12 4 L12 7.8"), THIN, Role.CUT),
            line(r(3f, 15.2f, 18f, 4.6f, 2.3f), THIN, Role.ACCENT),
            fill(c(6.6f, 17.5f, 0.95f), Role.ACCENT),
            fill(c(12f, 17.5f, 0.95f), Role.ACCENT),
            fill(c(17.4f, 17.5f, 0.95f), Role.ACCENT),
        )),

        /**
         * 一朵云 + 云下一道航迹弧线（圆点航迹太密，像下雨）：「云途」。由三个圆和一条圆角底座拼成，不是任何品牌的云形。
         * （纸飞机的方案放在蓝底上会让人想到某即时通讯 App，弃用）
         */
        CLOUD(listOf(
            fill(c(8.8f, 12.6f, 3.6f)),
            fill(c(13.4f, 10.4f, 4.6f)),
            fill(c(17.2f, 13.4f, 3.0f)),
            fill(r(5.6f, 12.4f, 14.6f, 4.8f, 2.4f)),
            line(p("M5.2 20.4 C9 18.9 13 21.1 18.8 19.4"), THIN, Role.ACCENT),
        )),

        /** 四芒星芒 + 淡圆环：四面八方，用于「国际件」。 */
        COMPASS(listOf(
            line(c(12f, 12f, 8.4f), THIN, alpha = 0.35f),
            fill(p("M12 3 L14.1 9.9 L21 12 L14.1 14.1 L12 21 L9.9 14.1 L3 12 L9.9 9.9 Z"), round = ROUND),
            fill(p("M12 3 L14.1 9.9 L12 12 L9.9 9.9 Z"), Role.ACCENT, round = ROUND),
            fill(c(12f, 12f, 1.5f), Role.CUT),
        )),

        /** 行李牌：认不出承运商时的通用徽标。 */
        TAG(listOf(
            fill(p("M11 3.5 L19 3.5 C19.828 3.5 20.5 4.172 20.5 5 L20.5 13 L12 21.5 L2.5 12 Z"), round = ROUND),
            fill(c(16.2f, 7.8f, 1.8f), Role.CUT),
            line(p("M8 12.5 L11.5 16"), THIN, Role.CUT),
        )),
    }

    /**
     * 外框参数（占方图边长的比例）。Painter 与预览都只认这里的数，改一处两边一起变。
     *
     * 2026-10-22 用户定风格为**扁平**（参照图标包「补全计划 One」）：纯色圆角底、扁平几何图形，
     * **不要渐变 / 投影 / 高光 / 内描边**。所以这里只剩三个数：
     *  - 圆角 = 边长 × [CORNER]，与岛上的封面图一致；
     *  - 图形占中间 [GLYPH_SPAN] 见方（24 网格等比放大），四周留白相等，整套一致；
     *  - [DOT_ON]：圆点虚线里「点」的实长（接近 0，靠圆头线帽画成圆点）。
     * 深色底在近黑的岛上靠**底色本身**拉开明度（表里最暗的底也不低于 #3A3A3C 一档），不加描边。
     */
    object Style {
        const val GRID = 24f
        const val CORNER = 0.25f
        const val GLYPH_SPAN = 0.58f
        const val DOT_ON = 0.01f
    }

    // ── 承运商表 ─────────────────────────────────────────────────────────

    private val POST = Badge("邮", "#00703C", accent = "#FFC92E", glyph = Glyph.ENVELOPE)

    internal val BY_CARRIER: Map<String, Badge> = mapOf(
        "顺丰速运" to Badge("顺", "#3A3A3C", glyph = Glyph.CHEVRONS),
        "圆通速递" to Badge("圆", "#6A2C91", accent = "#FFC94D", glyph = Glyph.ORBIT),
        "极兔速递" to Badge("极", "#E60012", accent = "#FFE08A", glyph = Glyph.BOLT),
        "德邦快递" to Badge("德", "#0B4EA2", accent = "#FFC20E", glyph = Glyph.CUBE),
        "京东物流" to Badge("京", "#E1251B", glyph = Glyph.WAREHOUSE),
        "中国邮政" to POST,
        "香港邮政" to POST,
        "中华邮政" to POST,
        "澳门邮政" to POST,
        "日本邮便" to Badge("〒", "#D7102A", accent = "#D7102A", glyph = Glyph.STAMP),
        "美国邮政" to Badge("USPS", "#333366", accent = "#FF4D5E", glyph = Glyph.MAILBOX),
        "UPS" to Badge("UPS", "#5C3A26", "#F4E9DC", accent = "#FFB500", glyph = Glyph.PARCEL),
        "DHL" to Badge("DHL", "#FFCC00", "#D40511", glyph = Glyph.GLOBE),
        "FedEx" to Badge("FedEx", "#4D148C", accent = "#FF6200", glyph = Glyph.ROUTE),
        "亚马逊物流" to Badge("亚", "#33425A", accent = "#FF9900", glyph = Glyph.CONVEYOR),
        "云途物流" to Badge("云", "#1E5CB3", accent = "#8FD8FF", glyph = Glyph.CLOUD),
        "国际件" to Badge("国际", "#0F7F86", accent = "#FFE08A", glyph = Glyph.COMPASS),
    )

    val GENERIC = Badge("快", "#5B7CFF", glyph = Glyph.TAG)

    fun forCarrier(name: String?): Badge = name?.let(BY_CARRIER::get) ?: GENERIC

    // ── 真 logo：承运商官方 App 的图标（2026-10-22 用户：「不侵权的同时用 logo」）──
    //
    // 装了官方 App 时，方图位用它的图标：由系统 PackageManager 当场取出，仓库里不放任何 logo 文件，
    // 只用来标明「这是哪家快递」。官网 favicon 实测大多 16~64px（顺丰 / 圆通 / 京东 64、德邦 / UPS 32、
    // 申通 / 韵达 / 云途 16），放大到方图位会糊，不用。

    /** 设备上验过的官方 App 包名（2026-10-06 / 2026-10-22，`pm list packages` 与深链取证）。 */
    val KNOWN_APPS: Map<String, List<String>> = mapOf(
        "顺丰速运" to listOf("com.sf.activity"),
        "京东物流" to listOf("com.jd.jdlogistic"),
    )

    /**
     * 没有已知包名时，按 App 名称认官方 App：等于承运商名，或「简称 + 快递 / 速递 / 速运 / 物流」。
     * 只认这几种完整名字，不做包含匹配 —— 「顺丰同城」「京东」这类不是快递查件 App。
     * 外文承运商（UPS / DHL / FedEx）认原名，以及「原名 + 空格」开头的名字（`DHL Express`）。
     */
    fun appLabelMatches(carrier: String, label: String): Boolean {
        val l = label.trim()
        if (l.equals(carrier, ignoreCase = true)) return true
        if (carrier.first().code < 0x80) return l.startsWith("$carrier ", ignoreCase = true)
        val short = carrier.take(2)
        return l in setOf("${short}快递", "${short}速递", "${short}速运", "${short}物流")
    }

    /** 所有徽标（单测用：颜色格式、字数）。 */
    val all: Collection<Badge> get() = BY_CARRIER.values + GENERIC

    // ── path 解析（core 里做，单测能覆盖；hook 侧只负责把命令搬进 android.graphics.Path）──

    sealed interface Cmd {
        data class Move(val x: Float, val y: Float) : Cmd
        data class Line(val x: Float, val y: Float) : Cmd
        data class Quad(val x1: Float, val y1: Float, val x: Float, val y: Float) : Cmd
        data class Cubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : Cmd
        data object Close : Cmd
    }

    /**
     * 解析 [Shape.Path.d]：只支持绝对坐标的 M / L / H / V / C / Q / Z（H / V 转成 Line）。
     * 不认识的命令、参数个数不对都直接抛 [IllegalArgumentException] —— 图形是写死的常量，
     * 写错应当在单测里炸出来，而不是在岛上悄悄画歪。
     */
    fun parsePath(d: String): List<Cmd> {
        val tokens = Regex("""[MLHVCQZ]|-?\d*\.?\d+""").findAll(d).map { it.value }.toList()
        val rest = Regex("""[MLHVCQZ]|-?\d*\.?\d+|[\s,]+""").replace(d, "")
        require(rest.isEmpty()) { "path 含不支持的内容：「$rest」 in $d" }
        val out = ArrayList<Cmd>()
        var i = 0
        var cx = 0f
        var cy = 0f
        fun num(): Float {
            require(i < tokens.size && tokens[i].first() !in "MLHVCQZ") { "参数不足：$d" }
            return tokens[i++].toFloat()
        }
        while (i < tokens.size) {
            val op = tokens[i++]
            when (op) {
                "M" -> { cx = num(); cy = num(); out += Cmd.Move(cx, cy) }
                "L" -> { cx = num(); cy = num(); out += Cmd.Line(cx, cy) }
                "H" -> { cx = num(); out += Cmd.Line(cx, cy) }
                "V" -> { cy = num(); out += Cmd.Line(cx, cy) }
                "Q" -> { val a = num(); val b = num(); cx = num(); cy = num(); out += Cmd.Quad(a, b, cx, cy) }
                "C" -> {
                    val a = num(); val b = num(); val e = num(); val f = num(); cx = num(); cy = num()
                    out += Cmd.Cubic(a, b, e, f, cx, cy)
                }
                "Z" -> out += Cmd.Close
                else -> throw IllegalArgumentException("不支持的命令 $op（隐式重复参数也不支持）：$d")
            }
        }
        require(out.firstOrNull() is Cmd.Move) { "path 必须以 M 开头：$d" }
        return out
    }
}
