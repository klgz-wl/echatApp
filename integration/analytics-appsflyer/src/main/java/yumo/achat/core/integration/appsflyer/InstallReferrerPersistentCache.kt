package yumo.achat.core.integration.appsflyer

internal interface InstallReferrerCacheStorage {
    fun successfulResult(): String?
    fun legacyFirstInstallTime(): Long?
    fun commitCanonical(encoded: String, isActive: () -> Boolean): Boolean
}

internal class InstallReferrerPersistentCache(
    private val storage: InstallReferrerCacheStorage,
    private val installationTime: () -> Long?,
) : InstallReferrerCache {
    override fun firstInstallTime() = installationTime()

    override fun read(installTime: Long, isActive: () -> Boolean): InstallReferrerRaw? {
        val encoded = runCatching { storage.successfulResult() }.getOrNull() ?: return null
        val raw = runCatching {
            decodeInstallReferrerCache(encoded, installTime, storage.legacyFirstInstallTime())
        }.getOrNull() ?: return null
        // Canonical memory may come from a failed or still-pending SharedPreferences disk write.
        if (!commit(installTime, raw, isActive)) {
            if (!isActive()) return null
            error("Install Referrer cache commit failed")
        }
        return raw.takeIf { isActive() }
    }

    override fun commit(installTime: Long, raw: InstallReferrerRaw, isActive: () -> Boolean): Boolean =
        storage.commitCanonical(encodeInstallReferrerCache(installTime, raw)) {
            isActive() && firstInstallTime() == installTime && isActive()
        }
}
