package com.bluersw.jenkins.libraries.v3

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import org.junit.Test
import org.yaml.snakeyaml.Yaml

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
        Map pod = new Yaml().load(steps.podYaml) as Map
        Map podSecurityContext = pod.spec.securityContext as Map
        assertEquals(true, podSecurityContext.runAsNonRoot)
        assertEquals(1000, podSecurityContext.runAsUser)
        assertEquals(1000, podSecurityContext.runAsGroup)
        assertEquals(1000, podSecurityContext.fsGroup)
        assertEquals('OnRootMismatch', podSecurityContext.fsGroupChangePolicy)

        Map<String, Map> containers = (pod.spec.containers as List).collectEntries { Map container ->
            [(container.name.toString()): container]
        }
        Map maven = containers.maven
        assertEquals([runAsNonRoot: true, runAsUser: 1000, runAsGroup: 1000,
            allowPrivilegeEscalation: false, capabilities: [drop: ['ALL']]], maven.securityContext)
        Map mavenEnvironment = (maven.env as List).collectEntries { Map entry ->
            [(entry.name.toString()): entry.value]
        }
        assertEquals('/home/jenkins', mavenEnvironment.HOME)
        assertEquals('/home/jenkins/.m2', mavenEnvironment.MAVEN_CONFIG)
        assertTrue(mavenEnvironment.MAVEN_OPTS.toString().contains('-Duser.home=/home/jenkins'))
        assertTrue(mavenEnvironment.MAVEN_OPTS.toString().contains('-Dmaven.repo.local=/home/jenkins/.m2/repository'))
        Map<String, Map> mavenMounts = (maven.volumeMounts as List).collectEntries { Map mount ->
            [(mount.name.toString()): mount]
        }
        assertEquals(['maven-home', 'maven-cache'],
            (maven.volumeMounts as List).collect { it.name.toString() })
        assertEquals('/home/jenkins', mavenMounts['maven-home'].mountPath)
        assertEquals('/home/jenkins/.m2', mavenMounts['maven-cache'].mountPath)

        Map buildkit = containers.buildkit
        assertEquals(true, buildkit.securityContext.runAsNonRoot)
        assertEquals(1000, buildkit.securityContext.runAsUser)
        assertEquals(1000, buildkit.securityContext.runAsGroup)
        assertEquals(true, buildkit.securityContext.allowPrivilegeEscalation)
        assertEquals('Unconfined', buildkit.securityContext.seccompProfile.type)
        assertEquals('Unconfined', buildkit.securityContext.appArmorProfile.type)
        assertEquals(['SETUID', 'SETGID'], buildkit.securityContext.capabilities.add)
        assertEquals(['ALL'], buildkit.securityContext.capabilities.drop)
        Map buildkitEnvironment = (buildkit.env as List).collectEntries { Map entry ->
            [(entry.name.toString()): entry.value]
        }
        assertTrue(buildkitEnvironment.BUILDKITD_FLAGS.toString().contains('--oci-worker-no-process-sandbox'))
        assertTrue(buildkitEnvironment.BUILDKITD_FLAGS.toString().contains('--root /home/user/.local/share/buildkit'))

        Map helm = containers.helm
        assertEquals(true, helm.securityContext.runAsNonRoot)
        assertEquals(1000, helm.securityContext.runAsUser)
        assertEquals(1000, helm.securityContext.runAsGroup)
        assertEquals(false, helm.securityContext.allowPrivilegeEscalation)
        Map helmEnvironment = (helm.env as List).collectEntries { Map entry ->
            [(entry.name.toString()): entry.value]
        }
        assertEquals('/home/jenkins', helmEnvironment.HOME)
        Map<String, Map> helmMounts = (helm.volumeMounts as List).collectEntries { Map mount ->
            [(mount.name.toString()): mount]
        }
        assertEquals('/home/jenkins', helmMounts['helm-home'].mountPath)

        Map<String, Map> volumes = (pod.spec.volumes as List).collectEntries { Map volume ->
            [(volume.name.toString()): volume]
        }
        assertTrue(volumes['maven-home'].containsKey('emptyDir'))
        assertTrue(volumes['maven-cache'].containsKey('emptyDir'))
        assertTrue(volumes['helm-home'].containsKey('emptyDir'))
        assertFalse(volumes.containsKey('maven-settings'))
        assertFalse(steps.podYaml.contains('MAVEN_SETTINGS_CONFIG_MAP'))
        assertFalse(steps.podYaml.contains('/root/.m2'))
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
        steps.params.RUN_MULTI = 'api,worker,scheduler,report,gateway'
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
    void escapesCommandArgumentsWithoutMethodClosures() {
        assertEquals("'one'\"'\"'two'", ShellEscaper.posix("one'two"))
        assertEquals("'one''two'", ShellEscaper.powershell("one'two"))
        assertEquals('"one""two"', ShellEscaper.batch('one"two'))

        String source = new File('../shared-library/src/com/bluersw/jenkins/libraries/v3/V3Pipeline.groovy').text
        assertFalse(source.contains('ShellEscaper.&'))
    }

    @Test
    void runsCommonControlsRuntimeVariablesAndPostHandlers() {
        FakeSteps steps = new FakeSteps()
        steps.params.putAll([TEXT_VALUE: 'text', BOOLEAN_VALUE: true, CHOICE_VALUE: 'one', MULTI_VALUE: 'one,two'])
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
        assertEquals(30, steps.httpInvocations[0].timeout)
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

    @Test
    void initializesParametersAndPreservesCurrentSelections() {
        FakeSteps firstRun = new FakeSteps()
        firstRun.populateParamsOnProperties = true
        Map firstResult = new V3Pipeline(firstRun, [configFiles: ['v3/parameters.json'], checkout: false]).run()

        assertTrue(firstResult.isEmpty())
        assertEquals('NOT_BUILT', firstRun.currentBuild.result)
        assertTrue(firstRun.events.every { !it.startsWith('node:') && it != 'checkout' })
        assertEquals(['BUILD_AGENT', 'PROJECTS', 'INLINE_PROJECTS'], firstRun.parameterDefinitions.collect { it.name })

        FakeSteps nextRun = new FakeSteps()
        nextRun.params.putAll([BUILD_AGENT: 'mac-m2-16g', PROJECTS: '', INLINE_PROJECTS: 'ios'])
        Map result = new V3Pipeline(nextRun, [configFiles: ['v3/parameters.json'], checkout: false]).run()

        assertEquals('SUCCESS', result.parameters.status)
        assertEquals('mac-m2-16g', nextRun.parameterDefinitions[0].defaultValue)
        assertEquals('', nextRun.parameterDefinitions[1].defaultValue)
        assertFalse(nextRun.events.contains('pwd'))
        assertEquals('/var/jenkins_home/project-list.yaml', nextRun.parameterDefinitions[1].uri)
        assertEquals('ios', nextRun.parameterDefinitions[2].defaultValue)
        assertEquals('FILE_PATH', nextRun.parameterDefinitions[1].protocol)
        assertEquals('HTTP_HTTPS', nextRun.parameterDefinitions[2].protocol)
        assertTrue(nextRun.parameterDefinitions[2].pipelineSubmitContent.toString().contains('checked'))
    }

    @Test
    void rejectsEmptyAndUnknownProjectSelectionsBeforeAllocatingAnAgent() {
        String config = 'resource:com/bluersw/jenkins/libraries/v3/acceptance/projects.json'

        FakeSteps empty = new FakeSteps()
        empty.params.PROJECT_SELECTION = ''
        try {
            new V3Pipeline(empty, [configFiles: [config], checkout: false]).run()
            fail('Expected empty project selection failure')
        } catch (V3ConfigException error) {
            assertTrue(error.message.contains('请至少选择一个项目'))
        }
        assertTrue(empty.events.every { !it.startsWith('node:') && it != 'checkout' && it != 'pwd' })

        FakeSteps unknown = new FakeSteps()
        unknown.params.PROJECT_SELECTION = 'unknown'
        try {
            new V3Pipeline(unknown, [configFiles: [config], checkout: false]).run()
            fail('Expected unknown project selection failure')
        } catch (V3ConfigException error) {
            assertTrue(error.message.contains('未知编号: unknown'))
        }
        assertTrue(unknown.events.every { !it.startsWith('node:') && it != 'checkout' && it != 'pwd' })
    }

    @Test
    void runsMultilanguageStepsCoverageAndAppleSigningCleanup() {
        FakeSteps steps = new FakeSteps()
        steps.stdoutByScriptContains["'xccov'"] = '''{"targets":[{"name":"SampleTests","files":[{"name":"App.swift","path":"Sources/App.swift","executableLines":10,"coveredLines":8}]}]}'''

        Map result = new V3Pipeline(steps, [configFiles: ['v3/multilanguage.json'], checkout: false]).run()

        assertEquals('SUCCESS', result.multilanguage.status)
        assertTrue(steps.commands.any { it.contains("'npm' 'ci'") })
        assertTrue(steps.commands.any { it.contains("'./gradlew' 'test'") })
        assertTrue(steps.shellInvocations.any { it.method == 'powershell' && it.arguments.script.startsWith("& 'MSBuild.exe'") })
        assertTrue(steps.commands.any { it.contains("'xcodebuild' '-workspace' 'Sample.xcworkspace'") && it.contains("'test'") })
        assertTrue(steps.commands.any { it.contains("'xcodebuild' '-exportArchive'") })
        assertTrue(steps.commands.any { it.contains('security create-keychain') })
        assertTrue(steps.commands.any { it.contains('security delete-keychain') })
        assertTrue(steps.writtenFiles['.jenkins-json-build/xcode-coverage.xml'].contains('line-rate="0.8"'))
        assertEquals(2, steps.coverageInvocations.size())
        assertEquals([[path: 'plugin/src']], steps.coverageInvocations[0].sourceDirectories)
        assertTrue(steps.commands.any { it == 'sonar-scanner -Dsonar.projectKey=sample' })
    }

    @Test
    void checksStaticAgentBeforeCheckoutAndRejectsWrongOperatingSystem() {
        FakeSteps mac = new FakeSteps()
        Map result = new V3Pipeline(mac, [configFiles: ['v3/static-requirements.json']]).run()

        assertEquals('SUCCESS', result['static-requirements'].status)
        assertTrue(mac.events.indexOf('requirements') < mac.events.indexOf('checkout'))

        FakeSteps linux = new FakeSteps()
        try {
            new V3Pipeline(linux, [configFiles: ['v3/windows-requirements.json']]).run()
            fail('Expected operating system validation failure')
        } catch (V3ConfigException error) {
            assertTrue(error.message.contains('不是 Windows'))
        }
        assertFalse(linux.events.contains('checkout'))
    }

    @Test
    void usesExplicitScmForCheckout() {
        FakeSteps steps = new FakeSteps()
        Map configuredScm = [url: 'https://example.test/project.git', branch: 'acceptance']

        Map result = new V3Pipeline(steps, [
            configFiles: ['v3/java-static.json'],
            scm: configuredScm
        ]).run()

        assertEquals('SUCCESS', result['java-test'].status)
        assertEquals([configuredScm], steps.checkoutInvocations)
    }

    @Test
    void cleansAppleSigningStateWhenNestedStepFails() {
        FakeSteps steps = new FakeSteps()
        steps.trustedFiles['generated.json'] = JsonOutput.toJson([
            schemaVersion: 3,
            project: [id: 'signing-failure'],
            agent: [type: 'none'],
            stages: [[id: 'archive', name: 'Archive', steps: [[
                type: 'appleSigning',
                certificateCredentialsId: 'ios-p12',
                certificatePasswordCredentialsId: 'ios-p12-password',
                provisioningProfileCredentialsIds: ['ios-profile'],
                steps: [[type: 'command', script: 'fail-signing']]
            ]]]]
        ])
        steps.failOnScriptContains = 'fail-signing'

        try {
            new V3Pipeline(steps, [configFiles: ['generated.json'], checkout: false]).run()
            fail('Expected signing failure')
        } catch (RuntimeException error) {
            assertTrue(error.message.contains('simulated command failure'))
        }

        assertTrue(steps.commands.any { it.contains('security create-keychain') })
        assertTrue(steps.commands.any { it.contains('security delete-keychain') })
        assertTrue(steps.commands.any { it.contains('rm -rf "$STATE"') })
    }

    @Test
    void loadsEveryBundledLanguageTemplate() {
        List<String> templates = [
            'node-npm-static', 'node-npm-kubernetes',
            'android-gradle-static', 'android-gradle-kubernetes',
            'react-native-android-static', 'react-native-android-kubernetes',
            'ios-xcode-static', 'react-native-ios-static', 'dotnet-framework-msbuild-windows'
        ]
        for (String template : templates) {
            FakeSteps steps = new FakeSteps()
            steps.stdoutByScriptContains["'xccov'"] = '''{"targets":[{"name":"Tests","files":[{"path":"App.swift","executableLines":1,"coveredLines":1}]}]}'''
            steps.trustedFiles['generated.json'] = JsonOutput.toJson([
                schemaVersion: 3,
                extends: template,
                project: [id: template],
                agent: [type: 'none']
            ])

            Map result = new V3Pipeline(steps, [configFiles: ['generated.json'], checkout: false]).run()
            assertEquals(template, 'SUCCESS', result[template].status)
        }
    }

    @Test
    void retriesNpmDependencyInstallationAfterTransientRegistryFailures() {
        FakeSteps steps = new FakeSteps()
        steps.trustedFiles['generated.json'] = JsonOutput.toJson([
            schemaVersion: 3,
            extends: 'node-npm-static',
            project: [id: 'node-install-retry'],
            agent: [type: 'none']
        ])

        Map result = new V3Pipeline(steps, [configFiles: ['generated.json'], checkout: false,
            onlyStages: ['install']]).run()

        assertEquals('SUCCESS', result['node-install-retry'].status)
        assertEquals([3], steps.retryCounts)
    }

    @Test
    void keepsReactNativeAndroidJestCoverageAfterTemplateMerge() {
        FakeSteps steps = new FakeSteps()
        steps.trustedFiles['generated.json'] = JsonOutput.toJson([
            schemaVersion: 3,
            extends: 'react-native-android-static',
            project: [id: 'react-native-android-coverage'],
            agent: [type: 'none']
        ])

        Map result = new V3Pipeline(steps, [configFiles: ['generated.json'], checkout: false,
            onlyStages: ['coverage']]).run()

        assertEquals('SUCCESS', result['react-native-android-coverage'].status)
        assertEquals(1, steps.coverageInvocations.size())
        assertEquals(1, steps.coverageInvocations[0].tools.size())
        assertEquals('LCOV', steps.coverageInvocations[0].tools[0].parser)
        assertEquals('coverage/lcov.info', steps.coverageInvocations[0].tools[0].pattern)
    }

    @Test
    void rendersSecurePinnedLanguagePods() {
        Map<String, List<String>> expectedContainers = [
            'node-npm-kubernetes': ['node'],
            'android-gradle-kubernetes': ['android'],
            'react-native-android-kubernetes': ['node', 'android']
        ]
        expectedContainers.each { String template, List<String> names ->
            FakeSteps steps = new FakeSteps()
            steps.trustedFiles['generated.json'] = JsonOutput.toJson([
                schemaVersion: 3,
                extends: template,
                project: [id: template]
            ])
            Map result = new V3Pipeline(steps, [configFiles: ['generated.json'], checkout: false,
                onlyStages: ['not-selected']]).run()

            assertEquals(template, 'SUCCESS', result[template].status)
            Map pod = new Yaml().load(steps.podYaml) as Map
            assertEquals(true, pod.spec.securityContext.runAsNonRoot)
            assertEquals(false, pod.spec.automountServiceAccountToken)
            Map<String, Map> containers = (pod.spec.containers as List).collectEntries { Map container ->
                [(container.name.toString()): container]
            }
            assertTrue(names.every { containers.containsKey(it) })
            assertTrue(containers.values().every { Map container ->
                container.image.toString().contains('@sha256:') &&
                    container.securityContext.runAsNonRoot == true &&
                    container.securityContext.allowPrivilegeEscalation == false &&
                    container.securityContext.capabilities.drop == ['ALL']
            })
            if (names.contains('android')) {
                Map androidEnvironment = (containers.android.env as List).collectEntries { Map entry ->
                    [(entry.name.toString()): entry.value.toString()]
                }
                assertTrue(androidEnvironment.GRADLE_OPTS.contains('/build-tools/36.0.0/aapt2'))
            }
            assertTrue(containers.values().every { Map container ->
                Map containerEnvironment = (container.env as List).collectEntries { Map entry ->
                    [(entry.name.toString()): entry.value.toString()]
                }
                containerEnvironment.HTTP_PROXY == '' && containerEnvironment.HTTPS_PROXY == '' &&
                    containerEnvironment.NO_PROXY == ''
            })
            Map<String, String> environment = steps.podTemplateArguments.envVars.collectEntries { Map entry ->
                [(entry.key.toString()): entry.value.toString()]
            }
            assertEquals('', environment.HTTP_PROXY)
            assertEquals('', environment.HTTPS_PROXY)
            assertEquals('', environment.NO_PROXY)
            assertFalse(steps.podYaml.contains('hostPath:'))
            assertFalse(steps.podYaml.contains('docker.sock'))
            assertFalse(steps.podYaml.contains('privileged: true'))
        }
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
    List<Map> coverageInvocations = []
    List<Map> httpInvocations = []
    List<Object> checkoutInvocations = []
    List<String> directories = []
    List<String> events = []
    List<String> messages = []
    Map<String, String> writtenFiles = [:]
    Map<String, String> stdoutByScriptContains = [:]
    Map<String, String> trustedFiles = [:]
    Map currentBuild = [result: null]
    Map podTemplateArguments = [:]
    String podYaml = ''
    String failOnScriptContains
    boolean unix = true
    boolean populateParamsOnProperties = false

    String libraryResource(String path) {
        return new File('../shared-library/resources', path).getText('UTF-8')
    }

    Object readJSON(Map arguments) {
        return new JsonSlurper().parseText(arguments.text.toString())
    }

    String readTrusted(String path) {
        if (trustedFiles.containsKey(path)) return trustedFiles[path]
        return new File('src/test/resources', path).getText('UTF-8')
    }

    void node(String label, Closure body) {
        events.add("node:${label}".toString())
        body.call()
    }
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
    Object dir(String path, Closure body) {
        directories.add(path)
        return body.call()
    }
    Object withEnv(List<String> values, Closure body) { body.call() }
    void podTemplate(Map arguments, Closure body) {
        podTemplateArguments = new LinkedHashMap(arguments)
        podYaml = arguments.yaml.toString()
        env.POD_LABEL = 'v3-test-pod'
        body.call()
    }
    String pwd() {
        events.add('pwd')
        return '/workspace'
    }
    boolean isUnix() { unix }
    void checkout(Object scm) {
        events.add('checkout')
        checkoutInvocations.add(scm)
    }
    void junit(Map arguments) { junitInvocations.add(new LinkedHashMap(arguments)) }
    void jacoco(Map arguments) { jacocoInvocations.add(new LinkedHashMap(arguments)) }
    void archiveArtifacts(Map arguments) { archiveInvocations.add(new LinkedHashMap(arguments)) }
    Object string(Map arguments) { new LinkedHashMap(arguments) }
    Object file(Map arguments) { new LinkedHashMap(arguments) }
    Object sshUserPrivateKey(Map arguments) { new LinkedHashMap(arguments) }
    Object booleanParam(Map arguments) { new LinkedHashMap(arguments) }
    Object choice(Map arguments) { new LinkedHashMap(arguments) }
    Object agentParameter(Map arguments) { new LinkedHashMap(arguments) }
    Object envVar(Map arguments) { new LinkedHashMap(arguments) }
    Object checkboxParameter(Map arguments) { new FakeCheckboxDefinition(arguments) }
    Object parameters(List arguments) {
        parameterDefinitions.addAll(arguments.collect { parameterDefinition(it) })
        return arguments
    }

    private static Map parameterDefinition(Object value) {
        if (value instanceof Map) return new LinkedHashMap(value as Map)
        Map result = [:]
        ['name', 'description', 'defaultValue', 'protocol', 'format', 'uri', 'displayNodePath',
            'valueNodePath', 'checkedNodePath', 'pipelineSubmitContent'].each { field ->
            try {
                Object configured = value."${field}"
                if (configured != null) result[field] = configured.toString()
            } catch (Throwable ignored) {
            }
        }
        return result
    }
    void properties(List arguments) {
        if (populateParamsOnProperties) {
            parameterDefinitions.each { definition ->
                if (!params.containsKey(definition.name)) {
                    params[definition.name] = definition.defaultValue ?: ''
                }
            }
        }
    }
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
    void recordCoverage(Map arguments) { coverageInvocations.add(new LinkedHashMap(arguments)) }
    Object httpRequest(Map arguments) {
        httpInvocations.add(new LinkedHashMap(arguments))
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
    void echo(String message) { messages.add(message) }

    Object sh(Map arguments) {
        shellInvocations.add([method: 'sh', arguments: new LinkedHashMap(arguments)])
        commands.add(arguments.script.toString())
        if (arguments.script.toString().contains('uname -s')) events.add('requirements')
        if (failOnScriptContains && arguments.script.toString().contains(failOnScriptContains)) {
            throw new RuntimeException('simulated command failure')
        }
        if (arguments.returnStatus) return 0
        if (arguments.returnStdout) {
            Map.Entry<String, String> configured = stdoutByScriptContains.find { entry ->
                arguments.script.toString().contains(entry.key)
            }
            return configured ? configured.value : 'output\n'
        }
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

class FakeCheckboxDefinition extends LinkedHashMap {
    FakeCheckboxDefinition(Map arguments) { super(arguments) }

    void setDefaultValue(String value) { put('defaultValue', value) }
}
