package de.erika.validation

import de.kosit.validationtool.api.Configuration
import de.kosit.validationtool.api.InputFactory
import de.kosit.validationtool.impl.DefaultCheck
import net.sf.saxon.s9api.Processor
import net.sf.saxon.s9api.QName
import net.sf.saxon.s9api.XPathExecutable
import net.sf.saxon.s9api.XdmNode
import java.util.concurrent.ConcurrentHashMap

/** One message of a KoSIT report, with the scenario's custom level already applied. */
data class KositMessage(
    val code: String,
    val level: String,
    val text: String,
    val xpathLocation: String?,
    val step: String,
)

data class KositResult(
    val scenario: String?,
    val accepted: Boolean,
    val processingErrors: List<String>,
    val messages: List<KositMessage>,
)

/**
 * The KoSIT validator (the reference implementation for XRechnung, VW-03) embedded
 * in-process. One compiled check per pack and configuration set; compiled stylesheets are
 * reused and the check is thread-safe.
 */
class KositEngine {
    private val processor = Processor(false)
    private val checks = ConcurrentHashMap<String, DefaultCheck>()

    // Compiled once; XPathExecutable is thread-safe (a selector is loaded per use).
    private val xpath =
        processor.newXPathCompiler().apply {
            declareNamespace("rep", REP)
            declareNamespace("s", SCENARIOS)
        }
    private val scenarioName = xpath.compile("/rep:report/rep:scenarioMatched/s:scenario/s:name")
    private val customLevel = xpath.compile("/rep:report/rep:scenarioMatched//s:customLevel")
    private val message = xpath.compile("//rep:message")

    /** All configurations of the pack: official XRechnung first, then ERIKA's EN 16931 scenarios. */
    fun validate(
        pack: ValidationPack,
        xml: ByteArray,
        name: String = "invoice.xml",
    ): KositResult = check(pack.id, pack.configurations).run(xml, name)

    /** Only the official configuration, exactly as published (reference for conformance tests). */
    fun validateOfficial(
        pack: ValidationPack,
        xml: ByteArray,
        name: String = "invoice.xml",
    ): KositResult = check("${pack.id}#official", listOf(pack.official)).run(xml, name)

    private fun check(
        key: String,
        configurations: List<PackConfiguration>,
    ): DefaultCheck =
        checks.computeIfAbsent(key) {
            val loaded: Array<Configuration> =
                configurations
                    .map { Configuration.load(it.scenarios.toUri(), it.repository.toUri()).build(processor) }
                    .toTypedArray()
            DefaultCheck(processor, *loaded)
        }

    private fun DefaultCheck.run(
        xml: ByteArray,
        name: String,
    ): KositResult {
        val result = checkInput(InputFactory.read(xml, name))
        val report: XdmNode = result.report
        return KositResult(
            scenario = select(scenarioName, report).firstOrNull()?.stringValue?.trim(),
            accepted = result.isAcceptable,
            processingErrors = result.processingErrors.orEmpty(),
            messages = messages(report),
        )
    }

    private fun messages(report: XdmNode): List<KositMessage> {
        // Custom levels of the matched scenario (e.g. BR-CL-23 fatal -> warning in XRechnung).
        val customLevels =
            select(customLevel, report)
                .flatMap { cl ->
                    cl.stringValue
                        .trim()
                        .split(Regex("\\s+"))
                        .map { it to cl.attr("level") }
                }.toMap()
        return select(message, report).map { m ->
            val code = m.attr("code").ifBlank { "UNSPECIFIC" }
            KositMessage(
                code = code,
                level = customLevels[code] ?: m.attr("level"),
                text = m.stringValue.trim().replace(Regex("\\s+"), " "),
                xpathLocation = m.attr("xpathLocation").ifBlank { null },
                step = m.parent?.attr("id").orEmpty(),
            )
        }
    }

    private fun select(
        expression: XPathExecutable,
        context: XdmNode,
    ): List<XdmNode> =
        expression
            .load()
            .apply { contextItem = context }
            .evaluate()
            .filterIsInstance<XdmNode>()

    private fun XdmNode.attr(name: String): String = getAttributeValue(QName(name)).orEmpty()

    private companion object {
        const val REP = "http://www.xoev.de/de/validator/varl/1"
        const val SCENARIOS = "http://www.xoev.de/de/validator/framework/1/scenarios"
    }
}
