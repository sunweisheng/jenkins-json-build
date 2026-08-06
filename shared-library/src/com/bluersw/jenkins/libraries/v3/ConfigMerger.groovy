package com.bluersw.jenkins.libraries.v3

class ConfigMerger implements Serializable {
    Map merge(Map base, Map override) {
        Map result = deepCopy(base ?: [:]) as Map
        (override ?: [:]).each { key, value ->
            if (key == 'stages' && value instanceof List && result[key] instanceof List) {
                result[key] = mergeNamedLists(result[key] as List, value as List, 'id')
            } else if (value instanceof Map && result[key] instanceof Map) {
                result[key] = merge(result[key] as Map, value as Map)
            } else {
                result[key] = deepCopy(value)
            }
        }
        return result
    }

    private List mergeNamedLists(List base, List override, String identityKey) {
        List result = deepCopy(base) as List
        for (Object item : override) {
            if (!(item instanceof Map) || !item[identityKey]) {
                result.add(deepCopy(item))
                continue
            }
            int index = result.findIndexOf { candidate ->
                candidate instanceof Map && candidate[identityKey]?.toString() == item[identityKey].toString()
            }
            if (index >= 0) {
                result[index] = merge(result[index] as Map, item as Map)
            } else {
                result.add(deepCopy(item))
            }
        }
        return result
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map) {
            Map copy = new LinkedHashMap()
            value.each { key, child -> copy[key] = deepCopy(child) }
            return copy
        }
        if (value instanceof List) {
            return value.collect { deepCopy(it) }
        }
        return value
    }
}
