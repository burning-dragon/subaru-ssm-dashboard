package kg.forester.sg.devices.innovate

enum class InnovateStatus(val label: String) {
    VALID("Измерение"), NO_MEASUREMENT("Нет измерения (статус 1)"), FREE_AIR("Калибровка на воздухе"),
    NEEDS_CALIBRATION("Нужна калибровка"), WARMING("Прогрев"), HEATER_CALIBRATION("Калибровка нагревателя"),
    ERROR("Ошибка датчика"), RESERVED("Неизвестное состояние")
}

data class InnovateReading(val status: InnovateStatus, val raw: Int, val factor: Int) {
    val afr: Float? get() = if (status == InnovateStatus.VALID && factor > 0) (raw + 500) * factor / 10000f else null
    /** BtSsm-compatible gasoline-equivalent lambda; do not silently change this for another fuel. */
    val lambda: Float? get() = afr?.div(14.7f)
}

/** LC/MTXL, one channel only. Input is serial data (USB chip status bytes already removed by the driver).
 * Multi-channel packets are deliberately rejected until hardware captures are available. */
class InnovateDecoder {
    private val frame = IntArray(6)
    private var size = 0

    fun reset() { size = 0 }

    fun feed(bytes: ByteArray, count: Int = bytes.size): List<InnovateReading> {
        require(count in 0..bytes.size)
        val result = ArrayList<InnovateReading>()
        for (i in 0 until count) {
            val b = bytes[i].toInt() and 255
            if ((b and 0xB2) == 0xB2 && (b and 0x40) == 0) {
                frame[0] = b
                size = 1
                continue
            }
            when (size) {
                0 -> Unit
                1 -> {
                    val length = 2 * (((frame[0] and 1) shl 7) or (b and 127))
                    if (b and 128 == 0 || length != 4) reset()
                    else { frame[1] = b; size = 2 }
                }
                else -> {
                    val valid = when (size) {
                        2 -> b and 0x42 == 0x42 && b and 0xA0 == 0
                        4 -> b and 0xC0 == 0
                        else -> b and 0x80 == 0
                    }
                    if (!valid) { reset(); continue }
                    frame[size++] = b
                    if (size == 6) {
                        val status = InnovateStatus.entries[(frame[2] and 0x1C) shr 2]
                        result += InnovateReading(status, ((frame[4] and 63) shl 7) or frame[5],
                            ((frame[2] and 1) shl 7) or frame[3])
                        reset()
                    }
                }
            }
        }
        return result
    }
}
