package yumo.achat.app.ui.login

internal fun cleanAccountInput(value: String): String = value.filterNot(Char::isWhitespace).take(50)

internal fun cleanPasswordInput(value: String): String = value.filterNot(Char::isWhitespace).take(18)

internal fun validLoginInput(account: String, password: String): Boolean =
    account.length in 3..50 && password.length in 6..18 &&
        account.none(Char::isWhitespace) && password.none(Char::isWhitespace)
