package de.erika.platform.storage

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import java.net.URI
import java.time.Duration

/**
 * S3-compatible object storage (MinIO locally, an EU provider in production).
 * The bucket is provisioned outside the app (infra), with versioning and, where
 * the provider supports it, Object Lock; the app never creates or deletes buckets.
 */
@ConfigurationProperties("erika.storage")
data class StorageProperties(
    /** Custom endpoint for S3-compatible providers; null means AWS. */
    val endpoint: URI?,
    val region: String = "eu-central-1",
    val bucket: String,
    val accessKey: String,
    val secretKey: String,
    /** Path-style addressing (`endpoint/bucket/key`); needed for MinIO. */
    val pathStyle: Boolean = true,
    val apiCallTimeout: Duration = Duration.ofSeconds(10),
)

@Configuration(proxyBeanMethods = false)
class StorageConfiguration {
    @Bean(destroyMethod = "close")
    fun s3Client(props: StorageProperties): S3Client =
        S3Client
            .builder()
            .region(Region.of(props.region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey, props.secretKey)))
            .apply { props.endpoint?.takeIf { it.toString().isNotBlank() }?.let { endpointOverride(it) } }
            .forcePathStyle(props.pathStyle)
            // Newer SDKs send CRC checksums by default, which several S3-compatible
            // providers reject. Only send them when the operation requires it.
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .overrideConfiguration { it.apiCallTimeout(props.apiCallTimeout) }
            .build()
}

/** Low-level access to the bucket. The `archive` module builds write-once storage on top (Stage 4). */
interface ObjectStorage {
    /** Throws if the bucket can't be reached with the configured credentials. */
    fun checkAccess()
}

@Component
class S3ObjectStorage(
    private val s3: S3Client,
    private val props: StorageProperties,
) : ObjectStorage {
    override fun checkAccess() {
        s3.headBucket(HeadBucketRequest.builder().bucket(props.bucket).build())
    }
}
