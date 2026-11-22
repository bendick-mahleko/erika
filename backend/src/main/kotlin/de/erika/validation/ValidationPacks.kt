package de.erika.validation

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

@ConfigurationProperties("erika.validation")
data class ValidationProperties(
    /** Directory with one sub-directory per pack (`<id>/pack.json` …), assembled by `prepareValidationPacks`. */
    val packsDir: Path = Path.of("build/validation-packs"),
    /** Validate on start-up so the first job isn't slow and a broken pack fails the deployment. */
    val warmUp: Boolean = true,
)

/** One KoSIT configuration of a pack: scenario file and the repository its paths are relative to. */
data class PackConfiguration(
    val scenarios: Path,
    val repository: Path,
)

/**
 * A validation pack (ADR 0007, 0021, BR-07, NF-15): versioned artefacts with an activation
 * date. Old packs stay available so earlier results can still be explained.
 */
data class ValidationPack(
    val id: String,
    val title: String,
    val activeFrom: LocalDate,
    val dir: Path,
    val configurations: List<PackConfiguration>,
    /** German plain-language text per rule ID (VW-04). */
    val ruleTexts: Map<String, String>,
) {
    /** The official (first) configuration, unchanged as published; used for conformance checks. */
    val official: PackConfiguration get() = configurations.first()
}

/** All packs found in [ValidationProperties.packsDir]; the active one is the newest that has started. */
@Component
class ValidationPacks(
    props: ValidationProperties,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val json = JsonMapper.builder().build()

    val all: List<ValidationPack> =
        run {
            val dir = props.packsDir.toAbsolutePath()
            check(dir.isDirectory()) { "Validation packs directory not found: $dir (run ./gradlew prepareValidationPacks)" }
            dir
                .listDirectoryEntries()
                .filter { it.resolve("pack.json").isRegularFile() }
                .map(::load)
                .sortedBy { it.activeFrom }
                .also { check(it.isNotEmpty()) { "No validation pack in $dir" } }
        }

    fun byId(id: String): ValidationPack = all.firstOrNull { it.id == id } ?: throw NoSuchElementException("Unknown validation pack $id")

    /** The pack in force on [date] (BR-07: activation date). */
    fun activeOn(date: LocalDate = LocalDate.now(clock)): ValidationPack =
        all.lastOrNull { !it.activeFrom.isAfter(date) } ?: error("No validation pack active on $date (earliest: ${all.first().activeFrom})")

    @Suppress("UNCHECKED_CAST")
    private fun load(dir: Path): ValidationPack {
        val manifest = json.readValue(dir.resolve("pack.json").toFile(), Map::class.java) as Map<String, Any?>
        val configurations =
            (manifest["configurations"] as List<Map<String, String>>).map {
                PackConfiguration(dir.resolve(it.getValue("scenarios")), dir.resolve(it.getValue("repository")))
            }
        configurations.forEach { check(Files.isRegularFile(it.scenarios)) { "Missing scenarios file ${it.scenarios}" } }
        val texts =
            (manifest["ruleTexts"] as String?)
                ?.let { json.readValue(dir.resolve(it).toFile(), Map::class.java) as Map<String, String> }
                .orEmpty()
        return ValidationPack(
            id = manifest["id"] as String,
            title = manifest["title"] as String,
            activeFrom = LocalDate.parse(manifest["activeFrom"] as String),
            dir = dir,
            configurations = configurations,
            ruleTexts = texts,
        )
    }
}
