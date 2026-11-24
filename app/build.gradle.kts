plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "lab.recovery.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "lab.recovery.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    buildFeatures.compose = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").assets.srcDir(rootProject.file("fixtures"))
}
kotlin.jvmToolchain(17)
dependencies {
    implementation(project(":feature:lab"))
    androidTestImplementation(project(":core:location"))
    androidTestImplementation(project(":core:map"))
    implementation(project(":core:simulation"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.coroutines.core)
    androidTestImplementation(libs.android.test.runner)
    debugImplementation(libs.compose.test.manifest)
    "benchmarkImplementation"(libs.profileinstaller)
}
