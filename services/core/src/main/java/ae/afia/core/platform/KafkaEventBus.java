package ae.afia.core.platform;

import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "afia.events.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaEventBus implements EventBus {

    private final KafkaTemplate<String, String> kafka;

    public KafkaEventBus(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void publish(String topic, String key, String payload) throws Exception {
        kafka.send(topic, key, payload).get(10, TimeUnit.SECONDS);
    }
}
