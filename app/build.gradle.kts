plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.esmu.ai"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.esmu.ai"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
        val key = System.getenv("ESMU_API_KEY") ?: ""
        buildConfigField("String", "API_KEY", "\"$key\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}