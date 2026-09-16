package com.aistra.hail.utils

/**
 * Marks unfreezes initiated by Hail itself. On some ROMs (e.g. HyperOS) the
 * PACKAGE_UNSUSPENDED_MANUALLY broadcast also fires for programmatic unsuspends,
 * which would make UnsuspendedReceiver re-freeze apps that were legitimately
 * unfreezed (after passing biometric auth). Entries expire after 30 seconds.
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

    /** Returns true (once) if this package was expected to be unfreezed by Hail itself. */
    @Synchronized
    fun consume(packageName: String): Boolean {
        val t = expected.remove(packageName) ?: return false
        return System.currentTimeMillis() - t <= EXPIRY_MS
    }
}
