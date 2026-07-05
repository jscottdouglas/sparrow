package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.Scripts
import com.sparrowwallet.mobile.crypto.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The scripthash is what we send the server to query an address. It must match desktop
 * drongo exactly (ElectrumServer.getScriptHash), or balances silently return empty.
 * Pubkeys are drongo-test-seed receive[0] (golden-vectors.json); expected hashes were
 * computed independently as reverse(sha256(scriptPubKey)).
 */
class ScriptHashParityTest {
    private val p2pkhPub = "02742890a7ca5fb0581d030e717ee9bd4bd5331d66c7f129c5c490dcca4217a92b".hexToBytes()
    private val p2shPub = "025f21590d25cb07f0ad3b0ee5955630bff83298725622f9706f42926c7670c59c".hexToBytes()
    private val p2wpkhPub = "03671f0d58e210fe5ca61e8510796c93c88557f8de4498dde477623ac1c8f1584d".hexToBytes()

    @Test
    fun p2pkhScriptHashMatches() {
        assertEquals("6df1bae2d287aa63a42a31ee462d3d94134b33ded87479709fafd3ec77eddc02",
            ElectrumProtocol.scriptHash(Scripts.p2pkhOutput(p2pkhPub)))
    }

    @Test
    fun p2shP2wpkhScriptHashMatches() {
        assertEquals("6858a1ee03b3b08f24c92934e40b2712b975f1d5d2ab74e31ec99b8adf3bfe53",
            ElectrumProtocol.scriptHash(Scripts.p2shP2wpkhOutput(p2shPub)))
    }

    @Test
    fun p2wpkhScriptHashMatches() {
        assertEquals("b0c03dcf07b0e458e9c17a6cb3e9c2b27d168dccf7cb5801771198b78da949fc",
            ElectrumProtocol.scriptHash(Scripts.p2wpkhOutput(p2wpkhPub)))
    }
}
