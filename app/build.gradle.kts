plugins { id("com.android.application") }

android {
    namespace = "com.assistant.ai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.assistant.ai"
        minSdk = 26
        targetSdk = 36
        versionCode = 26
        versionName = "0.26.2"
    }

    buildFeatures {
        buildConfig = true
    }

    // Dua build terpisah dari satu kode sama (sistem owner-user-app-system):
    //  - owner : com.senxvv.assistant -> dashboard + pairing + sidik jari
    //  - user  : com.senxuser.assistant -> gate password 4 digit ke server
    flavorDimensions += "role"
    productFlavors {
        create("owner") {
            dimension = "role"
            applicationId = "com.senxvv.assistant"
            buildConfigField("boolean", "IS_OWNER_BUILD", "true")
        }
        create("user") {
            dimension = "role"
            applicationId = "com.senxuser.assistant"
            buildConfigField("boolean", "IS_OWNER_BUILD", "false")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    // Dipakai OwnerGate.kt; ditaruh sebagai dependency biasa (bukan ownerImplementation)
    // karena OwnerGate.kt ada di source set main yang dipakai kedua flavor.
    implementation("androidx.biometric:biometric:1.1.0")
}
