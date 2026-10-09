package com.hermes.bot.feature.chat

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Never instantiate the production graph or open its vault in synthetic UI runs. */
class StreamProfileRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}
