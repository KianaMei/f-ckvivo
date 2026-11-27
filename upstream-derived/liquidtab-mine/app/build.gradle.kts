plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.offlineglass"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.offlineglass"
        minSdk = 35
        targetSdk = 37
        versionCode = 292
        versionName = "2.1.2-mine12"
        buildConfigField("Boolean", "BETA_BUILD", "false")

        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Xposed hooks touch non-SDK interfaces by design; lintVital is a false positive here.
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    packaging {
        jniLibs.useLegacyPackaging = false
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/versions/**"
        )
    }

    signingConfigs {
        // Signed with the SAME identity as the installed build (debug key
        // 11:5E:D9:8E…, registered in repo signing/README.md as "debug B") so
        // release can adb install -r over debug without an uninstall round
        // (PACKAGE_* broadcast risk). Resolution order keeps every machine on
        // one identity: ANDROID_DEBUG_KEYSTORE -> repo signing/ copy -> user
        // ~/.android/debug.keystore.
        create("installOver") {
            val repoKey = rootProject.file("../../shared/signing/debug-115ed9.keystore")
            val envKey = System.getenv("ANDROID_DEBUG_KEYSTORE")?.let(::File)
            storeFile = envKey ?: repoKey.takeIf { it.exists() }
                ?: File(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ""
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("installOver")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3:1.5.0-alpha24")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // Canonical smoothed-corner geometry shared by View/Canvas and Compose.
    implementation("androidx.graphics:graphics-shapes:1.1.0")

    // Navigation 3 owns the secondary-page back stack and predictive-back transition.
    implementation("androidx.navigation3:navigation3-runtime:1.1.4")
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")

    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-squircle-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-navigation3-ui-android:0.9.3")

    compileOnly("io.github.libxposed:api:101.0.1")
    implementation("io.github.libxposed:service:101.0.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
