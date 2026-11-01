package io.github.kotoriinsky.tsuzuri.core

import io.github.kotoriinsky.tsuzuri.core.geometry.ScreenRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 侧滑索引的「调整预览」（`sideIndexPreview`）—— **设置界面画的就是这个**。
 *
 * 这一层要钉住的不是「某个数字好不好看」，而是三件事：
 *
 *  1. **与真机同源**：所有数字都照 `hook/feature/sideindex/` 里那几份几何逐行复刻
 *     （`AppIndex.barLayout`、`SideIndexOverlay` 的 Companion 常量 + `buildBar` /
 *     `buildPanel` / `panelLeft` / `followerTop` / `applyThumb` / `updateBubble` /
 *     `placeBubble`、`SideIndexHandle.attach`）。每一条断言后面都写了它对应真机的哪一行 ——
 *     改投影时如果真机那边动了，这里会当场红；
 *  2. **边界档**：屏幕/字母数/应用数退化时不许抛、不许出 NaN（预览是界面线程上的纯函数，
 *     NaN 画出来就是「预览莫名其妙空了」，而那是最难排查的一类现象）；
 *  3. **每一处「夹取」都要有断言**：`letter_scale` / `icon_size` / `icon_radius` /
 *     `panel_inset` / `handle_height` 五个量在真机侧都有 `coerceIn`，
 *     预览不许自己发明第二套区间。
 *
 * 本机（Xiaomi 17 Pro）的真实尺寸：1220 × 2656、520dpi → density 3.25。
 * `dp(v) = (v * 3.25).toInt()` —— **注意是截断**，与 hook 侧的 `dp()` 同一条
 * （`SideIndexOverlay` 的 `private fun dp(v: Int) = (v * density).toInt()`）。
 */
class SideIndexPreviewTest {

    private fun dp(v: Int): Float = (v * 3.25f).toInt().toFloat()
    private fun dp(v: Float): Float = (v * 3.25f).toInt().toFloat()

    /** 条上的槽位：`AppIndex.ALPHABET`（A–Z + `#`），不带「最近使用」的 `★`。 */
    private val alphabet: List<String> = (('A'..'Z').map { it.toString() }) + "#"

    private fun params(
        letters: List<String> = alphabet,
        slot: Int = 1,
        appCount: Int = 18,
        scene: SideIndexPreviewScene = SideIndexPreviewScene.PANEL,
        letterOffsetPercent: Float = 50f,
        letterScalePercent: Float = 100f,
        iconSizeDp: Float = 50f,
        iconRadiusDp: Float = 0f,
        panelInsetDp: Float = 32f,
        onRight: Boolean = true,
        showHandle: Boolean = false,
        handleWidthDp: Float = 20f,
        handleHeightPercent: Float = 45f,
        handleOffsetPercent: Float = 69f,
        panelTouchFraction: Float = 0.5f,
        screenW: Float = 1220f,
        screenH: Float = 2656f,
        density: Float = 3.25f,
        cellHeightDp: Float = 108f,
        hideAppNames: Boolean = false,
        bubbleAnimMs: Float = 150f,
        bubbleIconSlots: Boolean = false,
        columns: Int = 5,
        cellWidthDp: Float = 66f,
    ) = SideIndexPreviewParams(
        screenW = screenW,
        screenH = screenH,
        density = density,
        letters = letters,
        slot = slot,
        appCount = appCount,
        scene = scene,
        letterOffsetPercent = letterOffsetPercent,
        letterScalePercent = letterScalePercent,
        iconSizeDp = iconSizeDp,
        iconRadiusDp = iconRadiusDp,
        panelInsetDp = panelInsetDp,
        onRight = onRight,
        showHandle = showHandle,
        handleWidthDp = handleWidthDp,
        handleHeightPercent = handleHeightPercent,
        handleOffsetPercent = handleOffsetPercent,
        panelTouchFraction = panelTouchFraction,
        cellHeightDp = cellHeightDp,
        hideAppNames = hideAppNames,
        bubbleAnimMs = bubbleAnimMs,
        bubbleIconSlots = bubbleIconSlots,
        columns = columns,
        cellWidthDp = cellWidthDp,
    )

    // ── 字母条（对应 AppIndex.barLayout） ────────────────────────────────

    @Test
    fun aSingleLetterGroupKeepsTheNativeItemHeight() {
        // 一格 = `dp(16) + 1`（AppIndex.kt:536 `((BAR_ITEM_DP * safeScale * density).toInt() + 1)`）
        // 1 格时不会被 0.82 那条上限碰，所以就是 53；条高 = 1 格
        val p = sideIndexPreview(params(letters = listOf("A"), slot = 0, appCount = 3))
        assertEquals(53f, p.bar.itemHeight, 0f)
        assertEquals(53f, p.bar.height, 0f)
        assertEquals(1, p.bar.count)
        assertEquals(0, p.bar.slot)
        // 居中：`(screenH - total) * 0.5`（AppIndex.kt:546-547 span × offsetFraction）
        assertEquals(((2656 - 53) * 0.5f).toInt().toFloat(), p.bar.barTop, 0f)
    }

    @Test
    fun theBarIsCentredByDefaultAndMovesWithTheOffsetPercent() {
        // `side_index.letter_offset`：0 贴顶、50 居中（Flyme）、100 贴底。
        // 真机是 `span = (screenH - total).coerceAtLeast(0)`、`top = span * fraction`（AppIndex.kt:546-547）
        val expectedItem = dp(16) + 1
        val span = 2656 - expectedItem * alphabet.size
        assertEquals(612f, sideIndexPreview(params(letterOffsetPercent = 50f)).bar.barTop, 0f)
        assertEquals(0f, sideIndexPreview(params(letterOffsetPercent = 0f)).bar.barTop, 0f)
        assertEquals(span.toFloat(), sideIndexPreview(params(letterOffsetPercent = 100f)).bar.barTop, 0f)
        // 两端的夹取：越界的百分比不会把条推出去
        assertEquals(0f, sideIndexPreview(params(letterOffsetPercent = -20f)).bar.barTop, 0f)
        assertEquals(span.toFloat(), sideIndexPreview(params(letterOffsetPercent = 150f)).bar.barTop, 0f)
        // 字母多到触发缩小之后，span 跟着变大 —— 位置是在**缩小之后**算的
        val many = sideIndexPreview(params(letters = List(300) { "A" }, letterOffsetPercent = 50f))
        assertEquals(((2656 - 300 * 7) * 0.5f).toInt().toFloat(), many.bar.barTop, 0f)
    }

    @Test
    fun letterScaleIsClampedAndCapsTheFontSize() {
        // `side_index.letter_scale`：整条缩放，同时乘在格高与字号上，字号再按格高的 0.8 封顶
        // （AppIndex.kt:536 格高、549-553 字号 + BAR_TEXT_MAX_RATIO）
        val at100 = sideIndexPreview(params(letterScalePercent = 100f))
        assertEquals(dp(16) + 1, at100.bar.itemHeight)
        assertEquals(dp(12), at100.bar.letterTextPx)

        // 放大到 180%：格高 94，但 27 格 × 94 = 2538 > 0.82 屏高 → 被等比压到 80；
        // 字号 min(70, 80×0.8=64) = 64（0.8 那条上限在这里真的生效）
        val big = sideIndexPreview(params(letterScalePercent = 180f))
        assertEquals(80f, big.bar.itemHeight, 0f)
        assertEquals(64f, big.bar.letterTextPx, 0f)
        // 同一档、只有 1 个字母时不会被压：格高 94、字号 70
        val bigOne = sideIndexPreview(params(letters = listOf("A"), slot = 0, letterScalePercent = 180f))
        assertEquals(dp(16 * 1.8f) + 1, bigOne.bar.itemHeight)
        assertEquals(dp(12 * 1.8f), bigOne.bar.letterTextPx)

        // 缩小到 60%：32 格高 / 23 字号
        val small = sideIndexPreview(params(letterScalePercent = 60f))
        assertEquals(dp(16 * 0.6f) + 1, small.bar.itemHeight)
        assertEquals(dp(12 * 0.6f), small.bar.letterTextPx)

        // 越界一律夹到 [0.6, 1.8]（AppIndex.kt:568-569 MIN_BAR_SCALE / MAX_BAR_SCALE）
        assertEquals(big.bar.itemHeight, sideIndexPreview(params(letterScalePercent = 500f)).bar.itemHeight, 0f)
        assertEquals(small.bar.itemHeight, sideIndexPreview(params(letterScalePercent = 1f)).bar.itemHeight, 0f)
        // 非有限值退回 100%（不许产出 NaN 格高）
        assertEquals(at100.bar.itemHeight, sideIndexPreview(params(letterScalePercent = Float.NaN)).bar.itemHeight, 0f)
    }

    @Test
    fun manyLettersShrinkTheItemHeightProportionally() {
        // 27 格 × 17dp ≈ 459dp 一般触发不到；字母一多就必须等比缩小，
        // 否则条会顶出屏幕（AppIndex.kt:537-542 的 `BAR_MAX_HEIGHT_FRACTION = 0.82`）
        val p = sideIndexPreview(params(letters = List(300) { "A" }, slot = 0))
        val maxTotal = (2656 * 0.82f).toInt()
        assertEquals(53f, sideIndexPreview(params()).bar.itemHeight, 0f) // 27 格不触发
        assertEquals(7f, p.bar.itemHeight, 0f) // 2177 / 300
        assertTrue(p.bar.height <= maxTotal, "整条必须落在 0.82 屏高内：${p.bar.height} > $maxTotal")
        // 缩小之后字号跟着格高走（5 = 7 × 0.8 取整）
        assertEquals(5f, p.bar.letterTextPx, 0f)
        // 恰好不越界的那一档不该被缩：26 格 × 53 = 1378 ≤ 2177
        assertEquals(dp(16) + 1, sideIndexPreview(params(letters = List(26) { "A" }, slot = 0)).bar.itemHeight)
    }

    @Test
    fun theBarColumnAndTheHitTestMatchThePublishedLayout() {
        // 字母格宽 12dp、贴边留白 4dp（SideIndexOverlay.kt:1522-1524 与 buildBar:637-655）；
        // 命中列宽 28dp（AppIndex.kt:434 BAR_WIDTH_DP）、边距 0（AppIndex.kt:398 BAR_MARGIN_DP）
        val right = sideIndexPreview(params(onRight = true))
        assertEquals(91f, right.bar.barWidth, 0f)
        assertEquals(1129f, right.bar.barLeft, 0f)
        // 字母中心：条内 content 盒 = [barLeft, barLeft + 91 - 13] = 78 宽，39 宽的字母居中 → +19
        assertEquals(1148f, right.bar.letterLeft, 0f)
        assertEquals(1167.5f, right.bar.letterCenterX, 0f)
        val left = sideIndexPreview(params(onRight = false))
        assertEquals(0f, left.bar.barLeft, 0f)
        assertEquals(51.5f, left.bar.letterCenterX, 0f)

        // 命中：整列都在「条」里（28dp），纵向是选择量、两端夹紧（AppIndex.letterAt 的三行算式）
        assertTrue(right.bar.inBarColumn(1129f))
        assertTrue(right.bar.inBarColumn(1220f))
        assertTrue(!right.bar.inBarColumn(1128f))
        assertTrue(!left.bar.inBarColumn(92f))
        val item = right.bar.itemHeight
        assertEquals(0, right.bar.indexAt(right.bar.barTop))
        assertEquals(26, right.bar.indexAt(right.bar.barTop + item * 26 + 1f))
        assertEquals(0, right.bar.indexAt(right.bar.barTop - 1000f))
        assertEquals(26, right.bar.indexAt(right.bar.barTop + 99999f))
        // 每一格的中心都落在自己那一格上（预览画的字母位置与命中判定必须一致）
        for (i in alphabet.indices) {
            assertEquals(i, right.bar.indexAt(right.bar.letterCenterY(i)), "slot $i")
        }
    }

    @Test
    fun slotIsCoercedIntoTheLetterRange() {
        // 与 `BarLayout.letters.getOrNull(slot)` 同一条语义：越界不会崩，也不会画到条外
        assertEquals(26, sideIndexPreview(params(slot = 999)).bar.slot)
        assertEquals(0, sideIndexPreview(params(slot = -5)).bar.slot)
        // 空清单：没有槽位可选（-1），条是空的
        val empty = sideIndexPreview(params(letters = emptyList()))
        assertEquals(-1, empty.bar.slot)
        assertEquals(0, empty.bar.count)
        assertEquals(0f, empty.bar.height, 0f)
    }

    // ── 面板（对应 SideIndexOverlay 的 Companion 常量与 buildPanel） ───────

    @Test
    fun panelViewportRowsFollowTheRowCountBoundaries() {
        // 视口固定 1–3 行：`viewportRows = contentRows.coerceIn(1, PANEL_MAX_ROWS)`，
        // `contentRows = ceil(apps / 5)`（SideIndexOverlay.kt:717-721，照 Flyme showApps 的 i2）
        fun rows(n: Int) = sideIndexPreview(params(appCount = n)).panel.viewportRows
        assertEquals(1, rows(1))
        assertEquals(1, rows(5))
        assertEquals(2, rows(6))
        assertEquals(2, rows(10))
        assertEquals(3, rows(11))
        assertEquals(3, rows(15))
        assertEquals(3, rows(16))
        assertEquals(3, rows(18))
        // 内容行数照实报出来（细条显示与否、滚动距离都由它推）
        assertEquals(4, sideIndexPreview(params(appCount = 18)).panel.contentRows)
        assertEquals(351f, sideIndexPreview(params(appCount = 18)).panel.maxScrollPx, 0f)
        assertEquals(0f, sideIndexPreview(params(appCount = 15)).panel.maxScrollPx, 0f)
        // 格子 66×108dp、5 列（SideIndexOverlay.kt:1531-1533）
        val p = sideIndexPreview(params())
        assertEquals(214f, p.panel.cellW, 0f)
        assertEquals(351f, p.panel.cellH, 0f)
        assertEquals(5, p.panel.columns)
        assertEquals(1070f, p.panel.contentWidth, 0f)
    }

    @Test
    fun panelIsInsetFromTheEdgeOnTheGivenSide() {
        // 面板内容离屏幕边 = `panel_inset`，默认 32dp（= Flyme 的 mPadding；SideIndexOverlay.kt:1005-1012）
        // 贴右时面板整体左移，留下 32dp 的缝给字母条（28dp）+ 细条（4dp）
        assertEquals(46f, sideIndexPreview(params(onRight = true)).panel.left, 0f)
        assertEquals(104f, sideIndexPreview(params(onRight = false)).panel.left, 0f)
        // 边距小于字母条时面板仍让出字母条的位置，只平移不缩小（用户 2026-10-31）
        assertEquals(46f, sideIndexPreview(params(panelInsetDp = 0f, onRight = true)).panel.left, 0f)
        assertEquals(104f, sideIndexPreview(params(panelInsetDp = 0f, onRight = false)).panel.left, 0f)
        assertEquals(1070f, sideIndexPreview(params(panelInsetDp = 0f, onRight = true)).panel.contentWidth, 0f)
        // 越界夹取：120dp 时贴左会夹到「面板刚好占满剩余宽度」
        assertEquals(150f, sideIndexPreview(params(panelInsetDp = 120f, onRight = false)).panel.left, 0f)
        assertEquals(0f, sideIndexPreview(params(panelInsetDp = 120f, onRight = true)).panel.left, 0f)
    }

    @Test
    fun panelTopIsTheFingerMinusSeventyDpClampedAtBothEnds() {
        // 跟手指：`h = y - dp(70)`，两头夹（SideIndexOverlay.kt:1037-1041，照 Flyme touchYChange）
        // 注意 `dp(70)` 是 **int**（70 × 3.25 = 227.5 截断成 227）
        val mid = sideIndexPreview(params(panelTouchFraction = 0.5f))
        assertEquals(1101f, mid.panel.top, 0f)
        assertEquals(1328f, mid.fingerY, 0f)
        // 手指贴到屏幕最下方：面板顶被夹到「刚好放得下视口、不许出屏」
        val down = sideIndexPreview(params(panelTouchFraction = 1f))
        assertEquals(1603f, down.panel.top, 0f)
        assertEquals(2656f - 1053f, down.panel.top, 0f)
        // 手指贴到屏幕最上方：面板顶夹到 0（Flyme 的 `if (h < 0) h = 0`）
        assertEquals(0f, sideIndexPreview(params(panelTouchFraction = 0f)).panel.top, 0f)
        // 视口高 = 行数 × 格子高；内容高 = 内容行数 × 格子高
        assertEquals(1053f, mid.panel.viewport.height, 0f)
        assertEquals(1404f, mid.panel.contentRect.height, 0f)
        assertEquals(mid.panel.left, mid.panel.viewport.left, 0f)
        assertEquals(1070f, mid.panel.viewport.width, 0f)
    }

    @Test
    fun cellsAreLaidOutFivePerRowAndTheLastRowIsAlignedToTheBarSide() {
        // 格子：图标 50dp 居中、左右各 3dp 内边距、名字上间距 4dp（SideIndexOverlay.kt:941-986）
        // 最后一行不满时按 rowGravity 靠边：贴右 → END、贴左 → START（assemblePanel:867-871）
        val p = sideIndexPreview(params(onRight = true))
        assertEquals(18, p.panel.cells.size)
        assertEquals(0, p.panel.cells[0].row)
        assertEquals(2, p.panel.cells[2].column)
        assertEquals(ScreenRect(474f, 1101f, 688f, 1452f), p.panel.cells[2].cellRect)
        // 图标与名字整块在格子里**居中**（Flyme 的 cell xml `gravity = CENTER`）：
        // 内容块高 230px（图标 162 + 名字块 68）、格高 351 → 上侧余量 (351−230)/2 = 60
        assertEquals(230f, p.panel.contentH, 0f)
        assertEquals(ScreenRect(500f, 1161f, 662f, 1323f), p.panel.cells[2].iconRect)
        assertEquals(1336f, p.panel.cells[2].nameTop, 0f)
        // 第 4 行只有 3 格：贴右时靠右（列 2/3/4），贴左时靠左（列 0/1/2）
        assertEquals(2, p.panel.cells[15].column)
        assertEquals(4, p.panel.cells[17].column)
        assertEquals(474f, p.panel.cells[15].cellRect.left, 0f)
        assertEquals(902f, p.panel.cells[17].cellRect.left, 0f)
        val left = sideIndexPreview(params(onRight = false))
        assertEquals(0, left.panel.cells[15].column)
        assertEquals(2, left.panel.cells[17].column)
        assertEquals(104f, left.panel.cells[15].cellRect.left, 0f)
        // 视口之外的格子照实报位置，但标出来「这一行被裁掉了」
        assertTrue(!p.panel.cells[15].inViewport)
        assertTrue(p.panel.cells[14].inViewport)
    }

    @Test
    fun iconSizeRadiusAndInsetAreClampedToTheirRanges() {
        // 三个量的区间在 SideIndexLook（MIN/MAX_ICON_DP = 24–80、ICON_RADIUS = 0–18、
        // PANEL_INSET = 0–120dp），预览不许自己发明第二套
        assertEquals(162f, sideIndexPreview(params(iconSizeDp = 50f)).panel.iconSize, 0f)
        assertEquals(260f, sideIndexPreview(params(iconSizeDp = 200f)).panel.iconSize, 0f)
        assertEquals(78f, sideIndexPreview(params(iconSizeDp = 1f)).panel.iconSize, 0f)
        assertEquals(0f, sideIndexPreview(params(iconRadiusDp = 0f)).panel.iconRadius, 0f)
        assertEquals(58f, sideIndexPreview(params(iconRadiusDp = 99f)).panel.iconRadius, 0f)
        assertEquals(dp(3).toFloat(), sideIndexPreview(params()).panel.contentPadH, 0f)
        assertEquals(dp(4).toFloat(), sideIndexPreview(params()).panel.nameTopMargin, 0f)
        // 名字字号按 sp 带出（`COMPLEX_UNIT_SP`），px 由绘制层用本地 density 换算
        assertEquals(12f, sideIndexPreview(params()).panel.nameFontSp, 0f)
        // 图标越大，名字越往下 —— 这正是用户在预览里要看的变化
        val big = sideIndexPreview(params(iconSizeDp = 80f))
        assertTrue(big.panel.cells[0].nameTop > sideIndexPreview(params()).panel.cells[0].nameTop)
        // 非有限值退回出厂默认（50dp），不许产出 NaN 尺寸
        assertEquals(162f, sideIndexPreview(params(iconSizeDp = Float.NaN)).panel.iconSize, 0f)
    }

    // ── 手指位置、高亮、气泡、细条、把手 ────────────────────────────────

    @Test
    fun theHighlightedCellIsTheOneUnderTheFinger() {
        // `trackPointer` → `hitTest` 用的是格子矩形的 contains（SideIndexOverlay.kt:1366-1374）
        val onPanel = sideIndexPreview(params(scene = SideIndexPreviewScene.PANEL, panelTouchFraction = 0.5f))
        assertEquals(2, onPanel.panel.highlighted)
        assertEquals(581f, onPanel.fingerX, 0f)
        assertTrue(onPanel.panel.cells[2].cellRect.contains(onPanel.fingerX, onPanel.fingerY))
        // 手指在字母条上时**没有**格子高亮（`setHighlight(-1)`，SideIndexOverlay.kt:371）
        val onBar = sideIndexPreview(params(scene = SideIndexPreviewScene.BAR))
        assertEquals(-1, onBar.panel.highlighted)
        assertTrue(onBar.bar.inBar(onBar.fingerX, onBar.fingerY))
        // 手指在条上 = 该槽位的**中心**，面板顶跟着它（真机是同一个 y 喂给 followFinger）
        assertEquals(onBar.bar.letterCenterY(onBar.bar.slot), onBar.fingerY, 0f)
        // 真机原文是 `(y - dp(70)).toInt()`：**先减再截断**，所以小数部分是在减完之后丢掉的
        assertEquals((onBar.fingerY - dp(70)).toInt().toFloat(), onBar.panel.top, 0f)
        // 手指落在格子之间的缝里就没命中
        val gap = sideIndexPreview(params(panelTouchFraction = 0.5f, appCount = 3))
        assertTrue(gap.panel.highlighted >= 0)
    }

    @Test
    fun bubbleHugsThePanelCornerAndHidesWithoutRoom() {
        // 大字母：50dp 圆、32sp 字、距面板 4dp、距屏幕上沿 8dp、顶到屏幕边时整块隐藏
        // （SideIndexOverlay.kt:1564-1574 常量、updateBubble:1292-1294、placeBubble:1317、applyBubbleVisibility:1334）
        val p = sideIndexPreview(params())
        assertEquals(162f, p.bubble.size, 0f)
        // 字号按 **sp** 带出（真机是 `COMPLEX_UNIT_SP`）：系统字号放大时预览要跟着变
        assertEquals(32f, p.bubble.fontSp, 0f)
        // 贴右：气泡右边缘 = 面板右边缘 - 4dp（所以是 panelLeft + groupW - 50dp - 4dp）
        assertEquals(941f, p.bubble.rect().left, 0f)
        assertEquals(1103f, p.bubble.rect().right, 0f)
        assertEquals(913f, p.bubble.rect().top, 0f)
        assertEquals(1075f, p.bubble.rect().bottom, 0f)
        assertEquals("B", p.bubble.text) // slot 1
        assertTrue(p.bubble.visible)
        // 贴左：气泡左边缘 = 面板左边缘 + 4dp（左右对称的另一半）
        assertEquals(117f, sideIndexPreview(params(onRight = false)).bubble.rect().left, 0f)
        // 顶部空间不够（面板顶被夹到 0）→ 整块不显示，而不是压在图标上
        assertTrue(!sideIndexPreview(params(panelTouchFraction = 0f)).bubble.visible)
        assertEquals(0f, sideIndexPreview(params(panelTouchFraction = 0f)).panel.top, 0f)
        // 这个字母下一个应用都没有 → 没有气泡（`show = ph == NONE && apps.isNotEmpty()`）
        assertTrue(!sideIndexPreview(params(appCount = 0)).bubble.visible)
        assertTrue(!sideIndexPreview(params(scene = SideIndexPreviewScene.BAR, appCount = 0)).bubble.visible)
    }

    @Test
    fun thumbAppearsOnlyWhenTheContentOverflowsTheViewport() {
        // 细条 4dp × 184dp、上边距 18dp、贴在面板**外侧**那条缝里，只在内容超过视口时显示
        // （SideIndexOverlay.kt:1551-1555 常量、applyThumb:1081-1088）
        val p = sideIndexPreview(params())
        val thumb = p.panel.thumb
        assertNotNull(thumb)
        assertEquals(13f, thumb.width, 0f)
        assertEquals(598f, thumb.height, 0f)
        assertEquals(1103f, thumb.left, 0f) // panelLeft + groupW - 4dp
        assertEquals(1159f, thumb.top, 0f) // panelTop + 18dp
        // 贴左时细条贴面板左边
        assertEquals(104f, sideIndexPreview(params(onRight = false)).panel.thumb!!.left, 0f)
        // 15 个应用 = 3 行内容 = 正好填满视口 → 没有细条
        assertNull(sideIndexPreview(params(appCount = 15)).panel.thumb)
        assertNull(sideIndexPreview(params(appCount = 1)).panel.thumb)
        assertNull(sideIndexPreview(params(appCount = 0)).panel.thumb)
    }

    @Test
    fun theHandleMatchesTheTriggerGeometry() {
        // 把手高度 = 屏高 × ratio（夹到 10%–100%）、顶 = (屏高 - 高) × offset、宽 = trigger_width
        // （SideIndexHandle.kt:123-128 与 MIN/MAX_HEIGHT_RATIO:270-271）
        val p = sideIndexPreview(params(showHandle = true))
        val h = p.handle
        assertNotNull(h)
        assertEquals(65f, h.rect.width, 0f)
        assertEquals(1195f, h.rect.height, 0f)
        assertEquals(1008f, h.rect.top, 0f)
        assertEquals(1155f, h.rect.left, 0f) // 贴右：屏幕宽 - 宽度
        assertEquals(1220f, h.rect.right, 0f)
        // 贴左时贴屏幕左边
        assertEquals(0f, sideIndexPreview(params(showHandle = true, onRight = false)).handle!!.rect.left, 0f)
        // 高度比例夹到 [10%, 100%]：5% → 10%、150% → 100%（贴顶）
        assertEquals(265f, sideIndexPreview(params(showHandle = true, handleHeightPercent = 5f)).handle!!.rect.height, 0f)
        val full = sideIndexPreview(params(showHandle = true, handleHeightPercent = 150f)).handle!!
        assertEquals(2656f, full.rect.height, 0f)
        assertEquals(0f, full.rect.top, 0f)
        // 关掉就没有（不是「画成透明」——没有就是没有）
        assertNull(sideIndexPreview(params(showHandle = false)).handle)
        // 宽度照真机夹到 ≥1px（`(widthDp * density).toInt().coerceAtLeast(1)`）——
        // 0 也是「有一个 1px 的窗口」，不是「没有把手」
        assertEquals(1f, sideIndexPreview(params(showHandle = true, handleWidthDp = 0f)).handle!!.rect.width, 0f)
    }

    @Test
    fun theSceneOnlyMovesTheFingerAndTheHighlight() {
        // 两个场景看的是**同一份几何**：条、面板的左右位置与格子排布不许因为换场景而动，
        // 变的只有「手指在哪」以及由它推出来的高亮/气泡/面板顶
        val bar = sideIndexPreview(params(scene = SideIndexPreviewScene.BAR))
        val panel = sideIndexPreview(params(scene = SideIndexPreviewScene.PANEL, panelTouchFraction = 0.5f))
        assertEquals(bar.bar.itemHeight, panel.bar.itemHeight, 0f)
        assertEquals(bar.bar.barTop, panel.bar.barTop, 0f)
        assertEquals(bar.bar.slot, panel.bar.slot)
        assertEquals(bar.panel.left, panel.panel.left, 0f)
        assertEquals(bar.panel.viewport.width, panel.panel.viewport.width, 0f)
        assertEquals(bar.panel.cells[2].column, panel.panel.cells[2].column)
        assertEquals(bar.panel.cells[2].cellRect.left, panel.panel.cells[2].cellRect.left, 0f)
        assertEquals(bar.panel.cells[2].cellRect.width, panel.panel.cells[2].cellRect.width, 0f)
        assertEquals(bar.scene, SideIndexPreviewScene.BAR)
        assertEquals(panel.scene, SideIndexPreviewScene.PANEL)
        // 场景只影响「手指」这一件事：一个在条上（那个 y = 该槽位中心）、一个在面板里。
        // 面板顶跟着手指走，所以两个场景下面板顶**本来就不同** —— 那正是「跟手」的可见证据。
        assertEquals(bar.bar.letterCenterY(bar.bar.slot), bar.fingerY, 0f)
        assertEquals(1328f, panel.fingerY, 0f)
        assertTrue(bar.panel.top < panel.panel.top, "手指在条上更靠上，面板也该更靠上")
    }

    @Test
    fun emptyLettersProduceADegenerateButFinitePreview() {
        // 空清单（索引还没扫出来 / 一个可启动应用都没有）：条是空的、没有槽位、
        // 面板退化成 1 行占位高，**不许抛、不许 NaN**（那会让整个预览消失）
        for (scene in SideIndexPreviewScene.entries) {
            val p = sideIndexPreview(params(letters = emptyList(), scene = scene, appCount = 0))
            assertEquals(0, p.bar.count)
            assertEquals(-1, p.bar.slot)
            assertEquals("", p.bubble.text)
            assertTrue(!p.bubble.visible)
            assertEquals(1, p.panel.viewportRows)
            assertEquals(0, p.panel.cells.size)
            assertEquals(-1, p.panel.highlighted)
            assertNull(p.panel.thumb)
            assertEquals(351f, p.panel.viewport.height, 0f)
        }
    }

    @Test
    fun degenerateScreensNeverThrowAndNeverProduceNaN() {
        // 预览是界面线程上的纯函数：屏幕尺寸退化时不许抛，也不许产出 NaN
        // （NaN 画出来就是空白，用户只会说「预览不见了」，且没有任何提示）
        for ((w, h, d) in listOf(
            Triple(0f, 0f, 3.25f),
            Triple(-100f, -100f, 3.25f),
            Triple(1220f, 0f, 3.25f),
            Triple(1f, 1f, 3.25f),
            Triple(1220f, 2656f, 0f),
            Triple(1220f, 2656f, Float.NaN),
        )) {
            val p = sideIndexPreview(
                params(screenW = w, screenH = h, density = d, showHandle = true),
            )
            val tag = "${w}x$h @$d"
            assertTrue(p.bar.itemHeight.isFinite() && p.bar.letterTextPx.isFinite(), tag)
            assertTrue(p.bar.barTop >= 0f && p.fingerY.isFinite(), tag)
            assertTrue(p.panel.top >= 0f && p.panel.viewport.width.isFinite(), tag)
            assertTrue(p.panel.iconSize.isFinite() && p.panel.iconSize >= 0f, tag)
            for (c in p.panel.cells) {
                assertTrue(c.cellRect.left.isFinite() && c.cellRect.top.isFinite(), tag)
                assertTrue(c.iconRect.width >= 0f, tag)
            }
            assertTrue(p.bubble.rect().left.isFinite(), tag)
            p.handle?.let { assertTrue(it.rect.height.isFinite() && it.rect.height >= 0f, tag) }
        }
    }

    @Test
    fun describeIsReadableForLogsAndDebugging() {
        val text = sideIndexPreview(params()).toString()
        assertTrue(text.contains("1220x2656"), text)
        assertTrue(text.contains("slots=27"), text)
        assertTrue(text.contains("panel=5x3"), text)
        assertTrue(sideIndexPreview(params()).describe().contains("letter="), "描述要能进日志")
    }

    // ── 本批新增：#5 格高可调 / #4 无字模式 / #10 气泡两项 ─────────────────

    @Test
    fun cellHeightIsExposedAndClampedButTheDefaultIsUnchanged() {
        // 需求 #5。默认必须与改动前逐位一致：格子 66×108dp（SideIndexOverlay.kt:1531-1534
        // 的常量已搬进 SideIndexLook，算式见 SideIndexLook.cellHeightPx）
        val at108 = sideIndexPreview(params())
        assertEquals(351f, at108.panel.cellH, 0f)
        assertEquals(214f, at108.panel.cellW, 0f)
        // 调低 → 格子真的变矮（视口、内容高、滚动距离都跟着走）
        val low = sideIndexPreview(params(cellHeightDp = 88f))
        assertEquals(dp(88), low.panel.cellH)
        assertEquals(dp(88) * 3, low.panel.viewport.height, 0f)
        assertEquals(dp(88), low.panel.maxScrollPx, 0f) // 18 项 = 4 行，视口 3 行
        // 调高 → 也认
        assertEquals(dp(132), sideIndexPreview(params(cellHeightDp = 132f)).panel.cellH)
        // 越界夹到 [88,132]（SideIndexLook.MIN/MAX_CELL_HEIGHT_DP）
        assertEquals(dp(88), sideIndexPreview(params(cellHeightDp = 1f)).panel.cellH)
        assertEquals(dp(132), sideIndexPreview(params(cellHeightDp = 999f)).panel.cellH)
        // 非有限值退回默认 108dp（预览不许产出 NaN）
        assertEquals(dp(108), sideIndexPreview(params(cellHeightDp = Float.NaN)).panel.cellH)
    }

    @Test
    fun theCellNeverGetsShorterThanItsContentAndNeverTallerThanTheScreen() {
        // 两条联动夹取（需求 #5 的「风险」那一段）：
        //  下限 = 图标 + 名字块，**抬格子不缩图标**；上限 = 可用屏高 / 3。
        // 图标 80dp（最大值）+ 名字块 21dp = 101dp > 用户要的 88dp → 抬到 101
        val big = sideIndexPreview(params(cellHeightDp = 88f, iconSizeDp = 80f))
        assertEquals(dp(80 + 21), big.panel.cellH)
        assertEquals(dp(80), big.panel.iconSize) // 图标**没有**被缩
        // 屏高极小（1000px / 3.25 = 307dp 的屏）：上限 = 可用屏高 / 3 = 333px，
        // 比 132dp(429px) 还小 → 压到 333（否则视口 3 行就快占满整屏，面板无处可放）
        val small = sideIndexPreview(params(screenH = 1000f, cellHeightDp = 132f))
        assertEquals(333f, small.panel.cellH, 0f)
        // 这一屏上默认格高还有余量：108dp(351px) → 减名字块(68px) 只有无字模式才做，
        // 所以这里就是 351 > 333 → 被压到 333
        assertEquals(333f, sideIndexPreview(params(screenH = 1000f)).panel.cellH, 0f)
        assertTrue(small.panel.cellH <= 1000 / 3)
    }

    @Test
    fun noLabelModeRemovesTheNameBlockHeightAndCentresTheIcon() {
        // 需求 #4。三件事：名字块不占高、格子跟着矮、图标整格居中。
        // 默认（显示名字）：格子 108dp、图标贴格子顶
        val named = sideIndexPreview(params(iconSizeDp = 50f))
        assertTrue(named.panel.namesVisible)
        assertEquals(351f, named.panel.cellH, 0f)
        // ⚠️ 改动前这里断言「图标贴格子顶」——那是抄错的形态：Flyme 的 cell xml 是
        // `gravity = 0x11`（CENTER），内容块在格子里居中，而「上方那 60px 余量」正是
        // 高亮抬起不被裁的前提（缺陷「顶行高亮被裁」的修复，见 highlightLift 那一组用例）。
        assertEquals(named.panel.cells[0].cellRect.top + 60f, named.panel.cells[0].iconRect.top, 0f)
        // 无字：108 − 21 = 87dp；图标纵向居中 → (87 − 50) / 2 = 18.5dp 的整数除法
        val blind = sideIndexPreview(params(iconSizeDp = 50f, hideAppNames = true))
        assertTrue(!blind.panel.namesVisible)
        assertEquals(dp(87), blind.panel.cellH)
        assertEquals(
            (dp(87).toInt() - dp(50).toInt()) / 2,
            (blind.panel.cells[0].iconRect.top - blind.panel.cells[0].cellRect.top).toInt(),
        )
        // 名字块省掉之后，同样的项数占用更少的高度（这是「面板不再空一块」的证据）
        assertTrue(blind.panel.contentRect.height < named.panel.contentRect.height)
    }

    @Test
    fun theBubbleCarriesThePressAnimationAndTheIconSlotFlag() {
        // 需求 #10。预览不做动画（同「预览画终态」），但两个参数必须带出来 ——
        // 真机侧：纯 alpha 淡入、AccelerateDecelerate、默认 150ms（Niagara 的取证值）
        assertEquals(150f, sideIndexPreview(params()).bubble.pressFadeMs, 0f)
        assertEquals(0f, sideIndexPreview(params(bubbleAnimMs = 0f)).bubble.pressFadeMs, 0f)
        assertEquals(400f, sideIndexPreview(params(bubbleAnimMs = 9999f)).bubble.pressFadeMs, 0f)
        assertEquals(
            150f,
            sideIndexPreview(params(bubbleAnimMs = Float.NaN)).bubble.pressFadeMs,
            0f,
        )
        // 特殊槽位（★ = AppIndex.RECENT_SLOT）：开关开着且当前槽是 ★ 时才是图标槽
        val star = listOf("★") + alphabet
        assertTrue(
            sideIndexPreview(params(letters = star, slot = 0, bubbleIconSlots = true)).bubble.iconSlot,
        )
        assertTrue(
            !sideIndexPreview(params(letters = star, slot = 1, bubbleIconSlots = true)).bubble.iconSlot,
        )
        assertTrue(
            !sideIndexPreview(params(letters = star, slot = 0, bubbleIconSlots = false)).bubble.iconSlot,
        )
    }

    @Test
    fun bothSpecialSlotsUseAnIconInTheBubble() {
        // 收藏项与最近使用是**两个**特殊槽位，气泡里都画图标，
        // 共用同一个开关（`side_index.bubble_icon_slots`，需求 #10 的机制）。
        //
        // 这一步的坑很具体：预览侧原先只认 `★`。只认一个的表现是
        // 「收藏槽在预览里画字母、在真机上画图标」—— 预览骗人，而那正是
        // 「预览与它控制的参数同页」最不该出现的事。
        // 两个字符串必须与真机 `AppIndex` 的同名常量一致：★ = 最近、♥ = 收藏。
        val both = listOf("♥", "★") + alphabet
        assertTrue(sideIndexPreview(params(letters = both, slot = 0, bubbleIconSlots = true)).bubble.iconSlot)
        assertTrue(sideIndexPreview(params(letters = both, slot = 1, bubbleIconSlots = true)).bubble.iconSlot)
        // 普通字母槽不是图标槽（第 2 槽是 A）
        assertTrue(!sideIndexPreview(params(letters = both, slot = 2, bubbleIconSlots = true)).bubble.iconSlot)
        // 关掉开关 → 两个特殊槽都退回字母（关态 = 改动前）
        assertTrue(!sideIndexPreview(params(letters = both, slot = 0, bubbleIconSlots = false)).bubble.iconSlot)
        assertTrue(!sideIndexPreview(params(letters = both, slot = 1, bubbleIconSlots = false)).bubble.iconSlot)
        // 气泡带出来的字仍然是槽位本身：图标取不到时真机画的就是它（`bindBubbleContent` 的二选一）
        assertEquals("♥", sideIndexPreview(params(letters = both, slot = 0, bubbleIconSlots = true)).bubble.text)
        assertEquals("★", sideIndexPreview(params(letters = both, slot = 1, bubbleIconSlots = true)).bubble.text)
    }

    // ── 需求 #5c：列数与横向间距（格宽）的**联动夹取** ─────────────────────
    //
    // 这一组是 #5c 的核心。上一轮只敢暴露「格高」的理由写在 `SideIndexLook.cellHeightDp`
    // 的 KDoc 里：格宽 × 列数一旦超过可用屏宽，`panelLeft` 的 `coerceAtLeast(0)`
    // 会把面板顶到 0，**最右一列直接落到屏幕外**（看不见也点不到）。
    // 下面这几条把这套夹取的**结论**钉死，规则本身写在 `sideIndexPreview` 的
    // `panelMetrics`（与 hook 侧 `SideIndexLook.panelMetrics` 逐行对应）。

    @Test
    fun theDefaultColumnsAndCellWidthReproduceTheCurrentGeometryExactly() {
        // 默认值必须还是 Flyme 原值：5 列 × 66dp。这是「不动滑杆 = 与改动前逐位一致」的证据
        // （真机侧同一份算式见 SideIndexLook.panelMetrics 的 KDoc）。
        val p = sideIndexPreview(params())
        assertEquals(5, p.panel.columns)
        assertEquals(5, p.panel.requestedColumns)
        assertEquals(dp(66), p.panel.cellW)
        // 面板总宽 = 格宽 × 列数 = 1070（与改动前那一版逐位相同）；
        // 夹取的上界是**屏宽**（1220），不是「屏宽 − inset」—— 后者会让「面板边距」
        // 这个滑杆意外地改掉列数（理由写在 panelMetrics 的 KDoc 里）。
        assertEquals(dp(66) * 5, p.panel.contentWidth)
        assertEquals(1070f, p.panel.contentWidth)
        assertEquals(46f, p.panel.left)
    }

    @Test
    fun requestedColumnsIsClampedToTheSliderRange() {
        // 滑杆区间 3–7（SideIndexLook.MIN/MAX_COLUMNS）。越界一律夹回，
        // 非有限输入到不了这里（参数是 Int），但 0/负数必须夹到 3 而不是画出一格都没有的面板。
        assertEquals(5, sideIndexPreview(params(columns = 5)).panel.requestedColumns)
        assertEquals(3, sideIndexPreview(params(columns = 1)).panel.requestedColumns)
        assertEquals(7, sideIndexPreview(params(columns = 99)).panel.requestedColumns)
        assertEquals(3, sideIndexPreview(params(columns = -4)).panel.requestedColumns)
        // 要 7 列时**生效**列数是 6：1116 / (50dp 图标 + 2×3dp) = 6 ——
        // 「requested ≠ columns」正是「被夹过」，两个数都要报出来（describe 里有）
        assertEquals(6, sideIndexPreview(params(columns = 7)).panel.columns)
        assertEquals(3, sideIndexPreview(params(columns = 3)).panel.columns)
    }

    @Test
    fun theCellIsNeverNarrowerThanTheIcon() {
        // 下限 = 图标边长 + 左右各 3dp 内边距（`SideIndexOverlay.createCell` 的 setPadding）。
        // 用户把格宽拖到最小、图标拖到最大时，选择是**抬格宽**而不是缩图标 ——
        // 与本项目 cellHeightPx 的下限同一条取舍（缩图标会让「图标尺寸」滑杆在大档位上
        // 变成「拖了没反应」）。
        val narrow = sideIndexPreview(params(iconSizeDp = 50f, cellWidthDp = 44f))
        assertEquals(5, narrow.panel.columns)
        assertEquals(dp(50).toInt() + 2 * dp(3f).toInt(), narrow.panel.cellW.toInt())
        assertEquals(dp(50), narrow.panel.iconSize)
        // 图标 80dp：5 列 × (80 + 6)dp = 430dp 装不进 343dp 的可用宽 → **生效列数降到 4**。
        // ⚠️ 这是本批唯一一处「已有用户会看到形态变化」的地方（图标 > 60dp 时），
        // 理由是那个组合在改动前就是**图标互相重叠**的：66dp 的格子装 80dp 的图标，
        // 相邻两格各溢出 7dp。需求明确要求「不会有列窄到放不下图标（图标上限 80dp）」。
        val big = sideIndexPreview(params(iconSizeDp = 80f))
        assertEquals(5, big.panel.requestedColumns)
        assertEquals(4, big.panel.columns)
        assertEquals(dp(80).toInt() + 2 * dp(3f).toInt(), big.panel.cellW.toInt())
        assertTrue(big.panel.cellW >= big.panel.iconSize)
    }

    @Test
    fun theCellWidthIsSqueezedSoTheRowStillFits() {
        // 上限 = 屏宽 ÷ 生效列数：5 列 × 96dp = 480dp 装不进 375dp 的屏宽，
        // 于是被压到 1220 / 5 = 244px（75dp）—— 面板总宽压到屏宽之内。
        val wide = sideIndexPreview(params(cellWidthDp = 96f))
        assertEquals(5, wide.panel.columns)
        assertEquals(244, wide.panel.cellW.toInt())
        assertEquals(1220f, wide.panel.contentWidth)
        // 列数一改，格宽的上限跟着变（1220 / 6 = 203）：这就是「联动」两个字的含义
        val six = sideIndexPreview(params(columns = 6, cellWidthDp = 96f))
        assertEquals(6, six.panel.columns)
        assertEquals(203, six.panel.cellW.toInt())
        assertEquals(1218f, six.panel.contentWidth)
    }

    @Test
    fun theGridNeverRunsOffScreenForAnyColumnsAndCellWidthCombination() {
        // **需求 #5c 的核心保证**：任何列数 × 任何格宽/图标/屏宽/哪一侧的组合下，
        // 面板与每一格都落在屏幕内。扫一遍组合，而不是只挑几个整齐的值。
        var cases = 0
        for (w in listOf(320f, 720f, 1080f, 1220f, 1440f)) {
            val availW = w.toInt()
            for (columns in 3..7) {
                for (cellWidthDp in 40..100 step 4) {
                    for (iconDp in listOf(24f, 40f, 50f, 64f, 80f)) {
                        for (onRight in listOf(true, false)) {
                            val p = sideIndexPreview(
                                params(
                                    screenW = w,
                                    appCount = 21,
                                    columns = columns,
                                    cellWidthDp = cellWidthDp.toFloat(),
                                    iconSizeDp = iconDp,
                                    onRight = onRight,
                                ),
                            )
                            val tag = "w=$w cols=$columns/${p.panel.columns} cw=$cellWidthDp " +
                                "icon=$iconDp right=$onRight"
                            cases++
                            // 面板总宽在屏宽之内 → 贴右不会把面板顶到 0、贴左不会顶出屏幕
                            assertTrue(p.panel.contentWidth <= availW, "$tag 总宽=${p.panel.contentWidth}")
                            assertTrue(p.panel.left >= 0f, "$tag left=${p.panel.left}")
                            assertTrue(p.panel.viewport.right <= w, "$tag 右缘=${p.panel.viewport.right}")
                            for (c in p.panel.cells) {
                                assertTrue(
                                    c.cellRect.left >= 0f && c.cellRect.right <= w,
                                    "$tag 第${c.index}格=${c.cellRect}",
                                )
                                // 「列不会窄到放不下图标」—— 图标 24–80dp 先按真机区间夹过。
                                // 唯一的例外是「屏宽连一列图标都放不下」（见下一条用例），
                                // 那一档在这一组屏宽下到不了（要 320px 屏 + 80dp 图标才会）。
                                if (availW >= dp(iconDp).toInt() + 2 * dp(3f).toInt()) {
                                    assertTrue(c.cellRect.width >= p.panel.iconSize, "$tag 第${c.index}格")
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(cases >= 1000, "组合覆盖太少：$cases")
    }

    @Test
    fun narrowScreensShrinkTheGridInsteadOfPushingColumnsOffScreen() {
        // 唯一的让步：屏宽连「一列 + 图标 + 内边距」都放不下时，**不出屏优先于装得下图标**
        // （出屏的列用户根本碰不到，而溢出的图标只是难看 —— 真机 LinearLayout 居中一个
        //  过宽的 child 本来就会溢出，见 SideIndexOverlay.createCell 的注释）。
        // 200px 的屏 + 80dp 的图标：图标要 278px，屏只有 200px。
        val tiny = sideIndexPreview(params(screenW = 200f, iconSizeDp = 80f))
        assertEquals(5, tiny.panel.requestedColumns) // 用户没动过列数
        assertEquals(1, tiny.panel.columns)          // 只排得下 1 列
        assertEquals(200, tiny.panel.cellW.toInt())  // 格宽让位给屏宽（用户要的 214 装不下）
        assertTrue(tiny.panel.cellW < tiny.panel.iconSize, "这一档如实承认图标会溢出")
        assertTrue(tiny.panel.viewport.right <= 200f)

        // 320px 的屏：同样只有一列，但图标仍装得下 —— 此时下限生效，抬格宽而不缩图标
        val narrow = sideIndexPreview(params(screenW = 320f, iconSizeDp = 80f))
        assertEquals(1, narrow.panel.columns)
        assertEquals(dp(80).toInt() + 2 * dp(3f).toInt(), narrow.panel.cellW.toInt())
        assertTrue(narrow.panel.cellW >= narrow.panel.iconSize)

        // 退化屏宽：只要求「不抛、不 NaN、列数 ≥ 1、格宽 ≥ 1px」，不要求几何有意义
        for (w in listOf(0f, 1f, 100f, -50f)) {
            val p = sideIndexPreview(params(screenW = w, appCount = 6))
            assertTrue(p.panel.columns >= 1, "w=$w")
            assertTrue(p.panel.cellW >= 1f && p.panel.cellW.isFinite(), "w=$w")
            assertTrue(p.panel.requestedColumns >= 3, "w=$w")
        }
    }

    // ── 缺陷修复：顶行高亮被内容块所在格子的上沿/视口上沿裁掉 ──────────────
    //
    // 现象：选中的那一格在**顶上一行**时，抬起的那一截被 ScrollView 裁掉。
    // 根因：改动前抬的是**整个格子**，而面板顶 = 手指 y − 70dp、格高 ≥ 88dp ——
    //       正常使用时手指永远落在第一行，第一行的格子上沿正好是视口上沿。
    // 修法（与 Flyme 的 cell xml 对齐）：拾升作用在**格子里的内容块**上，而内容块在格子里
    //       居中 → 上方有 (cellH−contentH)/2 的余量，抬升量不超过它就不会越界，
    //       与第几行、滚到哪里无关。抬升量本身也从写死的 18dp 改成算出来的（格高/6 ⊓ 余量）。

    @Test
    fun theHighlightLiftIsComputedNotHardCoded18dp() {
        // 默认档（格高 108dp）：351 / 6 = 58px = dp(18) —— **逐位等于 Flyme 的 -18dp**
        val p = sideIndexPreview(params())
        assertEquals(dp(18), p.highlightLiftPx)
        assertEquals(dp(18), p.highlightLiftIdealPx)
        assertEquals(58f, p.panel.highlightLiftPx, 0f)
        // 格高变大 → 抬得更多（与 #5c 的「纵向间距可调」自洽）；格高变小 → 抬得更少，
        // 但仍 > 0（退化成 0 只在「格子刚好等于内容块高」那一档，见下一条用例）
        val tall = sideIndexPreview(params(cellHeightDp = 132f))
        val short = sideIndexPreview(params(cellHeightDp = 88f))
        assertTrue(tall.highlightLiftPx > p.highlightLiftPx, "格高变大应抬得更多")
        assertTrue(short.highlightLiftPx < p.highlightLiftPx, "格高变小应抬得更少")
        assertTrue(short.highlightLiftPx > 0f, "格高 88dp 仍抬得起来：${short.highlightLiftPx}")
        // 格高 88dp：余量 (286−230−3)/2 = 26 < 上限 47 → 被余量夹住（这正是「不越界」的来源）
        assertEquals(26f, short.highlightLiftPx, 0f)
        assertTrue(short.highlightLiftPx < short.highlightLiftIdealPx)
    }

    @Test
    fun theLiftFormulaIsClampedByTheSpaceInsideTheCell() {
        // 纯算式（px）：抬升 = min(格高/6, (格高 − 内容块高 − 误差预留)/2)
        assertEquals(18, sideIndexHighlightLiftPx(cellH = 108, contentH = 0, safetyPx = 0))
        assertEquals(16, sideIndexHighlightLiftPx(cellH = 100, contentH = 40, safetyPx = 0))
        assertEquals(5, sideIndexHighlightLiftPx(cellH = 100, contentH = 90, safetyPx = 0))
        // 格子刚好等于内容块高 → 没有任何余量可抬
        assertEquals(0, sideIndexHighlightLiftPx(cellH = 100, contentH = 100, safetyPx = 0))
        // 退化输入不许产负数
        assertEquals(0, sideIndexHighlightLiftPx(cellH = 0, contentH = 0, safetyPx = 0))
        assertEquals(0, sideIndexHighlightLiftPx(cellH = 10, contentH = 100, safetyPx = 0))
    }

    @Test
    fun theHighlightLiftNeverLeavesTheCellOnAnyRow() {
        // 第一行 / 中间行 / 最后一行：抬升量**不依赖行号**（约束在格子内部），
        // 而且抬起之后内容块的顶没有越过格子顶 —— 三种行位置都不越界，
        // 也就不会被 ScrollView（它裁的是视口）或格子裁掉。
        // 手指位置决定命中哪一行：面板顶 = 手指 y − 70dp 且被两头夹，
        // 所以只有「面板顶被夹到 maxTop」那一段（手指靠屏幕下方）才能落到第 1/2 行。
        var seenLift: Float? = null

        /** @param row 期望命中的行（手指位置决定：面板顶 = 手指 y − 70dp，两头夹） */
        fun checkRow(count: Int, fraction: Float, row: Int, label: String) {
            val p = sideIndexPreview(params(appCount = count, panelTouchFraction = fraction))
            val hit = p.panel.highlighted
            assertTrue(hit >= 0, "$label 应该有命中的格子")
            val cell = p.panel.cells[hit]
            assertEquals(row, cell.row, "$label 命中的行不对（手指位置没落在那一行）")
            val tag = "$label(row=${cell.row} cell=$hit)"
            // 抬升量不依赖行号：三种行位置拿到同一个值
            if (seenLift == null) seenLift = p.highlightLiftPx else assertEquals(seenLift, p.highlightLiftPx, tag)
            assertTrue(p.highlightLiftPx > 0f, "$tag 默认档应抬得起来")
            // ⚠️ 断言必须读**预览报出来的几何**（iconRect 是内容块的位置），不能在测试里把
            //    居中算式重算一遍 —— 那样写的话「贴顶」的实现也会算出合法值，测试是空转的
            //    （第一版就是这么写错的，是 RED 验证把它抓出来的：那版 4 条里这条不红）。
            // 抬起之后内容块的顶 = 报出来的图标顶 − 抬升量，必须仍在格顶之下（不越界）
            val iconTopAfterLift = cell.iconRect.top - p.highlightLiftPx
            assertTrue(
                iconTopAfterLift >= cell.cellRect.top,
                "$tag 抬起后内容块顶 $iconTopAfterLift 越过了格顶 ${cell.cellRect.top}",
            )
            // 内容块底部也仍在格子内（这一侧本来就有余量，一并钉住）
            assertTrue(cell.iconRect.bottom - p.highlightLiftPx <= cell.cellRect.bottom, tag)
        }

        checkRow(count = 18, fraction = 0.5f, row = 0, label = "第一行")  // 偏移 70dp < 一格高 → 第一行
        checkRow(count = 18, fraction = 0.75f, row = 1, label = "中间行") // 面板顶被夹到 maxTop → 第 1 行
        checkRow(count = 15, fraction = 0.95f, row = 2, label = "最后一行") // 15 项 3 行，落到末行
    }

    @Test
    fun whenTheCellExactlyFitsItsContentSelectionFallsBackToABackground() {
        // 图标 80dp + 格高 88dp（贴着下限、被 cellHeightPx 抬到 101dp = 内容块高）
        // → 格子里一点余量都没有 → 抬升量算成 0，真机与预览改用底色表达选中
        // （`SideIndexOverlay.CELL_SELECT_BG` / `SideIndexPreviewInk.CELL_SELECT_BG`）。
        val p = sideIndexPreview(params(iconSizeDp = 80f, cellHeightDp = 88f))
        assertEquals(dp(101), p.panel.cellH)
        assertEquals(dp(101), p.panel.contentH)
        assertEquals(0f, p.highlightLiftPx, 0f)
        assertTrue(p.highlightNoLiftFallback, "抬升量为 0 时必须走底色兜底")
        // 理想值仍然按格高算（它只是「想要抬多少」，实际被余量夹成 0）
        assertTrue(p.highlightLiftIdealPx > 0f)
        // 没有高亮格时不算兜底态（抬升量是 0 但没有「那一格」）
        val noHit = sideIndexPreview(params(scene = SideIndexPreviewScene.BAR, iconSizeDp = 80f, cellHeightDp = 88f))
        assertEquals(-1, noHit.panel.highlighted)
        assertEquals(0f, noHit.highlightLiftPx, 0f)
        assertTrue(!noHit.highlightNoLiftFallback)
    }

    @Test
    fun theRowCountFollowsTheEffectiveColumnCount() {
        // 需求 #5c 点名的联动：行数 = ceil(项数 / **生效**列数)，而它决定
        // 视口几行（1–3）、细条出不出来、滚动距离。用用户要的那个数会让
        // 「视口 / 细条 / 一屏几格」全部和真机排出来的格子错位。
        val six = sideIndexPreview(params(columns = 6, appCount = 18))
        assertEquals(6, six.panel.columns)
        assertEquals(3, six.panel.contentRows)
        assertEquals(3, six.panel.viewportRows)
        assertNull(six.panel.thumb) // 3 行内容正好填满 3 行视口
        assertEquals(0f, six.panel.maxScrollPx, 0f)
        assertEquals(1, six.panel.cells[6].row) // 第 7 格落到第 2 行
        assertEquals(0, six.panel.cells[6].column)
        assertEquals(5, six.panel.cells[5].column)

        val three = sideIndexPreview(params(columns = 3, appCount = 18))
        assertEquals(3, three.panel.columns)
        assertEquals(6, three.panel.contentRows)
        assertEquals(3, three.panel.viewportRows)
        assertNotNull(three.panel.thumb) // 6 行内容 > 3 行视口 → 细条出现
        assertEquals(3 * dp(108), three.panel.maxScrollPx, 0f)
        assertEquals(4, three.panel.cells[12].row)
        // 细条仍然贴在面板外侧那条缝里（列数变了，面板宽度变了，细条跟着走）
        assertEquals(three.panel.left + 3 * dp(66) - dp(4), three.panel.thumb!!.left, 0f)
    }

    // ── 起手热区（与 hook 判定同一份算式，见 SideIndexHotZone） ────────────────

    @Test
    fun hotZonesAreTheWholeEdgeBandNotTheHandle() {
        val p = sideIndexPreview(params(showHandle = true, handleHeightPercent = 20f))
        val zone = p.hotZones.single()
        assertEquals(1220f - 20f * 3.25f, zone.left, 0.01f)
        assertEquals(120f * 3.25f, zone.top, 0.01f)
        assertEquals(2656f - 56f * 3.25f, zone.bottom, 0.01f)
        // 把手只有屏高的 20%，热区远比它高
        assertTrue(zone.height > p.handle!!.rect.height * 3f)
    }

    @Test
    fun hotZonesFollowSide() {
        assertEquals(0f, sideIndexPreview(params(onRight = false)).hotZones.single().left, 0f)
        val both = sideIndexPreview(params(onRight = true).let {
            SideIndexPreviewParams(
                screenW = it.screenW, screenH = it.screenH, density = it.density, letters = it.letters,
                slot = it.slot, appCount = it.appCount, scene = it.scene,
                letterOffsetPercent = it.letterOffsetPercent, letterScalePercent = it.letterScalePercent,
                iconSizeDp = it.iconSizeDp, iconRadiusDp = it.iconRadiusDp, panelInsetDp = it.panelInsetDp,
                onRight = it.onRight, showHandle = it.showHandle, handleWidthDp = it.handleWidthDp,
                handleHeightPercent = it.handleHeightPercent, handleOffsetPercent = it.handleOffsetPercent,
                bothSides = true,
            )
        })
        assertEquals(2, both.hotZones.size)
    }
}
