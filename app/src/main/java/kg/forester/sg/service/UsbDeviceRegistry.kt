package kg.forester.sg.service

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.*
import kg.forester.sg.data.Wideband

object UsbDeviceRegistry {
    const val ECU = "ecu"
    const val BOX = "sensorbox"
    const val SCREEN = "screen"
    const val INNOVATE = "innovate"
    const val FLASH = "flash"
    val ownership = UsbOwnership()
    private val flashing = HashSet<String>()
    private var reserved = emptySet<String>()

    fun serial(d: UsbDevice): String = runCatching { d.serialNumber.orEmpty() }.getOrDefault("")

    /** Without permission serial numbers are unavailable: conservatively reserve all matching IDs. */
    fun isWideband(d: UsbDevice): Boolean {
        val c = Wideband.config.value
        return c.reserves(d.deviceName, d.vendorId, d.productId, serial(d), Wideband.selectedDevice)
    }

    @Synchronized fun refresh(usb: UsbManager) {
        val devices = usb.deviceList.values
        if (devices.none { it.deviceName == Wideband.selectedDevice }) Wideband.selectedDevice = null
        val next = devices.filter(::isWideband).map { it.deviceName }.toSet()
        (reserved + next).forEach { name ->
            ownership.reserve(name, if (name in flashing) FLASH else if (name in next) INNOVATE else null)
        }
        reserved = next
    }

    @Synchronized fun flash(device: String, on: Boolean) {
        if (on) flashing += device else flashing -= device
        ownership.reserve(device, if (on) FLASH else if (device in reserved) INNOVATE else null)
    }

    /** Called by flash workers after reserving: wait for the reader's finally before opening. */
    fun awaitFlash(device: String): UsbOwnership.Lease {
        repeat(30) {
            ownership.claim(device, FLASH)?.let { return it }
            Thread.sleep(100)
        }
        error("USB-устройство занято: не удалось освободить его для прошивки")
    }

    fun available(d: UsbDevice, owner: String): Boolean =
        (owner == INNOVATE || !isWideband(d)) && ownership.available(d.deviceName, owner)

    fun claim(d: UsbDevice, owner: String): UsbOwnership.Lease? =
        if (owner != INNOVATE && isWideband(d)) null else ownership.claim(d.deviceName, owner)

    /** Same known USB-serial families as the ECU fallback. No arbitrary CDC modem probing. */
    fun drivers(usb: UsbManager): List<UsbSerialDriver> {
        val known = SensorBoxLink.drivers(usb)
        val names = known.map { it.device.deviceName }.toSet()
        return known + usb.deviceList.values.filter { it.deviceName !in names }.mapNotNull { d ->
            runCatching {
                when (d.vendorId) {
                    0x0403 -> FtdiSerialDriver(d)
                    0x067B -> ProlificSerialDriver(d)
                    0x1A86 -> Ch34xSerialDriver(d)
                    0x10C4 -> Cp21xxSerialDriver(d)
                    else -> null
                }
            }.getOrNull()
        }
    }
}
