plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

tasks.register("verifyModuleBoundaries") {
    group = "verification"
    description = "Check project modules against the allowed dependency directions."
    doLast {
        val allowed = mapOf(
            ":app" to setOf(":feature:lab", ":core:simulation"),
            ":feature:lab" to setOf(":core:map", ":core:location", ":core:simulation", ":ui:map"),
            ":ui:map" to setOf(":core:map"),
            ":core:map" to emptySet(),
            ":core:location" to setOf(":core:map"),
            ":core:simulation" to setOf(":core:map", ":core:location"),
            ":benchmark" to emptySet(),
            ":live" to setOf(":core:map", ":core:location", ":core:simulation", ":ui:map"),
            ":route-lab" to setOf(":core:map", ":core:location"),
        )
        val modules = project.subprojects.filter { it.buildFile.isFile }.associateBy(Project::getPath)
        check(modules.keys == allowed.keys) { "Unexpected modules: ${modules.keys}" }
        for ((path, module) in modules) {
            if (path.startsWith(":core:")) {
                check(!module.plugins.hasPlugin("com.android.library") && !module.plugins.hasPlugin("com.android.application"))
                check(!module.plugins.hasPlugin("org.jetbrains.kotlin.plugin.compose"))
            }
            val actual = mutableSetOf<String>()
            // Variant classpaths contain Android Gradle Plugin's generated tested-project dependencies.
            module.configurations.filterNot { it.isCanBeResolved }.forEach { configuration ->
                configuration.dependencies.forEach dependencies@ { dependency ->
                    if (dependency is ProjectDependency) {
                        val target = dependency.path
                        val appTestOnly = path == ":app" && configuration.name.startsWith("androidTest") &&
                            target in setOf(":core:location", ":core:map")
                        val routeLabTestOnly = path == ":route-lab" && configuration.name == "testImplementation" &&
                            target == ":core:simulation"
                        val testedApk = path == ":benchmark" && configuration.name == "testedApks" && target == ":app"
                        check(appTestOnly || routeLabTestOnly || testedApk || target in allowed.getValue(path)) {
                            "$path ${configuration.name} cannot depend on $target"
                        }
                        if (!appTestOnly && !routeLabTestOnly && !testedApk) actual += target
                    } else {
                        val http = dependency.group.orEmpty().startsWith("com.squareup.okhttp")
                        check(!http || path == ":route-lab") { "$path cannot include HTTP dependency $dependency" }
                        check(dependency.name != "mockwebserver" || configuration.name == "testImplementation")
                        if (!path.startsWith(":core:")) return@dependencies

                        val group = dependency.group.orEmpty()
                        check(!group.startsWith("androidx.") && !group.startsWith("com.android") &&
                            !group.startsWith("org.jetbrains.compose") && !group.startsWith("com.google.android")) {
                            "$path ${configuration.name} has an Android or Compose dependency: $dependency"
                        }
                    }
                }
            }
            check(actual == allowed.getValue(path)) { "$path declares $actual, expected ${allowed.getValue(path)}" }
        }
    }
}
