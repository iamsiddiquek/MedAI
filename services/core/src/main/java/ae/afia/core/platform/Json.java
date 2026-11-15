package ae.afia.core.platform;

import java.util.Map;

/**
 * Minimal JSON writer for event envelopes. Envelopes hold ids, versions and references
 * only, so strings, numbers, booleans, nulls and nested maps are all that is needed.
 */
public final class Json {

    private Json() {
    }

    public static String object(Map<String, ?> fields) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : fields.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(quote(entry.getKey())).append(':').append(value(entry.getValue()));
        }
        return out.append('}').toString();
    }

    private static String value(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return object(copy);
        }
        return quote(value.toString());
    }

    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
