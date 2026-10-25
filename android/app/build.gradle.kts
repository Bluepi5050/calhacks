plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Server push (FCM) is optional: only a build with app/google-services.json (never committed) gets the fcm source
// set, firebase-messaging and the google-services plugin. Without it the app builds and uses local notifications only.
val hasFcm = file("google-services.json").exists()
if (hasFcm) apply(plugin = "com.google.gms.google-services")

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
        versionCode = 7
        versionName = "3.4"
        buildConfigField("String", "STDB_HOST", "\"$stdbHost\"")
        buildConfigField("String", "STDB_DB", "\"$stdbDb\"")
        buildConfigField("boolean", "FCM", hasFcm.toString())
        manifestPlaceholders["fcmEnabled"] = hasFcm.toString()
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    sourceSets { getByName("main") { if (hasFcm) java.srcDir("src/fcm/java") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    lint { abortOnError = false; checkReleaseBuilds = false }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("shots.dir", (project.findProperty("shotsDir") as String?) ?: "/tmp/smartbag-shots")
                it.maxHeapSize = "3g"
            }
        }
    }
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
    implementation("com.google.android.gms:play-services-location:21.3.0")
    if (hasFcm) {
        implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
        implementation("com.google.firebase:firebase-messaging")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Screenshot tests: Robolectric native graphics + Roborazzi render real Compose pixels without an emulator.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.22.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.22.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
