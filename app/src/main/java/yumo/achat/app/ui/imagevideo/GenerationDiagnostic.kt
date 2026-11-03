package yumo.achat.app.ui.imagevideo

import kotlinx.coroutines.CancellationException
import yumo.achat.core.backend.GenerationCreationRejectedException
import yumo.achat.core.backend.GenerationNotSubmittedException
import yumo.achat.core.backend.ImagePreparationException
import yumo.achat.core.backend.SignedOutException
import yumo.achat.core.network.ServiceFailure
import yumo.achat.core.visual.GenerationException
import yumo.achat.core.visual.GenerationFailure

internal fun businessOperationDiagnostic(error: Throwable, fallback: String): String {
    if (error is CancellationException) throw error
    return if (error is SignedOutException || error is ServiceFailure.SignedOut || error is ServiceFailure.Superseded) {
        "E-207"
    } else fallback
}

internal fun imageUploadDiagnostic(error: Throwable): String = businessOperationDiagnostic(
    error, if (error is ImagePreparationException) "E-301" else "E-408",
)

internal fun profileUpdateDiagnostic(error: Throwable): String = businessOperationDiagnostic(
    error, if (error is ImagePreparationException) "E-301" else "E-705",
)

internal fun generationSubmissionDiagnostic(error: Throwable): String {
    if (businessOperationDiagnostic(error, "") == "E-207") return "E-207"
    if (shouldRouteGenerationErrorToTopUp(error.message)) return "E-405"
    if (error is GenerationException) return when (error.reason) {
        GenerationFailure.SIGNED_OUT -> "E-207"
        GenerationFailure.INSUFFICIENT -> "E-405"
        GenerationFailure.CONFLICT -> "E-406"
        GenerationFailure.UNAVAILABLE -> "E-407"
        GenerationFailure.INVALID_IMAGE -> "E-409"
        GenerationFailure.UNKNOWN -> "E-402"
    }
    val rejection = error as? GenerationCreationRejectedException ?: error.cause as? GenerationCreationRejectedException
    if (rejection != null) return when (rejection.backendCode) {
        400101, 402 -> "E-405"
        409 -> "E-406"
        503 -> "E-407"
        else -> "E-409"
    }
    return if (error is GenerationNotSubmittedException) "E-409" else "E-402"
}
