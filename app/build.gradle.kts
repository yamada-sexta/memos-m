import java.text.SimpleDateFormat
import java.util.Date

val gitShortHash: Provider<String> = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
}.standardOutput.asText.map { it.trim() }

val gitTag: Provider<String> = providers.exec {
    commandLine("git", "describe", "--tags", "--abbrev=0")
}.standardOutput.asText.map { it.trim() }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-parcelize")
    id("com.google.devtools.ksp")
    id("androidx.room") version libs.versions.roomRuntime.get()

    alias(libs.plugins.aboutlibraries)
}

android {
    namespace = "org.example.memosm"
    compileSdk = 37
    compileSdkMinor = 1
    
    defaultConfig {
        applicationId = "org.example.memosm"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "GIT_HASH", "\"${gitShortHash.get()}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Keep debug as the normal instrumentation target; opt in to the R8-minified
    // canary app when running the legacy-snapshot instrumentation check.
    testBuildType = if (providers.gradleProperty("minifiedSnapshotTests").orNull?.toBoolean() == true) {
        "canary"
    } else {
        "debug"
    }

    // Opt-in ABI splits: debug and default builds keep the previous layout;
    // release-like builds pass -PabiSplits=true to produce per-ABI APKs plus
    // a universal APK containing every ABI.
    val abiSplits = providers.gradleProperty("abiSplits").orNull?.toBoolean() ?: false

    signingConfigs {
        create("release") {
            val keystorePath = providers.environmentVariable("KEYSTORE_PATH")
            storeFile = keystorePath.map { file(it) }.orNull

            storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("KEY_ALIAS").orElse("key0").orNull
            keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug-${gitShortHash.get()}"
            manifestPlaceholders["appLabel"] = "MemosM (Debug)"
        }
        create("canary") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            applicationIdSuffix = ".canary"

            val timestamp = SimpleDateFormat("yyyyMMddHHmm").format(Date())
            versionNameSuffix = "-canary-$timestamp-${gitShortHash.get()}"

            manifestPlaceholders["appLabel"] = "MemosM"
            signingConfig = signingConfigs.getByName("release")
        }
        create("insider") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            applicationIdSuffix = ".insider"

            // Tag format is likely v1.1.1, so we strip the 'v' prefix
            // versionName assignment moved to applicationVariants.all block below
            versionNameSuffix = "-insider"

            manifestPlaceholders["appLabel"] = "MemosM (Insider)"
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            manifestPlaceholders["appLabel"] = "MemosM"
        }
    }

    // Opt-in ABI splits (-PabiSplits=true): arm64-v8a, armeabi-v7a, x86, and
    // x86_64 APKs plus a universal APK containing every ABI. Without the
    // flag, release-like builds keep one APK with all supported ABIs.
    splits {
        abi {
            isEnable = abiSplits
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
    androidResources {
        // Only the UI languages this project maintains; drops library translations.
        localeFilters += listOf("en", "de", "ja", "ko", "pl", "zh-rCN", "zh-rTW")
    }

    packaging {
        jniLibs {
            // Store native libraries deflated; saves ~16 MB in the universal APK.
            // Trade-off: install extracts them, so install size/time grows slightly.
            useLegacyPackaging = true
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    sourceSets {
        named("main") {
        }
        named("androidTest") {
            assets.directories.add("$projectDir/schemas")
        }
        named("canary") {
            res.directories.add(("src/canary/res"))
        }
        named("insider") {
            res.directories.add(("src/insider/res"))
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

androidComponents {
    onVariants(selector().withBuildType("insider")) { variant ->
        variant.outputs.forEach { output ->
            output.versionName.set(gitTag.map { it.removePrefix("v") })
        }
    }
}



dependencies {
    implementation(libs.msgpack.core)
    coreLibraryDesugaring(libs.android.desugarJdkLibs)

    // ----------------------------
    // Koin
    // ----------------------------
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // ----------------------------
    // Android / Compose
    // ----------------------------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.material.kolor)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.compose.material3.navigation.suite)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.svg)

    // REST Networking (Retrofit)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.java.net.cookiejar)


    // Location
    implementation(libs.androidx.collection)
    implementation(libs.google.play.services.location)
    implementation(libs.maplibre.android)

    // About Libraries (OSS Licenses)
    implementation(libs.aboutlibraries.compose)

    // Markdown
    implementation(libs.markdown)
    implementation(libs.highlights)
    implementation(libs.androidmath)

    // Media
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)
    implementation(libs.media3.datasource.okhttp)

    // Tests
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // The Compose test activity shares the app's classloader with Espresso.
    // Match its test core/monitor version to the instrumentation APK.
    debugImplementation(libs.androidx.core)
    testImplementation(libs.testcontainers)
    testImplementation(libs.jna)
    testImplementation(libs.slf4j.simple)


    // ROOM
    implementation(libs.androidx.room.runtime)

    // Cross-process durable sync retries
    implementation(libs.androidx.work.runtime.ktx)

    // If this project uses any Kotlin source, use Kotlin Symbol Processing (KSP)
    // See Add the KSP plugin to your project
    ksp(libs.androidx.room.compiler)

    // If this project only uses Java source, use the Java annotationProcessor
    // No additional plugins are necessary
    annotationProcessor(libs.androidx.room.compiler)

    // optional - Kotlin Extensions and Coroutines support for Room
    implementation(libs.androidx.room.ktx)

    // optional - Test helpers
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.room.testing)

    // optional - Paging 3 Integration
    implementation(libs.androidx.room.paging)

}
