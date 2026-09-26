package com.zorv.core.analytics

interface AnalyticsSink {
    fun initialize()
    fun identify(userId: String?)
    fun event(name: String, parameters: Map<String, Any> = emptyMap())
}
interface AttributionIdProvider { fun currentId(): String }

data class AnalyticsPolicy(val firebase: Boolean, val appsFlyer: Boolean, val thinkingData: Boolean, val backend: Boolean)
