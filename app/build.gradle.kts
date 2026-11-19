plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

val selkieVersion = "0.1.0-mvp.1"
val abis = listOf("arm64-v8a", "x86_64")
val nativeLibs = listOf("libdroidc.so", "libdroidc-guest.so", "libproot.so", "libproot-loader.so")

// Native binaries are not committed. They are copied into src/main/jniLibs/<abi>/ from
//   -Pselkie.nativeBinDir=<dir>   (layout <dir>/<abi>/lib*.so)   or   third_party/bin/<abi>/
// -Pselkie.allowMissingNative=true lets a local compile check proceed without them.
val nativeBinDir: File = (findProperty("selkie.nativeBinDir") as String?)?.let { file(it) }
    ?: rootProject.file("third_party/bin")
val allowMissingNative = (findProperty("selkie.allowMissingNative") as String?) == "true"
val jniLibsOut = layout.projectDirectory.dir("src/main/jniLibs")

val stageNativeLibs by tasks.registering {
    group = "selkie"
    description = "Copies libdroidc.so/libproot.so/libproot-loader.so into jniLibs (fails if missing)."
    inputs.property("dir", nativeBinDir.path)
    inputs.property("allowMissing", allowMissingNative)
    outputs.dir(jniLibsOut)
    doLast {
        val missing = mutableListOf<String>()
        for (abi in abis) for (lib in nativeLibs) {
            val src = File(nativeBinDir, "$abi/$lib")
            val dst = jniLibsOut.file("$abi/$lib").asFile
            if (src.isFile) {
                dst.parentFile.mkdirs()
                src.copyTo(dst, overwrite = true)
            } else if (!dst.isFile) {
                missing += src.path
            }
        }
        if (missing.isNotEmpty()) {
            val msg = "Missing native binaries:\n  " + missing.joinToString("\n  ") +
                "\nBuild droidc and proot (see README) and pass -Pselkie.nativeBinDir=<dir> " +
                "(layout <dir>/<abi>/lib*.so), or use -Pselkie.allowMissingNative=true for a compile-only build."
            if (allowMissingNative) logger.warn("WARNING: $msg") else throw GradleException(msg)
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(stageNativeLibs) }

android {
    namespace = "io.github.keplersj.selkie"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.keplersj.selkie"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = selkieVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += abis }
        buildConfigField("String", "PROOT_SOURCE_URL",
            "\"https://github.com/keplersj/Selkie/releases/tag/v$selkieVersion\"")
    }

    signingConfigs {
        create("mvp") {
            storeFile = rootProject.file("keystore/selkie-mvp.jks")
            storePassword = "selkie-mvp-insecure"
            keyAlias = "selkie-mvp"
            keyPassword = "selkie-mvp-insecure"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("mvp") }
        release {
            signingConfig = signingConfigs.getByName("mvp")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets.getByName("main").kotlin.srcDir("src/main/kotlin")
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperties["robolectric.pixelCopyRenderMode"] = "hardware"
            it.maxHeapSize = "4096m"
        }
    }
}

// Every @Preview in ui/ becomes a Robolectric screenshot test (see docs/ui-previews.md).
//   ./gradlew recordRoborazziDebug    writes PNGs to app/screenshots/
//   ./gradlew compareRoborazziDebug   writes diffs against them to build/outputs/roborazzi/
roborazzi {
    outputDir.set(layout.projectDirectory.dir("screenshots"))
    compare { outputDir.set(layout.buildDirectory.dir("outputs/roborazzi")) }
    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    generateComposePreviewRobolectricTests {
        enable = true
        packages = listOf("io.github.keplersj.selkie.ui")
        robolectricConfig = mapOf("sdk" to "[35]")
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.termux.terminal.view)
    implementation(libs.termux.terminal.emulator)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.preview.scanner.support)
    testImplementation(libs.composable.preview.scanner)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.test.manifest)
}
