package de.erika.invoicecore.support

import de.erika.invoicecore.model.Attachment
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/** The official test suites, downloaded by `./gradlew downloadTestSuites` (a dependency of `test`). */
object Suites {
    val root: File =
        File(System.getProperty("erika.testSuites") ?: "build/test-suites").also {
            check(it.isDirectory) { "Test suites missing at $it; run ./gradlew downloadTestSuites" }
        }

    val xrechnung: File get() = root.resolve("xrechnung/instances")
    val enUbl: File get() = root.resolve("en16931-ubl/examples")
    val enCii: File get() = root.resolve("en16931-cii/examples")
    val corpus: File get() = root.resolve("zugferd-corpus").listFiles()!!.single { it.isDirectory }

    fun files(
        dir: File,
        vararg extensions: String,
    ): List<File> =
        dir
            .walkTopDown()
            .filter { it.isFile && extensions.any { ext -> it.name.endsWith(".$ext", ignoreCase = true) } }
            .sortedBy { it.path }
            .toList()

    fun relative(file: File): String = file.relativeTo(root).invariantSeparatorsPath
}

/**
 * Stable, readable dump of any model object: one `path = value` line per non-null
 * value, in constructor order. Amounts are normalised (100.00 → 100) so that
 * syntax differences in number formatting don't count as differences.
 */
object ModelDump {
    fun render(value: Any?): String = buildString { dump("", value, this) }.trimEnd()

    private fun dump(
        path: String,
        value: Any?,
        out: StringBuilder,
    ) {
        when (value) {
            null -> {
                Unit
            }

            is String, is Boolean, is Number, is LocalDate, is Enum<*> -> {
                out
                    .append(path)
                    .append(" = ")
                    .append(scalar(value))
                    .append('\n')
            }

            is Attachment -> {
                out
                    .append(path)
                    .append(" = attachment(mime=${value.mimeCode}, file=${value.filename}, ")
                    .append("size=${value.size}, sha256=${value.sha256})\n")
            }

            is List<*> -> {
                value.forEachIndexed { i, item -> dump("$path[$i]", item, out) }
            }

            else -> {
                val kClass = value::class
                val order = kClass.primaryConstructor?.parameters?.map { it.name } ?: emptyList()
                val props = kClass.memberProperties.associateBy { it.name }
                order.forEach { name ->
                    val prop = props[name] ?: return@forEach
                    dump(if (path.isEmpty()) name!! else "$path.$name", prop.getter.call(value), out)
                }
            }
        }
    }

    private fun scalar(value: Any): String =
        when (value) {
            is BigDecimal -> value.stripTrailingZeros().toPlainString()
            is String -> value.replace("\r\n", "\n").replace("\n", "\\n")
            else -> value.toString()
        }
}

/** Golden-file comparison; `-PupdateGolden` rewrites the files instead. */
object Golden {
    private val dir = File(System.getProperty("erika.goldenDir") ?: "src/test/resources/golden")
    private val update = System.getProperty("erika.updateGolden") == "true"

    fun verify(
        name: String,
        actual: String,
    ) {
        val file = dir.resolve("$name.txt")
        if (update) {
            file.parentFile.mkdirs()
            file.writeText(actual + "\n")
            return
        }
        check(file.exists()) { "Golden file missing: $file (run ./gradlew test -PupdateGolden and review the diff)" }
        kotlin.test.assertEquals(file.readText().trimEnd(), actual, "Parsed model differs from golden file $name")
    }
}
