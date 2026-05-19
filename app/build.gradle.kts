plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.qc_ble_receive"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.qc_ble_receive"
        minSdk = 26
        targetSdk = 36
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
        // change(add)-hyungchul-20260513-1600: Java 17 source compatibility 설정
        sourceCompatibility = JavaVersion.VERSION_17

        // change(add)-hyungchul-20260513-1600: Java 17 target compatibility 설정
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}