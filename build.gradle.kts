plugins {
    id("com.android.application") version "8.6.1" apply false
    id("com.android.library") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
}

val engineModuleNames = setOf(
    "core-types", "core-model", "core-nav", "core-map", "core-assets", "core-replay", "engine"
)

subprojects {
    if (name in engineModuleNames) {
        tasks.register("verifyNoAndroidImports") {
            group = "verification"
            description = "Fails if this module imports android.* — it must stay pure Kotlin (edge-engine deliverable)."
            doLast {
                val offenders = mutableListOf<String>()
                val srcDir = project.projectDir.resolve("src")
                if (srcDir.exists()) {
                    srcDir.walkTopDown()
                        .filter { it.isFile && it.extension == "kt" }
                        .forEach { file ->
                            file.readLines().forEachIndexed { lineNo, line ->
                                val trimmed = line.trim()
                                if (trimmed.startsWith("import android.") || trimmed.startsWith("import androidx.")) {
                                    offenders += "${file.relativeTo(project.projectDir)}:${lineNo + 1}: $trimmed"
                                }
                            }
                        }
                }
                if (offenders.isNotEmpty()) {
                    throw GradleException(
                        "Module '${project.name}' is below the no-android.* line but imports Android " +
                            "types (Aneesh/SIH-IDR-android.md §2 — this breaks the edge-engine deliverable):\n" +
                            offenders.joinToString("\n")
                    )
                }
            }
        }
        plugins.withId("org.jetbrains.kotlin.jvm") {
            tasks.named("check") { dependsOn("verifyNoAndroidImports") }
        }
    }
}
