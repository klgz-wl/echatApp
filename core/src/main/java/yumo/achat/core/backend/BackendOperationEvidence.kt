package yumo.achat.core.backend

import kotlinx.coroutines.CancellationException

class ImagePreparationException(cause: Throwable) : IllegalStateException(cause.message, cause)

class GenerationNotSubmittedException(message: String) : IllegalArgumentException(message)

class GenerationCreationRejectedException(
    val backendCode: Int,
    message: String,
) : IllegalStateException(message)

internal inline fun <T> prepareImageUpload(prepare: () -> T): T = try {
    prepare()
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    throw ImagePreparationException(error)
}

internal fun validateGenerationCreationInputs(templateId: String, resourceId: String, idempotencyKey: String) {
    if (templateId.isBlank()) throw GenerationNotSubmittedException("Live template is required")
    if (resourceId.isBlank()) throw GenerationNotSubmittedException("Uploaded photo is required")
    if (idempotencyKey.isBlank()) throw GenerationNotSubmittedException("Generation idempotency key is required")
}
