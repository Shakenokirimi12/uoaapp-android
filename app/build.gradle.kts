import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.shakenokirimi12.uoa_app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shakenokirimi12.uoa_app"
        minSdk = 26
        targetSdk = 36
        // 値は gradle.properties の uoaVersionCode / uoaVersionName (1 か所で管理する)。
        versionCode = providers.gradleProperty("uoaVersionCode").get().toInt()
        versionName = providers.gradleProperty("uoaVersionName").get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        // 時間割共有のサーバー。debug だけ、手元の wrangler dev (uoa-app-push) に向けて試せる:
        // ./gradlew :app:assembleDebug -PtimetableShareBaseUrl=http://10.0.2.2:8788 。空なら本番。
        debug {
            val shareBase = providers.gradleProperty("timetableShareBaseUrl").getOrElse("")
            buildConfigField("String", "TIMETABLE_SHARE_BASE_URL", "\"$shareBase\"")
        }
        release {
            buildConfigField("String", "TIMETABLE_SHARE_BASE_URL", "\"\"")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)

    // Navigation
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)

    // Lifecycle (ViewModel + LiveData)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)

    // RecyclerView + SwipeRefreshLayout
    implementation(libs.recyclerview)
    implementation(libs.swiperefreshlayout)

    // Network
    implementation(libs.okhttp)
    implementation(libs.jsch)
    implementation(libs.gson)

    // ConstraintLayout
    implementation(libs.constraintlayout)

    // Location & Maps
    implementation(libs.play.services.location)
    // Chrome Custom Tabs
    implementation(libs.browser.customtabs)

    // Security (EncryptedSharedPreferences)
    implementation(libs.security.crypto)

    // Background sync
    implementation(libs.work.runtime)

    // Push (FCM)。app/google-services.json が必要 (Firebase コンソールから取得、gitignore 済み)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // 時間割共有の QR コードを作る (エンコードだけ)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
