package yumo.achat.app.data.backend

fun isVisualGenerationFinished(status: String): Boolean =
    status == "succeeded" || status == "failed"

fun visualGenerationPollIntervalSeconds(intervalSeconds: Int?): Int =
    intervalSeconds?.takeIf { it > 0 } ?: 3
