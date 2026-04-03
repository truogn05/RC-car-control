plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.esp32"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.esp32"
        minSdk = 36
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    
    // Thư viện Virtual Joystick
//    implementation("io.github.controlwear:virtualjoystick:1.10.1")
    implementation("com.github.controlwear:virtual-joystick-android:master")
    
    // Thư viện Paho MQTT
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation(libs.androidx.constraintlayout)

    // Thư viện cấu hình Wi-Fi SmartConfig (ESP-Touch)
    implementation("com.github.EspressifApp:lib-esptouch-android:1.1.1")

    // Gson (lưu danh sách xe)
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}