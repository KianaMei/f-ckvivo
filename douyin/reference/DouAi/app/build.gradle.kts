plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStoreFile = providers.gradleProperty("douaiReleaseStoreFile")
    .orElse(providers.environmentVariable("DOUAI_RELEASE_STORE_FILE"))
val releaseStorePassword = providers.gradleProperty("douaiReleaseStorePassword")
    .orElse(providers.environmentVariable("DOUAI_RELEASE_STORE_PASSWORD"))
val releaseKeyAlias = providers.gradleProperty("douaiReleaseKeyAlias")
    .orElse(providers.environmentVariable("DOUAI_RELEASE_KEY_ALIAS"))
val releaseKeyPassword = providers.gradleProperty("douaiReleaseKeyPassword")
    .orElse(providers.environmentVariable("DOUAI_RELEASE_KEY_PASSWORD"))
val releaseSigningPresent = listOf(releaseStoreFile, releaseStorePassword,
    releaseKeyAlias, releaseKeyPassword).map { it.isPresent }
require(releaseSigningPresent.none { it } || releaseSigningPresent.all { it }) {
    "Provide all four DouAi release signing properties or environment variables."
}

android {
    namespace = "com.douai.module"
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "io.github.yudigaga.douai"
        minSdk = 28
        targetSdk = 35
        versionCode = 176
        versionName = "0.2.75"
        require(versionName.orEmpty().matches(Regex("""[0-9]+\.[0-9]\.(?:0|[1-9][0-9]?)"""))) {
            "Version must carry at patch 100 and minor 10 (0.1.99 -> 0.2.0; 0.9.99 -> 1.0.0)."
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
        resValues = true
    }

    if (releaseSigningPresent.all { it }) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".adapt412"
            versionNameSuffix = "-41.2-unified-entryfix"
            resValue("string", "app_name", "抖音融合 41.2")
            resValue("string", "module_host_version", "抖音 41.2.0 · 六模块功能融合测试版")
            // The test build is also the APK people install, so shrink it like a release.
            // Module contracts stay: proguard-release.pro keeps com.douai.module.** and the
            // host-loader names. Only dependency code is removed or renamed.
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-release.pro", "proguard-debug-lines.pro")
        }
        create("legacy") {
            initWith(getByName("debug"))
            matchingFallbacks += "debug"
            applicationIdSuffix = ".legacy.adapt412"
            versionNameSuffix = "-41.2-legacy-commentpanel"
            resValue("string", "app_name", "抖音融合 41.2 传统兼容")
            resValue("string", "module_host_version", "抖音 41.2.0 · 传统 API82 兼容测试版")
            // Keep the API82 compatibility probe fully readable; it is not the shipped test APK.
            isMinifyEnabled = false
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-release.pro")
            if (releaseSigningPresent.all { it }) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        create("r8Experiment") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
    // Exercise the exact compatibility adapter source in the existing JVM test variant.
    // Classic entry/assets remain absent from the modern APK.
    sourceSets.getByName("test").java.srcDirs("src/legacy/java", "src/testLegacy/java")
    // Lint 9.1's Java PSI parser crashes on legal DEX identifiers such as X.1bIz.
    // Opt-in only: retain every other detector and record the excluded detector IDs.
    if (providers.gradleProperty("hostDexLintWorkaround").orNull == "true") {
        lint {
            disable += setOf("BlockedPrivateApi", "DiscouragedPrivateApi", "PrivateApi", "SoonBlockedPrivateApi")
        }
    }
}

androidComponents {
    onVariants { variant ->
        if (variant.buildType == "legacy") {
            // Do not also mark these as merged: AGP gives an explicit merge precedence
            // over excludes, which otherwise leaves both bootstrap identities in the APK.
            variant.packaging.resources.excludes.addAll(
                "META-INF/xposed/java_init.list", "META-INF/xposed/module.prop",
                "META-INF/xposed/scope.list", "META-INF/xposed/**")
        } else {
            variant.packaging.resources.merges.add("META-INF/xposed/*")
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    // Original-host and packaged-APK contracts must rerun when their configured input changes.
    for (variable in listOf("DOUAI_TEST_APK", "DOUAI_VERIFY_RELEASE_APK")) {
        inputs.files(providers.environmentVariable(variable).map { listOf(rootProject.file(it)) }.orElse(emptyList()))
            .withPropertyName(variable).withPathSensitivity(org.gradle.api.tasks.PathSensitivity.NONE)
    }
    // Robolectric SDKs and full-size host APK DEX contracts share a worker JVM.
    // Keep their bounded test heap separate from the module's runtime budget.
    maxHeapSize = "1g"
    if (providers.gradleProperty("releaseVerification").orNull == "true") {
        maxParallelForks = 1
        forkEvery = 20
        val profile = layout.buildDirectory.file("reports/release-verification/test-profile.json")
        doFirst {
            val output = profile.get().asFile
            output.parentFile.mkdirs()
            output.writeText("""{"gradleHeapMiB":${Runtime.getRuntime().maxMemory() / (1024 * 1024)},"testHeap":"$maxHeapSize","maxParallelForks":$maxParallelForks,"forkEvery":$forkEvery}""")
        }
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    compileOnly("io.github.libxposed:api:102.0.0")
    "legacyImplementation"("io.github.libxposed:api:102.0.0")
    "legacyCompileOnly"("de.robv.android.xposed:api:82")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("com.android.tools.smali:smali-dexlib2:3.0.9")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("de.sciss:jump3r:1.0.5")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.constraintlayout:constraintlayout:2.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4-android:1.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.12.0")
    testImplementation("io.github.libxposed:api:102.0.0")
    testImplementation("de.robv.android.xposed:api:82")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.google.code.gson:gson:2.10.1")
}
