package de.erika.intake

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("erika.intake")
data class IntakeProperties(
    /** Domain of the intake addresses: `<token>@<mailDomain>` (production: in.erika-app.de). */
    val mailDomain: String = "in.erika.local",
    /** Shared secret of the mail server for the internal intake endpoints. Blank disables mail intake. */
    val mailSecret: String = "",
    /** IN-02: largest upload (also the largest file inside a ZIP or email). */
    val maxFileBytes: Long = 20L * 1024 * 1024,
    /** Software Design §5: largest email Postfix accepts. */
    val maxMailBytes: Long = 25L * 1024 * 1024,
    /** How long a pre-signed upload URL stays valid. */
    val uploadUrlValidity: Duration = Duration.ofMinutes(15),
    val zip: ZipLimits = ZipLimits(),
    val clamav: ClamAvProperties = ClamAvProperties(),
)

/** ZIP limits (Software Design §5): depth 2, 50 files, zip-bomb ratio. */
data class ZipLimits(
    /** A ZIP inside a ZIP is fine; a ZIP inside that is rejected. */
    val maxDepth: Int = 2,
    /** Files (not folders) in one ZIP, nested ZIPs included. */
    val maxFiles: Int = 50,
    /** Uncompressed bytes of one ZIP in total, nested ZIPs included. */
    val maxTotalBytes: Long = 200L * 1024 * 1024,
    /** An entry that expands more than this many times its compressed size (and above 1 MB) is a zip bomb. */
    val maxRatio: Int = 100,
)

data class ClamAvProperties(
    val host: String = "localhost",
    val port: Int = 3310,
    val timeout: Duration = Duration.ofSeconds(60),
)
