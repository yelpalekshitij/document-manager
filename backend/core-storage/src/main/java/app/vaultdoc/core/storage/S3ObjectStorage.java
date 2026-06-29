package app.vaultdoc.core.storage;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * S3 / MinIO-backed {@link ObjectStorage}. Uses the SDK v2 {@code S3Presigner}
 * for direct browser uploads (no proxying through the gateway).
 */
public class S3ObjectStorage implements ObjectStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public S3ObjectStorage(S3Client s3, S3Presigner presigner, String bucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public URI presignPut(StorageKey key, String contentType, Duration ttl) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key.value())
                .contentType(contentType)
                .build();
        PutObjectPresignRequest req = PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(put)
                .build();
        return presigner.presignPutObject(req).url().toString().transform(URI::create);
    }

    @Override
    public URI presignGet(StorageKey key, Duration ttl) {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key.value())
                .build();
        GetObjectPresignRequest req = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(get)
                .build();
        return presigner.presignGetObject(req).url().toString().transform(URI::create);
    }

    @Override
    public Optional<ObjectMetadata> head(StorageKey key) {
        try {
            var resp = s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key.value())
                    .build());
            return Optional.of(new ObjectMetadata(
                    resp.contentType(),
                    resp.contentLength() == null ? 0L : resp.contentLength(),
                    stripQuotes(resp.eTag()),
                    resp.lastModified()));
        } catch (NoSuchKeyException notFound) {
            return Optional.empty();
        } catch (software.amazon.awssdk.services.s3.model.S3Exception other) {
            // 404 surfaces here when the key doesn't exist on some MinIO versions.
            if (other.statusCode() == 404) {
                return Optional.empty();
            }
            throw other;
        }
    }

    @Override
    public void delete(StorageKey key) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key.value())
                .build());
    }

    @Override
    public byte[] download(StorageKey key) {
        ResponseBytes<GetObjectResponse> bytes = s3.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key.value()).build(),
                ResponseTransformer.toBytes());
        return bytes.asByteArray();
    }

    private static String stripQuotes(String etag) {
        if (etag == null) return null;
        if (etag.length() >= 2 && etag.startsWith("\"") && etag.endsWith("\"")) {
            return etag.substring(1, etag.length() - 1);
        }
        return etag;
    }
}
