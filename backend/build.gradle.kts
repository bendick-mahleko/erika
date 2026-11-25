import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.springframework.boot.gradle.plugin.SpringBootPlugin
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

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
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-session-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    // Argon2id for Spring Security's Argon2PasswordEncoder.
    implementation(libs.bouncycastle.bcprov)
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation(libs.sentry.spring.boot)

    // E-invoice engine (same repository, built from source; ADR 0020).
    implementation(libs.invoice.core)
    // Validation (ADR 0021): KoSIT validator (XSD + Schematron), veraPDF for PDF/A-3.
    implementation(libs.kosit.validator)
    implementation("org.glassfish.jaxb:jaxb-runtime")
    implementation(libs.verapdf.validation.model)
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    // Builds test PDFs (a ZUGFeRD hybrid that is not PDF/A); same version as invoice-core.
    testImplementation("org.apache.pdfbox:pdfbox:3.0.8")
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

// The MARA mobile contract (ADR 0036–0038): same generator and options, own packages,
// so its models never clash with the web contract's.
val mobileApiSpec = layout.projectDirectory.file("../api/mobile.yaml")
val mobileApiOut = layout.buildDirectory.dir("generated/openapi-mobile")

val openApiGenerateMobile by tasks.registering(org.openapitools.generator.gradle.plugin.tasks.GenerateTask::class) {
    generatorName = "kotlin-spring"
    inputSpec = mobileApiSpec.asFile.toURI().toString()
    outputDir = mobileApiOut.get().asFile.absolutePath
    apiPackage = "de.erika.contract.mobile.api"
    modelPackage = "de.erika.contract.mobile.model"
    generateApiTests = false
    generateModelTests = false
    generateApiDocumentation = false
    generateModelDocumentation = false
    cleanupOutput = true
    configOptions = openApiGenerate.configOptions
    inputs.file(mobileApiSpec)
}

sourceSets.main {
    kotlin.srcDir(openApiOut.map { it.dir("src/main/kotlin") })
    kotlin.srcDir(mobileApiOut.map { it.dir("src/main/kotlin") })
}

tasks.compileKotlin { dependsOn(tasks.openApiGenerate, openApiGenerateMobile) }

springBoot {
    buildInfo()
}

// ---------------------------------------------------------------------------
// Pinned downloads (validation packs, test fixtures): fetched once into the Gradle
// user home, verified by SHA-256, extracted into build/. Nothing third-party is
// committed; the manifests with URLs and checksums are (ADR 0021).
// ---------------------------------------------------------------------------
val downloadCache = gradle.gradleUserHomeDir.resolve("caches/erika-downloads")

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun pinnedDownload(
    url: String,
    sha256: String,
    cacheDir: File,
): File {
    cacheDir.mkdirs()
    val file = cacheDir.resolve(sha256)
    if (!file.exists()) {
        val bytes = URI(url).toURL().openStream().use { it.readBytes() }
        val actual = sha256Hex(bytes)
        check(actual == sha256) { "Checksum mismatch for $url: expected $sha256, got $actual" }
        file.writeBytes(bytes)
    }
    return file
}

fun unzip(
    zip: File,
    target: File,
) {
    ZipInputStream(zip.inputStream()).use { input ->
        generateSequence { input.nextEntry }.forEach { entry ->
            val out = target.resolve(entry.name).normalize()
            check(out.toPath().startsWith(target.toPath())) { "Zip entry outside target: ${entry.name}" }
            if (entry.isDirectory) out.mkdirs() else out.also { it.parentFile.mkdirs() }.outputStream().use(input::copyTo)
        }
    }
}

/** validation-packs/<id>/ (manifest, rule texts, ERIKA scenarios) plus the downloaded artefacts. */
val validationPacksSource = layout.projectDirectory.dir("../validation-packs")
val validationPacksDir = layout.buildDirectory.dir("validation-packs")

val prepareValidationPacks by tasks.registering {
    description = "Assembles the validation packs: repository files plus pinned, checksum-verified artefacts."
    group = "build"
    val source = validationPacksSource
    val out = validationPacksDir
    val cache = downloadCache
    inputs.dir(source)
    outputs.dir(out)
    doLast {
        val target = out.get().asFile.apply { deleteRecursively() }
        source.asFile
            .listFiles { f -> f.resolve("pack.json").isFile }
            .orEmpty()
            .forEach { packDir ->
                @Suppress("UNCHECKED_CAST")
                val manifest = JsonSlurper().parse(packDir.resolve("pack.json")) as Map<String, Any?>
                val packTarget = target.resolve(manifest["id"] as String)
                packDir.copyRecursively(packTarget)
                @Suppress("UNCHECKED_CAST")
                (manifest["artefacts"] as List<Map<String, String>>).forEach { artefact ->
                    val zip = pinnedDownload(artefact.getValue("url"), artefact.getValue("sha256"), cache)
                    unzip(zip, packTarget.resolve(artefact.getValue("name")))
                }
            }
    }
}

/** Official test files for the conformance tests (same pinned suite as invoice-core). */
val testFixturesDir = layout.buildDirectory.dir("test-fixtures")

val prepareTestFixtures by tasks.registering {
    description = "Downloads the pinned XRechnung test suite and ZUGFeRD corpus for the validation tests."
    group = "verification"
    val out = testFixturesDir
    val cache = downloadCache
    outputs.dir(out)
    doLast {
        val target =
            out
                .get()
                .asFile
                .resolve("xrechnung")
                .apply { deleteRecursively() }
        val zip =
            pinnedDownload(
                "https://github.com/itplr-kosit/xrechnung-testsuite/releases/download/v2026-08-31/xrechnung-3.0.2-testsuite-2026-08-31.zip",
                "1e81ef7563e0fa04c0e0b0631318a7e0843a3c0beb57d83ef95ac0c4a095e3d7",
                cache,
            )
        unzip(zip, target)
        // ZUGFeRD corpus (official FeRD samples in XML-Rechnung/FX), same pin as invoice-core.
        val corpus =
            out
                .get()
                .asFile
                .resolve("zugferd-corpus")
                .apply { deleteRecursively() }
        unzip(
            pinnedDownload(
                "https://codeload.github.com/ZUGFeRD/corpus/zip/02d4b5684738cb2d9faac2cec6599dc0e86c16d5",
                "90d59b8f9ba98825616c9faf744609bb19c0ed9eff9d0d8aaf35c8c6d39c336e",
                cache,
            ),
            corpus,
        )
    }
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    dependsOn(prepareValidationPacks)
}

tasks.test {
    dependsOn(prepareValidationPacks, prepareTestFixtures)
    systemProperty("erika.validation.packs-dir", validationPacksDir.get().asFile.absolutePath)
    systemProperty("erika.test-fixtures", testFixturesDir.get().asFile.absolutePath)
    maxHeapSize = "2g"
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
