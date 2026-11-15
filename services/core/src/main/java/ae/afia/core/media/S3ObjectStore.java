package ae.afia.core.media;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
@ConditionalOnProperty(name = "afia.storage.s3.enabled", havingValue = "true", matchIfMissing = true)
public class S3ObjectStore implements ObjectStore {

    private final S3Client s3;

    public S3ObjectStore(@Value("${afia.storage.s3.endpoint}") String endpoint,
                         @Value("${afia.storage.s3.region}") String region,
                         @Value("${afia.storage.s3.access-key}") String accessKey,
                         @Value("${afia.storage.s3.secret-key}") String secretKey) {
        if (accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("AFIA_S3_ACCESS_KEY and AFIA_S3_SECRET_KEY must be set");
        }
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true)
                .build();
    }

    @Override
    public void put(String bucket, String key, byte[] content, String contentType) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                RequestBody.fromBytes(content));
    }
}
