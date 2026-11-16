plugins {
    id("com.android.application") version "9.1.0"
}

android {
    namespace = "dev.ganvivo.dragall"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.ganvivo.dragall"
        minSdk = 30
        targetSdk = 36
        versionCode = 33
        versionName = "0.1.32"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("persistent") {
            storeFile = rootProject.file(".signing/drag-all.keystore")
            storePassword = "dragall-debug"
            keyAlias = "dragall"
            keyPassword = "dragall-debug"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("persistent")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("persistent")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging.resources.merges += "META-INF/xposed/*"
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:101.0.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
}

tasks.register<Copy>("exportDebugApk") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.dir("outputs/apk/debug")) {
        include("*.apk")
        rename { "drag-all-${android.defaultConfig.versionName}-debug.apk" }
    }
    into(layout.projectDirectory.dir("dist"))
}
