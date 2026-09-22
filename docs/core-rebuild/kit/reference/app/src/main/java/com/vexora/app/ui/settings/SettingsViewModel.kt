package com.vexora.app.ui.settings

import androidx.lifecycle.ViewModel
import com.vexora.core.config.AppConfiguration
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

class SettingsConfiguration(val version: String, val contactEmail: String)
@HiltViewModel
class SettingsViewModel @Inject constructor(val config: AppConfiguration, settings: SettingsConfiguration) : ViewModel() {
    val version = settings.version
    val contact = settings.contactEmail
}
