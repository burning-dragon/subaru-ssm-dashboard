package kg.forester.sg.data

import kg.forester.sg.devices.innovate.InnovateReading
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

data class WidebandConfig(val enabled: Boolean = false, val vendor: Int = -1, val product: Int = -1,
                          val serial: String = "", val port: Int = 0) {
    fun reserves(name: String, vid: Int, pid: Int, deviceSerial: String, selected: String?): Boolean {
        if (!enabled || vendor != vid || product != pid) return false
        if (selected != null) return name == selected
        // Before USB permission, the serial may be inaccessible. Avoid probing a possible match.
        return serial.isEmpty() || deviceSerial.isEmpty() || serial == deviceSerial
    }
}

enum class WidebandLinkState { DISABLED, WAITING, PERMISSION, AMBIGUOUS, BUSY, CONNECTING, RECEIVING, STALE, ERROR, DEMO }

data class WidebandSample(val device: String, val timeMs: Long, val elapsedMs: Long, val reading: InnovateReading)

data class WidebandState(val link: WidebandLinkState = WidebandLinkState.DISABLED, val message: String = "Выключено",
                         val sample: WidebandSample? = null) {
    fun fresh(now: Long) = link in setOf(WidebandLinkState.RECEIVING, WidebandLinkState.DEMO) &&
        sample?.let { now - it.elapsedMs in 0..STALE_MS } == true
    fun afr(now: Long): Float? = if (fresh(now)) sample?.reading?.afr else null
    fun lambda(now: Long): Float? = if (fresh(now)) sample?.reading?.lambda else null
    fun status(now: Long): String = if (fresh(now)) sample!!.reading.status.name else
        if (sample != null && link == WidebandLinkState.RECEIVING) "STALE" else link.name
    companion object { const val STALE_MS = 1500L }
}

object Wideband {
    val config = MutableStateFlow(WidebandConfig())
    val state = MutableStateFlow(WidebandState())
    /** Only valid for the current attachment, never persisted as a stable USB identity. */
    @Volatile var selectedDevice: String? = null
    @Volatile var preview = false

    fun load() {
        val j = runCatching { JSONObject(Repo.settings.wideband) }.getOrElse { JSONObject() }
        config.value = WidebandConfig(j.optBoolean("enabled"), j.optInt("vendor", -1), j.optInt("product", -1),
            j.optString("serial", ""), j.optInt("port", 0).coerceAtLeast(0))
        selectedDevice = null
        preview = false
        state.value = WidebandState()
    }

    fun save(c: WidebandConfig, device: String? = selectedDevice) {
        selectedDevice = device
        preview = false
        config.value = c
        state.value = WidebandState(if (c.enabled) WidebandLinkState.WAITING else WidebandLinkState.DISABLED,
            if (c.enabled) "Ожидание подключения" else "Выключено")
        Repo.settings.wideband = JSONObject().put("enabled", c.enabled).put("vendor", c.vendor)
            .put("product", c.product).put("serial", c.serial).put("port", c.port).toString()
    }
}
