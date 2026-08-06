package com.bluersw.jenkins.libraries.v3

import groovy.json.JsonSlurper
import org.junit.Test

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertTrue
import static org.junit.Assert.fail

class V3PipelineTest {
    @Test
    void runsJavaStaticTemplateAndCallbacks() {
        FakeSteps steps = new FakeSteps()
        List<String> callbacks = []
        Map result = new V3Pipeline(steps, [
            configFiles: ['v3/java-static.json'],
            checkout: false,
            callbacks: [success: { context, error -> callbacks.add(context.projectId) }]
        ]).run()

        assertEquals('SUCCESS', result['java-test'].status)
        assertTrue(steps.commands.any { it.contains("'./mvnw' 'clean' 'test'") })
        assertTrue(steps.commands.any { it.contains("'./mvnw' 'package' '-DskipTests'") })
        assertEquals(['java-test'], callbacks)
    }

    @Test
    void registersCustomStepWithoutChangingCore() {
        FakeSteps steps = new FakeSteps()
        List<String> calls = []
        Map result = new V3Pipeline(steps, [
            configFiles: ['v3/custom.json'],
            checkout: false,
            stepHandlers: ['company.customStep': { context, config ->
                calls.add("${context.projectId}:${config.message}")
            }]
        ]).run()

        assertEquals(['custom-test:ready'], calls.collect { it.toString() })
        assertEquals('SUCCESS', result['custom-test'].status)
    }

    @Test
    void runsKubernetesKanikoDigestAndHelmTemplate() {
        FakeSteps steps = new FakeSteps()
        Map result = new V3Pipeline(steps, [configFiles: ['v3/java-kubernetes.json'], checkout: false]).run()

        assertEquals('SUCCESS', result['kubernetes-test'].status)
        assertTrue(steps.podYaml.contains('serviceAccountName: jenkins-deployer'))
        assertTrue(steps.podYaml.contains('maven:3.9.11-eclipse-temurin-21@sha256:'))
        assertFalse(steps.podYaml.contains('privileged: true'))
        assertFalse(steps.podYaml.contains('docker.sock'))
        assertTrue(steps.commands.any { it.contains('/kaniko/executor') && it.contains('--digest-file') })
        assertTrue(steps.commands.any { it.contains("'helm' 'upgrade'") && it.contains('sha256:' + ('a' * 64)) })
    }

    @Test
    void rollsBackHelmAndPreservesUpgradeFailure() {
        FakeSteps steps = new FakeSteps(failOnScriptContains: "'helm' 'upgrade'")
        try {
            new V3Pipeline(steps, [configFiles: ['v3/helm-failure.json'], checkout: false]).run()
            fail('Expected Helm upgrade failure')
        } catch (RuntimeException error) {
            assertTrue(error.message.contains('simulated command failure'))
        }
        assertTrue(steps.commands.any { it.contains("'helm' 'rollback' 'sample'") })
    }

    @Test
    void mergesConfigFilesIntoOneExecutionPlan() {
        FakeSteps steps = new FakeSteps()
        List<String> messages = []
        Map result = new V3Pipeline(steps, [
            configFiles: ['v3/merge-base.json', 'v3/merge-override.json'],
            checkout: false,
            stepHandlers: ['company.capture': { context, config -> messages.add(config.message.toString()) }]
        ]).run()

        assertEquals(['override'], messages)
        assertEquals(['merge-test'] as Set, result.keySet() as Set)
    }

    @Test
    void isolatesProjectVariablesAndLimitsParallelBatches() {
        FakeSteps steps = new FakeSteps()
        List<String> captured = []
        Map result = new V3Pipeline(steps, [
            configFiles: ['v3/multi-project.json'],
            checkout: false,
            stepHandlers: ['company.capture': { context, config ->
                captured.add("${context.projectId}:${config.message}".toString())
            }]
        ]).run()

        assertEquals([
            'api:api', 'worker:worker', 'scheduler:scheduler', 'report:report', 'gateway:gateway'
        ], captured)
        assertEquals([['api', 'worker'], ['scheduler', 'report'], ['gateway']], steps.parallelBatches)
        assertEquals(5, result.size())
        assertTrue(result.values().every { it.status == 'SUCCESS' })
        assertEquals(['RUN_MULTI'], steps.parameterDefinitions.collect { it.name })
    }

    @Test
    void dispatchesEverySupportedCommandShell() {
        FakeSteps steps = new FakeSteps()
        Map result = new V3Pipeline(steps, [configFiles: ['v3/command-shells.json'], checkout: false]).run()

        assertEquals('SUCCESS', result['command-shells'].status)
        assertEquals(['sh', 'sh', 'powershell', 'pwsh', 'bat'], steps.shellInvocations.collect { it.method })
        assertTrue(steps.shellInvocations[1].arguments.script.startsWith('#!/usr/bin/env bash\nset -e\n'))
        assertFalse(steps.shellInvocations[1].arguments.script.contains('bash -l'))
    }

    @Test
    void runsCommonControlsRuntimeVariablesAndPostHandlers() {
        FakeSteps steps = new FakeSteps()
        Map<String, Map> callbackVariables = [:]
        Map result = new V3Pipeline(steps, [
            configFiles: ['v3/common-controls.json'],
            checkout: false,
            trustedHosts: ['config.example.test'],
            callbacks: [
                success: { context, error -> callbackVariables.success = context.variables() },
                always: { context, error -> callbackVariables.always = context.variables() }
            ]
        ]).run()

        assertEquals('SUCCESS', result['common-controls'].status)
        assertEquals('42', callbackVariables.success.FROM_ENV)
        assertEquals('output', callbackVariables.success.FROM_COMMAND)
        assertEquals('file-value', callbackVariables.success.FROM_FILE)
        assertEquals('http-value', callbackVariables.success.FROM_HTTP)
        assertEquals('json-value', callbackVariables.success.FROM_JSON)
        assertEquals('ready', callbackVariables.success.DYNAMIC)
        assertEquals('success', callbackVariables.success.POST_SUCCESS)
        assertEquals('always', callbackVariables.always.POST_ALWAYS)
        assertEquals([2, 3], steps.retryCounts)
        assertTrue(steps.timeoutInvocations.any { it.time == 5 && it.unit == 'MINUTES' })
        assertTrue(steps.timeoutInvocations.any { it.time == 1 && it.unit == 'SECONDS' })
        assertEquals(1, steps.credentialInvocations.size())
        assertEquals(['SonarQube'], steps.sonarQubeInstallations)
        assertEquals(1, steps.qualityGateInvocations.size())
        assertEquals(1, steps.junitInvocations.size())
        assertEquals(1, steps.jacocoInvocations.size())
        assertEquals(1, steps.archiveInvocations.size())
        assertEquals(['TEXT_VALUE', 'BOOLEAN_VALUE', 'CHOICE_VALUE', 'MULTI_VALUE'],
            steps.parameterDefinitions.collect { it.name })
    }
}

class FakeSteps {
    Map env = [BUILD_NUMBER: '42']
    Map params = [:]
    Map scm = [:]
    List<String> commands = []
    List<List<String>> parallelBatches = []
    List<Map> parameterDefinitions = []
    List<Map> shellInvocations = []
    List<Integer> retryCounts = []
    List<Map> timeoutInvocations = []
    List<List> credentialInvocations = []
    List<String> sonarQubeInstallations = []
    List<Map> qualityGateInvocations = []
    List<Map> junitInvocations = []
    List<Map> jacocoInvocations = []
    List<Map> archiveInvocations = []
    String podYaml = ''
    String failOnScriptContains

    String libraryResource(String path) {
        return new File('../shared-library/resources', path).getText('UTF-8')
    }

    Object readJSON(Map arguments) {
        return new JsonSlurper().parseText(arguments.text.toString())
    }

    String readTrusted(String path) {
        return new File('src/test/resources', path).getText('UTF-8')
    }

    void node(String label, Closure body) { body.call() }
    void stage(String name, Closure body) { body.call() }
    void timeout(Map arguments, Closure body) {
        timeoutInvocations.add(new LinkedHashMap(arguments))
        body.call()
    }
    void retry(int count, Closure body) {
        retryCounts.add(count)
        body.call()
    }
    void container(String name, Closure body) { body.call() }
    void dir(String path, Closure body) { body.call() }
    void withEnv(List<String> values, Closure body) { body.call() }
    void podTemplate(Map arguments, Closure body) {
        podYaml = arguments.yaml.toString()
        env.POD_LABEL = 'v3-test-pod'
        body.call()
    }
    String pwd() { '/workspace' }
    void checkout(Object scm) { }
    void junit(Map arguments) { junitInvocations.add(new LinkedHashMap(arguments)) }
    void jacoco(Map arguments) { jacocoInvocations.add(new LinkedHashMap(arguments)) }
    void archiveArtifacts(Map arguments) { archiveInvocations.add(new LinkedHashMap(arguments)) }
    Object string(Map arguments) { new LinkedHashMap(arguments) }
    Object booleanParam(Map arguments) { new LinkedHashMap(arguments) }
    Object choice(Map arguments) { new LinkedHashMap(arguments) }
    Object parameters(List arguments) {
        parameterDefinitions.addAll(arguments.collect { new LinkedHashMap(it as Map) })
        return arguments
    }
    void properties(List arguments) { }
    void withCredentials(List bindings, Closure body) {
        credentialInvocations.add(bindings.collect { new LinkedHashMap(it as Map) })
        body.call()
    }
    void withSonarQubeEnv(String installation, Closure body) {
        sonarQubeInstallations.add(installation)
        body.call()
    }
    Object waitForQualityGate(Map arguments) {
        qualityGateInvocations.add(new LinkedHashMap(arguments))
        return [status: 'OK']
    }
    Object httpRequest(Map arguments) {
        return [content: 'http-value\n', status: 200]
    }
    void parallel(Map<String, Closure> branches) {
        parallelBatches.add(branches.keySet().collect { it.toString() })
        branches.values().each { it.call() }
    }
    List findFiles(Map arguments) { [[name: 'app.jar']] }
    String readFile(Map arguments) {
        if (arguments.file.toString().endsWith('image-digest')) return 'sha256:' + ('a' * 64) + '\n'
        return new File(arguments.file.toString()).getText('UTF-8')
    }
    void echo(String message) { }

    Object sh(Map arguments) {
        shellInvocations.add([method: 'sh', arguments: new LinkedHashMap(arguments)])
        commands.add(arguments.script.toString())
        if (failOnScriptContains && arguments.script.toString().contains(failOnScriptContains)) {
            throw new RuntimeException('simulated command failure')
        }
        if (arguments.returnStatus) return 0
        if (arguments.returnStdout) return 'output\n'
        return null
    }

    Object powershell(Map arguments) {
        shellInvocations.add([method: 'powershell', arguments: new LinkedHashMap(arguments)])
        return arguments.returnStatus ? 0 : (arguments.returnStdout ? 'output\r\n' : null)
    }

    Object pwsh(Map arguments) {
        shellInvocations.add([method: 'pwsh', arguments: new LinkedHashMap(arguments)])
        return arguments.returnStatus ? 0 : (arguments.returnStdout ? 'output\n' : null)
    }

    Object bat(Map arguments) {
        shellInvocations.add([method: 'bat', arguments: new LinkedHashMap(arguments)])
        return arguments.returnStatus ? 0 : (arguments.returnStdout ? 'output\r\n' : null)
    }
}
