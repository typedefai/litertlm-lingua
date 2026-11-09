import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.typedefai.lingua"
  compileSdk { this.version = release(37) { minorApiLevel = 0 } }

  defaultConfig {
    applicationId = "com.typedefai.lingua"
    minSdk = 31
    targetSdk = 37
    versionCode = 1
    versionName = "0.1.0"
    ndk {
      abiFilters += listOf("arm64-v8a", "x86_64")
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
  }
  packaging {
    // Gallery/official sample parity: only liblitertlm_jni.so collides between LiteRT-LM and
    // MediaPipe.
    jniLibs {
      pickFirsts.add("**/liblitertlm_jni.so")
    }
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_11)
    freeCompilerArgs.addAll("-Xskip-metadata-version-check")
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.material.icon.extended)
  implementation(libs.androidx.splashscreen)
  implementation(libs.litertlm)
  implementation(libs.tasks.audio)
  debugImplementation(libs.androidx.ui.tooling)
}
