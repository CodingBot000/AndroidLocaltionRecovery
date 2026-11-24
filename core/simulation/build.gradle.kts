plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}
kotlin.jvmToolchain(17)
dependencies {
    implementation(project(":core:map"))
    implementation(project(":core:location"))
    implementation(libs.gson)
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
application {
    mainClass.set("lab.recovery.simulation.EvaluateKt")
}
tasks.withType<Test>().configureEach {
    systemProperty("fixtureRoot", rootProject.file("fixtures").absolutePath)
}
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
