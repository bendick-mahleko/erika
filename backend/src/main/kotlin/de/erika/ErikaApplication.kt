package de.erika

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * ERIKA back end. One artefact, two runtime roles selected by Spring profile:
 * `api` (REST API, runs DB migrations) and `worker` (background jobs).
 * See docs/adr/0001-modular-monolith.md and docs/adr/0010-postgres-job-queue.md.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class ErikaApplication

fun main(args: Array<String>) {
    runApplication<ErikaApplication>(*args)
}
