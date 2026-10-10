plugins { id("com.android.application") }

android {
    namespace = "com.assistant.ai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.assistant.ai"
        minSdk = 26
        targetSdk = 36
        versionCode = 23
        versionName = "0.25.0"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
}
