plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    api(project(":core-types"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
