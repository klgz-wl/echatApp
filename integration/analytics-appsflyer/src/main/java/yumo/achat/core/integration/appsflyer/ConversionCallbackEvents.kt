package yumo.achat.core.integration.appsflyer

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** Lossless within the current process; one consumer owns attribution-result delivery. */
internal class ConversionCallbackEvents<T> {
    private val channel = Channel<T>(Channel.UNLIMITED)
    val events: Flow<T> = channel.receiveAsFlow()
    fun publish(value: T) { channel.trySend(value) }
}
