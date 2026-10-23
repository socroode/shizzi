import java.util.Properties
plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
}
android {
 namespace = "dev.shizzi.cammonitor"
 compileSdk = 35
 defaultConfig {
  applicationId = "dev.shizzi.cammonitor"
  minSdk = 30
  targetSdk = 35
  versionCode = 2
  versionName = "0.1.1-relay18-test"
 }
 sourceSets.getByName("main").java.srcDir("../admin/src/main/java")
 val keys = Properties().apply {
  val file = rootProject.file("keystore.properties")
  if(file.exists()) file.inputStream().use {load(it)}
 }
 signingConfigs {
  create("release") {
   val path=keys.getProperty("storeFile")
   if(path!=null){
    storeFile=rootProject.file(path)
    storePassword=keys.getProperty("storePassword")
    keyAlias=keys.getProperty("keyAlias")
    keyPassword=keys.getProperty("keyPassword")
   }
  }
 }
 buildTypes {
  getByName("release") {
   isMinifyEnabled=true
   proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro")
   if(keys.getProperty("storeFile")!=null) signingConfig=signingConfigs.getByName("release")
  }
 }
 compileOptions {
  sourceCompatibility=JavaVersion.VERSION_17
  targetCompatibility=JavaVersion.VERSION_17
 }
 kotlinOptions {jvmTarget="17"}
}
dependencies {
 implementation(project(":camrtc"))
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20250107")
}
