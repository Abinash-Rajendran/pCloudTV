import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing credentials, kept out of version control. Absent on a fresh
// clone, in which case the release build falls back to the debug key below so the
// project still configures and builds.
val keystorePropertiesFile = rootProject.file("app/keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystoreProperties.containsKey("storeFile")

android {
    namespace = "com.ar.pcloud"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ar.pcloud"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName(
                if (hasReleaseKeystore) "release" else "debug"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        // PCloudApiClient reads BuildConfig.DEBUG to keep HTTP logging out of release builds.
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

/**
 * Copies the built release APK to a fixed path.
 *
 * Deliberately not a [Copy] task: `Copy.into(rootProject…)` makes the whole repo
 * root a declared task output, which AGP's own `createReleaseApkListingFileRedirect`
 * then reads, and Gradle fails the build over the undeclared dependency. Declaring
 * the destination [Internal] keeps the copy out of Gradle's output graph. The task
 * is cheap and always runs.
 */
abstract class ExportApk : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:Internal
    abstract val destination: RegularFileProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun export() {
        val target = destination.get().asFile
        fs.copy {
            from(source)
            into(target.parentFile)
            rename { target.name }
        }
        logger.lifecycle("Release APK -> ${target.absolutePath}")
    }
}

// `./gradlew pcloudApk` -> signed release APK at the repo root, named Pcloud.apk.
tasks.register<ExportApk>("pcloudApk") {
    group = "build"
    description = "Builds the signed release APK and copies it to <repo>/Pcloud.apk"
    dependsOn("assembleRelease")
    source.set(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    destination.set(rootProject.layout.projectDirectory.file("Pcloud.apk"))
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Networking
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)

    // Image Loading
    implementation(libs.coil.compose)

    // Media3 Playback
    implementation(libs.media3.exoplayer)
    // HLS extractor for the gethlslink fallback; without it .m3u8 fails to load.
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}