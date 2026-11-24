plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace="com.assavendo.machinevirtuel"
    compileSdk=36
    defaultConfig {
        applicationId="com.assavendo.machinevirtuel"
        minSdk=26
        targetSdk=36
        versionCode=2
        versionName="0.2.0"
    }
    buildTypes {
        release {
            isMinifyEnabled=true
            isShrinkResources=true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro")
        }
        debug {
            applicationIdSuffix=".debug"
            versionNameSuffix="-debug"
        }
    }
    compileOptions {
        sourceCompatibility=JavaVersion.VERSION_17
        targetCompatibility=JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget="17" }
    packaging { jniLibs { useLegacyPackaging=true } }
}
dependencies {
    implementation("org.tukaani:xz:1.10")
    testImplementation(kotlin("test"))
}
