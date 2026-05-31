plugins {
    id("com.android.application")
}

android {
    namespace = "at.vchrist.gpsdosmviewer"
    compileSdk = 36

    defaultConfig {
        applicationId = "at.vchrist.gpsdosmviewer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.activity:activity:1.13.0")
}
