package io.github.kotoriinsky.tsuzuri.core

import java.security.MessageDigest

/**
 * 订阅规则分发包的校验。
 *
 * 仓库根目录：
 * ```text
 * index.json        { "format": 2, "version": 17, "files": [{ "path": "passcode/rules.json", "sha256": "…", "size": 1234 }] }
 * index.json.sig    { "key": 0, "sig": "<base64，对 index.json 原始字节的 ed25519 签名>" }
 * passcode/rules.json
 * ```
 *
 * 顺序是刻意的：**先验签名、再解析索引**（不解析未经认证的输入），索引通过后才去取规则文件
 * （[verify] 的 `load` 回调），逐个核对大小与 sha256。任何一步不过就整批拒绝，调用方继续用旧版。
 */
object RuleBundle {

    const val INDEX = "index.json"
    const val SIGNATURE = "index.json.sig"

    /** 单个规则文件的上限（与 [MiniJson] 的解析上限一致）。 */
    const val MAX_FILE_BYTES = 1 shl 20

    private const val MAX_INDEX_BYTES = 64 * 1024

    data class FileEntry(val path: String, val sha256: String, val size: Int)

    enum class Reason {
        /** 签名文件不是 `{key, sig}` 或 base64 解不开。 */
        BAD_SIGNATURE_FILE,
        /** 签名指向的公钥槽是空的或不存在。 */
        NO_KEY,
        BAD_SIGNATURE,
        BAD_INDEX,
        UNKNOWN_FORMAT,
        /** 比当前版本旧：防止把有问题的旧版重新推给用户。 */
        DOWNGRADE,
        MISSING_FILE,
        SIZE_MISMATCH,
        HASH_MISMATCH,
    }

    sealed interface Result {
        data class Ok(val version: Long, val keySlot: Int, val files: Map<String, ByteArray>) : Result
        data class UpToDate(val version: Long) : Result
        data class Rejected(val reason: Reason, val detail: String) : Result
    }

    /**
     * @param keys APK 内的公钥槽（[RuleKeys.SLOTS]），按下标对应签名文件里的 `key`
     * @param currentVersion 本机当前规则版本；没有订阅过传 -1
     * @param load 按路径取规则文件，取不到返回 null；只在签名与索引都通过后才调用
     */
    fun verify(
        indexBytes: ByteArray,
        signatureBytes: ByteArray,
        currentVersion: Long,
        keys: List<ByteArray?>,
        load: (String) -> ByteArray?,
    ): Result {
        if (indexBytes.size > MAX_INDEX_BYTES) return Result.Rejected(Reason.BAD_INDEX, "index.json 过大")

        // ── 1. 签名 ──
        val sigDoc = MiniJson.parse(String(signatureBytes, Charsets.UTF_8)) as? Map<*, *>
            ?: return Result.Rejected(Reason.BAD_SIGNATURE_FILE, "签名文件不是 JSON 对象")
        val slot = (sigDoc["key"] as? Double)?.takeIf { it == Math.floor(it) && it >= 0 }?.toInt()
            ?: return Result.Rejected(Reason.BAD_SIGNATURE_FILE, "缺少 key")
        val sig = (sigDoc["sig"] as? String)?.let {
            runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull()
        } ?: return Result.Rejected(Reason.BAD_SIGNATURE_FILE, "sig 不是 base64")
        val key = keys.getOrNull(slot) ?: return Result.Rejected(Reason.NO_KEY, "公钥槽 $slot 为空")
        if (!Ed25519.verify(key, indexBytes, sig)) return Result.Rejected(Reason.BAD_SIGNATURE, "签名不对")

        // ── 2. 索引 ──
        val index = MiniJson.parse(String(indexBytes, Charsets.UTF_8)) as? Map<*, *>
            ?: return Result.Rejected(Reason.BAD_INDEX, "index.json 不是 JSON 对象")
        val format = (index["format"] as? Double)?.toInt()
        if (format != RuleSet.FORMAT) return Result.Rejected(Reason.UNKNOWN_FORMAT, "format=$format")
        val version = (index["version"] as? Double)?.takeIf { it == Math.floor(it) && it >= 0 }?.toLong()
            ?: return Result.Rejected(Reason.BAD_INDEX, "缺少 version")
        if (version < currentVersion) {
            return Result.Rejected(Reason.DOWNGRADE, "仓库版本 $version 比本机 $currentVersion 旧")
        }
        if (version == currentVersion) return Result.UpToDate(version)
        val entries = parseFiles(index["files"])
            ?: return Result.Rejected(Reason.BAD_INDEX, "files 缺失或有非法项")

        // ── 3. 文件 ──
        val out = LinkedHashMap<String, ByteArray>()
        for (e in entries) {
            val bytes = load(e.path) ?: return Result.Rejected(Reason.MISSING_FILE, e.path)
            if (bytes.size != e.size) {
                return Result.Rejected(Reason.SIZE_MISMATCH, "${e.path}：期望 ${e.size}，实际 ${bytes.size}")
            }
            if (sha256Hex(bytes) != e.sha256) return Result.Rejected(Reason.HASH_MISMATCH, e.path)
            out[e.path] = bytes
        }
        return Result.Ok(version, slot, out)
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 只允许仓库内的相对路径：小写段、`.json` 结尾，不许 `..`、绝对路径、反斜杠。 */
    private val PATH = Regex("""^[a-z0-9_-]+(?:/[a-z0-9_-]+)*\.json$""")
    private val SHA = Regex("""^[0-9a-f]{64}$""")

    private fun parseFiles(raw: Any?): List<FileEntry>? {
        val list = raw as? List<*> ?: return null
        if (list.isEmpty()) return null
        val out = ArrayList<FileEntry>()
        val seen = HashSet<String>()
        for (o in list) {
            val m = o as? Map<*, *> ?: return null
            val path = (m["path"] as? String)?.takeIf { PATH.matches(it) && seen.add(it) } ?: return null
            val sha = (m["sha256"] as? String)?.lowercase()?.takeIf(SHA::matches) ?: return null
            val size = (m["size"] as? Double)?.takeIf { it == Math.floor(it) && it > 0 && it <= MAX_FILE_BYTES }
                ?.toInt() ?: return null
            out += FileEntry(path, sha, size)
        }
        return out
    }
}

/**
 * APK 内的规则签名公钥（Q13）：两个槽，0 = 日常，1 = 离线备份。
 *
 * 密钥由维护者本地生成后填入；在此之前两个槽都是空的，
 * [RuleBundle.verify] 对任何订阅包都返回 [RuleBundle.Reason.NO_KEY] —— 也就是订阅不可能被启用。
 */
object RuleKeys {
    val SLOTS: List<ByteArray?> = listOf(null, null)
}
