package com.wuye.agent.agent.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * LLM/用户参数的最小 JSON Schema 校验：必填、基础类型、enum。
 *
 * <p>LLM 输出不可信。白名单只保证 capabilityId 合法，参数仍可能缺字段或类型不对；
 * 这里在调用子 Agent 前先拦一次，缺关键信息就走“追问用户”，而不是把错误抛给用户。</p>
 */
@Service
public class InputSchemaValidator {

    private final CapabilitySchemaService schemaService;

    public InputSchemaValidator(CapabilitySchemaService schemaService) {
        this.schemaService = schemaService;
    }

    public Result validate(String capabilityId, Map<String, Object> input) {
        Map<String, Object> schema = schemaService.schemaFor(capabilityId);
        if (schema == null || schema.isEmpty()) {
            return Result.ok();
        }
        Map<String, Object> values = input == null ? Map.of() : input;
        List<String> missing = new ArrayList<>();
        List<String> invalid = new ArrayList<>();

        Object requiredNode = schema.get("required");
        if (requiredNode instanceof List<?> required) {
            for (Object item : required) {
                String field = String.valueOf(item);
                Object value = values.get(field);
                if (value == null || (value instanceof String text && text.isBlank())) {
                    missing.add(field);
                }
            }
        }

        Object propertiesNode = schema.get("properties");
        if (propertiesNode instanceof Map<?, ?> properties) {
            for (Map.Entry<?, ?> entry : properties.entrySet()) {
                String field = String.valueOf(entry.getKey());
                Object value = values.get(field);
                if (value == null || !(entry.getValue() instanceof Map<?, ?> fieldSchema)) {
                    continue;
                }
                String type = string(fieldSchema.get("type"));
                if (type != null && !matchesType(type, value)) {
                    invalid.add(field);
                }
                Object enumNode = fieldSchema.get("enum");
                if (enumNode instanceof List<?> options && !options.isEmpty()) {
                    boolean matched = options.stream()
                            .anyMatch(option -> String.valueOf(option).equals(String.valueOf(value)));
                    if (!matched) {
                        invalid.add(field);
                    }
                }
            }
        }

        if (missing.isEmpty() && invalid.isEmpty()) {
            return Result.ok();
        }
        List<String> fields = new ArrayList<>();
        fields.addAll(missing);
        fields.addAll(invalid);
        String message;
        if (!missing.isEmpty()) {
            message = "缺少必填字段：" + String.join("、", missing);
        } else {
            message = "参数格式不正确：" + String.join("、", invalid);
        }
        return new Result(false, message, fields);
    }

    private static boolean matchesType(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Number number && number.doubleValue() == number.longValue();
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            default -> true;
        };
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record Result(boolean valid, String message, List<String> fields) {
        public static Result ok() {
            return new Result(true, null, List.of());
        }
    }
}
