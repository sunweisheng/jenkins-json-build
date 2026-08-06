package com.bluersw.jenkins.libraries.v3

import java.util.regex.Pattern

class PodSecurityValidator implements Serializable {
    void validate(String yaml, List forbiddenPatterns) {
        if (!yaml?.trim()) {
            throw new V3ConfigException('Kubernetes Agent 的 Pod YAML 不能为空')
        }
        for (Object expression : forbiddenPatterns ?: []) {
            Pattern pattern = Pattern.compile(expression.toString(), Pattern.CASE_INSENSITIVE | Pattern.MULTILINE)
            if (pattern.matcher(yaml).find()) {
                throw new V3ConfigException("Pod YAML 含有禁止的安全配置: ${expression}")
            }
        }
    }
}
