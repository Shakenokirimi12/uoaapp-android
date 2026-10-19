import java.util.Properties

// Wear OS 版。スマホ (app) から Data Layer で時間割を受け取り、アプリ・タイル・コンプリケーションで見せる。
// Data Layer はパッケージ名と署名が同じアプリどうしでしか繋がらないので、applicationId と署名は app と揃える。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.shakenokirimi12.uoa_app.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shakenokirimi12.uoa_app"
        // Wear OS 3 以降 (Data Layer / Tiles / Compose for Wear OS の前提)
        minSdk = 30
        targetSdk = 36
        // Play は同じアプリの成果物どうしで versionCode の重複を許さない。スマホ版と被らないよう 1000000 を足す。
        versionCode = 1_000_000 + providers.gradleProperty("uoaVersionCode").get().toInt()
        versionName = providers.gradleProperty("uoaVersionName").get()
    }

    if (keystorePropertiesFile.exists()) {
        signingConfigs {
            create("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile")!!)
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)

    // Compose UI 本体は wear-compose が連れてくる版を使う (BOM で上げると compileSdk 37 を要求される)。
    implementation(libs.activity.compose)
    implementation(libs.fragment)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)

    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.material)
    implementation(libs.concurrent.futures)

    implementation(libs.wear.complications.data.source.ktx)

    // 授業のライブアップデート (Ongoing Activity)
    implementation(libs.wear.ongoing)

    testImplementation(libs.junit)
}
