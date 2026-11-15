package ae.afia.core.support;

import ae.afia.core.media.ObjectStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Test object store. The S3 adapter itself is exercised only against a running MinIO. */
@Component
public class InMemoryObjectStore implements ObjectStore {

    public final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String bucket, String key, byte[] content, String contentType) {
        objects.put(bucket + "/" + key, content);
    }
}
