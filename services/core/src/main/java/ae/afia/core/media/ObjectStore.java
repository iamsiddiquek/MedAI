package ae.afia.core.media;

/** Binary storage by reference. Audio never travels in an event or a database row. */
public interface ObjectStore {

    void put(String bucket, String key, byte[] content, String contentType);
}
