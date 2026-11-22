package de.erika

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter

/**
 * Enforces the module rules of Software Design §3 on every build: no cycles, no access
 * to another module's internals, only declared dependencies (ADR 0001).
 */
class ModularityTest {
    private val modules = ApplicationModules.of(ErikaApplication::class.java)

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
