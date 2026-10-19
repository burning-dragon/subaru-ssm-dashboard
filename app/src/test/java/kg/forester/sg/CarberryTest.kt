package kg.forester.sg

import kg.forester.sg.ssm.RomRaider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CarberryTest {
    private fun asset(name: String) = File("src/main/assets/$name").readText()
    private fun params() = RomRaider.parseCarberry(asset("carberry42_logger.json"))

    @Test fun profileIsExplicitAndRestrictedToEngineId() {
        assertTrue(RomRaider.useCarberry("carberry42", "3d04ea4605", false))
        assertFalse(RomRaider.useCarberry("stock", "3D04EA4605", false))
        assertFalse(RomRaider.useCarberry("carberry42", "3D04EA4705", false))
        assertFalse(RomRaider.useCarberry("carberry42", "3D04EA4605", true))
    }

    @Test fun allChannelsParseAndNewIdsCannotCollideWithStock() {
        val ps = params()
        assertEquals(67, ps.size)
        assertEquals(67, ps.map { it.id }.toSet().size)
        assertEquals(35, ps.count { it.id.startsWith("CB42_") })
        assertTrue(ps.all { it.expr != null && it.addr in 1..0xFFFFFF && it.len in listOf(1, 2, 4) })
        assertFalse(ps.any { it.id == "E2" || it.id == "E153" })
        assertTrue(ps.all { it.expr!!.refs.isEmpty() })
    }

    @Test fun commonChannelsKeepStockAddressesAndUnits() {
        val original = JSONObject(asset("romraider_ssm.json")).getJSONArray("ecuparams")
        val stock = (0 until original.length()).map { original.getJSONObject(it) }.associateBy { it.getString("id") }
        for (p in params().filterNot { it.id.startsWith("CB42_") }) {
            val old = stock.getValue(p.id)
            val a = old.getJSONObject("r").getJSONArray("3D04EA4605")
            assertEquals(a.getInt(0), p.addr)
            assertEquals(a.getInt(1), p.len)
            assertEquals(old.getJSONArray("c").getJSONArray(0).getString(0), p.units)
        }
    }

    @Test fun decodeCarberrySpecificSyntheticByteVectors() {
        val all = params()
        fun decode(id: String, bytes: ByteArray): Double {
            val p = all.single { it.id == id }
            return RomRaider.decode(listOf(p), p.addresses, bytes, all).getValue(id)
        }
        // Synthetic protocol vectors from the XML formulas, not a hardware recording.
        assertEquals(1.0, decode("CB42_E153", byteArrayOf(0x20, 0)), 1e-9)
        assertEquals(50.0, decode("CB42_E176", byteArrayOf(0x80.toByte(), 0)), 1e-9)
        assertEquals(50.0, decode("CB42_E182", byteArrayOf(0x80.toByte(), 0)), 1e-9)
        assertEquals(100.0, decode("CB42_E158", byteArrayOf(0x80.toByte(), 0)), 1e-9)
        // 16-bit ECU IAM uses 0..16 here; EngineGuard normalizes it for display.
        assertEquals(16.0, decode("E1", byteArrayOf(16)), 1e-9)
    }
}
