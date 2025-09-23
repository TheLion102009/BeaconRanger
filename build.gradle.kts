import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.24"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "de.thelion.beaconranger"
version = "1.0.0"

description = "BeaconRanger - Erweitert die Reichweite von Beacons"

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
    kotlinOptions.freeCompilerArgs += "-Xjvm-default=all"
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/repositories/snapshots")
    maven("https://repo.codemc.org/repository/maven-public")
}

dependencies {
    // Paper API (für 1.20.4)
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
    
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation(kotlin("stdlib-jdk8"))
}

tasks {
    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        
        // Replace variables in YML files
        filesMatching("**/*.yml") {
            expand(props)
        }
        filesMatching("**/*.yaml") {
            expand(props)
        }
    }
    
    shadowJar {
        archiveBaseName.set("BeaconRanger")
        archiveClassifier.set("")
        archiveVersion.set(version as String)
        
        relocate("kotlin", "$group.libs.kotlin")
        relocate("kotlinx.coroutines", "$group.libs.kotlinx.coroutines")
        
        minimize()
    }
    
    build {
        dependsOn(shadowJar)
    }
}