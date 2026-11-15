package ae.afia.core.platform;

/** Publishes one event and returns only when the broker has acknowledged it. */
public interface EventBus {

    void publish(String topic, String key, String payload) throws Exception;
}
