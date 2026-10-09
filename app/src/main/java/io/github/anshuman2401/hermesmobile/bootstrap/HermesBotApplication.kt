package io.github.anshuman2401.hermesmobile.bootstrap

import android.app.Application
import io.github.anshuman2401.hermesmobile.data.diagnostics.Diagnostics
import io.github.anshuman2401.hermesmobile.data.diagnostics.DiagnosticsLifecycle
import io.github.anshuman2401.hermesmobile.data.diagnostics.PrivateDiagnosticsRepository
import io.github.anshuman2401.hermesmobile.data.diagnostics.DiagnosticCrashHandler

class HermesBotApplication : Application() {
    lateinit var appContainer: AppContainer
    lateinit var diagnostics: Diagnostics

    override fun onCreate() {
        super.onCreate()
        runCatching { io.github.anshuman2401.hermesmobile.feature.chat.files.AndroidFileActions.clearOldExports(this) }
        runCatching { io.github.anshuman2401.hermesmobile.data.files.DefaultManagedFileRepository.clearOldTransfers(cacheDir) }
        val privateDiagnostics = PrivateDiagnosticsRepository.fromContext(filesDir)
        Thread.getDefaultUncaughtExceptionHandler()?.let { previous ->
            Thread.setDefaultUncaughtExceptionHandler(DiagnosticCrashHandler(privateDiagnostics::captureCrash, previous))
        }
        appContainer = DefaultAppContainer(this, privateDiagnostics = privateDiagnostics)
        runCatching { appContainer.diagnosticRing.updateEnvironment(io.github.anshuman2401.hermesmobile.data.diagnostics.DiagnosticEnvironment(
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
