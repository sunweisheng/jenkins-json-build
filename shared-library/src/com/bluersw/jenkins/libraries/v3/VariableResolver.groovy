package com.bluersw.jenkins.libraries.v3

import java.util.regex.Matcher
import java.util.regex.Pattern

class VariableResolver implements Serializable {
    private static final Pattern VARIABLE = Pattern.compile(/\$\{([A-Za-z_][A-Za-z0-9_.-]*)\}/)

    Object resolve(Object value, Map variables, String location = '配置') {
        if (value instanceof Map) {
            Map resolved = new LinkedHashMap()
            value.each { key, child -> resolved[key] = resolve(child, variables, "${location}.${key}") }
            return resolved
        }
        if (value instanceof List) {
            List resolved = []
            for (int index = 0; index < value.size(); index++) {
                resolved.add(resolve(value[index], variables, "${location}[${index}]"))
            }
            return resolved
        }
        if (!(value instanceof CharSequence)) {
            return value
        }

        String text = value.toString()
        Matcher exact = VARIABLE.matcher(text)
        if (exact.matches()) {
            String name = exact.group(1)
            requireVariable(name, variables, location)
            return variables[name]
        }

        Matcher matcher = VARIABLE.matcher(text)
        StringBuffer output = new StringBuffer()
        while (matcher.find()) {
            String name = matcher.group(1)
            requireVariable(name, variables, location)
            matcher.appendReplacement(output, Matcher.quoteReplacement(stringValue(variables[name])))
        }
        matcher.appendTail(output)
        return output.toString()
    }

    Map<String, Object> resolveVariableMap(Map definitions, Map inherited, String location) {
        Map<String, Object> resolved = new LinkedHashMap<String, Object>()
        Map<String, Object> available = new LinkedHashMap<String, Object>()
        available.putAll(inherited ?: [:])
        (definitions ?: [:]).each { key, value ->
            String name = key.toString()
            Object resolvedValue = resolve(value, available, "${location}.${name}")
            resolved[name] = resolvedValue
            available[name] = resolvedValue
        }
        return resolved
    }

    private static void requireVariable(String name, Map variables, String location) {
        if (variables == null || !variables.containsKey(name) || variables[name] == null) {
            throw new V3ConfigException("${location} 引用了未定义变量 ${name}")
        }
    }

    private static String stringValue(Object value) {
        if (value instanceof Collection) {
            return value.collect { it == null ? '' : it.toString() }.join(',')
        }
        return value == null ? '' : value.toString()
    }
}
