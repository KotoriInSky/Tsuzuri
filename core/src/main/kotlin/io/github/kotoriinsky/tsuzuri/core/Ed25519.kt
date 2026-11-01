package io.github.kotoriinsky.tsuzuri.core

import java.math.BigInteger
import java.security.MessageDigest

/**
 * ed25519 **验签**（只验不签；私钥只在规则维护者本地）。
 *
 * 按 RFC 8032 §6 的参考实现移植，用 BigInteger 运算：一次验签两次标量乘，几十毫秒，规则更新时才跑一次，够用。
 * 为什么不用 `Signature.getInstance("Ed25519")`：JDK 15+ 有，但设置 App 进程里是否可用没在这台机上验过
 * （ADR 待验证项）；自带实现在 JVM 单测与设备上行为一致，单测用 RFC 8032 测试向量与 JDK 签出的签名交叉验证。
 *
 * 不追求常数时间：验签的输入（公钥、签名、规则文件）都是公开的，没有可泄露的秘密。
 */
object Ed25519 {

    // BigInteger.TWO 要 Android API 33，自己定义一个
    private val TWO: BigInteger = BigInteger.valueOf(2)

    private val P: BigInteger = TWO.pow(255) - BigInteger.valueOf(19)
    private val Q: BigInteger = TWO.pow(252) + BigInteger("27742317777372353535851937790883648493")
    private val D: BigInteger = (BigInteger.valueOf(-121665) * inv(BigInteger.valueOf(121666))).mod(P)
    private val SQRT_M1: BigInteger = TWO.modPow((P - BigInteger.ONE) / BigInteger.valueOf(4), P)

    /** 扩展坐标 (X, Y, Z, T)：x = X/Z，y = Y/Z，xy = T/Z。 */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val G: Point = run {
        val gy = (BigInteger.valueOf(4) * inv(BigInteger.valueOf(5))).mod(P)
        val gx = recoverX(gy, 0)!!
        Point(gx, gy, BigInteger.ONE, (gx * gy).mod(P))
    }

    private val NEUTRAL = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    /** [publicKey] 32 字节、[signature] 64 字节；长度不对、点解不出来、S 越界都返回 false。 */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decompress(publicKey) ?: return false
        val rBytes = signature.copyOfRange(0, 32)
        val r = decompress(rBytes) ?: return false
        val s = le(signature.copyOfRange(32, 64))
        if (s >= Q) return false
        val sha = MessageDigest.getInstance("SHA-512")
        sha.update(rBytes)
        sha.update(publicKey)
        sha.update(message)
        val h = le(sha.digest()).mod(Q)
        return equal(mul(s, G), add(r, mul(h, a)))
    }

    private fun inv(x: BigInteger): BigInteger = x.modPow(P - TWO, P)

    private fun le(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    private fun add(p: Point, q: Point): Point {
        val a = ((p.y - p.x) * (q.y - q.x)).mod(P)
        val b = ((p.y + p.x) * (q.y + q.x)).mod(P)
        val c = (TWO * p.t * q.t * D).mod(P)
        val d = (TWO * p.z * q.z).mod(P)
        val e = b - a
        val f = d - c
        val g = d + c
        val h = b + a
        return Point((e * f).mod(P), (g * h).mod(P), (f * g).mod(P), (e * h).mod(P))
    }

    private fun mul(scalar: BigInteger, point: Point): Point {
        var s = scalar
        var p = point
        var q = NEUTRAL
        while (s.signum() > 0) {
            if (s.testBit(0)) q = add(q, p)
            p = add(p, p)
            s = s.shiftRight(1)
        }
        return q
    }

    private fun equal(p: Point, q: Point): Boolean =
        (p.x * q.z - q.x * p.z).mod(P).signum() == 0 && (p.y * q.z - q.y * p.z).mod(P).signum() == 0

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val x2 = ((y * y - BigInteger.ONE) * inv(D * y * y + BigInteger.ONE)).mod(P)
        if (x2.signum() == 0) return if (sign != 0) null else BigInteger.ZERO
        var x = x2.modPow((P + BigInteger.valueOf(3)) / BigInteger.valueOf(8), P)
        if ((x * x - x2).mod(P).signum() != 0) x = (x * SQRT_M1).mod(P)
        if ((x * x - x2).mod(P).signum() != 0) return null
        if (x.testBit(0) != (sign == 1)) x = P - x
        return x
    }

    private fun decompress(s: ByteArray): Point? {
        if (s.size != 32) return null
        var y = le(s)
        val sign = if (y.testBit(255)) 1 else 0
        y = y.clearBit(255)
        val x = recoverX(y, sign) ?: return null
        return Point(x, y, BigInteger.ONE, (x * y).mod(P))
    }
}
