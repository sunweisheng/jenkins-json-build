package com.bluersw.jenkins.libraries.v3

import com.cloudbees.groovy.cps.NonCPS

/** A per-project execution context. Instances are never shared between builds. */
class BuildContext implements Serializable {
    final String projectId
    final String configPath
    final Map<String, Object> environment
    final Map<String, Object> globalVariables
    final Map<String, Object> runtimeVariables
    final Map<String, Object> projectVariables
    final Map<String, Object> outputs = new LinkedHashMap<String, Object>()
    final Map<String, Object> result = new LinkedHashMap<String, Object>()

    BuildContext(String projectId,
                 String configPath,
                 Map environment = [:],
                 Map globalVariables = [:],
                 Map runtimeVariables = [:],
                 Map projectVariables = [:]) {
        this.projectId = projectId
        this.configPath = configPath
        this.environment = copy(environment)
        this.globalVariables = copy(globalVariables)
        this.runtimeVariables = copy(runtimeVariables)
        this.projectVariables = copy(projectVariables)
        this.result.putAll([status: 'PENDING', stages: new LinkedHashMap<String, Object>()])
    }

    Map<String, Object> variables(Map stageVariables = [:], Map stepVariables = [:]) {
        Map<String, Object> values = new LinkedHashMap<String, Object>()
        values.putAll(environment)
        values.putAll(globalVariables)
        values.putAll(runtimeVariables)
        values.putAll(outputs)
        values.putAll(projectVariables)
        values.putAll(copy(stageVariables))
        values.putAll(copy(stepVariables))
        return values
    }

    void setRuntimeVariable(String name, Object value) {
        runtimeVariables[name] = value
    }

    @NonCPS
    private static Map<String, Object> copy(Map source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>()
        if (source != null) {
            source.each { key, value -> result[key.toString()] = value }
        }
        return result
    }
}
