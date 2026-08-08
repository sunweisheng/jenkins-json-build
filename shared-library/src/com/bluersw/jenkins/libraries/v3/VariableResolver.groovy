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

        Map<String, Object> pending = new LinkedHashMap<String, Object>()
        (definitions ?: [:]).each { key, value -> pending[key.toString()] = value }
        while (!pending.isEmpty()) {
            boolean progressed = false
            List<String> names = new ArrayList<String>(pending.keySet())
            for (String name : names) {
                Set<String> dependencies = variableNames(pending[name])
                if (!dependencies.every { dependency ->
                    (!pending.containsKey(dependency) || dependency == name) &&
                        available.containsKey(dependency) && available[dependency] != null
                }) {
                    continue
                }
                Object resolvedValue = resolve(pending.remove(name), available, "${location}.${name}")
                resolved[name] = resolvedValue
                available[name] = resolvedValue
                progressed = true
            }
            if (!progressed) {
                String name = pending.keySet().iterator().next()
                Set<String> dependencies = variableNames(pending[name])
                String missing = dependencies.find { dependency ->
                    (!available.containsKey(dependency) || available[dependency] == null) && !pending.containsKey(dependency)
                }
                if (missing != null || dependencies.contains(name) &&
                    (!available.containsKey(name) || available[name] == null)) {
                    resolve(pending[name], available, "${location}.${name}")
                }
                throw new V3ConfigException("${location} 存在循环变量引用: ${pending.keySet().join(', ')}")
            }
        }
        return resolved
    }

    private static Set<String> variableNames(Object value) {
        Set<String> names = new LinkedHashSet<String>()
        collectVariableNames(value, names)
        return names
    }

    private static void collectVariableNames(Object value, Set<String> names) {
        if (value instanceof Map) {
            (value as Map).values().each { collectVariableNames(it, names) }
        } else if (value instanceof Collection) {
            (value as Collection).each { collectVariableNames(it, names) }
        } else if (value instanceof CharSequence) {
            Matcher matcher = VARIABLE.matcher(value.toString())
            while (matcher.find()) names.add(matcher.group(1))
        }
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
