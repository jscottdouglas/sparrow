package com.sparrowwallet.mobile.crypto

import java.math.BigInteger

/**
 * Pure-BigInteger secp256k1 — sufficient (and fully correct) for key derivation and
 * parity testing on the JVM. Production mobile targets will delegate to secp256k1-kmp
 * (libsecp256k1); the expect signatures mirror that API so the swap is drop-in.
 * NOT constant-time — do not use for signing in production.
 */
private val P = BigInteger("fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f", 16)
private val N = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
private val G = Point(
    BigInteger("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", 16),
    BigInteger("483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8", 16)
)

private data class Point(val x: BigInteger, val y: BigInteger)

actual fun secpPublicKeyCreate(privKey: ByteArray): ByteArray {
    require(privKey.size == 32) { "Private key must be 32 bytes" }
    val k = BigInteger(1, privKey)
    require(k.signum() > 0 && k < N) { "Private key out of range" }
    val point = multiply(G, k) ?: throw IllegalStateException("Point at infinity")
    val out = ByteArray(33)
    out[0] = if(point.y.testBit(0)) 3 else 2
    to32Bytes(point.x).copyInto(out, 1)
    return out
}

actual fun secpPrivKeyTweakAdd(privKey: ByteArray, tweak: ByteArray): ByteArray {
    require(privKey.size == 32 && tweak.size == 32) { "Keys must be 32 bytes" }
    require(BigInteger(1, tweak) < N) { "Tweak out of range" }
    val sum = (BigInteger(1, privKey) + BigInteger(1, tweak)).mod(N)
    require(sum.signum() > 0) { "Resulting key is zero" }
    return to32Bytes(sum)
}

actual fun secpPubKeyTweakAdd(pubKey: ByteArray, tweak: ByteArray): ByteArray {
    val t = BigInteger(1, tweak)
    require(t.signum() > 0 && t < N) { "Tweak out of range" }
    val sum = add(decompress(pubKey), pmulG(t)) ?: throw IllegalStateException("Point at infinity")
    return compress(sum)
}

actual fun secpPubKeyTweakMul(pubKey: ByteArray, scalar: ByteArray): ByteArray {
    val k = BigInteger(1, scalar)
    require(k.signum() > 0 && k < N) { "Scalar out of range" }
    val product = multiply(decompress(pubKey), k) ?: throw IllegalStateException("Point at infinity")
    return compress(product)
}

actual fun secpSign(messageHash: ByteArray, privKey: ByteArray): ByteArray {
    require(messageHash.size == 32 && privKey.size == 32) { "Hash and key must be 32 bytes" }
    val d = BigInteger(1, privKey)
    require(d.signum() > 0 && d < N) { "Private key out of range" }
    val z = BigInteger(1, messageHash)

    // Low-R grinding as drongo/Bitcoin Core do: standard RFC6979 first, then retries
    // mixing a counter (32-byte little-endian, RFC6979 §3.6 additional data) into the
    // nonce until r < 2^255, so the DER-encoded r fits in 32 bytes. Native mobile
    // actuals must reproduce this via libsecp256k1's ndata parameter.
    var counter: Long? = null
    while(true) {
        val (r, s) = signAttempt(d, z, privKey, counter)
        if(r.bitLength() <= 255) {
            return to32Bytes(r) + to32Bytes(s)
        }
        counter = (counter ?: 0L) + 1
    }
}

private fun signAttempt(d: BigInteger, z: BigInteger, privKey: ByteArray, counter: Long?): Pair<BigInteger, BigInteger> {
    // RFC6979 HMAC-SHA256 DRBG; bits2octets(h1) = h1 mod n since hash length == qlen
    val h1 = to32Bytes(z.mod(N))
    // 32-byte little-endian counter; guard it<8 because Kotlin's ushr masks shifts to 0..63
    val extra = if(counter == null) ByteArray(0) else ByteArray(32) { if(it < 8) ((counter ushr (8 * it)) and 0xFF).toByte() else 0 }
    var v = ByteArray(32) { 1 }
    var k = hmac256(ByteArray(32), v + byteArrayOf(0) + privKey + h1 + extra)
    v = hmac256(k, v)
    k = hmac256(k, v + byteArrayOf(1) + privKey + h1 + extra)
    v = hmac256(k, v)

    while(true) {
        v = hmac256(k, v)
        val nonce = BigInteger(1, v)
        if(nonce.signum() > 0 && nonce < N) {
            val point = multiply(G, nonce)
            if(point != null) {
                val r = point.x.mod(N)
                var s = (nonce.modInverse(N) * (z + r * d)).mod(N)
                if(r.signum() != 0 && s.signum() != 0) {
                    if(s > N.shiftRight(1)) {
                        s = N - s // low-S normalization (BIP62)
                    }
                    return r to s
                }
            }
        }
        k = hmac256(k, v + byteArrayOf(0))
        v = hmac256(k, v)
    }
}

private fun hmac256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

private fun pmulG(k: BigInteger): Point? = multiply(G, k)

private fun compress(point: Point): ByteArray {
    val out = ByteArray(33)
    out[0] = if(point.y.testBit(0)) 3 else 2
    to32Bytes(point.x).copyInto(out, 1)
    return out
}

private fun decompress(pubKey: ByteArray): Point {
    require(pubKey.size == 33 && (pubKey[0].toInt() == 2 || pubKey[0].toInt() == 3)) { "Expected compressed public key" }
    val x = BigInteger(1, pubKey.copyOfRange(1, 33))
    require(x < P) { "X coordinate out of range" }
    // y^2 = x^3 + 7; sqrt via (p+1)/4 exponent since p ≡ 3 (mod 4)
    val ySquared = (x.modPow(BigInteger.valueOf(3), P) + BigInteger.valueOf(7)).mod(P)
    var y = ySquared.modPow((P + BigInteger.ONE).shiftRight(2), P)
    require(y.multiply(y).mod(P) == ySquared) { "Point is not on the curve" }
    if(y.testBit(0) != (pubKey[0].toInt() == 3)) {
        y = P - y
    }
    return Point(x, y)
}

private fun to32Bytes(value: BigInteger): ByteArray {
    val raw = value.toByteArray()
    // only a leading sign byte may exceed 32 bytes — anything else is a reduction bug
    require(raw.size <= 32 || (raw.size == 33 && raw[0].toInt() == 0)) { "Scalar exceeds 32 bytes" }
    val out = ByteArray(32)
    val src = if(raw.size == 33) raw.copyOfRange(1, 33) else raw
    src.copyInto(out, 32 - src.size)
    return out
}

private fun multiply(point: Point, scalar: BigInteger): Point? {
    var result: Point? = null
    var addend: Point? = point
    var k = scalar
    while(k.signum() > 0) {
        if(k.testBit(0)) {
            result = add(result, addend)
        }
        addend = add(addend, addend)
        k = k.shiftRight(1)
    }
    return result
}

private fun add(a: Point?, b: Point?): Point? {
    if(a == null) return b
    if(b == null) return a
    val slope: BigInteger
    if(a.x == b.x) {
        if((a.y + b.y).mod(P).signum() == 0) {
            return null // point at infinity
        }
        slope = (BigInteger.valueOf(3) * a.x * a.x).mod(P) * (BigInteger.TWO * a.y).modInverse(P)
    } else {
        slope = (b.y - a.y).mod(P) * (b.x - a.x).mod(P).modInverse(P)
    }
    val m = slope.mod(P)
    val x = (m * m - a.x - b.x).mod(P)
    val y = (m * (a.x - x) - a.y).mod(P)
    return Point(x, y)
}
