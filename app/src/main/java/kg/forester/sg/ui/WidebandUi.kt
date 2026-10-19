package kg.forester.sg.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kg.forester.sg.data.*
import kg.forester.sg.service.EcuService
import kg.forester.sg.service.UsbDeviceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun ExtraSensorSettings() {
    var wideband by remember { mutableStateOf(false) }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("SensorBox", !wideband, { wideband = false })
            Chip("ШДК по USB", wideband, { wideband = true })
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f)) { if (wideband) WidebandSettings() else SensorBoxSettings() }
    }
}

@Composable
fun WidebandSettings() {
    val ctx = LocalContext.current
    val usb = remember { ctx.getSystemService(Context.USB_SERVICE) as UsbManager }
    val cfg by Wideband.config.collectAsState()
    val state by Wideband.state.collectAsState()
    var devices by remember { mutableStateOf<List<UsbSerialDriver>>(emptyList()) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var preview by remember { mutableStateOf(Wideband.preview) }
    LaunchedEffect(Unit) {
        EcuService.start(ctx)
        while (true) {
            devices = withContext(Dispatchers.IO) { runCatching { UsbDeviceRegistry.drivers(usb) }.getOrDefault(emptyList()) }
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    DisposableEffect(Unit) { onDispose { Wideband.preview = false } }
    fun save(c: WidebandConfig, device: String? = Wideband.selectedDevice) {
        preview = false
        Wideband.save(c, device)
        UsbDeviceRegistry.refresh(usb)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        T("Innovate LC-2 / LC / MTX-L", 20.sp)
        T("Цифровой выход контроллера → совместимый USB–serial адаптер → магнитола. Один контроллер, 19200 бод. Аналоговый вход Arduino настраивается в SensorBox.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(cfg.enabled, { save(cfg.copy(enabled = it)) })
            T("Читать ШДК по USB")
        }
        T(state.message, color = if (state.link == WidebandLinkState.RECEIVING) C.Green else C.Orange)
        val afr = state.afr(now)?.let { "%.2f".format(Locale.US, it) } ?: "—"
        val lambda = state.lambda(now)?.let { "%.3f".format(Locale.US, it) } ?: "—"
        T("AFR $afr     λ $lambda", 24.sp)
        T("λ показана как AFR / 14,7 (совместимость с BtSsm). Прогрев, ошибки и отсутствие свежих данных не заменяются числом.")
        if (cfg.vendor >= 0) T("Выбрано: %04X:%04X · порт %d%s".format(cfg.vendor, cfg.product, cfg.port + 1,
            cfg.serial.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: " · без серийного номера"))
        T("USB-адаптер", 18.sp)
        if (devices.isEmpty()) T("Совместимые USB–serial адаптеры не найдены")
        devices.forEach { driver ->
            val d = driver.device
            val serial = UsbDeviceRegistry.serial(d)
            T("%04X:%04X · %s · %s".format(d.vendorId, d.productId,
                runCatching { d.productName }.getOrNull().orEmpty(), d.deviceName))
            driver.ports.indices.forEach { port ->
                val chosen = cfg.vendor == d.vendorId && cfg.product == d.productId && cfg.port == port &&
                    (Wideband.selectedDevice == d.deviceName || (cfg.serial.isNotEmpty() && cfg.serial == serial))
                Chip("Выбрать порт ${port + 1}", chosen, {
                    save(WidebandConfig(true, d.vendorId, d.productId, serial, port), d.deviceName)
                    if (!usb.hasPermission(d)) {
                        val intent = Intent(EcuService.ACTION_USB_PERMISSION).setPackage(ctx.packageName)
                        usb.requestPermission(d, PendingIntent.getBroadcast(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE))
                    }
                })
            }
        }
        T("Выбранное устройство исключается из поиска ECU и SensorBox. При одинаковых адаптерах без серийных номеров после переподключения потребуется повторный выбор.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Забыть адаптер", false, { save(WidebandConfig(), null) })
            Chip(if (preview) "Остановить тест" else "Тест статусов", preview, {
                preview = !preview
                Wideband.preview = preview
            })
        }
        T("Тест циклически показывает измерение, прогрев и ошибки на синтетических данных; на это время чтение USB приостанавливается. Показатели «AFR ШДК» и «λ ШДК» доступны в выборе приборов. В CSV они отдельные от AFR ЭБУ.")
    }
}
