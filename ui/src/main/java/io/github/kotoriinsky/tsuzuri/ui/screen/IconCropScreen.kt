package io.github.kotoriinsky.tsuzuri.ui.screen

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import io.github.kotoriinsky.tsuzuri.ui.nav.PageBackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.kotoriinsky.tsuzuri.ui.fpString
import io.github.kotoriinsky.tsuzuri.ui.kit.FpEmphasis
import io.github.kotoriinsky.tsuzuri.ui.kit.FpSpace
import io.github.kotoriinsky.tsuzuri.ui.kit.LocalFpKit
import io.github.kotoriinsky.tsuzuri.ui.state.IconOverridesStore
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 「选图片 → 裁剪 → 保存」那一步。
 *
 * ## 为什么是「一页」而不是弹窗 / 浮层
 *
 * 裁剪要一块**够大的方形视口**才能看清主体（图标成品只有 192px，但裁歪一格就废了）。
 * 弹窗在手机上是窄条，拖动与缩放都会变得难用；把清单页整块换掉则不需要任何浮层或
 * 导航改动 —— 而且退出路径只有两个按钮（取消 / 使用这张），不存在「半途滑出去」这种
 * 让人怀疑「到底写没写盘」的中间态。
 *
 * ## 交互只有两件事：拖动、缩放
 *
 *  - **拖动**：手指往哪边划，图片就往哪边走（裁剪框相对图片是反的，这是所有人对裁剪的预期）；
 *  - **缩放**：滑杆。不用双指捏合是因为这一版要的是「够用」而不是「顺手」，
 *    而且滑杆顺带解决了另一个问题 —— 双指手势对读屏用户不可用（§9.0 可访问性）。
 *
 * 裁剪框**永远是正方形**（图标就是方的），所以只需要「框多大」这一个自由度：它由缩放决定，
 * 不需要用户再调一次宽高比。
 *
 * ## WYSIWYG
 *
 * 画的是**裁剪结果本身**（`drawImage` 把裁剪区直接缩放画满视口），不是「盖一层半透明遮罩」——
 * 后者要求用户在脑子里把遮罩换算成结果，换算错了要等写下盘才发现。
 * 这里画的位图与 [IconOverridesStore.importImage] 解码用的是同一套采样规则
 *（[IconOverridesStore.CROP_WORK_MIN_SIDE]），所以看到的像素就是会被裁的像素。
 *
 * ## 写盘发生在**这一页里**（本次修改的重点）
 *
 * 上一版是「这一页只交回一个 [IconOverridesStore.CropFraction]，写盘交给调用方」，
 * 于是确认之后这一页立刻消失、写盘在别处跑 —— 用户永远看不到写盘本身成没成，
 * 而当时那条写盘路径（框架共享目录）又是静默失败的：**现象就是「裁完像没发生一样」**。
 *
 * 现在 `onConfirm` 里直接调 [save]（由调用方提供，它负责写文件 + 写配置），并且：
 *
 *  - **保存中**：两个按钮都禁用（确认按钮连点两次会写两遍），并显示「正在保存…」；
 *  - **保存失败**：**留在这一页**，把失败的那一句显示出来（[failText] 按环节分开），
 *    用户可以直接再点一次「使用这张」重试，或者取消 —— 裁剪的位置不会丢；
 *  - **保存成功**：才回调 [onSaved] 让调用方回收这一页。
 *
 * 这样「失败」在界面上是**一个状态**，而不是一次什么都没发生的点击。
 *
 * ## 取消 = 不写盘
 *
 * 取消路径**不碰**任何配置也不写任何文件（[save] 根本没被调用）。
 * 唯一的例外是保存已经成功、但调用方还没来得及回收这一页的那一瞬间 —— 那时候
 * 图已经写进去了，走 [onSaved] 正常返回，不存在需要回滚的中间态。
 */
@Composable
fun IconCropScreen(
    uri: Uri,
    padding: PaddingValues,
    onCancel: () -> Unit,
    /** 写盘成功后回调：调用方用它回收这一页（配置已经写好、图标文件已经在目录里）。 */
    onSaved: () -> Unit,
    /**
     * 真正写盘（写文件 + 写那条配置）。**必须返回结果** ——
     * 这一页的失败提示就是它给的，抛异常在这里会被兜成一次「未知失败」。
     */
    save: suspend (IconOverridesStore.CropFraction) -> IconOverridesStore.SaveResult,
) {
    val kit = LocalFpKit.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /** 采样解码后的原图（比例与源图一致）。`null` = 还没读出来。 */
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }

    /** 视口 px ÷ 源 px。`0` = 视口还没量到，此时一切几何都不可用（也不去算，避免除零）。 */
    var scale by rememberSaveable(uri) { mutableFloatStateOf(0f) }

    /** [scale] 的下限 = 「刚好铺满短边」（能看到的最大范围）。放大倍率 = `scale / minScale`。 */
    var minScale by remember(uri) { mutableFloatStateOf(1f) }

    /** 裁剪框左上角在**源图坐标**里的位置。 */
    var originX by rememberSaveable(uri) { mutableFloatStateOf(0f) }
    var originY by rememberSaveable(uri) { mutableFloatStateOf(0f) }

    /** 视口边长（px）。**量出来的**（`onSizeChanged`）—— 裁剪框的一切都由它推出来。 */
    var viewportPx by remember(uri) { mutableFloatStateOf(0f) }

    /** 正在写盘（这段时间两个按钮都禁用，避免连点写两遍）。 */
    var saving by remember(uri) { mutableStateOf(false) }
    PageBackHandler { if (!saving) onCancel() }

    /** 上一次写盘为什么失败。`null` = 没失败过。**它是要显示给用户看的**，不是内部状态。 */
    var failure by remember(uri) { mutableStateOf<IconOverridesStore.FailReason?>(null) }

    LaunchedEffect(uri) {
        val b = IconOverridesStore.loadForCrop(context, uri)
        if (b == null) failed = true else bitmap = b
    }

    Column(Modifier.fillMaxSize().padding(padding)) {
        kit.VSpace(8.dp)
        kit.SectionHeader(fpString("fan_icon.crop.title"))

        val b = bitmap
        if (b == null) {
            kit.Body(
                fpString(if (failed) "fan_icon.crop.failed" else "fan_icon.crop.loading"),
                if (failed) FpEmphasis.WARNING else FpEmphasis.MUTED,
            )
            // 占位：视口尺寸只有量到才知道，而没量到就算不出裁剪框。它同时是 loading 的骨架。
            // **占满剩余高度**（而不是固定正方形）：横屏时那块正方形会把自己和下面的按钮
            // 一起顶出屏幕，而这一页没有滚动 —— 按钮够不着就等于「取消不了」
            Box(
                Modifier
                    .weight(1f)
                    .onSizeChanged { viewportPx = squareSide(it.width, it.height) },
            )
            kit.VSpace(8.dp)
        } else {
            // 视口量到之后才定初值：缩放先取「刚好铺满短边」（= 能看到整张图的最大范围），
            // 裁剪框居中。只在换图 / 换视口尺寸时初始化一次 —— 用户已经调过之后不能被重置
            LaunchedEffect(b, viewportPx) {
                if (viewportPx <= 0f || scale > 0f) return@LaunchedEffect
                val s = viewportPx / minOf(b.width, b.height).toFloat()
                val side = viewportPx / s
                minScale = s
                scale = s
                originX = (b.width - side) / 2f
                originY = (b.height - side) / 2f
            }

            CropViewport(
                bitmap = b,
                scale = scale,
                originX = originX,
                originY = originY,
                viewportPx = viewportPx,
                modifier = Modifier.weight(1f),
                onSize = { viewportPx = it },
                onPan = { dx, dy ->
                    if (scale > 0f) {
                        // 手指右移 = 图片右移 = 裁剪框左移。除以 scale 换成源图坐标，
                        // 否则放大之后拖动会「跑得比手指快」
                        val side = viewportPx / scale
                        originX = (originX - dx / scale)
                            .coerceIn(0f, (b.width - side).coerceAtLeast(0f))
                        originY = (originY - dy / scale)
                            .coerceIn(0f, (b.height - side).coerceAtLeast(0f))
                    }
                },
            )
            kit.VSpace(8.dp)

            val zoom = if (minScale > 0f) {
                (scale / minScale).coerceIn(1f, IconOverridesStore.MAX_CROP_ZOOM)
            } else {
                1f
            }
            kit.SliderRow(
                title = fpString("fan_icon.crop.zoom"),
                summary = null,
                value = zoom,
                range = 1f..IconOverridesStore.MAX_CROP_ZOOM,
                steps = ZoomSteps,
                valueText = String.format(LocalConfiguration.current.locales[0], "%.1f×", zoom),
                enabled = scale > 0f,
                onValueChange = { newZoom ->
                    if (scale <= 0f) return@SliderRow
                    // 以**当前裁剪框的中心**为锚点缩放：否则用户放大想看某个角落时，
                    // 画面会往中心外漂走，得重新找一遍位置
                    val newScale = minScale * newZoom.coerceIn(1f, IconOverridesStore.MAX_CROP_ZOOM)
                    val oldSide = viewportPx / scale
                    val newSide = viewportPx / newScale
                    val cx = originX + oldSide / 2f
                    val cy = originY + oldSide / 2f
                    scale = newScale
                    originX = (cx - newSide / 2f).coerceIn(0f, (b.width - newSide).coerceAtLeast(0f))
                    originY = (cy - newSide / 2f).coerceIn(0f, (b.height - newSide).coerceAtLeast(0f))
                },
            )
            kit.Body(fpString("fan_icon.crop.hint"), FpEmphasis.MUTED)
        }

        kit.VSpace(8.dp)

        // 写盘的状态就摆在按钮上方：成功时这一页会立刻消失（不需要提示），
        // 失败时它会**留在这里**，所以只有失败 / 进行中两种字面
        when {
            saving -> kit.Body(fpString("fan_icon.crop.saving"), FpEmphasis.MUTED)
            failure != null -> kit.Banner(failText(failure!!), FpEmphasis.WARNING)
        }
        kit.VSpace(8.dp)

        Row(
            Modifier.fillMaxWidth().padding(horizontal = FpSpace.l),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 保存中也禁用取消：那会在一次写盘进行到一半时切走这一页（写到一半的图没人认领）
            kit.Button(
                text = fpString("fan_icon.crop.cancel"),
                enabled = !saving,
                onClick = onCancel,
            )
            kit.Button(
                text = fpString("fan_icon.crop.confirm"),
                primary = true,
                // 图还没读出来 / 视口还没量到时不给点：那时算出来的裁剪框是 0 尺寸（会写出空图）。
                // 保存中同样不给点：连点两次会走两遍写盘
                enabled = b != null && scale > 0f && viewportPx > 0f && !saving,
                onClick = {
                    val bmp = b ?: return@Button
                    if (saving) return@Button
                    val side = viewportPx / scale
                    val crop = IconOverridesStore.CropFraction(
                        left = (originX / bmp.width).coerceIn(0f, 1f),
                        top = (originY / bmp.height).coerceIn(0f, 1f),
                        side = (side / minOf(bmp.width, bmp.height)).coerceIn(MinCropSide, 1f),
                    )
                    saving = true
                    failure = null
                    scope.launch {
                        // save 自己不吃异常（它把失败当返回值），这里再兜一层是防它写漏了：
                        // 兜底也**必须显示出来**，绝不能变成一次无声的点击
                        val r = try {
                            save(crop)
                        } catch (t: Throwable) {
                            Log.w(TAG, "保存自定义图标时抛异常：" + t.javaClass.simpleName + "：" + t.message)
                            IconOverridesStore.SaveResult.Failed(
                                IconOverridesStore.FailReason.WRITE,
                                t.javaClass.simpleName + "：" + t.message,
                            )
                        }
                        saving = false
                        when (r) {
                            is IconOverridesStore.SaveResult.Ok -> onSaved()
                            is IconOverridesStore.SaveResult.Failed -> failure = r.reason
                        }
                    }
                },
            )
        }
        kit.VSpace(16.dp)
    }
}

/**
 * 失败原因 → 界面上那一句。
 *
 * **每一档走一个字面量 [fpString]**（而不是把键名拼出来）：`:ui:verifyStringKeys`
 * 只扫源码里的字面键，拼出来的键漏了资源它发现不了 —— 那时用户看到的是
 * `fan_icon.crop.failed.xxx` 这种原始键名（本项目真发生过这种漏项）。
 */
@Composable
private fun failText(reason: IconOverridesStore.FailReason): String = when (reason) {
    IconOverridesStore.FailReason.DECODE -> fpString("fan_icon.crop.failed.decode")
    IconOverridesStore.FailReason.ENCODE -> fpString("fan_icon.crop.failed.encode")
    IconOverridesStore.FailReason.WRITE -> fpString("fan_icon.crop.failed.write")
    IconOverridesStore.FailReason.VERIFY -> fpString("fan_icon.crop.failed.verify")
}

/** 日志标签：与 `IconOverridesStore` 用同一个，排障时一个 `grep` 就能串起整条链路。 */
private const val TAG = "FP-IconOverride"

/**
 * 正方形裁剪视口：把源图上的裁剪区**等比画满**一块正方形（居中放在这块可用区域里）。
 *
 * 为什么不是「视口本身就是正方形（`aspectRatio(1f)`）」：横屏时一块宽度那么大的正方形
 * 会把下面的按钮顶出屏幕，而这一页没有滚动 —— 按钮够不着就等于「取消不了」。
 * 所以让画布**占满剩余高度**、在里面**居中画正方形**，两种朝向都不会溢出。
 * 报出去的尺寸也必须是那个正方形的边长（`min(宽, 高)`），否则裁剪比例会算错。
 *
 * 用 `drawImage` 的源/目标矩形，而不是自己算 `translate` + `scale`：这样画出来的就是
 * **裁剪结果**，与最终写盘那张图同源 —— 换算错了在界面上立刻看得见，不用等写下盘。
 *
 * `scale <= 0`（视口还没量到）时只画一块空占位，并照常上报尺寸：一帧的事，不值得为它
 * 让整页闪一下。
 */
@Composable
private fun CropViewport(
    bitmap: Bitmap,
    scale: Float,
    originX: Float,
    originY: Float,
    viewportPx: Float,
    modifier: Modifier = Modifier,
    onSize: (Float) -> Unit,
    onPan: (dx: Float, dy: Float) -> Unit,
) {
    val colors = LocalFpKit.current.colors()
    Canvas(
        modifier
            .fillMaxWidth()
            .clipToBounds()
            .onSizeChanged { onSize(squareSide(it.width, it.height)) }
            .pointerInput(bitmap, scale) {
                if (scale <= 0f) return@pointerInput
                detectDragGestures { change, drag ->
                    // 消费掉：这一页外面没有滚动容器，消费掉是为了免掉「拖动同时被当成点击」的歧义
                    change.consume()
                    onPan(drag.x, drag.y)
                }
            },
    ) {
        val canvasSide = minOf(size.width, size.height)
        if (scale <= 0f || viewportPx <= 0f || canvasSide <= 0f) return@Canvas
        val squareOffset = IntOffset(
            ((size.width - canvasSide) / 2f).toInt(),
            ((size.height - canvasSide) / 2f).toInt(),
        )
        // 裁剪区在源图坐标里的边长 = 视口 px ÷ 缩放
        val sidePx = viewportPx / scale
        val srcX = originX.toInt().coerceIn(0, (bitmap.width - 1).coerceAtLeast(0))
        val srcY = originY.toInt().coerceIn(0, (bitmap.height - 1).coerceAtLeast(0))
        val srcW = sidePx.toInt().coerceIn(1, bitmap.width - srcX)
        val srcH = sidePx.toInt().coerceIn(1, bitmap.height - srcY)
        drawImage(
            image = bitmap.asImageBitmap(),
            srcOffset = IntOffset(srcX, srcY),
            srcSize = IntSize(srcW, srcH),
            dstOffset = squareOffset,
            dstSize = IntSize(canvasSide.toInt(), canvasSide.toInt()),
            // 放大时用平滑过滤：默认那档在 2× 以上会有明显锯齿，而图标成品只有 192px，
            // 锯齿会一路带进扇形里
            filterQuality = FilterQuality.Medium,
        )
        // 边框：给「这就是最终的那一块」一个明确边界
        drawRect(
            color = colors.outline.copy(alpha = 0.5f),
            topLeft = Offset(squareOffset.x.toFloat(), squareOffset.y.toFloat()),
            size = Size(canvasSide, canvasSide),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

/** 裁剪视口的正方形边长（`min(宽, 高)`）：geometry 与画出去的那一块必须用同一个数。 */
private fun squareSide(width: Int, height: Int): Float = minOf(width, height).toFloat()

/** 缩放滑杆的档数（1×–4× 之间 20 档：够细，也不会让滑杆「一点就跳」）。 */
private const val ZoomSteps = 20

/** 裁剪框边长比例的**下限**：再小就是放大马赛克，同时保护 [IconOverridesStore.CropFraction] 不被塞进 0。 */
private const val MinCropSide = 0.02f
