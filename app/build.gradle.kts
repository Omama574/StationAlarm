import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics.plugin)
}

apply(plugin = "com.google.android.gms.oss-licenses-plugin")

// ── Version generation ────────────────────────────────────────────────────
// versionCode is derived from the Git commit count so it increases monotonically
// without manual bookkeeping. If git is unavailable (e.g. source tarball build),
// falls back to 1 so assembleDebug still works.
fun gitCommitCount(): Int {
    return try {
        val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        process.waitFor()
        process.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 1
    } catch (_: Exception) {
        1
    }
}

val appVersionCode = gitCommitCount()
val appVersionName = "1.0.$appVersionCode"

// ── Signing config (release) ──────────────────────────────────────────────
// Keystore credentials are loaded from an untracked keystore.properties file.
// If the file is absent (e.g. during local debug builds on a machine without
// the release keystore), the release build skips signing rather than failing.
// To set up: create keystore.properties in the project root with:
//   storeFile=/absolute/path/to/release.keystore
//   storePassword=...
//   keyAlias=...
//   keyPassword=...
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigningConfig = keystorePropertiesFile.exists() &&
    keystoreProperties.getProperty("storeFile")?.isNotBlank() == true

android {
    namespace = "com.omama.stationalarm"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.omama.stationalarm"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.compose.runtime:runtime-livedata:1.6.0")
// or version matching your Compose
    implementation("com.google.android.gms:play-services-location:21.0.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    // ── Robolectric stack — unit tests for Room DAO + DataStore + Android-bound helpers
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core-ktx:1.5.0")
    testImplementation("androidx.test.ext:junit-ktx:1.1.5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // ── Map Search Feature ───────────────────────────────────────────────
    // OSM tiles (unlimited, free)
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Retrofit + Gson for geocoding (LocationIQ via Cloudflare Worker)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Compose Foundation for HorizontalPager (tab navigation)
    implementation("androidx.compose.foundation:foundation:1.6.0")

    // Coroutines (explicit — needed for debounce in search)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ── Firebase ─────────────────────────────────────────────────────────────
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.remote.config)
    implementation(libs.firebase.analytics)

    // ── Preferences + OSS Licenses ───────────────────────────────────────────
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.play.services.oss.licenses)
    // OssLicensesMenuActivity extends AppCompatActivity — needed on the classpath
    implementation("androidx.appcompat:appcompat:1.7.0")

    // ── WorkManager (used by BootReceiver retry path) ────────────────────────
    implementation(libs.androidx.work.runtime.ktx)

    // ── Glassmorphism (frosted glass UI) ────────────────────────────────────
    implementation("dev.chrisbanes.haze:haze-android:1.6.10")
}
