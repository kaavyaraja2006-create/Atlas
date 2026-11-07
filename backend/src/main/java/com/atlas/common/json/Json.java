package com.atlas.common.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Component;

/** Unchecked wrapper around the application's ObjectMapper for JSONB columns. */
@Component
public class Json {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private static final TypeReference<java.util.Map<String, Object>> OBJECT = new TypeReference<>() {
    };

    private final ObjectMapper mapper;
    /** Reads decimals as BigDecimal so money keeps its exact value. */
    private final ObjectReader exact;

    public Json(ObjectMapper mapper) {
        this.mapper = mapper;
        this.exact = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    public java.util.Map<String, Object> exactObject(String json) {
        if (json == null) {
            return java.util.Map.of();
        }
        try {
            return exact.forType(OBJECT).readValue(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored JSON object could not be parsed", e);
        }
    }

    /** Reads a stored value back into its type, decimals as BigDecimal. */
    public <T> T read(String json, Class<T> type) {
        try {
            return exact.forType(type).readValue(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored JSON could not be read as " + type.getSimpleName(), e);
        }
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise value to JSON", e);
        }
    }

    public JsonNode tree(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored JSON could not be parsed", e);
        }
    }

    public List<String> stringList(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return mapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored JSON array could not be parsed", e);
        }
    }
}
