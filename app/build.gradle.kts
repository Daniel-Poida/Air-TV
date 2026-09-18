plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

import java.util.Properties

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

// Optional use of native libraries from the exact upstream v0.0.31 release.
// Full C/C++ source remains included; omit this property to build it with the NDK.
val usePrebuiltNative = providers.gradleProperty("prebuiltNative").orNull == "true"
val allAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

android {
    // Existing upstream advanced strings fall back to English; new lint errors remain checked.
    lint { baseline = file("lint-baseline.xml") }
    namespace = "io.github.jqssun.airplay"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    providers.gradleProperty("prototypeKeystore").orNull?.let { path ->
        signingConfigs.getByName("debug").storeFile = file(path)
    }
    if (localProps.containsKey("storeFile")) {
        signingConfigs {
            create("release") {
                storeFile = file(localProps.getProperty("storeFile"))
                storePassword = localProps.getProperty("storePassword")
                keyAlias = localProps.getProperty("keyAlias")
                keyPassword = localProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "dev.airtv.receiver"
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.4"

        if (!usePrebuiltNative) {
            externalNativeBuild {
                cmake {
                    arguments += "-DANDROID_STL=c++_shared"
                    arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                }
            }
        }
    }

    if (!usePrebuiltNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
    sourceSets.getByName("main").jniLibs.setSrcDirs(
        if (usePrebuiltNative) listOf("src/prebuilt/jniLibs") else emptyList<String>()
    )

    buildTypes {
        debug {
            ndk { abiFilters += allAbis }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            if (providers.gradleProperty("prototypeSigning").orNull == "true") {
                signingConfig = signingConfigs.getByName("debug")
            }
            ndk { abiFilters += allAbis }
        }
        // debuggable build with HWASan (arm64) + UBSan in native code
        create("sanitize") {
            initWith(getByName("debug"))
            matchingFallbacks += "debug"
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
            externalNativeBuild {
                cmake { arguments += "-DSANITIZE=ON" }
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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
        prefab = true
    }
}

tasks.register("applyUxplayPatches") {
    doLast {
        // Restore pristine versions of patch targets. This also works in source
        // archives without .git metadata and across repeated native builds.
        val source = file("src/main/cpp/third_party/UxPlay")
        val originals = file("src/main/cpp/upstream-original/UxPlay")
        originals.walkTopDown().filter { it.isFile }.forEach { original ->
            original.copyTo(source.resolve(original.relativeTo(originals)), overwrite = true)
        }
        val patches = file("src/main/cpp/patches/UxPlay").listFiles { f -> f.extension == "patch" }!!.sorted()
        patches.forEach { patch ->
            val proc = ProcessBuilder("git", "-c", "core.autocrlf=false", "-C", source.absolutePath,
                "apply", "--unidiff-zero", patch.absolutePath).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            check(proc.waitFor() == 0) { "Cannot apply ${patch.name}:\n$out" }
        }
    }
}
tasks.configureEach {
    if (!usePrebuiltNative && name.startsWith("configureCMake")) dependsOn("applyUxplayPatches")
}

tasks.withType<Zip>().configureEach {
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}

dependencies {
    implementation(project(":airdrop-core"))
    implementation("com.google.zxing:core:3.5.3")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.datastore.prefs)
    implementation(libs.androidx.media)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui.compose.material3)
    implementation(libs.media3.transformer)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.oboe)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
}
