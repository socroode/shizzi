import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.shizzi.camadmin"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.shizzi.camadmin"
        minSdk = 30
        targetSdk = 35
        versionCode = 8
        versionName = "0.3.3-toggle-intercom-test"
    }
    sourceSets.getByName("main").java.srcDir("../admin/src/main/java")
    val properties = Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    signingConfigs {
        create("release") {
            val file = properties.getProperty("storeFile")
            if (file != null) {
                storeFile = rootProject.file(file)
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (properties.getProperty("storeFile") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":camrtc"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
}
