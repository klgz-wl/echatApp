package yumo.achat.core.backend

import org.json.JSONObject

enum class TemplateVipAccess { Allowed, SubscriptionRequired, CheckRequired }
fun templateVipAccess(vip: Boolean, isVip: Boolean?): TemplateVipAccess = when {
    !vip || isVip == true -> TemplateVipAccess.Allowed
    isVip == false -> TemplateVipAccess.SubscriptionRequired
    else -> TemplateVipAccess.CheckRequired
}

/** Only the documented numeric generation rejection is a membership failure. */
fun isTemplateVipRejection(error: Throwable): Boolean = error is AchatBackendHttpException &&
    error.statusCode == 403 && runCatching {
        val code = JSONObject(error.responseBody).opt("code")
        code is Number && code.toDouble() == 200101.0
    }.getOrDefault(false)

/** Continue is the admission point; ordinary templates retain their original wallet flow. */
suspend fun checkTemplateContinueAccess(vip: Boolean, loadMembership: suspend () -> Boolean?): TemplateVipAccess {
    if (!vip) return TemplateVipAccess.Allowed
    return try { templateVipAccess(true, loadMembership()) }
    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { TemplateVipAccess.CheckRequired }
}
