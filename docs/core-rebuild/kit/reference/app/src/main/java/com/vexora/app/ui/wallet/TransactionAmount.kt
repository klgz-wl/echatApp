package com.vexora.app.ui.wallet

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.vexora.app.R
import com.vexora.app.ui.theme.Design
import com.vexora.core.wallet.CoinTransaction

internal data class TransactionAmount(@StringRes val format: Int, val value: String, val color: Color)

/** 收支方向以接口 type 为准；金额绝对值避免正数支出或负数收入显示错误。 */
internal fun CoinTransaction.amountPresentation(): TransactionAmount {
    val amountValue = amount.toBigDecimal()
    val magnitude = amountValue.abs().setScale(2).toPlainString()
    return when (type) {
        "income" -> TransactionAmount(R.string.transaction_income_amount, magnitude, Design.Credit)
        "expense" -> TransactionAmount(R.string.transaction_expense_amount, magnitude, Design.White)
        // 未知类型保留原始数值，不能擅自解释为收入或支出。
        else -> TransactionAmount(R.string.transaction_unknown_amount, amountValue.setScale(2).toPlainString(), Design.White)
    }
}
