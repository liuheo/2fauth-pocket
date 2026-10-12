plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cn.liuheo.twofauth"
    compileSdk = 35
    defaultConfig {
        applicationId = "cn.liuheo.twofauth"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    providers.gradleProperty("pocketDebugKeystore").orNull?.let { path ->
        signingConfigs.getByName("debug").storeFile = file(path)
    }
    val releaseKey = providers.environmentVariable("POCKET_RELEASE_KEYSTORE").orNull
    if (releaseKey != null) {
        signingConfigs.create("production") {
            storeFile = file(releaseKey)
            storePassword = providers.environmentVariable("POCKET_RELEASE_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("POCKET_RELEASE_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("POCKET_RELEASE_KEY_PASSWORD").get()
        }
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseKey != null) signingConfig = signingConfigs.getByName("production")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.caverock:androidsvg-aar:1.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

