package com.aistra.hail.utils

/**
 * Marks unfreezes initiated by Hail itself, so the unsuspend detection (broadcast receiver
 * and the guard service's poller) can tell them apart from a manual unsuspend by the user.
 * Entries expire after 30 seconds.
 */
object UnfreezeGate {
    private const val EXPIRY_MS = 30_000L
    private val expected = HashMap<String, Long>()

    @Synchronized
    fun expect(packageName: String) {
        val now = System.currentTimeMillis()
        expected.entries.removeAll { now - it.value > EXPIRY_MS }
        expected[packageName] = now
    }

    /** True when Hail itself unfreezed this package recently. Not consumed on read. */
    @Synchronized
    fun isExpected(packageName: String): Boolean {
        val t = expected[packageName] ?: return false
        if (System.currentTimeMillis() - t > EXPIRY_MS) {
            expected.remove(packageName)
            return false
        }
        return true
    }
}
