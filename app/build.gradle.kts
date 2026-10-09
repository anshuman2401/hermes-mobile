plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.anshuman2401.hermesmobile"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "io.github.anshuman2401.hermesmobile"
        testInstrumentationRunner = "io.github.anshuman2401.hermesmobile.feature.chat.StreamProfileRunner"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            val storeFileProp = findProperty("RELEASE_STORE_FILE") as String? ?: System.getenv("RELEASE_STORE_FILE")
            val storePasswordProp = findProperty("RELEASE_STORE_PASSWORD") as String? ?: System.getenv("RELEASE_STORE_PASSWORD")
            val keyAliasProp = findProperty("RELEASE_KEY_ALIAS") as String? ?: System.getenv("RELEASE_KEY_ALIAS")
            val keyPasswordProp = findProperty("RELEASE_KEY_PASSWORD") as String? ?: System.getenv("RELEASE_KEY_PASSWORD")

            if (storeFileProp != null && file(storeFileProp).exists()) {
                storeFile = file(storeFileProp)
                storePassword = storePasswordProp
                keyAlias = keyAliasProp
                keyPassword = keyPasswordProp
            } else {
                // Sideload candidate default: sign with local developer keystore for verifiable private installation
                val debugKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
                if (debugKeystore.exists()) {
                    storeFile = debugKeystore
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = if (providers.gradleProperty("streamProfile").isPresent) ".streamprofile" else ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Hermes Mobile")
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            resValue("string", "app_name", "Hermes Mobile")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    lint {
        abortOnError = true
        checkDependencies = true
        ignoreTestSources = false
        warningsAsErrors = false
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

dependencies {
    implementation(libs.commonmark)
    implementation(libs.autolink)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.strikethrough)
    coreLibraryDesugaring(libs.desugar)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.5.2")
    if (providers.gradleProperty("streamProfile").isPresent) {
        debugImplementation("androidx.compose.ui:ui-test-manifest")
    }
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp.tls)
}
