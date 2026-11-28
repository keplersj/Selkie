// Selkie's watch remote for Wear OS. It shares the phone app's applicationId and signing key, which the
// Wear Data Layer needs before the two apps will talk to each other.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// Same version and release key as the phone app (see app/build.gradle.kts): the Data Layer needs matching signatures.
val appVersion = (findProperty("selkie.version") as String?) ?: "0.1.0-mvp.1"
val appVersionCode = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(appVersion)?.destructured
    ?.let { (major, minor, patch) -> major.toInt() * 1_000_000 + minor.toInt() * 1_000 + patch.toInt() } ?: 1
val releaseKeystore: String? = System.getenv("SELKIE_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "io.github.keplersj.selkie.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.keplersj.selkie"
        minSdk = 30
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
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
        release { signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "mvp") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    sourceSets.getByName("main").kotlin.srcDir("src/main/kotlin")
    // What the phone and the watch say to each other, shared with :app.
    sourceSets.getByName("main").kotlin.srcDir(rootProject.file("shared/watch/kotlin"))
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperties["robolectric.pixelCopyRenderMode"] = "hardware"
            it.maxHeapSize = "4096m"
            // Full causes in the log: CI's test reports can't be read from everywhere.
            it.testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        }
    }
}

// Every @Preview in the wear package becomes a Robolectric screenshot test, as in :app.
roborazzi {
    outputDir.set(layout.projectDirectory.dir("screenshots"))
    compare { outputDir.set(layout.buildDirectory.dir("outputs/roborazzi")) }
    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    generateComposePreviewRobolectricTests {
        enable = true
        packages = listOf("io.github.keplersj.selkie.wear")
        robolectricConfig = mapOf("sdk" to "[35]")
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.play.services.wearable)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    // Tiles hand back a ListenableFuture. Named here because the debug-only renderer brings
    // all of Guava, which turns the listenablefuture stub on the compile classpath empty.
    implementation(libs.guava)
    implementation(libs.wear.remote.interactions)
    implementation(libs.androidx.concurrent.futures)

    testImplementation(libs.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.preview.scanner.support)
    testImplementation(libs.composable.preview.scanner)
    // Draws the tile the way a watch does, for its screenshot (src/testDebug). A test-only
    // dependency's resources never reach the test R jar, so it rides on the debug build.
    debugImplementation(libs.wear.tiles.renderer)
    debugImplementation(libs.compose.ui.test.manifest)
}
