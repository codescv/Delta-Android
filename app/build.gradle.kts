import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val deltaVersionName = "2.0.8"
val deltaVersionCode = 20008

android {
    namespace = "com.codescv.delta"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.codescv.delta"
        minSdk = 26
        targetSdk = 35
        versionCode = deltaVersionCode
        versionName = deltaVersionName

        buildConfigField("String", "UPDATE_REPO", "\"codescv/Delta-Android\"")
        buildConfigField(
            "String",
            "UPDATE_API_BASE",
            "\"https://api.github.com/repos/codescv/Delta-Android\"",
        )

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
                cppFlags += listOf("-fexceptions", "-frtti")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    val keystorePath = System.getenv("DELTA_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).isFile }
    signingConfigs {
        // Checked-in key shared by local and CI builds so in-app updates keep the same signature.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("DELTA_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("DELTA_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("DELTA_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-DEBUG"
            externalNativeBuild {
                cmake {
                    // The cores are far too slow at -O0; always optimize native code.
                    arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
                }
            }
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    androidResources {
        // Keep PDFs / TSV uncompressed so they can be opened via AssetFileDescriptor.
        noCompress += listOf("pdf", "tsv", "json", "png")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = false
        unitTests.all {
            it.maxHeapSize = "2g"
            it.systemProperty("delta.assets", file("src/main/assets").absolutePath)
            it.systemProperty(
                "delta.hostlib",
                layout.buildDirectory.file("hostjni/libdelta_core.so").get().asFile.absolutePath,
            )
            it.dependsOn("buildHostJni")
            it.systemProperty("delta.requireHostJni", requireHostJni.toString())
            (project.findProperty("deltaGbaRom") as String?)?.let { rom -> it.systemProperty("delta.gbarom", rom) }
        }
    }

    // Release: universal APK + per-ABI splits (design §1). Debug stays a single APK.
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module")
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Host JNI must build (and NativeCoreTest must run) on CI or with -PrequireHostJni=true.
val requireHostJni: Boolean =
    (project.findProperty("requireHostJni") as String?)?.toBoolean() == true || System.getenv("CI") == "true"

// Builds the same native engine (all cores + JNI bridge) for the host so JVM unit tests
// can load it with System.load() and exercise real core loading / emulation.
val sdkDir: String = run {
    val props = Properties()
    val lp = rootProject.file("local.properties")
    if (lp.isFile) lp.inputStream().use { props.load(it) }
    props.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: ""
}

tasks.register<Exec>("configureHostJni") {
    val out = layout.buildDirectory.dir("hostjni").get().asFile
    val cmakeBin = "$sdkDir/cmake/3.22.1/bin"
    val hostClang = "$sdkDir/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin"
    inputs.file("src/main/cpp/CMakeLists.txt")
    outputs.file(File(out, "build.ninja"))
    doFirst { out.mkdirs() }
    workingDir = out
    isIgnoreExitValue = !requireHostJni
    commandLine(
        "$cmakeBin/cmake", "-G", "Ninja",
        "-DCMAKE_MAKE_PROGRAM=$cmakeBin/ninja",
        "-DDELTA_HOST_BUILD=ON",
        "-DCMAKE_C_COMPILER=$hostClang/clang",
        "-DCMAKE_CXX_COMPILER=$hostClang/clang++",
        "-DCMAKE_C_COMPILER_TARGET=x86_64-linux-gnu",
        "-DCMAKE_CXX_COMPILER_TARGET=x86_64-linux-gnu",
        "-DCMAKE_BUILD_TYPE=Release",
        "-DJAVA_HOME=${System.getProperty("java.home")}",
        file("src/main/cpp").absolutePath,
    )
}

tasks.register<Exec>("buildHostJni") {
    dependsOn("configureHostJni")
    val out = layout.buildDirectory.dir("hostjni").get().asFile
    workingDir = out
    isIgnoreExitValue = !requireHostJni
    inputs.dir("src/main/cpp")
    outputs.file(File(out, "libdelta_core.so"))
    commandLine("$sdkDir/cmake/3.22.1/bin/ninja", "delta_core")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.documentfile)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
