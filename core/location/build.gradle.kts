plugins {
    alias(libs.plugins.kotlin.jvm)
}
kotlin.jvmToolchain(17)
dependencies {
    implementation(project(":core:map"))
    testImplementation(libs.junit)
}
