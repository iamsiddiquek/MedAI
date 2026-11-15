package ae.afia.core.support;

import ae.afia.core.platform.EventBus;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/** Test event bus: records what the relay publishes and can fail on demand. */
@Component
public class RecordingEventBus implements EventBus {

    public record Published(String topic, String key, String payload) {
    }

    public final List<Published> published = new CopyOnWriteArrayList<>();
    public volatile boolean failNext;

    @Override
    public void publish(String topic, String key, String payload) {
        if (failNext) {
            failNext = false;
            throw new IllegalStateException("broker unavailable");
        }
        published.add(new Published(topic, key, payload));
    }
}
