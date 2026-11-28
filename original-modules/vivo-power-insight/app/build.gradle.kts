import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.ganvivo.powerinsight"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.ganvivo.powerinsight"
        minSdk = 31
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"

        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("persistent") {
            val envRoot = System.getenv("SIGNING_DIR")?.let(::File)
            storeFile = envRoot?.resolve("vivopowerinsight.keystore")
                ?: rootProject.file("../../shared/signing/vivopowerinsight.keystore")
            storePassword = "vivopowerinsight"
            keyAlias = "vivopowerinsight"
            keyPassword = "vivopowerinsight"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("persistent")
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.getByName("persistent")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/versions/**"
        )
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}

tasks.register<Copy>("exportReleaseApk") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename { "vivo-power-insight-${android.defaultConfig.versionName}.apk" }
    }
    into(rootProject.layout.projectDirectory.dir("dist"))
}
