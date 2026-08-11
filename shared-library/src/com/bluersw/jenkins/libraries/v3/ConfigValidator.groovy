package com.bluersw.jenkins.libraries.v3

class ConfigValidator implements Serializable {
    private final Set<String> supportedSteps

    ConfigValidator(Collection supportedSteps) {
        this.supportedSteps = new LinkedHashSet<String>()
        (supportedSteps ?: []).each { this.supportedSteps.add(it.toString()) }
    }

    void validate(Map config, String source = '配置') {
        if (config.schemaVersion?.toString() != '3') {
            throw new V3ConfigException("${source} 的 schemaVersion 必须为 3")
        }
        if (!(config.stages instanceof List) || config.stages.isEmpty()) {
            throw new V3ConfigException("${source} 的 stages 必须是非空数组")
        }
        requireMap(config, 'variables', source)
        requireMap(config, 'post', source)

        Set<String> stageIds = new LinkedHashSet<String>()
        for (int stageIndex = 0; stageIndex < config.stages.size(); stageIndex++) {
            Map stage = requireMapValue(config.stages[stageIndex], "${source}.stages[${stageIndex}]")
            String stageId = requiredText(stage, 'id', "${source}.stages[${stageIndex}]")
            requiredText(stage, 'name', "${source}.stages[${stageIndex}]")
            if (!stageIds.add(stageId)) {
                throw new V3ConfigException("${source} 含有重复阶段编号 ${stageId}")
            }
            requireMap(stage, 'variables', "${source}.stages[${stageIndex}]")
            validateSteps(stage.steps, "${source}.stages[${stageIndex}].steps")
        }

        if (config.post instanceof Map) {
            ['success', 'failure', 'cancelled', 'always'].each { event ->
                if ((config.post as Map).containsKey(event)) {
                    validateSteps((config.post as Map)[event], "${source}.post.${event}")
                }
            }
        }
        validateProjects(config.projects, source)
    }

    private void validateSteps(Object value, String location) {
        if (!(value instanceof List)) {
            throw new V3ConfigException("${location} 必须是数组")
        }
        for (int index = 0; index < value.size(); index++) {
            Map step = requireMapValue(value[index], "${location}[${index}]")
            String type = requiredText(step, 'type', "${location}[${index}]")
            if (!supportedSteps.contains(type)) {
                throw new V3ConfigException("${location}[${index}] 使用了未注册步骤 ${type}")
            }
            if (['condition', 'retry', 'timeout', 'credentials', 'appleSigning'].contains(type)) {
                validateSteps(step.steps, "${location}[${index}].steps")
            }
            requireMap(step, 'variables', "${location}[${index}]")
        }
    }

    private static void validateProjects(Object value, String source) {
        if (value == null) {
            return
        }
        Map projects = requireMapValue(value, "${source}.projects")
        if (projects.items != null && !(projects.items instanceof List)) {
            throw new V3ConfigException("${source}.projects.items 必须是数组")
        }
        Set<String> ids = new LinkedHashSet<String>()
        for (Object itemValue : projects.items ?: []) {
            Map item = requireMapValue(itemValue, "${source}.projects.items")
            String id = requiredText(item, 'id', "${source}.projects.items")
            if (!ids.add(id)) {
                throw new V3ConfigException("${source}.projects.items 含有重复项目编号 ${id}")
            }
            if (!(item.configFiles instanceof List) || item.configFiles.isEmpty()) {
                throw new V3ConfigException("项目 ${id} 的 configFiles 必须是非空数组")
            }
        }
    }

    private static void requireMap(Map parent, String key, String location) {
        if (parent[key] != null && !(parent[key] instanceof Map)) {
            throw new V3ConfigException("${location}.${key} 必须是对象")
        }
    }

    private static Map requireMapValue(Object value, String location) {
        if (!(value instanceof Map)) {
            throw new V3ConfigException("${location} 必须是对象")
        }
        return value as Map
    }

    private static String requiredText(Map value, String key, String location) {
        String text = value[key]?.toString()?.trim()
        if (!text) {
            throw new V3ConfigException("${location}.${key} 不能为空")
        }
        return text
    }
}
