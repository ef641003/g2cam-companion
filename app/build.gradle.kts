plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ef64nagaoka.g2cam"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ef64nagaoka.g2cam"
        // 30 以上: camera 種別のフォアグラウンドサービス定数があり、MediaStore で DCIM に直接保存できる
        minSdk = 30
        targetSdk = 34
        versionCode = 106
        versionName = "0.1.6"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-extensions:$camerax")

    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
