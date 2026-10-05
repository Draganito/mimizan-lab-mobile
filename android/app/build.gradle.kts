import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------- Rust core
// The Mimizan Lab core is a Rust cdylib (../rust/mimizan-mobile, depending on
// the unchanged mimizan-core). Gradle drives cargo-ndk and the UniFFI Kotlin
// generator so that a plain `./gradlew assembleDebug` builds everything.

val rustDir = rootProject.file("../rust/mimizan-mobile")
val mimizanCoreDir = rootProject.file("../../mimizan/crates/mimizan-core")
val rustProfile = (project.findProperty("mimizan.rustProfile") as String?) ?: "release"
val rustAbi = "arm64-v8a"
val rustJniLibs = layout.buildDirectory.dir("rust/jniLibs")
val uniffiOut = layout.buildDirectory.dir("generated/uniffi/kotlin")
val androidPlatform = libs.versions.minSdk.get()

fun ndkHome(): String {
    System.getenv("ANDROID_NDK_HOME")?.let { return it }
    val sdk = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: run {
            val lp = rootProject.file("local.properties")
            if (lp.exists()) {
                Properties().also { p -> lp.inputStream().use { p.load(it) } }.getProperty("sdk.dir")
            } else null
        }
        ?: "${System.getProperty("user.home")}/.local/opt/android-sdk"
    val ndks = file("$sdk/ndk").listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
    return ndks.lastOrNull()?.absolutePath ?: error("No NDK found under $sdk/ndk; install one with sdkmanager")
}

val cargoBuild = tasks.register<Exec>("cargoBuild") {
    group = "rust"
    description = "cargo-ndk build of libmimizan_mobile.so ($rustAbi, $rustProfile)"
    workingDir = rustDir
    inputs.dir(rustDir.resolve("src"))
    inputs.file(rustDir.resolve("Cargo.toml"))
    inputs.file(rustDir.resolve("uniffi.toml"))
    inputs.dir(mimizanCoreDir.resolve("src"))
    inputs.file(mimizanCoreDir.resolve("Cargo.toml"))
    inputs.property("profile", rustProfile)
    outputs.dir(rustJniLibs)
    environment("ANDROID_NDK_HOME", ndkHome())
    val args = mutableListOf(
        "cargo", "ndk", "-t", rustAbi, "--platform", androidPlatform,
        "-o", rustJniLibs.get().asFile.absolutePath, "build", "--lib",
    )
    if (rustProfile == "release") args += "--release"
    commandLine(args)
}

val generateUniffiBindings = tasks.register<Exec>("generateUniffiBindings") {
    group = "rust"
    description = "UniFFI Kotlin bindings from the compiled library (library mode)"
    dependsOn(cargoBuild)
    workingDir = rustDir
    val lib = rustJniLibs.map { it.file("$rustAbi/libmimizan_mobile.so") }
    inputs.file(lib)
    inputs.file(rustDir.resolve("uniffi.toml"))
    outputs.dir(uniffiOut)
    commandLine(
        "cargo", "run", "--quiet", "--features", "bindgen", "--bin", "uniffi-bindgen", "--",
        "generate", "--library", lib.get().asFile.absolutePath,
        "--language", "kotlin", "--no-format",
        "--out-dir", uniffiOut.get().asFile.absolutePath,
    )
}

// ------------------------------------------------------------------ Android

android {
    namespace = "ch.bojovic.mimizanlab"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()
    // Same NDK cargo-ndk links against, so AGP can strip the Rust library.
    ndkVersion = file(ndkHome()).name

    defaultConfig {
        applicationId = "ch.bojovic.mimizanlab"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 4
        versionName = "0.4.0"
        ndk { abiFilters += rustAbi }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key until a release key exists, so
            // `./gradlew installRelease` works on the test phone.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            kotlin.directories.add(uniffiOut.get().asFile.absolutePath)
            jniLibs.directories.add(rustJniLibs.get().asFile.absolutePath)
        }
    }

    packaging {
        jniLibs {
            // JNA ships its own natives per ABI; keep them uncompressed for JNA's loader.
            useLegacyPackaging = false
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

tasks.named("preBuild") {
    dependsOn(generateUniffiBindings)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.exifinterface)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation("${libs.jna.get()}@aar")
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
