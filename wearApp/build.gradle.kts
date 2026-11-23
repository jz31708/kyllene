import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Herald's Wear OS companion: talk to Hermes from the wrist. It holds no gateway code; everything goes through
// Herald on the phone over the Data Layer (shared:wearlink), which pairs the two only when they share the
// application ID and signing key. So its IDs, versions and signing follow androidApp's exactly.
val appVersion = (findProperty("appVersion") as String?)?.removePrefix("v") ?: "0.1.0"
val releasesRepo = (findProperty("releasesRepo") as String?).orEmpty()

android {
    namespace = "dev.hermeskotlin.wear"
    compileSdk {
        version = release(libs.versions.android.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.android.compileSdkMinor.get().toInt()
        }
    }

    defaultConfig {
        applicationId = "dev.herald.android"
        minSdk = libs.versions.android.wear.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        val (major, minor, patch) = appVersion.substringBefore('-').split('.').map { it.toInt() }.plus(listOf(0, 0)).take(3)
        require(minor < 100 && patch < 100) { "appVersion $appVersion: minor and patch must stay under 100" }
        // androidApp's code plus a million: Play takes each version code once per app, across phone and watch.
        versionCode = 1_000_000 + major * 10_000 + minor * 100 + patch
        versionName = appVersion
        // The upstream watch is a microphone. The Board's is not: its build is a remote for the phone, which
        // records. The flag turns the talk, dictation and watch-held call paths off at compile time (R8 then
        // drops them), and the board manifest asks for no microphone permission at all.
        buildConfigField("boolean", "BOARD_REMOTE", "false")
    }

    val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use(::load) }
    }
    // As androidApp: a published watch APK signed with the debug key wouldn't pair with the phone, so a release fails.
    // Also as there: only packaging an APK fails, so the desktop release, which configures this project, still builds.
    if (keystore == null && releasesRepo.isNotEmpty()) {
        val missing = "releasesRepo is set but keystore.properties is missing"
        tasks.configureEach { if (name.startsWith("package")) doFirst { throw GradleException(missing) } }
    }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = rootProject.file("androidApp").resolve(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG turns on the stand-in phone for trying the app on a watch emulator that isn't paired.
        buildConfig = true
    }

    // As androidApp: WebRTC's native libraries stay compressed, so the watch app downloads smaller.
    packaging { jniLibs.useLegacyPackaging = true }

    // A wrist-sized debug APK: with -PboardArm64, only the watch's own ABI is packaged, which is most of the
    // download the wear app otherwise carries (WebRTC's four ABIs). Off by default, so normal builds are unchanged.
    splits {
        abi {
            isEnable = project.hasProperty("boardArm64")
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = false
        ignoreTestSources = true
        disable += setOf("PropertyEscape", "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Pairs with "Herald Dev" on the phone (androidApp's dev build): same suffix, same debug key.
        create("dev") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        // Pairs with "Kyllene Board" on the phone (androidApp's debug build): the same suffix, because the
        // Data Layer only connects a watch app to the phone app whose package name is identical.
        debug {
            applicationIdSuffix = ".board"
            versionNameSuffix = "-board"
        }
        // "Kyllene Board (release)": the Board's installable build — R8, not debuggable — that still installs
        // beside Thomas's released Kyllene because of the suffix. Signed with the debug key unless a release
        // keystore is present, so each Board build installs over the last.
        create("board") {
            initWith(getByName("release"))
            applicationIdSuffix = ".board"
            versionNameSuffix = "-board"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "BOARD_REMOTE", "true")
        }
    }
}

/**
 * The README's hard invariants, as a build check (from `agent-config/mobile/wear`): the Board's watch is never
 * standalone and never the microphone. A board APK that asks for RECORD_AUDIO fails here, so it can't ship.
 */
val verifyBoardWatchInvariants by tasks.registering {
    description = "Checks the Board's watch APK for zero RECORD_AUDIO and standalone=false."
    group = "verification"
    val apk = layout.buildDirectory.file("outputs/apk/board/wearApp-board.apk")
    val sdk = androidComponents.sdkComponents.sdkDirectory
    doLast {
        val file = apk.get().asFile
        check(file.exists()) { "The Board's watch APK wasn't built: $file" }
        val aapt2 = sdk.get().asFile.resolve("build-tools").listFiles().orEmpty()
            .filter { it.isDirectory && it.resolve("aapt2").exists() }
            .maxByOrNull { it.name }?.resolve("aapt2")
            ?: error("aapt2 isn't in the Android SDK's build-tools")
        fun dump(vararg args: String): String {
            val process = ProcessBuilder(listOf(aapt2.absolutePath) + args + file.absolutePath)
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            return text
        }
        val permissions = dump("dump", "permissions")
        check(!permissions.contains("RECORD_AUDIO")) {
            "The Board's watch must not ask for RECORD_AUDIO: the Oppo is the microphone.\n$permissions"
        }
        val manifest = dump("dump", "xmltree", "--file", "AndroidManifest.xml")
        check(manifest.contains("com.google.android.wearable.standalone")) {
            "The Board's watch must declare com.google.android.wearable.standalone=false."
        }
        check(Regex("standalone[\\s\\S]{0,200}?=false").containsMatchIn(manifest)) {
            "The Board's watch must be standalone=false (it pairs with the phone app)."
        }
    }
}

tasks.matching { it.name == "assembleBoard" }.configureEach { finalizedBy(verifyBoardWatchInvariants) }

dependencies {
    implementation(projects.shared.wearlink)
    implementation(libs.androidx.activity.compose)
    // A library pulls in an old Fragment, which breaks activity results (lint's InvalidFragmentVersionForActivityResult).
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.core.ktx)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.wear.tiles)
    // The tile answers with a ListenableFuture.
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.material3)
    implementation(libs.wear.complications.data.source)
    // Live voice calls, held on the watch.
    implementation(libs.webrtc.android)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    testImplementation(libs.kotlin.test.junit)
}
