package de.erika.platform.storage

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.ObjectLockMode
import software.amazon.awssdk.services.s3.model.ObjectLockRetention
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRetentionRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64

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
    /**
     * Quarantine for uploads before the virus scan (no Object Lock; the app may delete).
     * Objects that are never processed expire through the bucket's lifecycle rule.
     */
    val uploadsBucket: String = "erika-uploads",
    /** Objects rebuilt from originals (PDF view copies, decoded attachments): no Object Lock. */
    val derivedBucket: String = "erika-derived",
    /**
     * Endpoint the browser uses for pre-signed upload URLs, when it differs from [endpoint]
     * (locally the API reaches S3 as `http://s3:8333`, the browser as `http://localhost:9000`).
     */
    val publicEndpoint: URI? = null,
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

    /** Signs upload URLs for the browser, against the public endpoint. */
    @Bean(destroyMethod = "close")
    fun s3Presigner(props: StorageProperties): S3Presigner =
        S3Presigner
            .builder()
            .region(Region.of(props.region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey, props.secretKey)))
            .apply {
                (props.publicEndpoint ?: props.endpoint)?.takeIf { it.toString().isNotBlank() }?.let { endpointOverride(it) }
            }.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(props.pathStyle).build())
            .build()
}

/** A URL the browser can `PUT` one file to, with the headers it must send unchanged. */
data class PresignedUpload(
    val url: String,
    val headers: Map<String, String>,
    val expiresAt: Instant,
)

/** Result of a write to the originals bucket. */
data class StoredVersion(
    val key: String,
    /** S3 version ID; null if the bucket has no versioning. */
    val versionId: String?,
)

/** Low-level access to the buckets. The `archive` module builds write-once storage on top. */
interface ObjectStorage {
    /**
     * Writes to the originals bucket. With [retainUntil], the version is locked in
     * COMPLIANCE mode until then: nobody, not even the bucket owner, can delete or change it.
     */
    fun putOriginal(
        key: String,
        bytes: ByteArray,
        contentType: String,
        retainUntil: Instant?,
    ): StoredVersion

    /** Reads one version (or the current one if [versionId] is null) from the originals bucket; null if missing. */
    fun getOriginal(
        key: String,
        versionId: String?,
    ): ByteArray?

    /** Extends the COMPLIANCE lock of a version (S3 only allows extending it). */
    fun extendRetention(
        key: String,
        versionId: String?,
        retainUntil: Instant,
    )

    /** Throws if the bucket can't be reached with the configured credentials. */
    fun checkAccess()

    /** Writes an object to [bucket] (e.g. the backup bucket). Not for originals: use `archive`. */
    fun put(
        bucket: String,
        key: String,
        bytes: ByteArray,
        contentType: String,
    )

    /** Reads an object, or null if it doesn't exist. */
    fun get(
        bucket: String,
        key: String,
    ): ByteArray?

    /** Keys under [prefix], sorted (at most 1,000). */
    fun list(
        bucket: String,
        prefix: String,
    ): List<String>

    /**
     * Pre-signed `PUT` of exactly [size] bytes of [contentType] to [key] in the uploads
     * bucket. Content type and length are part of the signature, so the browser can't send
     * a larger file than announced.
     */
    fun presignUpload(
        key: String,
        contentType: String,
        size: Long,
        validFor: Duration,
    ): PresignedUpload

    /** Size of an object, or null if it doesn't exist. */
    fun size(
        bucket: String,
        key: String,
    ): Long?

    /** Deletes an object (uploads bucket only; the app key can't delete originals). */
    fun delete(
        bucket: String,
        key: String,
    )
}

@Component
class S3ObjectStorage(
    private val s3: S3Client,
    private val presigner: S3Presigner,
    private val props: StorageProperties,
) : ObjectStorage {
    override fun checkAccess() {
        s3.headBucket(HeadBucketRequest.builder().bucket(props.bucket).build())
    }

    override fun putOriginal(
        key: String,
        bytes: ByteArray,
        contentType: String,
        retainUntil: Instant?,
    ): StoredVersion {
        val request =
            PutObjectRequest
                .builder()
                .bucket(props.bucket)
                .key(key)
                .contentType(contentType)
                // Content-MD5 is required by S3 for writes with Object Lock parameters.
                .contentMD5(Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes)))
                .apply {
                    if (retainUntil != null) {
                        objectLockMode(ObjectLockMode.COMPLIANCE)
                        objectLockRetainUntilDate(retainUntil)
                    }
                }.build()
        val response = s3.putObject(request, RequestBody.fromBytes(bytes))
        return StoredVersion(key, response.versionId())
    }

    override fun getOriginal(
        key: String,
        versionId: String?,
    ): ByteArray? =
        try {
            s3
                .getObjectAsBytes(
                    GetObjectRequest
                        .builder()
                        .bucket(props.bucket)
                        .key(key)
                        .apply { versionId?.let { versionId(it) } }
                        .build(),
                ).asByteArray()
        } catch (e: NoSuchKeyException) {
            null
        }

    override fun extendRetention(
        key: String,
        versionId: String?,
        retainUntil: Instant,
    ) {
        s3.putObjectRetention(
            PutObjectRetentionRequest
                .builder()
                .bucket(props.bucket)
                .key(key)
                .apply { versionId?.let { versionId(it) } }
                .retention(
                    ObjectLockRetention
                        .builder()
                        .mode(ObjectLockRetentionMode.COMPLIANCE)
                        .retainUntilDate(retainUntil)
                        .build(),
                ).build(),
        )
    }

    override fun put(
        bucket: String,
        key: String,
        bytes: ByteArray,
        contentType: String,
    ) {
        s3.putObject(
            PutObjectRequest
                .builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build(),
            RequestBody.fromBytes(bytes),
        )
    }

    override fun get(
        bucket: String,
        key: String,
    ): ByteArray? =
        try {
            s3
                .getObjectAsBytes(
                    GetObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(key)
                        .build(),
                ).asByteArray()
        } catch (e: NoSuchKeyException) {
            null
        }

    override fun list(
        bucket: String,
        prefix: String,
    ): List<String> =
        s3
            .listObjectsV2(
                ListObjectsV2Request
                    .builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .build(),
            ).contents()
            .map { it.key() }
            .sorted()

    override fun presignUpload(
        key: String,
        contentType: String,
        size: Long,
        validFor: Duration,
    ): PresignedUpload {
        val put =
            PutObjectRequest
                .builder()
                .bucket(props.uploadsBucket)
                .key(key)
                .contentType(contentType)
                .contentLength(size)
                .build()
        val signed =
            presigner.presignPutObject(
                PutObjectPresignRequest
                    .builder()
                    .signatureDuration(validFor)
                    .putObjectRequest(put)
                    .build(),
            )
        // The browser sets Host and Content-Length itself; it must send the rest as signed.
        val headers =
            signed
                .signedHeaders()
                .filterKeys { !it.equals("host", ignoreCase = true) && !it.equals("content-length", ignoreCase = true) }
                .mapValues { (_, values) -> values.joinToString(",") }
        return PresignedUpload(signed.url().toString(), headers, signed.expiration())
    }

    override fun size(
        bucket: String,
        key: String,
    ): Long? =
        try {
            s3
                .headObject(
                    HeadObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(key)
                        .build(),
                ).contentLength()
        } catch (e: NoSuchKeyException) {
            null
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) null else throw e
        }

    override fun delete(
        bucket: String,
        key: String,
    ) {
        s3.deleteObject(
            DeleteObjectRequest
                .builder()
                .bucket(bucket)
                .key(key)
                .build(),
        )
    }
}
