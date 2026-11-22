import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.openapi.generator)
    alias(libs.plugins.spotless)
}

group = "de.erika"
version = providers.environmentVariable("ERIKA_VERSION").getOrElse("0.1.0-SNAPSHOT")

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.addAll("-Xjsr305=strict")
        allWarningsAsErrors = false
    }
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(platform(libs.spring.modulith.bom))
    implementation(platform(libs.awssdk.bom))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation(libs.sentry.spring.boot)
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-docs")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---------------------------------------------------------------------------
// Contract-first API: server interfaces and models are generated from
// ../api/openapi.yaml. Controllers implement the generated interfaces, so a
// contract change that the code doesn't follow fails compilation.
// ---------------------------------------------------------------------------
val openApiSpec = layout.projectDirectory.file("../api/openapi.yaml")
val openApiOut = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
    generatorName = "kotlin-spring"
    // A file: URI, not a path: the spec parser misreads Windows paths ("E:\…") as URIs.
    inputSpec = openApiSpec.asFile.toURI().toString()
    outputDir = openApiOut.get().asFile.absolutePath
    apiPackage = "de.erika.contract.api"
    modelPackage = "de.erika.contract.model"
    generateApiTests = false
    generateModelTests = false
    generateApiDocumentation = false
    generateModelDocumentation = false
    cleanupOutput = true
    configOptions =
        mapOf(
            "interfaceOnly" to "true",
            "skipDefaultInterface" to "true",
            "useSpringBoot3" to "true",
            "useTags" to "true",
            "useBeanValidation" to "true",
            "documentationProvider" to "none",
            "annotationLibrary" to "none",
            "exceptionHandler" to "false",
            "gradleBuildFile" to "false",
            "enumPropertyNaming" to "UPPERCASE",
            "requestMappingMode" to "api_interface",
        )
}

tasks.openApiGenerate {
    inputs.file(openApiSpec)
}

sourceSets.main {
    kotlin.srcDir(openApiOut.map { it.dir("src/main/kotlin") })
}

tasks.compileKotlin { dependsOn(tasks.openApiGenerate) }

springBoot {
    buildInfo()
}

tasks.test {
    useJUnitPlatform()
    // Integration tests need Docker (Testcontainers); containers are shared per JVM.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}

tasks.check { dependsOn(tasks.spotlessCheck) }
