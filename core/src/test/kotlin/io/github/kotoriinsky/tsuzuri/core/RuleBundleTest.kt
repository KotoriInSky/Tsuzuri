package io.github.kotoriinsky.tsuzuri.core

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 验签与分发包校验。自写的 [Ed25519] 用两种来源钉住：
 * RFC 8032 §7.1 的测试向量，以及 JDK 自带 Ed25519 现签的签名（交叉验证）。
 */
class RuleBundleTest {

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // ── Ed25519 ──

    @Test
    fun `RFC 8032 测试向量`() {
        // TEST 1：空消息
        assertTrue(
            Ed25519.verify(
                hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"),
                ByteArray(0),
                hex(
                    "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
                ),
            ),
        )
        // TEST 2：一字节消息 0x72
        assertTrue(
            Ed25519.verify(
                hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"),
                hex("72"),
                hex(
                    "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
                ),
            ),
        )
    }

    private val keys: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    /** X.509 编码的最后 32 字节就是裸公钥。 */
    private fun raw(kp: KeyPair): ByteArray = kp.public.encoded.takeLast(32).toByteArray()

    private fun sign(kp: KeyPair, data: ByteArray): ByteArray =
        Signature.getInstance("Ed25519").run {
            initSign(kp.private)
            update(data)
            sign()
        }

    @Test
    fun `与 JDK 交叉验证 且能识破篡改`() {
        val r = Random(1)
        repeat(8) { i ->
            val msg = r.nextBytes(i * 37)
            val sig = sign(keys, msg)
            assertTrue(Ed25519.verify(raw(keys), msg, sig), "第 $i 条")
            if (msg.isNotEmpty()) {
                val bad = msg.copyOf().also { it[0] = (it[0] + 1).toByte() }
                assertFalse(Ed25519.verify(raw(keys), bad, sig))
            }
            val badSig = sig.copyOf().also { it[10] = (it[10] + 1).toByte() }
            assertFalse(Ed25519.verify(raw(keys), msg, badSig))
        }
        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        assertFalse(Ed25519.verify(raw(other), "x".toByteArray(), sign(keys, "x".toByteArray())))
        assertFalse(Ed25519.verify(ByteArray(31), ByteArray(0), ByteArray(64)))
        assertFalse(Ed25519.verify(raw(keys), ByteArray(0), ByteArray(63)))
    }

    // ── 分发包 ──

    private val rules = """{"format":2,"version":5,"passcodes":[]}""".toByteArray()

    private fun index(version: Long = 5, path: String = "passcode/rules.json", body: ByteArray = rules, size: Int = body.size) =
        """{"format":2,"version":$version,"files":[{"path":"$path","sha256":"${RuleBundle.sha256Hex(body)}","size":$size}]}"""
            .toByteArray()

    private fun sigFile(data: ByteArray, slot: Int = 0, kp: KeyPair = keys): ByteArray =
        """{"key":$slot,"sig":"${java.util.Base64.getEncoder().encodeToString(sign(kp, data))}"}""".toByteArray()

    private fun verify(
        idx: ByteArray = index(),
        sig: ByteArray = sigFile(idx),
        current: Long = 4,
        slots: List<ByteArray?> = listOf(raw(keys), null),
        files: Map<String, ByteArray> = mapOf("passcode/rules.json" to rules),
    ) = RuleBundle.verify(idx, sig, current, slots) { files[it] }

    private fun assertRejected(r: RuleBundle.Result, reason: RuleBundle.Reason) {
        assertIs<RuleBundle.Result.Rejected>(r)
        assertEquals(reason, r.reason, r.detail)
    }

    @Test
    fun `正常更新`() {
        val r = assertIs<RuleBundle.Result.Ok>(verify())
        assertEquals(5L, r.version)
        assertEquals(0, r.keySlot)
        assertTrue(r.files.getValue("passcode/rules.json").contentEquals(rules))
    }

    @Test
    fun `备份公钥槽签的同样有效 空槽拒绝`() {
        val backup = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val idx = index()
        assertIs<RuleBundle.Result.Ok>(verify(idx, sigFile(idx, slot = 1, kp = backup), slots = listOf(raw(keys), raw(backup))))
        assertRejected(verify(idx, sigFile(idx, slot = 1, kp = backup)), RuleBundle.Reason.NO_KEY)
        assertRejected(verify(slots = RuleKeys.SLOTS), RuleBundle.Reason.NO_KEY)
    }

    @Test
    fun `索引被改 签名就不对`() {
        val idx = index()
        val tampered = String(idx).replace("\"version\":5", "\"version\":6").toByteArray()
        assertRejected(verify(tampered, sigFile(idx)), RuleBundle.Reason.BAD_SIGNATURE)
        assertRejected(verify(sig = "{}".toByteArray()), RuleBundle.Reason.BAD_SIGNATURE_FILE)
        assertRejected(verify(sig = """{"key":0,"sig":"%%%"}""".toByteArray()), RuleBundle.Reason.BAD_SIGNATURE_FILE)
    }

    @Test
    fun `防降级 同版本不更新`() {
        assertRejected(verify(current = 6), RuleBundle.Reason.DOWNGRADE)
        assertEquals(RuleBundle.Result.UpToDate(5), verify(current = 5))
    }

    @Test
    fun `文件缺失 大小或哈希不符都拒绝`() {
        assertRejected(verify(files = emptyMap()), RuleBundle.Reason.MISSING_FILE)
        val swapped = """{"format":2,"version":5,"passcodes":[ ]}""".toByteArray()
        assertRejected(verify(files = mapOf("passcode/rules.json" to swapped)), RuleBundle.Reason.SIZE_MISMATCH)
        val sameSize = String(rules).replace("5", "6").toByteArray()
        assertRejected(verify(files = mapOf("passcode/rules.json" to sameSize)), RuleBundle.Reason.HASH_MISMATCH)
    }

    @Test
    fun `索引里的路径只能是仓库内相对路径`() {
        for (p in listOf("../etc/passwd.json", "/abs.json", "passcode\\\\rules.json", "Rules.json", "rules.txt")) {
            val idx = index(path = p)
            assertRejected(verify(idx, sigFile(idx)), RuleBundle.Reason.BAD_INDEX)
        }
        val unknown = """{"format":3,"version":9,"files":[]}""".toByteArray()
        assertRejected(verify(unknown, sigFile(unknown)), RuleBundle.Reason.UNKNOWN_FORMAT)
    }

    @Test
    fun `签名通过之前不读规则文件`() {
        var loaded = false
        val idx = index()
        RuleBundle.verify(idx, sigFile(idx, kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()), 4, listOf(raw(keys))) {
            loaded = true
            rules
        }
        assertFalse(loaded)
    }
}
