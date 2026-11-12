plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun configString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r") + "\""

android {
    namespace = "br.com.medidor.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "br.com.medidor.app"
        minSdk = 30
        targetSdk = 36
        versionCode = System.getenv("APPMETRICAS_VERSION_CODE")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        versionName = System.getenv("APPMETRICAS_VERSION_NAME") ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PLAY_PRODUCT_ID", configString(System.getenv("APPMETRICAS_PLAY_PRODUCT_ID")?.takeIf { it.isNotBlank() } ?: "pro_completo"))
        buildConfigField("String", "PLAY_LICENSE_KEY", configString(System.getenv("APPMETRICAS_PLAY_LICENSE_KEY") ?: ""))
    }

    // Signed releases require secrets at build time. Never commit the keystore.
    val keyFile = System.getenv("APPMETRICAS_KEYSTORE_PATH")
    val storePasswordSecret = System.getenv("APPMETRICAS_KEYSTORE_PASSWORD")
    val alias = System.getenv("APPMETRICAS_KEY_ALIAS")
    val privateKeySecret = System.getenv("APPMETRICAS_KEY_PASSWORD")
    val signedRelease = listOf(keyFile, storePasswordSecret, alias, privateKeySecret).all { !it.isNullOrBlank() }
    signingConfigs {
        if (signedRelease) {
            create("appmetricasRelease") {
                storeFile = file(keyFile!!)
                storePassword = storePasswordSecret
                keyAlias = alias
                keyPassword = privateKeySecret
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            if (signedRelease) signingConfig = signingConfigs.getByName("appmetricasRelease")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.fragment:fragment:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")

    val cameraX = "1.6.2"
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("com.google.ar:core:1.56.0")
    implementation("org.opencv:opencv:4.13.0")
    implementation("com.android.billingclient:billing:9.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
