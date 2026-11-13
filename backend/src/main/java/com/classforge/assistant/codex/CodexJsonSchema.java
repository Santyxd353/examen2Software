package com.classforge.assistant.codex;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Set;

/** The schema subset used by the UML catalogs and image pipelines, with strict-mode optional handling. */
final class CodexJsonSchema {
    private final JsonMapper json;

    CodexJsonSchema(JsonMapper json) { this.json = json; }

    JsonNode strict(JsonNode original) {
        if (original == null || !original.isObject()) throw invalid();
        JsonNode result = original.deepCopy();
        makeStrict(result, 0);
        return result;
    }

    private void makeStrict(JsonNode node, int depth) {
        if (depth > 64) throw invalid();
        if (node instanceof ObjectNode object) {
            for (var field : object.properties()) makeStrict(field.getValue(), depth + 1);
            JsonNode properties = object.get("properties");
            if (properties != null && properties.isObject()) {
                Set<String> required = required(object);
                ArrayNode allRequired = json.createArrayNode();
                for (var property : properties.properties()) {
                    String name = property.getKey();
                    allRequired.add(name);
                    if (!required.contains(name)) {
                        JsonNode child = property.getValue();
                        ObjectNode nullable = json.createObjectNode();
                        ArrayNode variants = json.createArrayNode();
                        variants.add(child);
                        variants.add(json.createObjectNode().put("type", "null"));
                        nullable.set("anyOf", variants);
                        ((ObjectNode) properties).set(name, nullable);
                    }
                }
                object.set("required", allRequired);
                object.put("additionalProperties", false);
            } else if ("object".equals(text(object.get("type")))) {
                object.set("properties", json.createObjectNode());
                object.set("required", json.createArrayNode());
                object.put("additionalProperties", false);
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) makeStrict(item, depth + 1);
        }
    }

    void validate(JsonNode value, JsonNode schema) { validate(value, schema, schema, 0); }

    private void validate(JsonNode value, JsonNode schema, JsonNode root, int depth) {
        if (depth > 64 || schema == null || !schema.isObject() || value == null) throw invalid();
        if (schema.has("$ref")) {
            String ref = text(schema.get("$ref"));
            if (!ref.startsWith("#/")) throw invalid();
            validate(value, root.at(ref.substring(1)), root, depth + 1);
        }
        if (schema.has("anyOf") && matching(value, schema.get("anyOf"), root, depth) == 0) throw invalid();
        if (schema.has("oneOf") && matching(value, schema.get("oneOf"), root, depth) != 1) throw invalid();
        if (schema.has("allOf")) for (JsonNode member : schema.get("allOf")) validate(value, member, root, depth + 1);
        JsonNode type = schema.get("type");
        if (type != null) {
            boolean match = type.isArray() ? false : hasType(value, text(type));
            if (type.isArray()) for (JsonNode option : type) match |= hasType(value, text(option));
            if (!match) throw invalid();
        }
        if (schema.has("const") && !schema.get("const").equals(value)) throw invalid();
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode option : schema.get("enum")) found |= option.equals(value);
            if (!found) throw invalid();
        }
        if (value.isObject()) {
            for (String name : required(schema)) if (!value.has(name)) throw invalid();
            JsonNode properties = schema.get("properties");
            for (var property : value.properties()) {
                JsonNode child = properties == null ? null : properties.get(property.getKey());
                if (child != null) validate(property.getValue(), child, root, depth + 1);
                else if (schema.has("additionalProperties")) {
                    JsonNode additional = schema.get("additionalProperties");
                    if (additional.isBoolean() && !additional.asBoolean()) throw invalid();
                    if (additional.isObject()) validate(property.getValue(), additional, root, depth + 1);
                }
            }
        } else if (value.isArray()) {
            if (schema.has("minItems") && value.size() < schema.get("minItems").asInt()) throw invalid();
            if (schema.has("maxItems") && value.size() > schema.get("maxItems").asInt()) throw invalid();
            if (schema.has("items")) for (JsonNode item : value) validate(item, schema.get("items"), root, depth + 1);
            if (schema.path("uniqueItems").asBoolean(false)) {
                Set<JsonNode> items = new HashSet<>();
                for (JsonNode item : value) if (!items.add(item)) throw invalid();
            }
        } else if (value.isString()) {
            int length = value.asString().codePointCount(0, value.asString().length());
            if (schema.has("minLength") && length < schema.get("minLength").asInt()) throw invalid();
            if (schema.has("maxLength") && length > schema.get("maxLength").asInt()) throw invalid();
            if (schema.has("pattern") && !java.util.regex.Pattern.compile(schema.get("pattern").asString()).matcher(value.asString()).find()) throw invalid();
        } else if (value.isNumber()) {
            double number = value.asDouble();
            if (!Double.isFinite(number)) throw invalid();
            if (schema.has("minimum") && number < schema.get("minimum").asDouble()) throw invalid();
            if (schema.has("maximum") && number > schema.get("maximum").asDouble()) throw invalid();
            if (schema.has("exclusiveMinimum") && number <= schema.get("exclusiveMinimum").asDouble()) throw invalid();
            if (schema.has("exclusiveMaximum") && number >= schema.get("exclusiveMaximum").asDouble()) throw invalid();
        }
    }

    private int matching(JsonNode value, JsonNode alternatives, JsonNode root, int depth) {
        int matches = 0;
        if (!alternatives.isArray() || alternatives.isEmpty()) throw invalid();
        for (JsonNode alternative : alternatives) if (valid(value, alternative, root, depth + 1)) matches++;
        return matches;
    }

    private boolean valid(JsonNode value, JsonNode schema, JsonNode root, int depth) {
        try { validate(value, schema, root, depth); return true; }
        catch (CodexApiException invalid) { return false; }
    }

    JsonNode normalize(JsonNode value, JsonNode original) {
        JsonNode result = normalize(value.deepCopy(), original, original, 0);
        validate(result, original);
        return result;
    }

    private JsonNode normalize(JsonNode value, JsonNode schema, JsonNode root, int depth) {
        if (depth > 64) throw invalid();
        if (schema.has("$ref")) return normalize(value, root.at(text(schema.get("$ref")).substring(1)), root, depth + 1);
        for (String union : new String[]{"anyOf", "oneOf"}) {
            if (schema.has(union)) {
                for (JsonNode option : schema.get(union)) {
                    JsonNode candidate = normalize(value.deepCopy(), option, root, depth + 1);
                    if (valid(candidate, option, root, depth + 1)) return candidate;
                }
                throw invalid();
            }
        }
        if (value instanceof ObjectNode object && schema.has("properties")) {
            Set<String> required = required(schema);
            for (var property : schema.get("properties").properties()) {
                String name = property.getKey();
                JsonNode child = object.get(name);
                if (child == null) continue;
                // Only nulls forced onto originally optional, non-nullable fields mean "absent".
                if (child.isNull() && !required.contains(name) && !valid(child, property.getValue(), root, depth + 1)) object.remove(name);
                else object.set(name, normalize(child, property.getValue(), root, depth + 1));
            }
        } else if (value instanceof ArrayNode array && schema.has("items")) {
            for (int index = 0; index < array.size(); index++) array.set(index, normalize(array.get(index), schema.get("items"), root, depth + 1));
        }
        return value;
    }

    private Set<String> required(JsonNode schema) {
        Set<String> required = new HashSet<>();
        if (schema.has("required")) {
            if (!schema.get("required").isArray()) throw invalid();
            for (JsonNode item : schema.get("required")) required.add(item.asString());
        }
        return required;
    }

    private boolean hasType(JsonNode value, String type) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isString();
            case "boolean" -> value.isBoolean();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "null" -> value.isNull();
            default -> false;
        };
    }

    private String text(JsonNode node) { return node != null && node.isString() ? node.asString() : ""; }
    static CodexApiException invalid() { return new CodexApiException(503, 0, "Codex devolvió una respuesta que no cumple el esquema solicitado."); }
}
