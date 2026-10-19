package kg.forester.sg.service

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import kg.forester.sg.data.*
import kg.forester.sg.devices.innovate.*
import kg.forester.sg.ssm.SsmTrace
import kotlinx.coroutines.*

/** Independent of the ECU poll loop; one explicit LC/MTXL binding, one decoder per session. */
object InnovateLink {
    fun start(scope: CoroutineScope, usb: UsbManager, askPermission: (UsbDevice) -> Unit) = scope.launch {
        try {
            while (isActive) {
                UsbDeviceRegistry.refresh(usb)
                val cfg = Wideband.config.value
                if (Wideband.preview) {
                    val status = InnovateStatus.entries[((SystemClock.elapsedRealtime() / 3000) % 8).toInt()]
                    publish("demo", InnovateReading(status, 500, 147), true)
                    delay(100)
                    continue
                }
                if (!cfg.enabled) { state(WidebandLinkState.DISABLED, "Выключено"); delay(500); continue }
                if (Repo.demoOn.value || Repo.linkDemo.value) {
                    state(WidebandLinkState.WAITING, "В деморежиме USB ШДК не открывается. Можно включить тест статусов.")
                    delay(500); continue
                }
                val all = UsbDeviceRegistry.drivers(usb).filter {
                    it.device.vendorId == cfg.vendor && it.device.productId == cfg.product
                }
                val selected = Wideband.selectedDevice
                val candidates = all.filter { d ->
                    if (selected != null) d.device.deviceName == selected
                    else cfg.serial.isEmpty() || UsbDeviceRegistry.serial(d.device) == cfg.serial
                }
                // A stored serial needs permission before it can be matched.
                val withoutPermission = all.firstOrNull { !usb.hasPermission(it.device) }
                if (withoutPermission != null && (candidates.isEmpty() || cfg.serial.isNotEmpty())) {
                    state(WidebandLinkState.PERMISSION, "Разрешите доступ к USB-адаптеру ШДК")
                    askPermission(withoutPermission.device); delay(4000); continue
                }
                if (candidates.isEmpty()) { state(WidebandLinkState.WAITING, "Подключите выбранный USB-адаптер ШДК"); delay(1000); continue }
                if (candidates.size > 1) { state(WidebandLinkState.AMBIGUOUS, "Найдено несколько одинаковых адаптеров: выберите нужный в настройках"); delay(1000); continue }
                val d = candidates.single()
                if (!usb.hasPermission(d.device)) {
                    state(WidebandLinkState.PERMISSION, "Разрешите доступ к USB-адаптеру ШДК")
                    askPermission(d.device); delay(4000); continue
                }
                val serial = UsbDeviceRegistry.serial(d.device)
                if (cfg.serial.isEmpty() && serial.isNotEmpty()) {
                    Wideband.save(cfg.copy(serial = serial), d.device.deviceName)
                    continue
                }
                if (cfg.port !in d.ports.indices) { state(WidebandLinkState.ERROR, "Выбранный порт отсутствует: выберите адаптер заново"); delay(1000); continue }
                val lease = UsbDeviceRegistry.claim(d.device, UsbDeviceRegistry.INNOVATE)
                if (lease == null) { state(WidebandLinkState.BUSY, "Ожидаю освобождения USB-адаптера"); delay(500); continue }
                try {
                    session(usb, d, cfg, selected, lease)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    SsmTrace.log("Innovate: $e")
                    state(WidebandLinkState.ERROR, e.message ?: "Ошибка USB ШДК")
                } finally { lease.close() }
                delay(500)
            }
        } finally { state(WidebandLinkState.DISABLED, "Сервис остановлен") }
    }

    private suspend fun session(usb: UsbManager, d: UsbSerialDriver, cfg: WidebandConfig, selected: String?, lease: UsbOwnership.Lease) {
        state(WidebandLinkState.CONNECTING, "Подключение LC/MTXL · 19200 8N1")
        val conn = usb.openDevice(d.device) ?: error("Не удалось открыть USB-адаптер")
        lease.interrupt = { runCatching { conn.close() }; Unit }
        val port = d.ports[cfg.port]
        try {
            if (!lease.permitted) return
            port.open(conn)
            port.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            val decoder = InnovateDecoder()
            val buffer = ByteArray(512)
            var lastFrame = SystemClock.elapsedRealtime()
            state(WidebandLinkState.CONNECTING, "Ожидание пакетов Innovate")
            while (currentCoroutineContext().isActive && lease.permitted && Wideband.config.value == cfg &&
                Wideband.selectedDevice == selected && !Wideband.preview && !Repo.demoOn.value && !Repo.linkDemo.value) {
                val count = port.read(buffer, 200)
                if (Wideband.config.value != cfg || Wideband.selectedDevice != selected || Wideband.preview) break
                for (reading in decoder.feed(buffer, count.coerceAtLeast(0))) {
                    lastFrame = SystemClock.elapsedRealtime()
                    publish(d.device.deviceName, reading)
                }
                val age = SystemClock.elapsedRealtime() - lastFrame
                if (age > WidebandState.STALE_MS) {
                    Wideband.state.value = Wideband.state.value.copy(link = WidebandLinkState.STALE, message = "Нет свежих пакетов Innovate")
                }
                if (age > 5000) break
                // Explicit cancellation point even on a USB stream which never blocks.
                yield()
            }
        } finally {
            runCatching { port.close() }
            runCatching { conn.close() }
            state(WidebandLinkState.WAITING, "Соединение ШДК закрыто")
        }
    }

    private fun state(link: WidebandLinkState, message: String) { Wideband.state.value = WidebandState(link, message) }
    private fun publish(device: String, reading: InnovateReading, demo: Boolean = false) {
        Wideband.state.value = WidebandState(if (demo) WidebandLinkState.DEMO else WidebandLinkState.RECEIVING,
            (if (demo) "Тест · " else "") + reading.status.label,
            WidebandSample(device, System.currentTimeMillis(), SystemClock.elapsedRealtime(), reading))
    }
}
