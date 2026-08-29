plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    api(project(":core-types"))
    api(project(":core-model"))
    api(project(":core-nav"))
    api(project(":core-map"))
    api(project(":core-assets"))
    testImplementation(project(":core-replay"))
    testImplementation(kotlin("test"))

}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
