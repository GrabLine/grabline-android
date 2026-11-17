import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ABI splits for release builds (each APK carries one set of yt-dlp/Python/FFmpeg libraries).
// Debug-only invocations stay a single APK with the three ABIs the project supports.
// Release signing: keystore.properties (local, gitignored) or GRABLINE_* environment variables (CI).
// Without either, release APKs are built unsigned. See docs/RELEASING.md.
val releaseSigning: Map<String, String>? = run {
    val file = rootProject.file("keystore.properties")
    val props = Properties()
    if (file.isFile) file.inputStream().use { props.load(it) }
    fun pick(key: String, env: String) = props.getProperty(key) ?: System.getenv(env)
    val values = mapOf(
        "storeFile" to pick("storeFile", "GRABLINE_KEYSTORE"),
        "storePassword" to pick("storePassword", "GRABLINE_KEYSTORE_PASSWORD"),
        "keyAlias" to pick("keyAlias", "GRABLINE_KEY_ALIAS"),
        "keyPassword" to pick("keyPassword", "GRABLINE_KEY_PASSWORD"),
    )
    if (values.values.all { !it.isNullOrBlank() }) values.mapValues { it.value!! } else null
}

val splitAbis = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) || it.contains("bundle", ignoreCase = true) }

android {
    namespace = "dev.grabline.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.grabline.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // -Pgrabline.abi=arm64-v8a builds a debug APK for one ABI only (smaller to install on a test phone).
        val onlyAbi = providers.gradleProperty("grabline.abi").orNull
        if (!splitAbis) ndk { abiFilters += onlyAbi?.let { listOf(it) } ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    splits {
        abi {
            isEnable = splitAbis
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        // youtubedl-android loads Python and FFmpeg from zipped .so files in the native library directory.
        jniLibs.useLegacyPackaging = true
    }

    signingConfigs {
        releaseSigning?.let { cfg ->
            create("release") {
                storeFile = file(cfg.getValue("storeFile"))
                storePassword = cfg.getValue("storePassword")
                keyAlias = cfg.getValue("keyAlias")
                keyPassword = cfg.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Bounded test JVMs: the dev PC also runs an emulator and the desktop app.
        unitTests.all { it.maxHeapSize = "1g"; it.maxParallelForks = 1 }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// sshd-sftp registers a java.nio FileSystemProvider that breaks Robolectric's native SQLite loading on the test
// classpath. The in-process SFTP test server does not need it, so test builds use a copy without that one file.
val sshdSftp by configurations.creating { isTransitive = false }
val cleanSshdSftp = tasks.register<Jar>("cleanSshdSftp") {
    archiveFileName.set("sshd-sftp-clean.jar")
    destinationDirectory.set(layout.buildDirectory.dir("test-libs"))
    from(sshdSftp.elements.map { files -> files.map { zipTree(it.asFile) } }) { exclude("META-INF/services/java.nio.file.spi.FileSystemProvider") }
}

dependencies {
    sshdSftp(libs.sshd.sftp)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.zxing.android.embedded)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
    implementation(libs.libtorrent4j)
    implementation(libs.libtorrent4j.arm64)
    implementation(libs.libtorrent4j.arm)
    implementation(libs.libtorrent4j.amd64)
    // Phase 6: SFTP (sshj with its own BouncyCastle, since Android's copy is stripped) and FTP/FTPS.
    implementation(libs.sshj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.slf4j.nop)
    implementation(libs.commons.net)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.sshd.core)
    testImplementation(files(cleanSshdSftp))
    testImplementation(libs.ftpserver.core)
    testImplementation(libs.slf4j.nop)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.okhttp.mockwebserver)
}

// Copies the canonical protocol docs and JSON fixtures from the desktop repo.
// The copied files are committed so the build works without the desktop repo.
val grablineDesktopDir = (findProperty("grablineDesktopDir") as String?) ?: "../GrabLine-phone-remote"
tasks.register<Copy>("syncProtocolFixtures") {
    val source = layout.projectDirectory.dir("$grablineDesktopDir/protocol")
    from(source)
    into(layout.projectDirectory.dir("src/test/resources/protocol"))
    doFirst {
        if (!source.asFile.isDirectory) {
            logger.warn("syncProtocolFixtures: ${source.asFile} not found; keeping committed fixtures.")
        }
    }
}
