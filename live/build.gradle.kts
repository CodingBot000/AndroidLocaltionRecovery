import groovy.json.JsonOutput
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "lab.recovery.live"
    compileSdk = 36
    defaultConfig {
        applicationId = "lab.recovery.live"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures.compose = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").assets.srcDirs(
        layout.buildDirectory.dir("generated/liveFixtures"), layout.buildDirectory.dir("generated/livePlans"),
    )
}

val syncLiveFixtures by tasks.registering(Sync::class) {
    from(rootProject.file("fixtures")) {
        include("osm/catalog.json", "osm/maps/*.json", "osm/NOTICE.txt")
    }
    into(layout.buildDirectory.dir("generated/liveFixtures"))
}

val generateLivePlans by tasks.registering {
    val fixtures = rootProject.fileTree("fixtures/osm/scenarios") { include("*.json") }
    val output = layout.buildDirectory.dir("generated/livePlans")
    inputs.files(fixtures).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(output)
    doLast {
        val folder = output.get().asFile
        folder.deleteRecursively()
        val directory = folder.resolve("osm/scenarios").apply { mkdirs() }
        val fields = setOf("schemaVersion", "scenarioId", "mapId", "mapChecksum", "routeVersion", "plannedRoute")
        fixtures.forEach { file ->
            val scenario = JsonSlurper().parse(file) as Map<*, *>
            val plan = scenario.filterKeys { it in fields }
            check(plan.size == fields.size)
            directory.resolve(file.name).writeText(JsonOutput.toJson(plan))
        }
    }
}

tasks.named("preBuild") { dependsOn(syncLiveFixtures, generateLivePlans) }

kotlin.jvmToolchain(17)
dependencies {
    implementation(project(":core:map"))
    implementation(project(":core:location"))
    implementation(project(":core:simulation"))
    implementation(project(":ui:map"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.compose)
    implementation(libs.coroutines.core)
    implementation(libs.gson)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.uiautomator)
}
