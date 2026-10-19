package kg.forester.sg

import kg.forester.sg.devices.innovate.*
import kg.forester.sg.data.*
import kg.forester.sg.service.UsbOwnership
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InnovateTest {
    private fun frame(status: Int = 0, raw: Int = 500, factor: Int = 147) = byteArrayOf(
        0xB2.toByte(), 0x82.toByte(), (0x42 or (status shl 2) or (factor shr 7)).toByte(),
        (factor and 127).toByte(), (raw shr 7).toByte(), (raw and 127).toByte())

    @Test fun everySplitAndBytewise() {
        for (split in 0..6) {
            val decoder = InnovateDecoder()
            val bytes = frame()
            val results = decoder.feed(bytes.copyOfRange(0, split)) + decoder.feed(bytes.copyOfRange(split, bytes.size))
            assertEquals(1, results.size)
            assertEquals(14.7f, results.single().afr!!, 0.0001f)
        }
        val d = InnovateDecoder()
        val result = frame(raw = 300).flatMap { d.feed(byteArrayOf(it)) }
        assertEquals(11.76f, result.single().afr!!, 0.0001f)
    }

    @Test fun concatenatedFramesAndNoise() {
        val d = InnovateDecoder()
        val result = d.feed(byteArrayOf(0, 12, 127, 0x82.toByte()) + frame() + frame(raw = 300))
        assertEquals(2, result.size)
        assertEquals(0.8f, result.last().lambda!!, 0.0001f)
    }

    @Test fun allStatusesNeverManufactureReadings() {
        for (status in 0..7) {
            val r = InnovateDecoder().feed(frame(status)).single()
            assertEquals(InnovateStatus.entries[status], r.status)
            if (status != 0) { assertNull(r.afr); assertNull(r.lambda) }
        }
    }

    @Test fun lengthAndBitErrorsRecoverAtNextHeader() {
        for (size in listOf(0, 1, 3, 127)) {
            val bad = frame().also { it[1] = (128 or size).toByte() }
            assertEquals(1, InnovateDecoder().feed(bad + frame()).size)
        }
        for ((index, value) in listOf(0 to 0xF2, 1 to 2, 2 to 0xC3, 3 to 0x93, 4 to 0x43, 5 to 0xF4)) {
            val bad = frame().also { it[index] = value.toByte() }
            assertEquals("index $index", 1, InnovateDecoder().feed(bad + frame()).size)
        }
    }

    @Test fun truncatedFrameAndLongGarbageCannotPoisonNextFrame() {
        val d = InnovateDecoder()
        assertTrue(d.feed(frame().copyOf(4)).isEmpty())
        assertEquals(1, d.feed(frame()).size)
        assertTrue(d.feed(ByteArray(20000) { 0xFF.toByte() }).isEmpty())
        assertEquals(1, d.feed(frame()).size)
    }

    @Test fun instancesAndResetAreIndependent() {
        val a = InnovateDecoder(); val b = InnovateDecoder()
        a.feed(frame().copyOf(3))
        assertTrue(b.feed(frame().copyOfRange(3, 6)).isEmpty())
        assertEquals(1, a.feed(frame().copyOfRange(3, 6)).size)
        b.feed(frame().copyOf(3)); b.reset()
        assertTrue(b.feed(frame().copyOfRange(3, 6)).isEmpty())
    }

    @Test fun gasolineEquivalentLambdaAndZeroFactor() {
        val r = InnovateDecoder().feed(frame(factor = 100)).single()
        assertEquals(10f / 14.7f, r.lambda!!, 0.0001f)
        assertNull(InnovateDecoder().feed(frame(factor = 0)).single().afr)
    }

    @Test fun freshnessAndLinkStateGateReadings() {
        val sample = WidebandSample("usb", 50000, 1000, InnovateReading(InnovateStatus.VALID, 500, 147))
        val live = WidebandState(WidebandLinkState.RECEIVING, sample = sample)
        assertNotNull(live.afr(1000))
        assertNotNull(live.afr(2500))
        assertNull(live.afr(2501)); assertEquals("STALE", live.status(2501))
        assertNull(live.afr(999))
        for (state in WidebandLinkState.entries.filter { it != WidebandLinkState.RECEIVING && it != WidebandLinkState.DEMO }) {
            assertNull(live.copy(link = state).afr(1000))
        }
        assertNull(live.copy(sample = sample.copy(reading = sample.reading.copy(status = InnovateStatus.WARMING))).afr(1000))
    }

    @Test fun reservationsRevokeButDoNotStealOpenDevices() {
        val r = UsbOwnership()
        val ecu = r.claim("usb", "ecu")!!
        var closed = false
        ecu.interrupt = { closed = true }
        r.reserve("usb", "innovate")
        assertTrue(closed); assertFalse(ecu.permitted)
        assertNull(r.claim("usb", "innovate"))
        ecu.close()
        assertNull(r.claim("usb", "ecu"))
        val innovate = r.claim("usb", "innovate")!!
        ecu.close() // stale token must not release the new owner
        assertNull(r.claim("usb", "innovate"))
        innovate.close()
        r.reserve("usb", null)
        assertNotNull(r.claim("usb", "ecu"))
    }

    @Test fun concurrentClaimsHaveOneWinner() {
        val r = UsbOwnership()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (1..8).map { i -> pool.submit<UsbOwnership.Lease?> { start.await(); r.claim("usb", "owner$i") } }
            start.countDown()
            val leases = futures.mapNotNull { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, leases.size)
            leases.single().close()
            assertTrue(r.available("usb", "next"))
        } finally { pool.shutdownNow() }
    }

    @Test fun bindingHandlesIdenticalAdaptersAndMissingPermission() {
        val cfg = WidebandConfig(true, 1027, 24597)
        assertTrue(cfg.reserves("a", 1027, 24597, "", null))
        assertTrue(cfg.reserves("b", 1027, 24597, "", null)) // unresolved: reserve both
        assertTrue(cfg.reserves("a", 1027, 24597, "", "a"))
        assertFalse(cfg.reserves("b", 1027, 24597, "", "a"))
        assertFalse(cfg.reserves("a", 1027, 1, "", "a"))
        val serial = cfg.copy(serial = "lc2")
        assertTrue(serial.reserves("a", 1027, 24597, "", null)) // no permission yet
        assertTrue(serial.reserves("a", 1027, 24597, "lc2", null))
        assertFalse(serial.reserves("b", 1027, 24597, "ecu", null))
        assertFalse(cfg.copy(enabled = false).reserves("a", 1027, 24597, "", "a"))
    }

    @Test fun csvSeparatesEcuAndWidebandAndBlanksStaleValues() {
        val ecu = kg.forester.sg.ssm.EcuSnapshot(lambda = 1f)
        val sample = WidebandSample("usb", 50000, 1000, InnovateReading(InnovateStatus.VALID, 300, 147))
        val state = WidebandState(WidebandLinkState.RECEIVING, sample = sample)
        fun row(at: Long) = kg.forester.sg.service.LogRow(ecu, null, 0.0, null,
            kg.forester.sg.service.GuardState(), state, at)
        fun column(key: String, at: Long) = kg.forester.sg.service.LogColumns.all.single { it.key == key }.get(row(at))
        assertEquals("14.70", column("afr", 1000))
        assertEquals("11.76", column("wideband_afr", 1000))
        assertEquals("0.800", column("wideband_lambda", 1000))
        assertEquals("", column("wideband_afr", 3000))
        assertEquals("STALE", column("wideband_status", 3000))
        assertEquals("2000", column("wideband_age_ms", 3000))
    }
}
