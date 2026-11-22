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

/**
 * PostgreSQL and S3 (SeaweedFS) containers shared by all integration tests in a JVM.
 * They use the same bootstrap files as the local Docker Compose stack, so tests
 * exercise the real role set-up (erika_owner / erika_app) and bucket policy.
 */
object TestInfrastructure {
    const val BUCKET = "erika-originals"

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

    init {
        Startables.deepStart(postgres, s3).join()
        createBucket()
    }

    val jdbcUrl: String
        get() = "jdbc:postgresql://${postgres.host}:${postgres.getMappedPort(5432)}/erika?currentSchema=erika"

    val s3Endpoint: String
        get() = "http://${s3.host}:${s3.getMappedPort(S3_PORT)}"

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
            .use {
                it.createBucket(
                    CreateBucketRequest
                        .builder()
                        .bucket(BUCKET)
                        .objectLockEnabledForBucket(true)
                        .build(),
                )
            }
    }

    private const val S3_PORT = 8333
}
