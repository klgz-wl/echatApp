package yumo.achat.core.config

enum class AppMode { A, B }

data class AccountRules(val usernameMin: Int, val usernameMax: Int, val passwordMin: Int, val passwordMax: Int) {
    init {
        require(usernameMin > 0 && usernameMax >= usernameMin)
        require(passwordMin > 0 && passwordMax >= passwordMin)
    }
    fun accepts(account: String, password: String): Boolean =
        account.trim().length in usernameMin..usernameMax && password.length in passwordMin..passwordMax
}

data class AppConfiguration(
    val accountRules: AccountRules,
    val privacyUrl: String,
    val termsUrl: String,
    val aboutUrl: String,
    val contactUrl: String,
)
