package yumo.achat.core.backend

internal object StableInstallId {
    private val lock = Any()

    fun resolve(
        read: () -> String?,
        persist: (String) -> Boolean,
        generate: () -> String,
    ): String = synchronized(lock) {
        read()?.takeIf(String::isNotBlank)?.let { return@synchronized it }
        val generated = generate()
        check(generated.isNotBlank()) { "Generated device id is blank" }
        check(persist(generated)) { "Unable to persist device id" }
        generated
    }
}
