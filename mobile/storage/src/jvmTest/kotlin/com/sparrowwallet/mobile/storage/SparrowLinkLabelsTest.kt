package com.sparrowwallet.mobile.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Label sync must never lose data: fill-in-the-blanks both ways, conflicting labels
 * kept locally and counted, empty labels ignored.
 */
class SparrowLinkLabelsTest {
    @Test
    fun mergeFillsBlanksBothWays() {
        val merge = SparrowLink.mergeLabels(
            local = mapOf("aa" to "phone label", "bb" to "same"),
            remote = mapOf("bb" to "same", "cc" to "desktop label")
        )
        assertEquals(mapOf("cc" to "desktop label"), merge.toLocal)
        assertEquals(mapOf("aa" to "phone label"), merge.toRemote)
        assertEquals(0, merge.conflicts)
    }

    @Test
    fun conflictingLabelsAreKeptAndCounted() {
        val merge = SparrowLink.mergeLabels(
            local = mapOf("aa" to "phone says X"),
            remote = mapOf("aa" to "desktop says Y")
        )
        assertTrue(merge.toLocal.isEmpty())
        assertTrue(merge.toRemote.isEmpty())
        assertEquals(1, merge.conflicts)
    }

    @Test
    fun emptyLabelsAreIgnored() {
        val merge = SparrowLink.mergeLabels(
            local = mapOf("aa" to ""),
            remote = mapOf("aa" to "desktop label", "bb" to "")
        )
        assertEquals(mapOf("aa" to "desktop label"), merge.toLocal)
        assertTrue(merge.toRemote.isEmpty())
        assertEquals(0, merge.conflicts)
    }

    @Test
    fun parseLabelsMessageRoundTrip() {
        val wallets = SparrowLink.parseLabelsMessage(
            """{"op":"labels","wallets":[
                {"name":"Main","fingerprint":"73C5DA0A","labels":{"ab12":"rent","cd34":"coffee"}},
                {"name":"Main MWEB","fingerprint":"deadbeef","labels":{}}
            ]}"""
        )
        assertEquals(2, wallets.size)
        assertEquals("73c5da0a", wallets[0].fingerprint)
        assertEquals(mapOf("ab12" to "rent", "cd34" to "coffee"), wallets[0].labels)
        assertEquals("deadbeef", wallets[1].fingerprint)
        assertTrue(wallets[1].labels.isEmpty())
    }

    @Test
    fun parseRejectsWrongOp() {
        assertFailsWith<SparrowLink.LinkException> {
            SparrowLink.parseLabelsMessage("""{"op":"wallet","name":"x","file":""}""")
        }
    }

    @Test
    fun labelsQrModeAccepted() {
        val pairing = SparrowLink.parseQr(
            "sparrowlink1;m=labels;h=192.168.1.2;p=4242;k=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        )
        assertEquals("labels", pairing.mode)
    }
}
