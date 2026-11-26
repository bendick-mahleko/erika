package de.erika.support

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.lifecycle.Startables
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import java.net.URI
import java.time.Duration

/**
 * PostgreSQL, S3 (SeaweedFS), SMTP (Mailpit) and ClamAV containers shared by all integration tests in a JVM.
 * They use the same bootstrap files as the local Docker Compose stack, so tests
 * exercise the real role set-up (erika_owner / erika_app) and bucket policy.
 */
object TestInfrastructure {
    const val BUCKET = "erika-originals"
    const val BACKUP_BUCKET = "erika-backups"
    const val UPLOADS_BUCKET = "erika-uploads"
    const val MEDIA_BUCKET = "erika-media"
    const val DERIVED_BUCKET = "erika-derived"
    const val EXPORTS_BUCKET = "erika-exports"

    private val postgres =
        PostgreSQLContainer("postgres:16.15-alpine")
            .withDatabaseName("erika")
            .withUsername("postgres")
            .withPassword("postgres_dev")
            .withCopyFileToContainer(
                MountableFile.forHostPath("../infra/local/postgres/01-roles.sql"),
                "/docker-entrypoint-initdb.d/01-roles.sql",
            )

    private val s3: GenericContainer<*> =
        GenericContainer("chrislusf/seaweedfs:4.50")
            .withCommand("mini", "-dir=/data", "-s3.config=/etc/seaweedfs/s3.json")
            .withCopyFileToContainer(
                MountableFile.forHostPath("../infra/local/seaweedfs/s3.json"),
                "/etc/seaweedfs/s3.json",
            ).withExposedPorts(S3_PORT)
            .waitingFor(Wait.forHttp("/healthz").forPort(S3_PORT))

    private val mailpit: GenericContainer<*> =
        GenericContainer("axllent/mailpit:v1.34.0")
            .withExposedPorts(SMTP_PORT, MAILPIT_API_PORT)
            .waitingFor(Wait.forHttp("/livez").forPort(MAILPIT_API_PORT))

    // Real ClamAV (NF-04 exit criterion: EICAR is rejected). Signatures ship with the image;
    // freshclam is off so tests don't depend on the internet. clamd opens its port only
    // after loading the signatures (about a minute).
    private val clamav: GenericContainer<*> =
        GenericContainer("clamav/clamav:1.4.7")
            .withEnv("CLAMAV_NO_FRESHCLAMD", "true")
            .withEnv("CLAMAV_NO_MILTERD", "true")
            .withExposedPorts(CLAMAV_PORT)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(5)))

    init {
        Startables.deepStart(postgres, s3, mailpit, clamav).join()
        createBucket()
    }

    val jdbcUrl: String
        get() = "jdbc:postgresql://${postgres.host}:${postgres.getMappedPort(5432)}/erika?currentSchema=erika"

    val s3Endpoint: String
        get() = "http://${s3.host}:${s3.getMappedPort(S3_PORT)}"

    /** Mailpit's HTTP API (messages received over SMTP). */
    val mailpitApi: String
        get() = "http://${mailpit.host}:${mailpit.getMappedPort(MAILPIT_API_PORT)}"

    /** Spring properties pointing the application at the containers. */
    fun properties(): Map<String, String> =
        mapOf(
            "spring.datasource.url" to jdbcUrl,
            "spring.datasource.username" to "erika_app",
            "spring.datasource.password" to "erika_app_dev",
            "spring.flyway.user" to "erika_owner",
            "spring.flyway.password" to "erika_owner_dev",
            "erika.storage.endpoint" to s3Endpoint,
            "erika.storage.access-key" to "erika-dev",
            "erika.storage.secret-key" to "erika-dev-secret",
            "erika.storage.bucket" to BUCKET,
            "erika.audit.head-bucket" to BACKUP_BUCKET,
            "spring.mail.host" to mailpit.host,
            "spring.mail.port" to mailpit.getMappedPort(SMTP_PORT).toString(),
            "erika.storage.uploads-bucket" to UPLOADS_BUCKET,
            "erika.storage.derived-bucket" to DERIVED_BUCKET,
            "erika.storage.exports-bucket" to EXPORTS_BUCKET,
            "erika.storage.media-bucket" to MEDIA_BUCKET,
            "erika.billing.stripe-api-base" to FakeStripe.baseUrl,
            "erika.intake.clamav.host" to clamav.host,
            "erika.intake.clamav.port" to clamav.getMappedPort(CLAMAV_PORT).toString(),
        )

    fun register(registry: DynamicPropertyRegistry) {
        properties().forEach { (key, value) -> registry.add(key) { value } }
    }

    private fun createBucket() {
        // Admin key, as in infra/local/seaweedfs/init.sh; the app key may not create buckets.
        S3Client
            .builder()
            .region(Region.EU_CENTRAL_1)
            .endpointOverride(URI.create(s3Endpoint))
            .forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("seaweed_admin", "seaweed_admin_dev")))
            .build()
            .use { s3 ->
                listOf(BUCKET, BACKUP_BUCKET).forEach {
                    s3.createBucket(
                        CreateBucketRequest
                            .builder()
                            .bucket(it)
                            .objectLockEnabledForBucket(true)
                            .build(),
                    )
                }
                // Quarantine for uploads: no Object Lock (infra/local/seaweedfs/init.sh).
                s3.createBucket(CreateBucketRequest.builder().bucket(UPLOADS_BUCKET).build())
                s3.createBucket(CreateBucketRequest.builder().bucket(DERIVED_BUCKET).build())
                s3.createBucket(CreateBucketRequest.builder().bucket(EXPORTS_BUCKET).build())
                s3.createBucket(CreateBucketRequest.builder().bucket(MEDIA_BUCKET).build())
            }
    }

    private const val S3_PORT = 8333
    private const val SMTP_PORT = 1025
    private const val MAILPIT_API_PORT = 8025
    private const val CLAMAV_PORT = 3310
}
