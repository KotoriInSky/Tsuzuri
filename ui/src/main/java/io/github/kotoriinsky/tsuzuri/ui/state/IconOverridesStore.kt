package io.github.kotoriinsky.tsuzuri.ui.state

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import io.github.kotoriinsky.tsuzuri.core.Keys
import io.github.kotoriinsky.tsuzuri.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「单项图标覆盖」在**设置界面**这一侧：怎么存、怎么写、怎么把图片送过去。
 *
 * 存法（**必须与 hook 侧 `IconOverrides` 逐字一致**）：
 *
 * ```
 * <特性>.icon_overrides = {
 *   "<targetKey>\u0001pack\u0001<图标包包名>/<drawable 名>",
 *   "<targetKey>\u0001file\u0001<文件名>\u0002<指纹>",
 * }
 * ```
 *
 * `targetKey` 与扇形清单同一套键（应用 = `包名`，快捷方式 = `包名/id`）。
 *
 * ## 图片那条路为什么是「处理完再送」
 *
 * 扇子跑在 SystemUI 进程，用户选的图在**另一个 App 的数据目录 / 一个 content:// URI** 里，
 * SystemUI 读不到（跨 uid + scoped storage）。所以：
 *
 * ```
 * 设置界面：contentResolver 读原图（采样解码） → 用户裁剪（CropFraction，方形）
 *        → 缩到 192×192 → 编码 PNG → filesDir/icon_overrides/<文件名>（模块 App 自己的目录）
 * hook    ：contentResolver.openInputStream(content://…/icon/<文件名>) 读出 → 当图标
 * ```
 *
 * 裁剪那一步是**同一个通道里的一段**，不是另开一条：读、裁、缩、编码、投递全在
 * [importImage] 里做完，所以「取消裁剪」= 这个方法根本没被调用 = 一个字节都没写。
 *
 * ## 为什么不再用框架的共享目录（`openRemoteFile`）
 *
 * 那条通道在真机上**从来没有成功过**（设备日志：共享目录里只有占位用的 `probe.png`），
 * 而且失败是静默的：拿不到框架服务就 `return null`，抛异常就被 catch 吞掉，
 * 日志里只剩一行类名。换成模块 App 自己的 files 目录 + [IconFileProvider]（content://）之后，
 * 失败会带着具体异常与错误信息上浮，两边都看得见 —— 见 [SaveResult.Failed]。
 *
 * ## 文件名里的**指纹**（`\u0002<8 位十六进制>`）是干什么的
 *
 * 文件名对同一个 target 是**稳定**的（反复改图只覆盖同一个文件），但这样配置里的值
 * 就不会变 —— 而 hook 侧判断「要不要丢掉解码缓存」靠的正是配置值的签名
 *（`IconOverrides.Set.signature`）。值不变，用户第二次换图（例如重新裁了一次）
 * 就会看到**旧图**，因为整个渲染链根本不知道有东西变了。
 *
 * 所以值里带上图片内容的指纹（PNG 字节的 SHA-256 前 8 位）：内容变了值就变，
 * 签名跟着变，缓存整块丢掉 —— 而且换回同一张图时指纹相同，等于一次没有意义的刷新都不会发生。
 * 指纹只影响这一条覆盖的缓存判定，**不参与渲染**，解析不出指纹（老配置）也只是回到
 * 「这一项显示成默认图标」，不会把扇子搞坏。
 */
object IconOverridesStore {

    private const val TAG = "FP-IconOverride"

    /** 与 hook 侧 `IconOverrides.FIELD` 必须一致。 */
    private const val FIELD = '\u0001'

    /**
     * 值里的指纹分隔符。与 hook 侧 `IconOverrides.STAMP` 必须一致。
     *
     * 用另一个控制字符而不是复用 [FIELD]：值里出现第二个 [FIELD] 会让「三个字段」
     * 这个约定变得有歧义，将来读这段代码的人一定会数错。
     */
    private const val STAMP = '\u0002'

    const val KIND_PACK = "pack"
    const val KIND_FILE = "file"

    /** 自定义图标的边长（px）。192 够画满扇形的图标位（3.25 密度下约 139px）还留了余量。 */
    const val ICON_PX = 192

    /** 一条覆盖。[value] 的含义由 [kind] 决定（见类注释）。 */
    data class Entry(val kind: String, val value: String) {
        /** 界面上显示的一句话。 */
        val describe: String
            get() = when (kind) {
                KIND_FILE -> "自定义图片"
                KIND_PACK -> "来自图标包：" + value.substringAfter('/', value)
                else -> value
            }
    }

    /**
     * 写盘为什么失败。**失败要能说到「哪一步」**，不能只是「没成功」：
     * 界面上那句话与日志里那一行都由它决定（[SaveResult.Failed]）。
     */
    enum class FailReason {
        /** 读不出用户选的那张图（URI 失效、不是图片、OOM）。 */
        DECODE,

        /** 裁完之后的图编不成 PNG（基本只可能是内存不够）。 */
        ENCODE,

        /** 落盘失败：目录建不出来、没有写入权限、磁盘满。 */
        WRITE,

        /** 写完之后自校验没过（字节数对不上 / 文件不见了）。 */
        VERIFY,
    }

    /**
     * [importImage] 的结果。
     *
     * 为什么不是 `String?`：返回 null 时**调用方无法说出为什么**，而这个 bug 的教训就是
     * 「静默失败」。带上 [FailReason] 之后，界面才能照实说一句「哪一步没成」。
     */
    sealed class SaveResult {
        /**
         * 成功。[value] 是要写进配置的那条值（`文件名\u0002指纹`），
         * 调用方必须原样存进 [Entry]，**不要自己去拼**。
         */
        class Ok(val value: String, val bytes: Int) : SaveResult()

        /** 失败。[detail] 只进日志（异常类名等），界面上用 [reason] 选文案。 */
        class Failed(val reason: FailReason, val detail: String) : SaveResult()
    }

    /** 覆盖存在哪个键上（与 hook 侧同一个键）。 */
    fun key(featureKey: String): String = Keys.pref(featureKey, "icon_overrides")

    /** 读全部覆盖。读失败当「一条都没有」（规范 §9）。 */
    fun read(prefs: Prefs, featureKey: String): Map<String, Entry> {
        val raw = try {
            prefs.rawStringSet(key(featureKey))
        } catch (_: Throwable) {
            return emptyMap()
        }
        if (raw.isEmpty()) return emptyMap()
        val out = HashMap<String, Entry>(raw.size * 2)
        for (s in raw) {
            val a = s.indexOf(FIELD)
            if (a <= 0) continue
            val b = s.indexOf(FIELD, a + 1)
            if (b <= a) continue
            out[s.substring(0, a)] = Entry(s.substring(a + 1, b), s.substring(b + 1))
        }
        return out
    }

    /** `prefs` 变了要重新读一次（[read] 不是可观察的）。 */
    @Composable
    fun observe(prefs: Prefs, featureKey: String): Map<String, Entry> {
        // 用 revision 订阅：没有它，用户在下一屏改完图标回到这一屏会看到旧值
        @Suppress("UNUSED_EXPRESSION")
        revision
        return read(prefs, featureKey)
    }

    private var revision by mutableIntStateOf(0)

    /** 写一条覆盖（同一 targetKey 只会留一条 —— 图片与图标包覆盖互斥）。 */
    fun put(featureKey: String, target: String, entry: Entry): ConfigStore.WriteResult {
        val current = read(ConfigStore.prefs(), featureKey).toMutableMap()
        current[target] = entry
        val outcome = ConfigStore.putStringSet(key(featureKey), encode(current))
        if (outcome != ConfigStore.WriteResult.FAILED) revision++
        return outcome
    }

    /**
     * 删一条覆盖（界面上的「恢复默认」）。
     *
     * [context] 给了就顺手把图片文件也删掉（免得它在目录里留成孤儿）；
     * 不给也能正常删配置 —— 删文件永远只是**附带**动作，失败不影响这条覆盖被移除。
     */
    fun remove(featureKey: String, target: String, context: Context? = null) {
        val current = read(ConfigStore.prefs(), featureKey).toMutableMap()
        val gone = current.remove(target) ?: return
        val outcome = if (current.isEmpty()) {
            ConfigStore.remove(key(featureKey))
        } else {
            ConfigStore.putStringSet(key(featureKey), encode(current))
        }
        revision++
        if (outcome == ConfigStore.WriteResult.REMOTE && gone.kind == KIND_FILE) deleteQuietly(context, nameOf(gone.value))
    }

    private fun encode(map: Map<String, Entry>): Set<String> =
        map.entries.map { it.key + FIELD + it.value.kind + FIELD + it.value.value }.toSet()

    // ── 图片的处理与投递 ──────────────────────────────────────────────

    /** 自定义图标在图标目录里的文件名。**必须是稳定的**：同一个 target 反复改图只覆盖同一个文件。 */
    fun fileNameOf(target: String): String {
        // 文件名里不能有 '/'（快捷方式项的键是 `包名/id`）与 ':'（链接项的键是 `link:scheme://…`）。
        val flat = target.replace('/', '_').replace(':', '_')
        // ⚠️ 还要有**长度上限**：provider 侧 `isSafeName` 拒掉长度 > 128 的名字，而链接项的键是
        // **用户自己填的 URI**，带一串查询参数就越界了。越界时的现象很隐蔽：写盘成功
        // （写入侧不查长度）、读回来被拒（读取侧查长度）—— 又是「图标没变」。
        //
        // 截断后必须再追一段**由完整 target 算出来的**指纹，否则两个长键会撞到同一个文件
        // （前缀相同就完全一样了）。用 `String.hashCode()`：它的算法由 Java 规范钉死，
        // 跨进程、跨 APK 版本都一致 —— 而这条通道两端各算一次，不一致就等于读不到。
        val safe =
            if (flat.length <= MAX_NAME_BODY) flat else flat.take(MAX_NAME_BODY) + "_" + shortHash(flat)
        return "fp_icon_" + safe + IconFileProvider.SUFFIX
    }

    /** [fileNameOf] 里截断时那一段指纹。十六进制是为了可读（能和日志里的文件名对上）。 */
    private fun shortHash(flat: String): String = Integer.toHexString(flat.hashCode())

    /**
     * 文件名主体（前缀与后缀之间那一段）的长度上限。
     *
     * 96 是从 provider 那侧的上限**倒推**出来的：`IconFileProvider.isSafeName` 拒掉 > 128 的名字，
     * 而整名是 `"fp_icon_" (8) + 主体 + "_" + 8 位指纹 + ".png" (4)` —— 取 96 时最长 117，
     * 留了 11 个字符的余量（后缀或前缀哪天改长了也不会立刻越界）。
     */
    private const val MAX_NAME_BODY = 96

    /**
     * 解码时的**短边**目标（px）。
     *
     * 这个值同时决定两件事，所以只在这里写一次：
     *
     *  - **裁剪界面的预览**（[loadForCrop]）与实际写盘（[importImage]）用**同一个**采样率，
     *    于是「界面上看到的那一块」就是「写进去的那一块」（不会出现预览清楚、成品糊）；
     *  - 它给裁剪留的余量：最大放大 [MAX_CROP_ZOOM] 倍时裁剪框还有
     *    `512 / 4 = 128px`，缩到 192px 成品只放大 1.5 倍。
     *
     * 取 512 而不是更大是为了内存：4000×3000 的相册图在这个采样率下降成 1000×750
     * （约 3MB），而设置界面进程的堆不该为一张图标图承担几十 MB。
     */
    const val CROP_WORK_MIN_SIDE = 512

    /** 裁剪界面最大放大倍数（滑杆上限）。与 [CROP_WORK_MIN_SIDE] 一起决定最低清晰度，见它。 */
    const val MAX_CROP_ZOOM = 4f

    /**
     * 裁剪框：**四个量都是相对源图的比例**（不是像素）。
     *
     * 为什么用比例而不是像素：界面上看到的是**采样后**的位图（[CROP_WORK_MIN_SIDE]），
     * 而写盘时是**另一次**解码（可能是另一个采样率）。用像素传的话，两次采样的差异
     * 会让「预览里的那一块」与「写进去的那一块」错开几个像素；比例没有这个问题。
     *
     * 三个量的基准**不一样**，这是刻意的（裁剪框是正方形，用两个基准就没有歧义）：
     *
     *  - [side]：裁剪框边长 ÷ **短边**。`1` = 整个短边（最大范围），越小裁得越紧；
     *  - [left] / [top]：裁剪框左上角 ÷ **宽** / **高**。
     */
    class CropFraction(val left: Float, val top: Float, val side: Float)

    /**
     * 把用户选的图片处理成 192×192 PNG，写进**模块 App 自己的** files 目录。
     *
     * ```
     * contentResolver 读原图（采样） → 按 [crop] 裁一个正方形 → 缩到 192 → PNG
     *   → filesDir/icon_overrides/<文件名>（先写 .part 再改名，写完自校验）
     *   → 给 SystemUI 这类读取方 grantUriPermission
     * ```
     *
     * @param crop 用户的裁剪框；`null` = **自动居中裁方**（加裁剪界面之前的行为，
     *             也是「没裁过」的那条路）。越界/非法的值一律夹取，不抛异常
     * @return [SaveResult.Ok] 带要存进配置的值；失败是 [SaveResult.Failed]，
     *         界面必须把原因**说出来** —— 用户选完图什么都没发生的话，
     *         他只会以为是自己点错了（这正是上一版 `String?` 的病根）
     */
    suspend fun importImage(
        context: Context,
        target: String,
        uri: Uri,
        crop: CropFraction? = null,
    ): SaveResult = withContext(Dispatchers.IO) {
        val name = fileNameOf(target)

        // ① 先确认「出口」真的在包里。这一刻意在这里：provider 要是没进 APK
        //（AndroidManifest 漏了 / R8 裁掉了），文件写得再对 SystemUI 也读不到，
        // 而那种失败在真机上表现为「又是图标没变」—— 提前一步把它变成一句明确的日志。
        if (!providerDeclared(context)) {
            return@withContext fail(
                target,
                FailReason.WRITE,
                "APK 里没有注册 provider（authority=${IconFileProvider.AUTHORITY}）",
            )
        }

        val dir = IconFileProvider.dir(context)
            ?: return@withContext fail(target, FailReason.WRITE, "拿不到 filesDir")

        var src: Bitmap? = null
        var square: Bitmap? = null
        try {
            val decoded = decodeSampled(context, uri)
                ?: return@withContext fail(target, FailReason.DECODE, "解码结果为 null（$uri）")
            src = decoded
            val sq = cropSquare(decoded, crop)
            square = sq
            val out = if (sq.width == ICON_PX) {
                sq
            } else {
                Bitmap.createScaledBitmap(sq, ICON_PX, ICON_PX, true)
            }
            // 中间产物就地释放：先放 sq（全幅裁切时它就是 decoded 本身），再放 decoded
            if (out !== sq) sq.recycle()
            if (decoded !== sq && decoded !== out) decoded.recycle()

            val png = ByteArrayOutputStream()
            if (!out.compress(Bitmap.CompressFormat.PNG, 100, png)) {
                return@withContext fail(target, FailReason.ENCODE, "Bitmap.compress 返回 false")
            }
            val bytes = png.toByteArray()
            if (bytes.isEmpty()) {
                return@withContext fail(target, FailReason.ENCODE, "编码结果 0 字节")
            }

            if (!dir.isDirectory && !dir.mkdirs()) {
                return@withContext fail(target, FailReason.WRITE, "建目录失败：$dir")
            }
            // 先写 .part 再改名：改名在同一目录里是原子的，SystemUI 绝不会读到半张图。
            // 而且**同一个 target 反复改图只覆盖同一个文件**，目录里不会越堆越多。
            val tmp = File(dir, name + ".part")
            try {
                FileOutputStream(tmp).use { os ->
                    os.write(bytes)
                    os.flush()
                }
            } catch (t: Throwable) {
                deleteFileQuietly(tmp)
                return@withContext fail(target, FailReason.WRITE, "写临时文件失败：" + brief(t))
            }
            val dst = File(dir, name)
            if (!tmp.renameTo(dst)) {
                deleteFileQuietly(tmp)
                return@withContext fail(target, FailReason.WRITE, "改名失败（$tmp → $dst）")
            }
            // ② 自校验：**「写了」不等于「写对了」**。
            //    磁盘满、被 SELinux 拦掉之类的情况在这里当场暴露，而不是等 SystemUI 读不到。
            if (!dst.isFile || dst.length() != bytes.size.toLong()) {
                return@withContext fail(
                    target,
                    FailReason.VERIFY,
                    "落盘后校验不符：存在=${dst.isFile} 长度=${dst.length()} 期望=${bytes.size}",
                )
            }

            val value = name + STAMP + fingerprint(bytes)
            // ③ 显式授权给读取方。SystemUI 能不能直接读是**实测过会通**的（同一个 provider
            //    形态的 FanShortcutCatalogProvider 一直在用），这一步是兜底：万一包可见性
            //    过滤把我们的 provider 藏起来，有 URI 授权的一方仍然看得到。
            grantToReaders(context, name)
            Log.i(TAG, "已写入自定义图标：$name（${bytes.size} 字节）→ $dir")
            SaveResult.Ok(value, bytes.size)
        } catch (t: Throwable) {
            fail(target, FailReason.WRITE, brief(t))
        } finally {
            // 只有「还没走到裁切那一步」的解码结果需要在这里兜底释放
            if (square == null) src?.recycle()
        }
    }

    /**
     * 读一张图给**裁剪界面**用（采样到 [CROP_WORK_MIN_SIDE]，与原图同比例）。
     *
     * 与 [importImage] 用同一套采样规则：界面上看到的像素 = 最终会被裁的那些像素。
     * 调用方持有它直到用户确认/取消；**不要**手动 recycle（可能还有一帧在画它，
     * 它是几 MB 的小位图，交给 GC 就好）。
     */
    suspend fun loadForCrop(context: Context, uri: Uri): Bitmap? =
        withContext(Dispatchers.IO) { decodeSampled(context, uri) }

    // ── 内部 ────────────────────────────────────────────────────────

    /** 值里的文件名（去掉指纹那一段）。老配置没有指纹，[String.substringBefore] 原样返回。 */
    fun nameOf(value: String): String = value.substringBefore(STAMP)

    private fun fail(target: String, reason: FailReason, detail: String): SaveResult.Failed {
        Log.w(TAG, "写自定义图标失败（$target／$reason）：$detail")
        return SaveResult.Failed(reason, detail)
    }

    private fun brief(t: Throwable): String = t.javaClass.simpleName + "：" + t.message

    /** 「provider 在不在包里」只查一次（PackageManager 调用没必要每次写盘都做一次）。 */
    private val providerChecked = AtomicBoolean(false)

    @Volatile
    private var providerOk = false

    private fun providerDeclared(context: Context): Boolean {
        if (providerChecked.get()) return providerOk
        providerOk = try {
            context.packageManager?.resolveContentProvider(IconFileProvider.AUTHORITY, 0) != null
        } catch (t: Throwable) {
            Log.w(TAG, "查询 provider 失败：" + brief(t))
            false
        }
        providerChecked.set(true)
        if (!providerOk) {
            Log.e(
                TAG,
                "APK 里没有 authority=${IconFileProvider.AUTHORITY} 的 provider：" +
                    "SystemUI 永远读不到自定义图标。检查 ui/src/main/AndroidManifest.xml 的 <provider> " +
                    "有没有被 R8/合并裁掉。",
            )
        }
        return providerOk
    }

    /**
     * 把这条 URI 显式授权给读取方。失败**只记日志**：授权拿不到不改变结论
     *（provider 本身是 exported 的，同形态的 `FanShortcutCatalogProvider` 一直在被
     * SystemUI 直接调用），所以不值得为此让一次成功的写盘变失败。
     *
     * 每个包**只提醒一次**（[grantWarned]）：有些机器上 `com.miui.home` / 负一屏
     * 未必装着，那种情况下每写一次图就打一行 warning，会把真正有用的日志淹掉。
     */
    private fun grantToReaders(context: Context, name: String) {
        val uri = IconFileProvider.uriOf(name)
        for (pkg in IconFileProvider.READER_PACKAGES) {
            try {
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (t: Throwable) {
                if (grantWarned.add(pkg)) {
                    Log.w(TAG, "给 $pkg 授权失败（不影响 provider 的公开读）：" + brief(t))
                }
            }
        }
    }

    /** 已经提醒过的包（见 [grantToReaders]）。 */
    private val grantWarned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 删一个图标文件。**失败只记日志**：文件删不掉顶多占几 KB，
     * 而这里抛异常会把「恢复默认」这个动作本身弄失败。
     */
    private fun deleteQuietly(context: Context?, name: String?) {
        if (context == null || name.isNullOrEmpty()) return
        deleteFileQuietly(IconFileProvider.resolveFile(context, name))
    }

    /** 同上，直接给 File（写盘中途失败时清理临时文件用）。`null` 安全。 */
    private fun deleteFileQuietly(f: File?) {
        if (f == null) return
        try {
            if (f.isFile && !f.delete()) Log.w(TAG, "删除文件失败：$f")
        } catch (t: Throwable) {
            Log.w(TAG, "删除文件异常：" + brief(t))
        }
    }

    /**
     * 内容指纹：PNG 字节的 SHA-256 前 8 位十六进制。
     *
     * 只要「内容变了值就变」，用哈希而不是时间戳是为了**幂等**：用户对同一个 target
     * 反复裁同一张图（或者只是把同一张图再选一遍），指纹相同 → 配置值不变 →
     * hook 侧不会白白丢一次解码缓存。
     */
    private fun fingerprint(bytes: ByteArray): String = try {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = CharArray(8)
        for (i in 0 until 4) {
            val v = d[i].toInt() and 0xFF
            hex[i * 2] = HEX[v ushr 4]
            hex[i * 2 + 1] = HEX[v and 0x0F]
        }
        String(hex)
    } catch (t: Throwable) {
        // 摘要算不出来不该让写盘失败：退成一个「总是不同」的值，行为是「多刷新一次」
        Log.w(TAG, "算指纹失败：" + brief(t))
        System.currentTimeMillis().toString(16)
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * 按 [crop] 从 [src] 上裁一个**正方形**。
     *
     * `crop == null` = 居中裁一个最大的正方形（旧行为）。有 crop 时全部夹取到图像内：
     * 界面给的比例理论上一定合法，但配置通道上的值不该被信任（一张图被换成另一张时，
     * 老比例可能就越界了）。
     */
    private fun cropSquare(src: Bitmap, crop: CropFraction?): Bitmap {
        val w = src.width
        val h = src.height
        val shorter = minOf(w, h)
        val side: Int
        val left: Int
        val top: Int
        if (crop == null) {
            side = shorter
            left = (w - side) / 2
            top = (h - side) / 2
        } else {
            side = (shorter * crop.side).toInt().coerceIn(1, shorter)
            left = (w * crop.left).toInt().coerceIn(0, w - side)
            top = (h * crop.top).toInt().coerceIn(0, h - side)
        }
        return Bitmap.createBitmap(src, left, top, side, side)
    }

    /**
     * 采样解码（**不裁**）。
     *
     * 两趟解码（先只读尺寸、再按采样率读像素）是必须的：相册里的图动辄 4000×3000
     * （48MB 的 ARGB），直接整张解出来会在设置界面进程里制造一次几十毫秒的尖峰，
     * 而且完全没必要 —— 我们最终只要 192px。
     */
    private fun decodeSampled(context: Context, uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "解码不到图片尺寸（$uri）：可能不是图片，或那次选择的读权限已经过期")
            null
        } else {
            val sample = sampleSize(bounds.outWidth, bounds.outHeight)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "解码图片失败：" + brief(t))
        null
    }

    /**
     * 采样率：**短边不低于 [CROP_WORK_MIN_SIDE]**，同时**长边不高于 [MAX_WORK_SIDE]**。
     *
     * 两个目标冲突时（超长条幅图，例如 8000×1000）**优先保内存**：短边降到 512 以下是
     * 可以接受的（裁剪余量小一点），而一张 8000px 宽的位图是 32MB —— 那会直接把
     * 设置界面拖进 OOM，比「放大到顶有点糊」糟得多。
     */
    private fun sampleSize(w: Int, h: Int): Int {
        var s = 1
        while (true) {
            val next = s * 2
            val keepsDetail = w / next >= CROP_WORK_MIN_SIDE && h / next >= CROP_WORK_MIN_SIDE
            val stillBig = maxOf(w, h) / s > MAX_WORK_SIDE
            if (!keepsDetail && !stillBig) return s
            s = next
        }
    }

    /** 解码后长边的上限（px）。只用来挡超长条幅图，见 [sampleSize]。 */
    private const val MAX_WORK_SIDE = 2048
}
