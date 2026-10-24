plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// SpacetimeDB database the app talks to (override with -PstdbHost=... -PstdbDb=...)
val stdbHost: String = (project.findProperty("stdbHost") as String?) ?: "https://maincloud.spacetimedb.com"
val stdbDb: String = (project.findProperty("stdbDb") as String?) ?: "smartcart-5dt35"

android {
    namespace = "space.mana.smartbag"
    compileSdk = 34
    defaultConfig {
        applicationId = "space.mana.smartbag"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
        buildConfigField("String", "STDB_HOST", "\"$stdbHost\"")
        buildConfigField("String", "STDB_DB", "\"$stdbDb\"")
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
