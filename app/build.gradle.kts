plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.neonvpn.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.neonvpn.app"
        minSdk = 24          // Android 7.0 — lowest the Xray core (libv2ray) supports
        targetSdk = 34
        versionCode = 21
        versionName = "4.0"

        // §4.8 — instrumented test runner. The anti-Random guard test lives in
        // app/src/androidTest and runs on device/emulator via this runner.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Universal build — bundle every common ABI in a single APK
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            // Signing secrets may be supplied through the environment (CI build
            // secrets). When a secret is unset, GitHub Actions still injects the
            // variable as an EMPTY string, so a plain `?:` null-check is not
            // enough — we must also treat blank values as "absent" and fall back
            // to the project's bundled dev key. The same release key is preserved
            // so existing users can install updates over their current install.
            fun env(name: String) = System.getenv(name)?.takeUnless { it.isBlank() }
            storeFile = file("neonvpn.keystore")
            storePassword = env("KEYSTORE_PASSWORD") ?: "neonvpn123"
            keyAlias = env("KEY_ALIAS") ?: "neonvpn"
            keyPassword = env("KEY_PASSWORD") ?: "neonvpn123"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false          // keep JNI / Xray reflection intact, avoid R8 memory spikes
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
        }
    }

    // Single universal APK (no per-ABI splits)
    splits {
        abi { isEnable = false }
    }

    // §4.8 — bundle the shipped core/ + ui/ Kotlin sources into the androidTest
    // APK's assets so NoRandomInStatsTest can scan them ON DEVICE for forbidden
    // RNG usage. The .kt files are copied verbatim under assets/source-scan/.
    sourceSets {
        getByName("androidTest") {
            assets.srcDir(layout.buildDirectory.dir("generated/sourceScan"))
        }
    }
}

// §4.8 — copy the production Kotlin sources that the anti-Random guard must
// audit (config = core engine, service = core engine, stats, ui) into a
// generated assets folder consumed by the androidTest APK.
val collectSourceScan by tasks.registering(Copy::class) {
    val base = projectDir.resolve("src/main/java/com/neonvpn/app")
    from(base.resolve("config")) { into("config") }
    from(base.resolve("service")) { into("service") }
    from(base.resolve("stats")) { into("stats") }
    from(base.resolve("ui")) { into("ui") }
    include("**/*.kt")
    into(layout.buildDirectory.dir("generated/sourceScan/source-scan"))
}

tasks.matching { it.name == "generateDebugAndroidTestAssets" || it.name == "mergeDebugAndroidTestAssets" }
    .configureEach { dependsOn(collectSourceScan) }

dependencies {
    // Real Xray-core (V2Ray) engine, gomobile-bound .aar
    implementation(files("libs/libv2ray.aar"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    // ProcessLifecycleOwner — app-scoped lifecycle for the singleton PingService
    // (v3.8 §4.4) so a ping sweep keeps running across tab switches and only
    // backs off when the whole app is in the background.
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // RecyclerView for the configs list
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // DrawerLayout for the hamburger side menu
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")

    // JSON handling for Xray config generation
    implementation("com.google.code.gson:gson:2.10.1")

    // OkHttp — disk-cached config-feed fetching (ETag/304 revalidation, stale
    // fallback) for the prfgame/CC_new feed (see config/FeedCache.kt).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Lottie — vector JSON animations for the splash + connect screens
    implementation("com.airbnb.android:lottie:6.4.0")

    // LeakCanary — memory-leak detection in DEBUG builds only (never ships in
    // release). Catches leaked fragments / GL / Bitmap references during the
    // stress-test pass.
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.13")

    // §4.8 — instrumented test deps. NoRandomInStatsTest scans the shipped
    // core/ and ui/ sources for forbidden RNG usage and fails the build if any
    // fabricated stats sneak back in (Golden Rule #2).
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
}
