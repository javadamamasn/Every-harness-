plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.harnesshub.app"
    compileSdk = 36 // androidx stack (core 1.18, nav 2.9.8) requires 36; targetSdk stays 35

    defaultConfig {
        applicationId = "com.harnesshub.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    androidResources {
        localeFilters += listOf("en")
    }

    buildTypes {
        release {
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
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:harness-api"))
    implementation(project(":core:termux"))
    implementation(project(":core:transport"))
    implementation(project(":core:discovery"))
    implementation(project(":harness:hermes"))
    implementation(project(":harness:claude"))
    implementation(project(":harness:opencode"))
    implementation(project(":harness:codex"))
    implementation(libs.okhttp)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
