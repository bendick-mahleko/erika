package de.erika.validation

import de.erika.invoicecore.InvoiceParseException
import de.erika.invoicecore.InvoiceParser
import de.erika.invoicecore.ParseErrorCode
import de.erika.invoicecore.check.InvoiceChecks
import de.erika.invoicecore.check.Severity
import de.erika.invoicecore.model.InvoiceFormat
import de.erika.invoicecore.model.ParsedDocument
import de.erika.platform.runtime.RuntimeInstance
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Validates e-invoices (VW-03, VW-04, VW-05, BR-07). Published API of the `validation`
 * module. Runs in the worker only: compiled Schematron and PDF checks need memory and time
 * (Software Design §7).
 *
 * Steps:
 * 1. invoice-core reads the file and detects format and profile;
 * 2. the KoSIT validator checks the invoice XML (XML Schema + EN 16931 / XRechnung
 *    Schematron) with the pack active on the given day;
 * 3. for ZUGFeRD/Factur-X, veraPDF checks the PDF/A-3 part;
 * 4. invoice-core's content checks (arithmetic, § 14 UStG) run on the neutral model.
 * The overall level is the worst message; every message has a German text.
 */
@Service
@Profile(RuntimeInstance.PROFILE_WORKER)
class InvoiceValidator(
    private val packs: ValidationPacks,
    private val kosit: KositEngine,
    private val pdfa: PdfaCheck,
    meters: MeterRegistry,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val timer = meters.timer("erika.validation.duration")

    fun validate(
        bytes: ByteArray,
        onDate: LocalDate = LocalDate.now(clock),
    ): ValidationReport = check(bytes, onDate).report

    /**
     * Like [validate], and also returns what invoice-core read from the file, so callers
     * that store the neutral model don't parse the file a second time.
     */
    fun check(
        bytes: ByteArray,
        onDate: LocalDate = LocalDate.now(clock),
    ): ValidationOutcome {
        val started = System.nanoTime()
        val pack = packs.activeOn(onDate)
        val outcome = run(pack, bytes)
        val millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        timer.record(millis, TimeUnit.MILLISECONDS)
        return outcome.copy(report = outcome.report.copy(durationMillis = millis))
    }

    /** Compiles the active pack's stylesheets once at start-up, so the first job is fast and a broken pack fails early. */
    fun warmUp() {
        val pack = packs.activeOn()
        val millis =
            measure {
                kosit.validate(pack, WARM_UP_XML)
                kosit.validateOfficial(pack, WARM_UP_XML)
            }
        log.info("Validation pack {} ready in {} ms", pack.id, millis)
    }

    private fun run(
        pack: ValidationPack,
        bytes: ByteArray,
    ): ValidationOutcome {
        val texts = RuleTextResolver(pack)
        val parsed =
            try {
                InvoiceParser.parse(bytes)
            } catch (e: InvoiceParseException) {
                val applicable = e.code != ParseErrorCode.UNKNOWN_FILE_TYPE
                val message = texts.format("ERIKA-FORMAT-${e.code.name}", MessageSeverity.ERROR)
                return ValidationOutcome(null, e.code, report(pack, applicable, null, null, null, null, listOf(message)))
            }
        val format = parsed.format
        if (!format.isEInvoice) {
            val reason = format.reason?.name ?: "UNKNOWN_PROFILE"
            val message = texts.format("ERIKA-NOT-EINVOICE-$reason", MessageSeverity.ERROR)
            return ValidationOutcome(
                parsed,
                null,
                report(pack, false, format.format.name, format.profile?.name, null, null, listOf(message)),
            )
        }

        val messages = mutableListOf<ValidationMessage>()
        val xml = InvoiceParser.invoiceXml(bytes)!!
        val result = kosit.validate(pack, xml)
        result.messages.mapTo(messages) { texts.kosit(it) }
        if (result.scenario == null) messages += texts.format("ERIKA-NO-SCENARIO", MessageSeverity.ERROR)

        if (format.format == InvoiceFormat.ZUGFERD) messages += pdfaMessages(pdfa.check(bytes), texts)

        parsed.invoice?.let { invoice ->
            InvoiceChecks.check(invoice).mapTo(messages) { f ->
                ValidationMessage(
                    ruleId = f.ruleId,
                    severity = if (f.severity == Severity.ERROR) MessageSeverity.ERROR else MessageSeverity.WARNING,
                    source = MessageSource.CONTENT,
                    text = f.message("de"),
                    originalText = f.message("en"),
                    location = f.businessTerms.joinToString(", ").ifEmpty { null },
                )
            }
        }
        return ValidationOutcome(
            parsed,
            null,
            report(pack, true, format.format.name, format.profile?.name, result.scenario, result.accepted, messages),
        )
    }

    private fun pdfaMessages(
        result: PdfaResult,
        texts: RuleTextResolver,
    ): List<ValidationMessage> =
        when {
            result.unreadable -> {
                listOf(texts.format("PDFA-UNREADABLE", MessageSeverity.ERROR, MessageSource.PDFA))
            }

            else -> {
                result.failures.map {
                    ValidationMessage(
                        ruleId = "PDFA-3",
                        severity = MessageSeverity.ERROR,
                        source = MessageSource.PDFA,
                        text = texts.text("PDFA-3").replace("{clause}", it.rule),
                        originalText = "ISO 19005-3 ${it.rule}: ${it.description}",
                        location = it.rule,
                    )
                }
            }
        }

    private fun report(
        pack: ValidationPack,
        applicable: Boolean,
        format: String?,
        profile: String?,
        scenario: String?,
        accepted: Boolean?,
        messages: List<ValidationMessage>,
    ) = ValidationReport(
        level = ValidationReport.levelOf(messages),
        applicable = applicable,
        packId = pack.id,
        format = format,
        profile = profile,
        scenario = scenario,
        kositAccepted = accepted,
        messages = messages,
        checkedAt = Instant.now(clock),
        durationMillis = 0,
    )

    private fun measure(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    }

    private companion object {
        val WARM_UP_XML = "<warm-up/>".toByteArray()
    }
}

/**
 * What [InvoiceValidator.check] found: the parsed document (null if the file couldn't be
 * read at all, then [parseError] says why) and the validation report.
 */
data class ValidationOutcome(
    val parsed: ParsedDocument?,
    val parseError: ParseErrorCode?,
    val report: ValidationReport,
)

/** Turns rule IDs into German texts: the pack's catalogue for KoSIT rules, ERIKA's own for the rest. */
internal class RuleTextResolver(
    private val pack: ValidationPack,
) {
    fun text(ruleId: String): String =
        pack.ruleTexts[ruleId]
            ?: ErikaRuleTexts.de[ruleId]
            ?: "Die Prüfregel $ruleId ist verletzt. Die technische Meldung steht darunter."

    fun kosit(m: KositMessage): ValidationMessage {
        val (ruleId, source) =
            when {
                m.code.startsWith("cvc-") -> "XSD" to MessageSource.XML_SCHEMA
                m.code == "generic-error" -> "XML" to MessageSource.XML_SCHEMA
                m.code == "PROCESSING_ERROR" -> "ERIKA-PROCESSING-ERROR" to MessageSource.FORMAT
                else -> m.code to MessageSource.SCHEMATRON
            }
        return ValidationMessage(
            ruleId = ruleId,
            severity =
                when (m.level) {
                    "error" -> MessageSeverity.ERROR
                    "warning" -> MessageSeverity.WARNING
                    else -> MessageSeverity.INFO
                },
            source = source,
            text = text(ruleId),
            originalText = (if (source == MessageSource.XML_SCHEMA) "${m.code}: ${m.text}" else m.text).removeRulePrefix(m.code),
            location = m.xpathLocation,
        )
    }

    fun format(
        ruleId: String,
        severity: MessageSeverity,
        source: MessageSource = MessageSource.FORMAT,
    ) = ValidationMessage(ruleId, severity, source, text(ruleId), ErikaRuleTexts.en[ruleId])

    /** "[BR-01]-An Invoice shall …" → "An Invoice shall …". */
    private fun String.removeRulePrefix(code: String): String = replace(Regex("^\\[${Regex.escape(code)}]\\s*-?\\s*"), "")
}

@Configuration(proxyBeanMethods = false)
@Profile(RuntimeInstance.PROFILE_WORKER)
class ValidationConfiguration {
    @Bean
    fun kositEngine() = KositEngine()

    @Bean
    fun pdfaCheck() = PdfaCheck()

    @Bean
    fun validationWarmUp(
        validator: InvoiceValidator,
        props: ValidationProperties,
    ) = ValidationWarmUp(validator, props)
}

class ValidationWarmUp(
    private val validator: InvoiceValidator,
    private val props: ValidationProperties,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun warmUp() {
        if (props.warmUp) validator.warmUp()
    }
}
