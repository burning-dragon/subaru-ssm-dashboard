package kg.forester.sg.service

import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import kg.forester.sg.data.BoxFrame
import kg.forester.sg.data.BoxInfo
import kg.forester.sg.data.BoxKind
import kg.forester.sg.data.Repo
import kg.forester.sg.data.SensorBox
import kg.forester.sg.ssm.SsmTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * The SensorBox on USB next to the ECU cable. A Nano's CH340 is the same chip as many KKL cables, so a device is
 * taken for the box only when it answers «?» with «I SENSORBOX». The ECU loop and this one never hold the same
 * device: [claim] under one lock, the ECU loop skips what is [busy], this loop skips what the ECU has open.
 */
object SensorBoxLink {
    private val lock = Any()
    /** Devices being probed or read as the box, by USB device name. */
    private val busy = HashSet<String>()
    /** Devices asked once since they were plugged in: not asked again. */
    private val asked = HashSet<String>()
    /** The ECU loop's device while it has it open. */
    @Volatile var ecuDevice: String? = null
    @Volatile var ecuLease: UsbOwnership.Lease? = null
        private set
    /** The device the box last answered on: the one the firmware update goes to. */
    @Volatile var lastBoxDevice: String? = null
    /** The device being flashed (also as its bootloader): nobody else opens it meanwhile. */
    private var flashLease: UsbOwnership.Lease? = null
    @Volatile var flashDevice: String? = null
        set(value) {
            flashLease?.close(); flashLease = null
            field?.let { UsbDeviceRegistry.flash(it, false) }
            field = value
            value?.let {
                UsbDeviceRegistry.flash(it, true)
                flashLease = UsbDeviceRegistry.awaitFlash(it)
            }
        }
    @Volatile private var paused = false
    /** Lines for the box (output commands, heartbeats), written by the session loop. */
    private val outbox = java.util.concurrent.ConcurrentLinkedQueue<String>()
    /** Counts the times the box answered anew: the relay engine sets the outputs up again on each. */
    @Volatile var session = 0

    fun send(line: String) { if (outbox.size < 200) outbox.add(line) }

    /** A real box with outputs (firmware 3+) is on the cable. */
    fun canDrive() = SensorBox.info.value?.let { it.via.startsWith("USB") && it.fw >= 3 } == true

    /** The box can draw on displays (firmware 5+). */
    fun canDisplay() = SensorBox.info.value?.let { it.via.startsWith("USB") && it.fw >= 5 } == true

    /** A firmware update needs the board alone: the loop lets go of it ([on]), and asks again afterwards. */
    fun pauseForFlash(on: Boolean) {
        paused = on
        if (!on) synchronized(lock) { asked.clear() }
    }

    /** The usual serial chips plus SparkFun's Pro Micro, which the library's table doesn't list. */
    private val prober = com.hoho.android.usbserial.driver.UsbSerialProber(
        com.hoho.android.usbserial.driver.UsbSerialProber.getDefaultProbeTable().apply {
            listOf(0x9203, 0x9204, 0x9205, 0x9206).forEach { addProduct(0x1B4F, it, com.hoho.android.usbserial.driver.CdcAcmSerialDriver::class.java) }
            listOf(0x0043, 0x0042, 0x8036, 0x8037, 0x0036, 0x0037).forEach { addProduct(0x2A03, it, com.hoho.android.usbserial.driver.CdcAcmSerialDriver::class.java) }
            // the Caterina bootloader (Leonardo, Micro) a Pro Micro restarts into for a firmware update
            listOf(0x0036, 0x0037).forEach { addProduct(0x2341, it, com.hoho.android.usbserial.driver.CdcAcmSerialDriver::class.java) }
        })

    fun drivers(usb: UsbManager): List<UsbSerialDriver> = prober.findAllDrivers(usb)

    /** Arduino, arduino.org and SparkFun boards with their own USB (Uno R3, Mega, Leonardo, Micro, Pro Micro): never an ECU cable. */
    fun isArduino(vid: Int) = vid == 0x2341 || vid == 0x2A03 || vid == 0x1B4F

    /** What the ECU loop may use: the box and boards still being asked are left out (only with the box switched on). */
    fun forEcu(all: List<UsbSerialDriver>): List<UsbSerialDriver> {
        // the colour screen (an ESP32-S3) is never an ECU cable
        val drivers = all.filter { !ScreenLink.isScreen(it.device.vendorId) && UsbDeviceRegistry.available(it.device, UsbDeviceRegistry.ECU) }
        flashDevice?.let { f -> if (!SensorBox.enabled.value) return drivers.filter { it.device.deviceName != f } }
        if (!SensorBox.enabled.value) return drivers
        synchronized(lock) {
            return drivers.filter { it.device.deviceName !in busy && it.device.deviceName != flashDevice && !isArduino(it.device.vendorId) }
        }
    }

    /** The ECU loop takes [d]: the box loop leaves it alone. False when the box loop got there first. */
    fun claimForEcu(d: UsbSerialDriver): Boolean = synchronized(lock) {
        if (d.device.deviceName in busy || d.device.deviceName == flashDevice) false else {
            val lease = UsbDeviceRegistry.claim(d.device, UsbDeviceRegistry.ECU) ?: return@synchronized false
            ecuLease = lease
            ecuDevice = d.device.deviceName
            true
        }
    }

    fun releaseEcu() { ecuLease?.close(); ecuLease = null; ecuDevice = null }

    /** Every few seconds: ask a device nobody holds whether it is the box, read it while it is. */
    suspend fun loop(scope: CoroutineScope, usb: UsbManager, list: () -> List<UsbSerialDriver>, isAdapter: (UsbSerialDriver) -> Boolean,
                     askPermission: (android.hardware.usb.UsbDevice) -> Unit) {
        var demoT = 0f
        while (scope.isActive) {
            UsbDeviceRegistry.refresh(usb)
            if (Repo.demoOn.value) {
                demoT += 0.1f
                demoFrame(demoT)
                delay(100)
                continue
            }
            if (SensorBox.info.value?.via == "демо") { SensorBox.info.value = null; SensorBox.readings.value = emptyMap() }
            if (paused) { delay(300); continue }
            if (!SensorBox.enabled.value) { delay(1000); continue }
            val drivers = runCatching { list() }.getOrDefault(emptyList())
            val names = drivers.map { it.device.deviceName }.toSet()
            synchronized(lock) { asked.retainAll(names) } // unplugged: asked again when it comes back
            val candidate = synchronized(lock) {
                drivers.firstOrNull { d ->
                    val n = d.device.deviceName
                    n !in busy && n !in asked && n != ecuDevice && !isAdapter(d) && !ScreenLink.isScreen(d.device.vendorId) &&
                        UsbDeviceRegistry.available(d.device, UsbDeviceRegistry.BOX)
                }?.also { busy += it.device.deviceName; asked += it.device.deviceName }
            }
            if (candidate == null) {
                if (SensorBox.info.value == null) SensorBox.status.value =
                    if (drivers.isEmpty()) "Плата не найдена: подключите её к USB магнитолы" else "Плата не отвечает: залита ли прошивка SensorBox?"
                delay(3000)
                continue
            }
            val n = candidate.device.deviceName
            if (!usb.hasPermission(candidate.device)) {
                askPermission(candidate.device)
                SensorBox.status.value = "Разрешите доступ к USB-устройству в окне Android"
                synchronized(lock) { busy -= n; asked -= n }
                delay(4000)
                continue
            }
            val lease = UsbDeviceRegistry.claim(candidate.device, UsbDeviceRegistry.BOX)
            if (lease == null) {
                synchronized(lock) { busy -= n; asked -= n }
                delay(300); continue
            }
            val wasBox = try { session(usb, candidate, lease) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { SsmTrace.log("SensorBox: $e"); false }
                finally { lease.close(); synchronized(lock) { busy -= n } }
            // the box that went quiet is asked again; anything else only after it is plugged in again
            synchronized(lock) { busy -= n; if (wasBox) asked -= n }
            delay(1000)
        }
    }

    /** Open, ask, and read for as long as the box answers; true when it was the box. */
    private suspend fun session(usb: UsbManager, d: UsbSerialDriver, lease: UsbOwnership.Lease): Boolean {
        val conn = usb.openDevice(d.device) ?: return false
        lease.interrupt = { runCatching { conn.close() }; Unit }
        var known = false
        val port = d.ports[0]
        val id = "%04X:%04X".format(d.device.vendorId, d.device.productId)
        try {
            if (!lease.permitted) return false
            port.open(conn)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            runCatching { port.dtr = true } // a 32U4 sends nothing until the host raises DTR; a Nano restarts on it
            delay(2200) // a Nano's bootloader
            val buf = ByteArray(512)
            val line = StringBuilder()
            var lastData = System.currentTimeMillis()
            var askedAt = 0L
            while (true) {
                if (paused || !lease.permitted || !kotlinx.coroutines.currentCoroutineContext().isActive || !SensorBox.enabled.value) return known
                if (known) while (true) { val l = outbox.poll() ?: break; port.write("$l\n".toByteArray(Charsets.ISO_8859_1), 300) }
                val now = System.currentTimeMillis()
                if (now - askedAt > 3000 && now - lastData > 1500) {
                    port.write("?\r\n".toByteArray(), 300)
                    askedAt = now
                }
                // not the box (or it went quiet): let the device go
                if (now - lastData > if (known) 5000 else 3500) {
                    if (known) SensorBox.status.value = "Плата перестала отвечать"
                    return known
                }
                val n = port.read(buf, 200)
                for (i in 0 until n) {
                    val c = buf[i].toInt().toChar()
                    if (c == '\n' || c == '\r') {
                        if (line.isNotEmpty()) {
                            val s = line.toString()
                            line.clear()
                            SensorBox.parseInfo(s, "USB $id")?.let {
                                if (!known) { SsmTrace.log("SensorBox: $s"); outbox.clear(); session++ }
                                known = true
                                lastBoxDevice = d.device.deviceName
                                lastData = System.currentTimeMillis()
                                SensorBox.info.value = it
                                SensorBox.status.value = ""
                            }
                            if (known) SensorBox.parseFrame(s)?.let { f -> lastData = System.currentTimeMillis(); SensorBox.apply(f) }
                            if (known && s == "Y R") DisplayEngine.redraw()
                        }
                    } else if (line.length < 400) line.append(c)
                }
            }
        } finally {
            if (SensorBox.info.value?.via == "USB $id") { SensorBox.info.value = null; SensorBox.readings.value = emptyMap() }
            runCatching { port.close() }
            runCatching { conn.close() }
        }
    }

    // ---- the demo: a box that follows the made-up drive, its readings run through the same conversion

    private var oilT = Float.NaN
    private var egt = 300f

    private fun demoFrame(t: Float) {
        val b = SensorBox.board.value
        if (SensorBox.info.value?.via != "демо") SensorBox.info.value = BoxInfo(b.id, "DEMO", 10, b.inputs, "демо", kg.forester.sg.data.BOX_FIRMWARE)
        val e = Repo.live.value
        val rpm = (e.rpm ?: 800).toFloat()
        val maf = e.mafGs ?: 3f
        val c = (e.coolantC ?: 85).toFloat()
        if (oilT.isNaN()) oilT = c + 9f // the demo drive starts warm
        oilT += ((c + 10f + maf * 0.3f).coerceAtMost(c + 35f) - oilT) * 0.0015f
        // a healthy car: well under the 900° warning
        egt += ((280f + maf * 5f + rpm * 0.03f).coerceAtMost(780f) - egt) * 0.03f
        val oilP = ((0.9f + rpm / 1000f * 0.75f) * (1f + (90f - oilT).coerceIn(0f, 60f) / 120f)).coerceAtMost(6.5f)
        val afr = e.afr ?: 14.7f
        val raw = HashMap<String, Float>()
        var tc: Float? = null
        var hz: Float? = null
        for (s in SensorBox.slots.value) {
            val noise = sin(t * 7.3f + s.input.hashCode()) * 0.004f
            val v = when (s.role) {
                "oil" -> oilT
                "oilP" -> oilP
                "afr" -> afr + sin(t * 3f) * 0.15f
                "egt" -> egt
                else -> when (s.p.kind) { BoxKind.PRESSURE -> 2.8f + maf * 0.01f; BoxKind.FREQ -> 10f; else -> s.a + s.b * 2.5f }
            }
            when (s.p.kind) {
                BoxKind.TC -> tc = v
                BoxKind.FREQ -> hz = (v - s.a) / s.b
                else -> raw[s.input] = (SensorBox.rawFor(s, v) * (1f + noise)).coerceIn(0f, 1023f)
            }
        }
        b.analog.forEach { if (it !in raw) raw[it] = 0f }
        // the demo box does what the rules ask, so the output screen comes alive too
        val st = kg.forester.sg.data.Relays.status.value
        val outs = (1..(kg.forester.sg.data.Relays.channels.value.maxOfOrNull { it.ch } ?: 0)).joinToString("") { ch ->
            st[ch]?.let { if (!it.want) "0" else if (it.blink) "b" else "1" } ?: "-"
        }
        SensorBox.apply(BoxFrame(System.currentTimeMillis(), 5000, raw, tc, 0, hz ?: 0f, outs, false))
    }

    /** Starts the box loop next to the ECU one. */
    fun start(scope: CoroutineScope, usb: UsbManager, list: () -> List<UsbSerialDriver>, isAdapter: (UsbSerialDriver) -> Boolean,
              askPermission: (android.hardware.usb.UsbDevice) -> Unit) {
        scope.launch(Dispatchers.IO) { loop(scope, usb, list, isAdapter, askPermission) }
    }
}
