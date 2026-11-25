plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin.jvmToolchain(17)
dependencies {
    implementation(project(":core:map"))
    implementation(project(":core:location"))
    implementation(libs.coroutines.core)
    implementation(libs.gson)
    implementation(libs.okhttp)
    testImplementation(project(":core:simulation"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
}

tasks.test {
    workingDir = rootProject.projectDir
}
