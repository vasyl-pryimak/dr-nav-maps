plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

application {
    applicationName = "map-builder"
    mainClass.set("ua.pryimak.drnav.maps.MainKt")
    // all of Ukraine needs ~3 GB; more heap means fewer GC pauses
    applicationDefaultJvmArgs = listOf("-Xmx8g")
}

dependencies {
    implementation("org.openstreetmap.pbf:osmpbf:1.5.0")
    testImplementation("junit:junit:4.13.2")
}
