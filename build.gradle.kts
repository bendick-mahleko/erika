import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.spotless)
    alias(libs.plugins.kover)
    `java-library`
    `maven-publish`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
    withSourcesJar()
}

kotlin {
    // Library: every public declaration is deliberate (SemVer, ADR 0003).
    explicitApi()
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

// The spike (S2-a) has heavy dependencies (Mustangproject, veraPDF) that must
// never leak into the library, so it lives in its own source set.
val spike: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
val spikeImplementation: Configuration by configurations.getting
val spikeRuntimeOnly: Configuration by configurations.getting

dependencies {
    // Only dependency of the library: PDF/A-3 attachment extraction.
    implementation(libs.pdfbox)

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlin:kotlin-reflect")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    spikeImplementation(platform(libs.junit.bom))
    spikeImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    spikeImplementation(libs.mustang.library)
    spikeImplementation(libs.mustang.validator)
    spikeImplementation(libs.pdfbox)
    spikeRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---------------------------------------------------------------------------
// Official test files, pinned by version and SHA-256 (Build Plan, Stage 2).
// Downloaded once into the Gradle user home and extracted into build/test-suites.
// ---------------------------------------------------------------------------
data class TestSuite(
    val name: String,
    val url: String,
    val sha256: String,
)

val testSuites =
    listOf(
        TestSuite(
            "xrechnung",
            "https://github.com/itplr-kosit/xrechnung-testsuite/releases/download/v2026-08-31/xrechnung-3.0.2-testsuite-2026-08-31.zip",
            "1e81ef7563e0fa04c0e0b0631318a7e0843a3c0beb57d83ef95ac0c4a095e3d7",
        ),
        TestSuite(
            "en16931-ubl",
            "https://github.com/ConnectingEurope/eInvoicing-EN16931/releases/download/validation-1.3.17/en16931-ubl-1.3.17.zip",
            "bf02a0e3f6cbe692a4a28e70be7bdcaded5612b4169b543e13d6cb4f33c29e92",
        ),
        TestSuite(
            "en16931-cii",
            "https://github.com/ConnectingEurope/eInvoicing-EN16931/releases/download/validation-1.3.17/en16931-cii-1.3.17.zip",
            "e5f3235824b6b38454b1d54cf3f14e01efb5b33971fbefc4e64997d0ef619e89",
        ),
        TestSuite(
            "zugferd-corpus",
            "https://codeload.github.com/ZUGFeRD/corpus/zip/02d4b5684738cb2d9faac2cec6599dc0e86c16d5",
            "90d59b8f9ba98825616c9faf744609bb19c0ed9eff9d0d8aaf35c8c6d39c336e",
        ),
    )

val testSuitesDir = layout.buildDirectory.dir("test-suites")
val suiteCacheDir = gradle.gradleUserHomeDir.resolve("caches/erika-test-suites")

val downloadTestSuites by tasks.registering {
    description = "Downloads the pinned official e-invoice test suites (verified by SHA-256)."
    group = "verification"
    val outDir = testSuitesDir
    val cacheDir = suiteCacheDir
    val suites = testSuites
    inputs.property("suites", suites.map { "${it.name}=${it.sha256}" })
    outputs.dir(outDir)
    doLast {
        cacheDir.mkdirs()
        for (suite in suites) {
            val zip = cacheDir.resolve("${suite.sha256}.zip")
            if (!zip.exists() || sha256(zip.readBytes()) != suite.sha256) {
                logger.lifecycle("Downloading test suite ${suite.name}")
                val bytes = URI(suite.url).toURL().openStream().use { it.readBytes() }
                val actual = sha256(bytes)
                check(actual == suite.sha256) { "Checksum mismatch for ${suite.name}: expected ${suite.sha256}, got $actual" }
                zip.writeBytes(bytes)
            }
            val target = outDir.get().asFile.resolve(suite.name)
            target.deleteRecursively()
            unzip(zip, target)
        }
    }
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun unzip(
    zip: File,
    target: File,
) {
    ZipInputStream(zip.inputStream()).use { input ->
        generateSequence { input.nextEntry }.forEach { entry ->
            val file = target.resolve(entry.name).normalize()
            check(file.toPath().startsWith(target.toPath())) { "Zip entry outside target: ${entry.name}" }
            if (entry.isDirectory) {
                file.mkdirs()
            } else {
                file.parentFile.mkdirs()
                file.outputStream().use { input.copyTo(it) }
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    dependsOn(downloadTestSuites)
    systemProperty("erika.testSuites", testSuitesDir.get().asFile.absolutePath)
    systemProperty(
        "erika.goldenDir",
        layout.projectDirectory
            .dir("src/test/resources/golden")
            .asFile.absolutePath,
    )
    // ./gradlew test -PupdateGolden rewrites the golden files instead of comparing.
    systemProperty("erika.updateGolden", providers.gradleProperty("updateGolden").isPresent.toString())
    maxHeapSize = "1g"
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

val spikeTest by tasks.registering(Test::class) {
    description = "Spike S2-a: ZUGFeRD PDF/A-3 generation with Mustangproject, checked by veraPDF. Not part of `check`."
    group = "verification"
    testClassesDirs = spike.output.classesDirs
    classpath = spike.runtimeClasspath
    useJUnitPlatform()
    systemProperty(
        "erika.spikeOut",
        layout.buildDirectory
            .dir("spike")
            .get()
            .asFile.absolutePath,
    )
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

kover {
    reports {
        verify {
            rule("invoice-core line coverage (Software Design §9 gate)") {
                minBound(90)
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.spotlessCheck, tasks.named("koverVerify"))
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

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            pom {
                name = "invoice-core"
                description =
                    "Neutral EN 16931 invoice model: parse XRechnung (UBL/CII) and ZUGFeRD/Factur-X, detect formats, check arithmetic and § 14 UStG content."
            }
        }
    }
    repositories {
        // GitHub Packages; CI sets GITHUB_REPOSITORY, GITHUB_ACTOR and GITHUB_TOKEN.
        val repo = System.getenv("GITHUB_REPOSITORY")
        if (repo != null) {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/$repo")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}
