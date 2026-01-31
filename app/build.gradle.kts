plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.technicallyrural.smsmatrixbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.technicallyrural.smsmatrixbridge"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class).configureEach {
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.service)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.gson)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Room Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // Lifecycle (ViewModel, LiveData)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
