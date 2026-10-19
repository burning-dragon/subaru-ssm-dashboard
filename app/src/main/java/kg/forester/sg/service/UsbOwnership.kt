package kg.forester.sg.service

/** Device-level exclusion, including multi-port adapters. A token cannot release somebody else's lease. */
class UsbOwnership {
    private val owners = HashMap<String, Lease>()
    private val reservations = HashMap<String, String>()

    inner class Lease internal constructor(val device: String, val owner: String) : AutoCloseable {
        @Volatile var interrupt: (() -> Unit)? = null
        val permitted: Boolean get() = synchronized(this@UsbOwnership) {
            owners[device] === this && (reservations[device] == null || reservations[device] == owner)
        }
        override fun close() = synchronized(this@UsbOwnership) {
            if (owners[device] === this) owners.remove(device)
            Unit
        }
    }

    @Synchronized fun available(device: String, owner: String) = device !in owners &&
        (reservations[device] == null || reservations[device] == owner)

    @Synchronized fun claim(device: String, owner: String): Lease? =
        if (!available(device, owner)) null else Lease(device, owner).also { owners[device] = it }

    fun reserve(device: String, owner: String?) {
        val interrupt = synchronized(this) {
            if (owner == null) reservations.remove(device) else reservations[device] = owner
            owners[device]?.takeIf { owner != null && it.owner != owner }?.interrupt
        }
        // Closing a USB connection must never run while holding the registry lock.
        interrupt?.invoke()
    }
}
