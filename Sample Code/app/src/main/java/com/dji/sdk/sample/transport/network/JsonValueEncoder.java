package com.dji.sdk.sample.transport.network;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Structured, dependency-free JSON serializer for fixed transport records. */
final class JsonValueEncoder {
    private JsonValueEncoder() { }

    static byte[] toUtf8(Object value, int maximumBytes) {
        StringBuilder output = new StringBuilder(512);
        append(output, value);
        byte[] bytes = output.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximumBytes) {
            throw new IllegalStateException("JSON datagram exceeds " + maximumBytes + " bytes");
        }
        return bytes;
    }

    @SuppressWarnings("unchecked")
    private static void append(StringBuilder output, Object value) {
        if (value == null) output.append("null");
        else if (value instanceof Boolean) output.append(value);
        else if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) output.append("null");
            else output.append(value);
        } else if (value instanceof Map) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<Object, Object> item : ((Map<Object, Object>) value).entrySet()) {
                if (!first) output.append(',');
                first = false;
                appendString(output, String.valueOf(item.getKey()));
                output.append(':');
                append(output, item.getValue());
            }
            output.append('}');
        } else appendString(output, String.valueOf(value));
    }

    private static void appendString(StringBuilder output, String value) {
        output.append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"': output.append("\\\""); break;
                case '\\': output.append("\\\\"); break;
                case '\n': output.append("\\n"); break;
                case '\r': output.append("\\r"); break;
                case '\t': output.append("\\t"); break;
                default:
                    if (character < 0x20) output.append(String.format("\\u%04x", (int) character));
                    else output.append(character);
            }
        }
        output.append('"');
    }
}
