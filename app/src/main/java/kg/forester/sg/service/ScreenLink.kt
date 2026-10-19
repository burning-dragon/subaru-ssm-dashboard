package kg.forester.sg.service

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import kg.forester.sg.data.ScreenInfo
import kg.forester.sg.data.UsbScreen
import kg.forester.sg.ssm.SsmTrace
import kg.forester.sg.ui.ScreenRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The colour screen on its own USB (an ESP32-S3, Espressif's vendor id): asks who it is, then draws the chosen page
 * as often as it keeps up and sends only what changed (hardware/usbscreen/usbscreen.ino). Its BOOT button turns pages.
 */
object ScreenLink {
    /** Devices being flashed: their sessions stop, nobody opens them. */
    private val flashing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Devices with a session running. */
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val flashLeases = java.util.concurrent.ConcurrentHashMap<String, UsbOwnership.Lease>()

    fun isScreen(vid: Int) = vid == 0x303A

    fun pauseForFlash(device: String, on: Boolean) {
        if (on) flashing += device else flashing -= device
        UsbDeviceRegistry.flash(device, on)
        if (on) {
            if (!flashLeases.containsKey(device)) flashLeases[device] = UsbDeviceRegistry.awaitFlash(device)
        } else flashLeases.remove(device)?.close()
    }

    /** Every screen on USB gets its own session: two or three can be on at once. */
    fun start(scope: CoroutineScope, ctx: Context, usb: UsbManager, askPermission: (android.hardware.usb.UsbDevice) -> Unit) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                UsbDeviceRegistry.refresh(usb)
                val found = runCatching { SensorBoxLink.drivers(usb).filter { isScreen(it.device.vendorId) } }.getOrDefault(emptyList())
                UsbScreen.status.value = if (found.isEmpty()) "Экран не найден на USB" else ""
                for (d in found) {
                    val n = d.device.deviceName
                    if (n in active || n in flashing || !UsbDeviceRegistry.available(d.device, UsbDeviceRegistry.SCREEN)) continue
                    if (!usb.hasPermission(d.device)) {
                        UsbScreen.status.value = "Разрешите доступ к экрану в окне Android"
                        askPermission(d.device)
                        continue
                    }
                    val lease = UsbDeviceRegistry.claim(d.device, UsbDeviceRegistry.SCREEN) ?: continue
                    active += n
                    scope.launch(Dispatchers.IO) {
                        try { runCatching { session(ctx, usb, d, lease) }.onFailure { SsmTrace.log("screen $n: $it") } }
                        finally { lease.close(); active -= n }
                    }.invokeOnCompletion { lease.close(); active -= n }
                }
                delay(2000)
            }
        }
    }

    /** Lines from the board, split as they come; K/E acknowledge a picture, the rest are its own news. */
    private class Lines(val port: UsbSerialPort) {
        private val buf = ByteArray(512)
        private val line = StringBuilder()
        private val ready = ArrayDeque<String>()

        fun next(timeoutMs: Int): String? {
            val end = System.currentTimeMillis() + timeoutMs
            while (ready.isEmpty()) {
                val left = (end - System.currentTimeMillis()).toInt()
                if (left <= 0) return null
                val n = port.read(buf, left.coerceIn(10, 200))
                for (i in 0 until n) {
                    val c = buf[i].toInt().toChar()
                    if (c == '\n' || c == '\r') { if (line.isNotEmpty()) { ready.addLast(line.toString()); line.clear() } }
                    else if (line.length < 200) line.append(c)
                }
            }
            return ready.removeFirst()
        }
    }

    private suspend fun session(ctx: Context, usb: UsbManager, d: UsbSerialDriver, lease: UsbOwnership.Lease) {
        val dev = d.device.deviceName
        val conn = usb.openDevice(d.device) ?: return
        lease.interrupt = { runCatching { conn.close() }; Unit }
        val port = d.ports[0]
        try {
            if (!lease.permitted) return
            port.open(conn)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // DTR and RTS stay low: on this USB they are the reset and boot lines
            val lines = Lines(port)
            var info: ScreenInfo? = null
            repeat(4) {
                if (info != null) return@repeat
                port.write("?\n".toByteArray(), 300)
                val end = System.currentTimeMillis() + 800
                while (info == null && System.currentTimeMillis() < end) info = lines.next(200)?.let { ScreenInfo.parse(it) }
            }
            val inf = info ?: run { UsbScreen.status.value = "Плата ESP32 на USB не отвечает как экран: прошейте её"; Thread.sleep(3000); return }
            SsmTrace.log("screen: ${inf.board} ${inf.w}x${inf.h} fw ${inf.fw} id ${inf.id}")
            UsbScreen.attach(inf, dev)
            val id = inf.id.ifEmpty { "dev:$dev" }
            try { loop(ctx, port, lines, inf, id, dev, lease) } finally { UsbScreen.detach(id) }
        } finally {
            runCatching { port.close() }
            runCatching { conn.close() }
        }
    }

    private suspend fun loop(ctx: Context, port: UsbSerialPort, lines: Lines, inf: ScreenInfo, id: String, dev: String, lease: UsbOwnership.Lease) {
        var prev: IntArray? = null
        var drawn = Triple(-1, -1, "")
        var bright = -1
        var turnedAt = System.currentTimeMillis()
        var frames = 0
        var countedAt = System.currentTimeMillis()
        var lastSend = 0L
        fun news(l: String) {
            when (l) {
                "N" -> { UsbScreen.next(id); turnedAt = System.currentTimeMillis() }
                "Y R" -> prev = null
            }
        }
        while (dev !in flashing && lease.permitted && kotlinx.coroutines.currentCoroutineContext().isActive) {
            val t0 = System.currentTimeMillis()
            val cfg = UsbScreen.cfgOf(id)
            if (cfg.brightness != bright) { bright = cfg.brightness; port.write("B $bright\n".toByteArray(), 300) }
            if (cfg.switchS > 0 && t0 - turnedAt > cfg.switchS * 1000L) { UsbScreen.next(id); turnedAt = t0 }
            val m = ScreenRenderer.ctxNow()
            val pageIx = ScreenRenderer.pageToShow(cfg, UsbScreen.pageOf(id), m)
            val page = cfg.pages.getOrNull(pageIx) ?: cfg.pages.first()
            // the board's own size is the truth; the turn decides which way up the page is drawn
            val (cw, ch) = if (cfg.rotation % 2 == 1) inf.h to inf.w else inf.w to inf.h
            val key = Triple(pageIx, cfg.rotation, page.toString())
            if (key != drawn) { drawn = key; prev = null }
            val bmp = ScreenRenderer.toPanel(ScreenRenderer.render(ctx, page, cw, ch, inf.round, m), cfg.rotation)
            val px = IntArray(inf.w * inf.h)
            bmp.getPixels(px, 0, inf.w, 0, 0, inf.w, inf.h)
            for (i in px.indices) px[i] = ScreenFrames.rgb565(px[i])
            val rects = ScreenFrames.dirty(prev, px, inf.w, inf.h)
            var ok = true
            for (r in rects) {
                val packed = ScreenFrames.pack(px, inf.w, r)
                port.write("P ${r.x} ${r.y} ${r.w} ${r.h} ${packed.size}\n".toByteArray() + packed, 2000)
                // wait for this picture's answer; the button and redraw requests may come in between
                var ack: String? = null
                val end = System.currentTimeMillis() + 2000
                while (ack == null && System.currentTimeMillis() < end) {
                    val l = lines.next(200) ?: continue
                    if (l == "K" || l == "E") ack = l else news(l)
                }
                if (ack != "K") { ok = false; break }
            }
            if (rects.isNotEmpty()) lastSend = System.currentTimeMillis()
            // a lost answer or a refused picture: start again from black
            prev = if (ok) px else { port.write("Z\n".toByteArray(), 300); null }
            // nothing changed for a while: tell the board the app is still here, or it shows NO LINK
            if (ok && rects.isEmpty() && System.currentTimeMillis() - lastSend > 2000) { port.write("H\n".toByteArray(), 300); lastSend = System.currentTimeMillis() }
            frames++
            val now = System.currentTimeMillis()
            if (now - countedAt >= 2000) { UsbScreen.fps.value = UsbScreen.fps.value + (id to frames * 1000f / (now - countedAt)); frames = 0; countedAt = now }
            // the board's button between frames
            val wait = (1000L / cfg.fps.coerceIn(2, 30) - (now - t0)).coerceAtLeast(5)
            val until = now + wait
            while (System.currentTimeMillis() < until) lines.next((until - System.currentTimeMillis()).toInt().coerceAtLeast(1))?.let(::news)
        }
    }
}
