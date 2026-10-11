package com.hermes.bot.bootstrap

import android.app.Application
import com.hermes.bot.data.diagnostics.Diagnostics
import com.hermes.bot.data.diagnostics.DiagnosticsLifecycle
import com.hermes.bot.data.diagnostics.PrivateDiagnosticsRepository
import com.hermes.bot.data.diagnostics.DiagnosticCrashHandler

class HermesBotApplication : Application() {
    lateinit var appContainer: AppContainer
    lateinit var diagnostics: Diagnostics

    override fun onCreate() {
        super.onCreate()
        runCatching { com.hermes.bot.data.attachments.AndroidCameraPhotoStore(this).clearAbandoned() }
        runCatching { com.hermes.bot.feature.chat.files.AndroidFileActions.clearOldExports(this) }
        runCatching { com.hermes.bot.data.files.DefaultManagedFileRepository.clearOldTransfers(cacheDir) }
        val privateDiagnostics = PrivateDiagnosticsRepository.fromContext(filesDir)
        Thread.getDefaultUncaughtExceptionHandler()?.let { previous ->
            Thread.setDefaultUncaughtExceptionHandler(DiagnosticCrashHandler(privateDiagnostics::captureCrash, previous))
        }
        appContainer = DefaultAppContainer(this, privateDiagnostics = privateDiagnostics)
        runCatching { appContainer.diagnosticRing.updateEnvironment(com.hermes.bot.data.diagnostics.DiagnosticEnvironment(
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown",
            buildTimestamp = "unknown",
            androidApiLevel = android.os.Build.VERSION.SDK_INT,
            deviceModel = android.os.Build.MODEL
        )) }
        diagnostics = appContainer.diagnostics
        val lifecycle = DiagnosticsLifecycle(diagnostics)
        lifecycle.applicationStarted(prepared = true)
        lifecycle.compositionReady()
    }
}
