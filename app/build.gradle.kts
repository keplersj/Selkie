plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

val selkieVersion = "0.1.0-mvp.1"
// The release workflow passes the tag's version as -Pselkie.version; every other build keeps selkieVersion.
val appVersion = (findProperty("selkie.version") as String?) ?: selkieVersion
// major * 1,000,000 + minor * 1,000 + patch, so each release installs over the one before. selkieVersion is 1.
val appVersionCode = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(appVersion)?.destructured
    ?.let { (major, minor, patch) -> major.toInt() * 1_000_000 + minor.toInt() * 1_000 + patch.toInt() } ?: 1
// A private release key that only the release workflow has (from the GitHub environment `release`).
// Without it, release builds keep the public MVP test key in keystore/, as debug and CI builds always do.
val releaseKeystore: String? = System.getenv("SELKIE_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val abis = listOf("arm64-v8a", "x86_64")
val nativeLibs = listOf(
    "libdroidc.so", "libdroidc-guest.so", "libcgshim.so", "libifshim.so", "libproot.so", "libproot-loader.so",
    // native/selkie-compositor (cargo ndk), the in-app Wayland compositor.
    "libselkie_compositor.so",
)

// Native binaries are not committed. They are copied into src/main/jniLibs/<abi>/ from
//   -Pselkie.nativeBinDir=<dir>   (layout <dir>/<abi>/lib*.so)   or   third_party/bin/<abi>/
// -Pselkie.allowMissingNative=true lets a local compile check proceed without them.
val nativeBinDir: File = (findProperty("selkie.nativeBinDir") as String?)?.let { file(it) }
    ?: rootProject.file("third_party/bin")
// The watch remote talks to the watch over the Wear Data Layer, which needs Google Play services.
// -Pselkie.wear=false builds without them (F-Droid and the like): the remote is left out, the rest is the same.
val wearRemote = (findProperty("selkie.wear") as String?) != "false"
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
    // Android Automotive's driving state; the library is on the car's system image, not in the APK.
    useLibrary("android.car")

    defaultConfig {
        applicationId = "io.github.keplersj.selkie"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += abis }
        buildConfigField("String", "PROOT_SOURCE_URL",
            "\"https://github.com/keplersj/Selkie/releases/tag/v$appVersion\"")
    }

    signingConfigs {
        create("mvp") {
            storeFile = rootProject.file("keystore/selkie-mvp.jks")
            storePassword = "selkie-mvp-insecure"
            keyAlias = "selkie-mvp"
            keyPassword = "selkie-mvp-insecure"
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("SELKIE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SELKIE_KEY_ALIAS")
                keyPassword = System.getenv("SELKIE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("mvp") }
        release {
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "mvp")
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
    // What the phone and the watch say to each other, shared with :wear.
    sourceSets.getByName("main").kotlin.srcDir(rootProject.file("shared/watch/kotlin"))
    sourceSets.getByName("main").kotlin.srcDir(if (wearRemote) "src/wear/kotlin" else "src/nowear/kotlin")
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
    implementation(libs.androidx.window)
    // App icons from a container's icon theme are often SVG.
    implementation(libs.androidsvg)
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
    implementation(libs.xr.compose)
    implementation(libs.xr.runtime)
    implementation(libs.xr.scenecore)
    if (wearRemote) {
        implementation(libs.play.services.wearable)
        implementation(libs.androidx.fragment)
    }

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
