package com.vexora.app

import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.vexora.core.integration.appsflyer.AppsFlyerAnalytics
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 最小集成宿主，仅展示启动/profile与钱包请求状态，不模拟业务到账。 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val model: HarnessViewModel by viewModels()
    @Inject lateinit var sdk: Lazy<AppsFlyerAnalytics>
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.ENABLE_SECURE_WINDOW && !BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(TextView(this).apply { setText(R.string.app_name) })
        layout.addView(TextView(this).apply { setText(R.string.build_identity) })
        val status = TextView(this); layout.addView(status)
        layout.addView(Button(this).apply { setText(R.string.retry); setOnClickListener { model.start() } })
        layout.addView(Button(this).apply { setText(R.string.wallet); setOnClickListener { model.loadWallet() } })
        layout.addView(Button(this).apply { setText(R.string.library); setOnClickListener { model.readCatalogAndLibrary() } })
        setContentView(layout)
        lifecycleScope.launch { model.status.collect { status.setText(it) } }
        lifecycleScope.launch { model.allowed.collect { if (it && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) sdk.get().onActivityResumed(this@MainActivity) } }
    }
    override fun onResume() { super.onResume(); if (model.allowed.value) sdk.get().onActivityResumed(this); model.foreground(true) }
    override fun onPause() { if (model.allowed.value) sdk.get().onActivityPaused(this); model.foreground(false); super.onPause() }
}
