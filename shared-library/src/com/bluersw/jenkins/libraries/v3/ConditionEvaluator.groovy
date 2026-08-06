package com.bluersw.jenkins.libraries.v3

import java.util.regex.Pattern

class ConditionEvaluator implements Serializable {
    boolean evaluate(Object condition, Map variables) {
        if (condition == null) {
            return true
        }
        if (condition instanceof Boolean) {
            return condition
        }
        if (!(condition instanceof Map)) {
            throw new V3ConfigException('condition 必须是对象或布尔值')
        }

        Map rule = condition as Map
        if (rule.containsKey('all')) {
            return asRules(rule.all, 'all').every { evaluate(it, variables) }
        }
        if (rule.containsKey('any')) {
            return asRules(rule.any, 'any').any { evaluate(it, variables) }
        }
        if (rule.containsKey('not')) {
            return !evaluate(rule.not, variables)
        }

        String variable = requiredString(rule, 'variable')
        boolean exists = variables != null && variables.containsKey(variable) && variables[variable] != null
        String operator = (rule.operator ?: 'equals').toString()
        Object actual = exists ? variables[variable] : null
        Object expected = rule.value

        switch (operator) {
            case 'exists':
                return exists
            case 'equals':
                return comparable(actual) == comparable(expected)
            case 'notEquals':
                return comparable(actual) != comparable(expected)
            case 'in':
                return asValues(expected).collect { comparable(it) }.contains(comparable(actual))
            case 'notIn':
                return !asValues(expected).collect { comparable(it) }.contains(comparable(actual))
            case 'matches':
                return actual != null && Pattern.compile(expected?.toString() ?: '').matcher(actual.toString()).matches()
            default:
                throw new V3ConfigException("不支持的条件操作符 ${operator}")
        }
    }

    private static List asRules(Object value, String name) {
        if (!(value instanceof List) || value.isEmpty()) {
            throw new V3ConfigException("condition.${name} 必须是非空数组")
        }
        return value as List
    }

    private static List asValues(Object value) {
        return value instanceof Collection ? new ArrayList(value as Collection) : [value]
    }

    private static String requiredString(Map value, String key) {
        if (!value[key]?.toString()?.trim()) {
            throw new V3ConfigException("condition.${key} 不能为空")
        }
        return value[key].toString()
    }

    private static String comparable(Object value) {
        return value == null ? null : value.toString()
    }
}
