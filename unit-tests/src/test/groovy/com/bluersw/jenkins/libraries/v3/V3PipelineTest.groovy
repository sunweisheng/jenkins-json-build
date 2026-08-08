package com.bluersw.jenkins.libraries.v3

import groovy.json.JsonSlurper
import org.junit.Test

import java.nio.file.Files

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
    void runsKubernetesBuildKitDigestAndHelmTemplate() {
        FakeSteps steps = new FakeSteps()
        Map result = new V3Pipeline(steps, [configFiles: ['v3/java-kubernetes.json'], checkout: false]).run()

        assertEquals('SUCCESS', result['kubernetes-test'].status)
        assertTrue(steps.podYaml.contains('serviceAccountName: jenkins-deployer'))
        assertTrue(steps.podYaml.contains('maven:3.9.11-eclipse-temurin-21@sha256:'))
        assertTrue(steps.podYaml.contains('moby/buildkit:v0.32.2-rootless@sha256:504731e577c20559c00f968f33219f30115e70be29ab96728d1d06e963fc494b'))
        assertTrue(steps.podYaml.contains('appArmorProfile:'))
        assertTrue(steps.podYaml.contains('type: Unconfined'))
        assertTrue(steps.podYaml.contains('runAsNonRoot: true'))
        assertFalse(steps.podYaml.contains('privileged: true'))
        assertFalse(steps.podYaml.contains('docker.sock'))
        assertTrue(steps.commands.any { it.contains('buildctl-daemonless.sh') && it.contains('--metadata-file') &&
            it.contains('--import-cache') && it.contains('--export-cache') })
        assertTrue(steps.commands.any { it.contains("'helm' 'upgrade'") && it.contains('sha256:' + ('a' * 64)) })
        assertEquals('sha256:' + ('a' * 64) + '\n', steps.writtenFiles['.jenkins-json-build/image-digest'])
    }

    @Test
    void keepsExplicitKanikoTemplateCompatible() {
        FakeSteps steps = new FakeSteps()
        Map result = new V3Pipeline(steps, [configFiles: ['v3/java-kubernetes-kaniko.json'], checkout: false]).run()

        assertEquals('SUCCESS', result['kaniko-test'].status)
        assertTrue(steps.podYaml.contains('registry.example.test/kaniko:custom@sha256:'))
        assertTrue(steps.podYaml.contains('mountPath: /config/kaniko'))
        assertTrue(steps.podYaml.contains('value: /config/kaniko'))
        assertTrue(steps.podYaml.contains('secretName: private-kaniko-config'))
        assertTrue(steps.podYaml.contains('memory: "3Gi"'))
        assertFalse(steps.podYaml.contains('name: buildkit'))
        assertTrue(steps.commands.any { it.contains("'/opt/kaniko/executor'") && it.contains("'--digest-file'") })
    }

    @Test
    void keepsDefaultKubernetesImagesPinnedAndPodImagesVariableDriven() {
        FakeSteps steps = new FakeSteps()
        List<String> imageVariables = ['MAVEN_IMAGE', 'BUILDKIT_IMAGE', 'KANIKO_IMAGE', 'HELM_IMAGE']
        for (String template : ['java-maven-kubernetes.json', 'java-maven-kubernetes-kaniko.json']) {
            Map parsed = new JsonSlurper().parseText(steps.libraryResource(
                "com/bluersw/jenkins/libraries/v3/templates/${template}")) as Map
            for (String variable : imageVariables) {
                String image = (parsed.variables as Map)[variable].toString()
                assertTrue("${template} ${variable} must use a digest", image.contains('@sha256:'))
                assertFalse("${template} ${variable} must not use latest", image.contains(':latest'))
            }
            assertFalse((parsed.variables as Map).BUILDKIT_IMAGE.toString().contains('60d1f642'))
        }

        for (String pod : ['java-buildkit-helm.yaml', 'java-kaniko-helm.yaml']) {
            List<String> imageLines = steps.libraryResource(
                "com/bluersw/jenkins/libraries/v3/pods/${pod}").readLines().findAll { it.trim().startsWith('image:') }
            assertTrue(imageLines.every { it.contains('${') })
        }
    }

    @Test
    void supportsAdvancedBuildKitOptionsWithoutExposingCredentialValues() {
        FakeSteps steps = new FakeSteps()
        steps.env.putAll([BUILD_SECRET: 'top-secret-value', BUILD_SECRET_FILE: '/tmp/build-secret',
            BUILD_SSH_KEY: '/tmp/build-ssh-key'])
        Map callbackVariables = [:]
        Map result = new V3Pipeline(steps, [
            configFiles: ['v3/buildkit-advanced.json'],
            checkout: false,
            callbacks: [success: { context, error -> callbackVariables.putAll(context.variables()) }]
        ]).run()

        assertEquals('SUCCESS', result['buildkit-advanced'].status)
        String command = steps.commands.find { it.contains("'buildctl-daemonless.sh' 'build'") }
        assertTrue(command.contains("'platform=linux/amd64,linux/arm64'"))
        assertTrue(command.contains("'target=runtime'"))
        assertTrue(command.contains("'build-arg:VERSION=42'"))
        assertTrue(command.contains("'dockerfile=docker'"))
        assertTrue(command.contains("'filename=Dockerfile.release'"))
        assertTrue(command.contains("'--import-cache'"))
        assertTrue(command.contains("'--export-cache'"))
        assertTrue(command.contains("'type=image,name=ghcr.io/example/app:42,ghcr.io/example/app:latest,push=true'"))
        assertTrue(command.contains("'--progress=plain'"))
        assertTrue(command.contains('set +x'))
        assertTrue(command.contains('${BUILD_SECRET+x}'))
        assertTrue(command.contains('chmod 600'))
        assertTrue(command.contains('ln -s "$BUILD_SECRET_FILE"'))
        assertTrue(command.contains('$BUILD_SECRET'))
        assertTrue(command.contains('$BUILD_SECRET_FILE'))
        assertTrue(command.contains('$BUILD_SSH_KEY'))
        assertTrue(command.contains('trap'))
        assertFalse(command.contains('top-secret-value'))
        assertEquals(['service'], steps.directories)
        assertEquals(1, steps.credentialInvocations.size())
        assertEquals('sha256:' + ('a' * 64), callbackVariables.IMAGE_DIGEST)
        assertEquals('ghcr.io/example/app@sha256:' + ('a' * 64), callbackVariables.IMAGE_REFERENCE)

        File temporaryDirectory = Files.createTempDirectory('buildkit-secret-cleanup-').toFile()
        try {
            File fileSecret = new File(temporaryDirectory, 'file-secret')
            File sshKey = new File(temporaryDirectory, 'ssh-key')
            fileSecret.setText('file-secret-value', 'UTF-8')
            sshKey.setText('ssh-secret-value', 'UTF-8')
            List<String> failingScript = new ArrayList<String>(command.readLines())
            failingScript.remove(failingScript.size() - 1)
            failingScript.add('false')
            ProcessBuilder processBuilder = new ProcessBuilder('/bin/sh', '-c', failingScript.join('\n'))
            processBuilder.directory(temporaryDirectory)
            processBuilder.redirectErrorStream(true)
            processBuilder.environment().putAll([
                BUILD_SECRET: 'top-secret-value',
                BUILD_SECRET_FILE: fileSecret.absolutePath,
                BUILD_SSH_KEY: sshKey.absolutePath
            ])
            Process process = processBuilder.start()
            String output = process.inputStream.getText('UTF-8')
            assertTrue(process.waitFor() != 0)
            assertFalse(new File(temporaryDirectory, '.jenkins-json-build/buildkit-secrets').exists())
            assertFalse(output.contains('top-secret-value'))
            assertFalse(output.contains('file-secret-value'))
            assertFalse(output.contains('ssh-secret-value'))
        } finally {
            temporaryDirectory.deleteDir()
        }
    }

    @Test
    void rendersProjectOverridesForImagesPathsResourcesAndExecutables() {
        FakeSteps steps = new FakeSteps()
        Map result = new V3Pipeline(steps, [configFiles: ['v3/template-overrides.json'], checkout: false]).run()

        assertEquals('SUCCESS', result['template-overrides'].status)
        assertTrue(steps.podYaml.contains('registry.example.test/maven:custom@sha256:'))
        assertTrue(steps.podYaml.contains('registry.example.test/buildkit:custom@sha256:'))
        assertTrue(steps.podYaml.contains('registry.example.test/helm:custom@sha256:'))
        assertTrue(steps.podYaml.contains('--oci-worker-no-process-sandbox --debug'))
        assertTrue(steps.podYaml.contains('--root /cache/buildkit'))
        assertTrue(steps.podYaml.contains('mountPath: /cache/buildkit'))
        assertTrue(steps.podYaml.contains('mountPath: /config/docker'))
        assertTrue(steps.podYaml.contains('secretName: private-registry-config'))
        assertTrue(steps.podYaml.contains('cpu: "7"'))
        assertTrue(steps.commands.any { it.contains("'/opt/buildkit/buildctl-daemonless.sh'") &&
            it.contains("'company.dockerfile.v0'") })
        assertTrue(steps.commands.any { it.contains("'/opt/helm' 'upgrade'") })
    }

    @Test
    void rejectsUnknownBuilderAndConflictingCacheConfiguration() {
        try {
            new V3Pipeline(new FakeSteps(), [configFiles: ['v3/unknown-builder.json'], checkout: false]).run()
            fail('Expected unknown builder failure')
        } catch (V3ConfigException error) {
            assertTrue(error.message.contains('buildkit'))
            assertTrue(error.message.contains('kaniko'))
        }

        try {
            new V3Pipeline(new FakeSteps(), [configFiles: ['v3/buildkit-cache-conflict.json'], checkout: false]).run()
            fail('Expected BuildKit cache conflict')
        } catch (V3ConfigException error) {
            assertTrue(error.message.contains('cacheFrom'))
        }

        try {
            new V3Pipeline(new FakeSteps(), [configFiles: ['v3/buildkit-invalid-secret.json'], checkout: false]).run()
            fail('Expected BuildKit secret validation failure')
        } catch (V3ConfigException error) {
            assertTrue(error.message.contains('必须且只能设置'))
        }

        try {
            new V3Pipeline(new FakeSteps(), [configFiles: ['v3/buildkit-invalid-path.json'], checkout: false]).run()
            fail('Expected BuildKit metadata path validation failure')
        } catch (V3ConfigException error) {
            assertTrue(error.message.contains('metadataFile'))
            assertTrue(error.message.contains('相对路径'))
        }
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
    List<String> directories = []
    Map<String, String> writtenFiles = [:]
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
    void dir(String path, Closure body) {
        directories.add(path)
        body.call()
    }
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
    Object file(Map arguments) { new LinkedHashMap(arguments) }
    Object sshUserPrivateKey(Map arguments) { new LinkedHashMap(arguments) }
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
        if (writtenFiles.containsKey(arguments.file.toString())) return writtenFiles[arguments.file.toString()]
        if (arguments.file.toString().contains('metadata')) {
            return '{"containerimage.digest":"sha256:' + ('a' * 64) + '"}\n'
        }
        if (arguments.file.toString().endsWith('image-digest')) return 'sha256:' + ('a' * 64) + '\n'
        return new File(arguments.file.toString()).getText('UTF-8')
    }
    void writeFile(Map arguments) { writtenFiles[arguments.file.toString()] = arguments.text.toString() }
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
