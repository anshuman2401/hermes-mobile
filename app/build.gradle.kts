import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appVersion = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("version.properties"))
        .asText.get().reader().use { load(it) }
}
val appVersionName = requireNotNull(appVersion.getProperty("VERSION_NAME")) {
    "version.properties must define VERSION_NAME"
}
require(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(appVersionName)) {
    "VERSION_NAME must use MAJOR.MINOR.PATCH without leading zeros"
}
val appVersionCode = requireNotNull(appVersion.getProperty("VERSION_CODE")?.toIntOrNull()) {
    "version.properties must define an integer VERSION_CODE"
}
require(appVersionCode in 1..2_100_000_000) {
    "VERSION_CODE must be between 1 and 2100000000"
}

android {
    namespace = "com.hermes.bot"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "com.anshuman2401.hermesmobile"
        testInstrumentationRunner = "com.hermes.bot.feature.chat.StreamProfileRunner"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            val storeFileProp = findProperty("RELEASE_STORE_FILE") as String? ?: System.getenv("RELEASE_STORE_FILE")
            val storePasswordProp = findProperty("RELEASE_STORE_PASSWORD") as String? ?: System.getenv("RELEASE_STORE_PASSWORD")
            val keyAliasProp = findProperty("RELEASE_KEY_ALIAS") as String? ?: System.getenv("RELEASE_KEY_ALIAS")
            val keyPasswordProp = findProperty("RELEASE_KEY_PASSWORD") as String? ?: System.getenv("RELEASE_KEY_PASSWORD")

            // Release signing is explicit. Missing or invalid values are rejected
            // by the release task gate and AGP; debug builds keep their own key.
            storeFile = storeFileProp?.takeIf { it.isNotEmpty() }?.let { file(it) }
            storePassword = storePasswordProp?.takeIf { it.isNotEmpty() }
            keyAlias = keyAliasProp?.takeIf { it.isNotEmpty() }
            keyPassword = keyPasswordProp?.takeIf { it.isNotEmpty() }
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

// AGP can emit an unsigned APK for an incomplete signing config. Gate the
// release task graph explicitly; debug tasks never depend on this check.
val releaseSigning = android.signingConfigs.getByName("release")
val missingReleaseSigningFields = listOfNotNull(
    "RELEASE_STORE_FILE".takeIf { releaseSigning.storeFile == null },
    "RELEASE_STORE_PASSWORD".takeIf { releaseSigning.storePassword.isNullOrEmpty() },
    "RELEASE_KEY_ALIAS".takeIf { releaseSigning.keyAlias.isNullOrEmpty() },
    "RELEASE_KEY_PASSWORD".takeIf { releaseSigning.keyPassword.isNullOrEmpty() }
)
val releaseSigningStoreFile = releaseSigning.storeFile
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    val missingFieldsForTask = missingReleaseSigningFields
    val storeFileForTask = releaseSigningStoreFile
    doFirst {
        if (missingFieldsForTask.isNotEmpty()) {
            throw GradleException("Release signing requires: ${missingFieldsForTask.joinToString()}")
        }
        if (storeFileForTask?.isFile != true) {
            throw GradleException("Release signing requires an existing keystore file")
        }
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
