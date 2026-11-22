package de.erika

import com.tngtech.archunit.core.domain.JavaClass
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter

/**
 * Enforces the module rules of Software Design §3 on every build: no cycles, no access
 * to another module's internals, only declared dependencies (ADR 0001).
 */
class ModularityTest {
    // invoice-core (de.erika.invoicecore) is an external library, not an application module,
    // although its package sits below de.erika (ADR 0020).
    private val modules =
        ApplicationModules.of(ErikaApplication::class.java, JavaClass.Predicates.resideInAPackage("de.erika.invoicecore.."))

    @Test
    fun `module boundaries are respected`() {
        modules.verify()
    }

    @Test
    fun `writes module documentation`() {
        // Output: build/spring-modulith-docs (component diagrams and module canvases).
        Documenter(modules).writeDocumentation()
    }
}
