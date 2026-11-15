plugins {
    id("com.android.application")
}

android {
    namespace = "dev.xhook"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.xhook"
        minSdk = 31
        targetSdk = 36
        versionCode = 11
        versionName = "0.2.9"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // libxposed 模块描述文件必须原样进入 APK 根目录
            merges += "META-INF/xposed/*"
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    // 运行时由 Vector / LSPosed 提供
    compileOnly("io.github.libxposed:api:102.0.0")

    // 模块 App 通过 LibXposed Service 写 Remote Preferences（hook 进程读同一组）
    implementation("io.github.libxposed:service:101.0.0")

    // DexKit：按结构查找 X 的混淆类，避免硬编码类名。
    implementation("org.luckypray:dexkit:2.2.0")
}
