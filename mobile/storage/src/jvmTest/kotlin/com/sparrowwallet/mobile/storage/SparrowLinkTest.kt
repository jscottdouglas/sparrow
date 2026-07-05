package com.sparrowwallet.mobile.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SparrowLinkTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun frameRoundTrip() {
        val out = ByteArrayOutputStream()
        SparrowLink.writeFrame(DataOutputStream(out), key, "hello wallet".encodeToByteArray())
        val read = SparrowLink.readFrame(DataInputStream(ByteArrayInputStream(out.toByteArray())), key)
        assertContentEquals("hello wallet".encodeToByteArray(), read)
    }

    @Test
    fun wrongKeyRejected() {
        val out = ByteArrayOutputStream()
        SparrowLink.writeFrame(DataOutputStream(out), key, "secret".encodeToByteArray())
        val otherKey = ByteArray(32) { (it + 1).toByte() }
        assertFailsWith<SparrowLink.LinkException> {
            SparrowLink.readFrame(DataInputStream(ByteArrayInputStream(out.toByteArray())), otherKey)
        }
    }

    @Test
    fun parsesQrPayload() {
        val qr = "sparrowlink1;m=push;h=192.168.1.10,192.168.42.5;p=38929;k=" +
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key)
        val pairing = SparrowLink.parseQr(qr)
        assertEquals("push", pairing.mode)
        assertEquals(listOf("192.168.1.10", "192.168.42.5"), pairing.hosts)
        assertEquals(38929, pairing.port)
        assertContentEquals(key, pairing.key)
    }

    @Test
    fun rejectsForeignQr() {
        assertFailsWith<SparrowLink.LinkException> { SparrowLink.parseQr("ltc1qabc") }
        assertFailsWith<SparrowLink.LinkException> { SparrowLink.parseQr("sparrowlink1;m=push;h=1.2.3.4;p=1;k=short") }
    }

    @Test
    fun parsesSingleWalletMessage() {
        val b64 = java.util.Base64.getEncoder().encodeToString("FILEBYTES".toByteArray())
        val payload = SparrowLink.parseWalletMessage("""{"op":"wallet","name":"Pub","file":"$b64"}""")
        assertEquals("Pub", payload.wallet.name)
        assertContentEquals("FILEBYTES".toByteArray(), payload.wallet.file)
        assertEquals(null, payload.linked)
    }

    @Test
    fun parsesLinkedPairMessage() {
        val pub = java.util.Base64.getEncoder().encodeToString("PUB".toByteArray())
        val priv = java.util.Base64.getEncoder().encodeToString("PRIV".toByteArray())
        val payload = SparrowLink.parseWalletMessage(
            """{"op":"wallet","name":"Pub","file":"$pub","linked":{"name":"Priv","file":"$priv"}}"""
        )
        assertEquals("Pub", payload.wallet.name)
        assertEquals("Priv", payload.linked!!.name)
        assertContentEquals("PRIV".toByteArray(), payload.linked!!.file)
    }
}
