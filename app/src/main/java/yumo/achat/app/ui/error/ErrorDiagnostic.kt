package yumo.achat.app.ui.error

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import yumo.achat.app.R

private val diagnosticSuffix = Regex("(?: \\((?:E-\\d{3}|R\\d{3}(?:-\\d{3})?)\\))+(?=\\r?\\n|$)")
private val businessCodes = setOf(
    "E-201", "E-202", "E-203", "E-204", "E-205", "E-206", "E-207",
    "E-301", "E-302", "E-303", "E-304", "E-305",
    "E-401", "E-402", "E-403", "E-404", "E-405", "E-406", "E-407", "E-408", "E-409",
    "E-501", "E-601", "E-602", "E-603", "E-701", "E-702", "E-703", "E-704", "E-705",
)
private val regionCodes = setOf("R101", "R102", "R103", "R200", "R201", "R202", "R203", "R204", "R206", "R207", "R299")

internal fun isRegionDiagnosticCode(code: String): Boolean =
    code in regionCodes || Regex("R205-[1-5][0-9]{2}").matches(code)

internal fun withDiagnosticCode(body: String, code: String, format: String): String {
    require(code in businessCodes || isRegionDiagnosticCode(code))
    return format.format(body.replace(diagnosticSuffix, ""), code)
}

internal fun Context.diagnosticMessage(body: String, code: String): String =
    withDiagnosticCode(body, code, getString(R.string.error_with_code))

@Composable
internal fun diagnosticString(body: String, code: String): String =
    withDiagnosticCode(body, code, stringResource(R.string.error_with_code))
