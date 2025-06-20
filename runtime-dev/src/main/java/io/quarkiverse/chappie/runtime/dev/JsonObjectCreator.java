package io.quarkiverse.chappie.runtime.dev;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.quarkus.builder.Version;

public class JsonObjectCreator {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final ObjectNode commonInputNode;

    static {
        commonInputNode = objectMapper.createObjectNode();
        commonInputNode.put("programmingLanguage", "Java / Kotlin"); // TODO: Find a clean way to decide if this is Kotlin or Java
        commonInputNode.put("programmingLanguageVersion", System.getProperty("java.version"));
        commonInputNode.put("quarkusVersion", Version.getVersion());
    }

    public static String getWorkspaceInput(String systemmessageTemplate, String usermessageTemplate,
            Map<String, String> variables, List<Path> paths) {
        return getInput(systemmessageTemplate, usermessageTemplate, variables, Map.of("paths", paths));
    }

    public static String getInput(String systemmessageTemplate, String usermessageTemplate, Map<String, String> variables,
            Map<String, Object> params) {
        try {

            ObjectNode genericInputNode = commonInputNode.deepCopy();

            genericInputNode.put("systemmessageTemplate", systemmessageTemplate);
            genericInputNode.put("usermessageTemplate", usermessageTemplate);
            ObjectNode variablesAsNode = objectMapper.valueToTree(variables);
            genericInputNode.set("variables", variablesAsNode);

            ObjectNode inputNode = objectMapper.createObjectNode();
            inputNode.set("genericInput", genericInputNode);
            ObjectNode paramsAsNode = objectMapper.valueToTree(params);
            inputNode.setAll(paramsAsNode);

            return objectMapper.writeValueAsString(inputNode);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static <T> T getOutput(String json, Class<T> responseType) {
        try {
            return objectMapper.readValue(json, responseType);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON response \n" + json, e);
        }
    }

    public static String toJsonString(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException(ex);
        }
    }
}
