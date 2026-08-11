plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Version aus Gradle-Eigenschaften, damit der CI-Lauf seine Nummer
// einsetzen kann (siehe .github/workflows/build-apk.yml). Ohne Angabe –
// also bei einem lokalen Build – bleibt es bei 1.0.0.
//
// Das ist die Grundlage der In-App-Aktualisierung: Vorher standen hier
// feste Werte, die App hätte sich also nie von einer neueren Ausgabe
// unterscheiden können.
val appVersionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
val appVersionName = (findProperty("appVersionName") as String?) ?: "1.0.0"

// Öffentliches Repository, aus dem die App ihre Aktualisierungen bezieht.
val updateRepo = (findProperty("updateRepo") as String?) ?: "neunelfer-sketch/9elf-Player"

android {
    namespace = "de.neunelf.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.neunelf.player"
        // minSdk 22 deckt Fire OS 5 (Fire TV Stick 2. Gen) sowie Android TV 5.1 ab.
        minSdk = 22
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Fester Signaturschlüssel für Testbuilds.
        //
        // Ohne ihn erzeugt jeder CI-Lauf einen neuen Debug-Schlüssel, und
        // Android verweigert dann die Installation über eine bestehende
        // Version ("Signaturen stimmen nicht überein"). Der Schlüssel ist
        // bewusst öffentlich und ausschließlich für Testbuilds gedacht –
        // für eine Veröffentlichung im Store gehört ein eigener, geheimer
        // Schlüssel her (siehe README).
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // Eigene Anwendungs-ID, damit Test- und Release-Build
            // nebeneinander installiert werden können.
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Vorerst ebenfalls mit dem Testschlüssel, damit die APK ohne
            // weitere Einrichtung installierbar ist.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Nötig für java.time-APIs auf alten Fire-OS-Geräten (API 22).
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api",
            "-opt-in=androidx.media3.common.util.UnstableApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Media3 markiert große Teile seiner API als "UnstableApi". Die
        // Nutzung ist hier bewusst – ohne diese Ausnahme bricht
        // lintVitalRelease den Release-Build ab.
        disable += "UnsafeOptInUsageError"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    implementation(libs.androidx.core.ktx)
    // Ausschließlich für AppCompatDelegate.setApplicationLocales() – die
    // Sprache lässt sich damit ohne App-Neustart umschalten, auch ohne dass
    // die Activity von AppCompatActivity erbt.
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons)
    // Nur für Eingabefelder: tv-material3 kennt keine TextFields.
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Compose for TV (Material3-Varianten mit korrektem Fokus-Verhalten)
    implementation(libs.androidx.tv.material)

    // Player
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.exoplayer.rtsp)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.session)

    // Persistenz
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    // Netzwerk
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Bilder (Sender-Logos, VOD-Poster)
    implementation(libs.coil.compose)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
