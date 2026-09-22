package com.vexora.app.ui.web

import android.content.Context
import android.text.TextUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vexora.app.ui.settings.SettingsConfiguration
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class WebContent(val loading: Boolean = true, val failed: Boolean = false, val html: String? = null)
@HiltViewModel
class WebViewModel @Inject constructor(@ApplicationContext private val context: Context,
    private val settings: SettingsConfiguration) : ViewModel() {
    private val mutable = MutableStateFlow(WebContent())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun prepare(url: String) {
        job?.cancel(); mutable.value = WebContent()
        job = viewModelScope.launch {
            try {
                val uri = java.net.URI(url)
                if (uri.scheme in setOf("https", "http")) { mutable.value = WebContent(loading = false); return@launch }
                require(uri.scheme == "file" && uri.path.startsWith("/android_asset/web/") && !uri.path.contains(".."))
                val html = withContext(Dispatchers.IO) {
                    val source = context.assets.open(uri.path.removePrefix("/android_asset/")).bufferedReader().use { it.readText() }
                    Regex("\\{\\{([a-z_]+)\\}\\}").replace(source) { match ->
                        val key = match.groupValues[1]
                        val value = if (key == "contact_email") settings.contactEmail else {
                            val id = context.resources.getIdentifier(key, "string", context.packageName)
                            require(id != 0); context.getString(id)
                        }
                        TextUtils.htmlEncode(value)
                    }
                }
                mutable.value = WebContent(loading = false, html = html)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { timber.log.Timber.w("本地网页模板解析失败：%s", error.javaClass.simpleName); mutable.value = WebContent(loading = false, failed = true) }
        }
    }
}
