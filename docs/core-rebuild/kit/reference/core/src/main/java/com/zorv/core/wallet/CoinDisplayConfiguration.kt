package com.zorv.core.wallet

import java.util.Locale

/** CurrencySymbol 的配置化迁移，保留参考工程的货币符号与未知币种回退。 */
data class CoinDisplayConfiguration(val symbols: Map<String, String>) {
    fun symbol(currency: String): String {
        val code = currency.trim().uppercase(Locale.US)
        return symbols[code] ?: if (code.isNotEmpty()) "$code " else symbols.getValue("USD")
    }
}
