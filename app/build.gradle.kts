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
        versionCode = 118
        versionName = "0.1.18"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 署名の鍵を固定する（0.1.16 から）。以前は CI のたびに鍵が変わり、上書きで入れられなかった。
    // 鍵はリポジトリの signing/g2cam.jks（利用者が了承。公開されているが、更新はこのリポジトリの配布からしか取らない）
    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("signing/g2cam.jks")
            storePassword = "g2cam-companion"
            keyAlias = "g2cam"
            keyPassword = "g2cam-companion"
            storeType = "PKCS12"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
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

    // 遡り録画のつなぎ合わせ（Mp4Merger）と区切りの計算（PreRollPlan）の試験
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
