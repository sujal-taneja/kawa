package dev.kawa.client;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

final class Json {

    // ignoring unknown fields lets an older client keep working when the server adds fields to a response
    static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private Json() {
    }

    static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (RuntimeException e) {
            throw new KawaException("Could not read JSON as " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }
}
