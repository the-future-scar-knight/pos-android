import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing is driven by an untracked keystore.properties at the project
// root (see keystore.properties.example).
//
// ★ A RELEASE APK MUST NEVER BE SIGNED WITH THE DEBUG KEY. This used to fall back
// to debug signing when keystore.properties was absent, so a fresh clone produced an
// installable APK that looked like a release build and gave no hint it wasn't one.
// The damage is not abstract: Android refuses to update an installed app whose signing
// certificate changed, so the day the real keystore finally arrives the shop has to
// UNINSTALL first — and uninstalling wipes the Room database, which IS the books.
// A till that has to be wiped to be updated is worse than a till that won't build.
//
// So: no keystore.properties => the release variant is left UNSIGNED (never debug-signed)
// and any task that would produce a release artifact fails with instructions. Debug
// builds and unit tests are untouched and still work on a fresh clone.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

android {
    namespace = "com.portionspot.pos"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.portionspot.pos"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 strips unused code and obfuscates; keep-rules live in
            // proguard-rules.pro (Room/serialization/okhttp/zxing reflection).
            isMinifyEnabled = true
            // Resource shrinking stays OFF: this build host has ~4 GB RAM and no
            // page file, and full R8 + resource shrink has a real OOM risk here.
            // Re-enable once building on a roomier machine.
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Real keystore, or nothing at all. `findByName` returns null when
            // keystore.properties is absent, which leaves the artifact unsigned —
            // uninstallable, and therefore unshippable by accident. The task-graph
            // check below turns that into a clear failure instead of a puzzle.
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        aidl = true   // Sunmi InnerPrinter (woyou.aidlservice.jiuiv5)
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.ui.text.google.fonts)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    // ---- Cloud sync (bring-your-own Supabase) ----
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // ---- Paynow online: QR generation (pure-Java, no UI deps) ----
    implementation(libs.zxing.core)

    // ---- Barcode scanning: CameraX preview + analysis, decoded with the
    // zxing-core reader above. No Play Services / ML Kit dependency. ----
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ---- Product images: Compose image loader with memory/disk cache. Loads
    // local files (freshly-picked, offline) and remote Supabase Storage URLs. ----
    implementation(libs.coil.compose)
}

// ★ NO KEYSTORE => NO RELEASE ARTIFACT. Checked against the task graph rather than at
// configuration time so a fresh clone can still run `assembleDebug` and the unit tests;
// only a task that would actually emit a release APK/AAB is stopped. Paired with the
// unsigned `signingConfig` above: even if this check is somehow bypassed, what comes out
// cannot be installed, so a debug-signed build can never reach the shop's phone by
// accident. See the note at the top of this file for why that matters more than it sounds.
gradle.taskGraph.whenReady {
    if (keystorePropsFile.exists()) return@whenReady
    val releaseTask = allTasks.firstOrNull { t ->
        t.project.path == project.path &&
            Regex("^(assemble|bundle|install|package)Release").containsMatchIn(t.name)
    } ?: return@whenReady
    throw GradleException(
        """
        Refusing to build ${releaseTask.name}: no release keystore.

        keystore.properties is missing, so this release build would be unsigned.
        It used to fall back to the DEBUG key, which produced an APK that installed
        fine and then could never be updated by a properly signed one without
        uninstalling — and uninstalling this app erases the shop's local database.

        To build a real release:
          1. Generate the keystore once, from the pos-android/ folder:
               keytool -genkeypair -v -keystore spot-pos-release.jks \
                 -alias spot-pos -keyalg RSA -keysize 2048 -validity 10000
          2. cp keystore.properties.example keystore.properties
          3. Fill in the four values with the passwords you just chose.
          4. Back up the .jks file AND those passwords somewhere off this machine.
             Losing them means never being able to update the installed app again.

        For a test build that does not need signing, use assembleDebug.
        """.trimIndent()
    )
}
