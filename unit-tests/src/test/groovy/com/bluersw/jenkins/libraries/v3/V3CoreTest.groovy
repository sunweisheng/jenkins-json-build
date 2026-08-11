package com.bluersw.jenkins.libraries.v3

import com.cloudbees.groovy.cps.Continuable
import com.cloudbees.groovy.cps.CpsTransformer
import com.cloudbees.groovy.cps.NonCPS
import org.codehaus.groovy.control.CompilerConfiguration
import org.junit.Test

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertNotNull
import static org.junit.Assert.assertTrue
import static org.junit.Assert.fail

class V3CoreTest {
    @Test
    void keepsBuildContextCopyOutsideCpsTransformation() {
        assertNotNull(BuildContext.getDeclaredMethod('copy', Map).getAnnotation(NonCPS))
    }

    @Test
    void constructsBuildContextFromCpsTransformedScript() {
        CompilerConfiguration configuration = new CompilerConfiguration()
        configuration.addCompilationCustomizers(new CpsTransformer())
        String source = new File('../shared-library/src/com/bluersw/jenkins/libraries/v3/BuildContext.groovy')
            .getText('UTF-8') + '''

def context = new BuildContext('api', 'ci/project.json', [BUILD_NUMBER: 2, BRANCH_NAME: 'main'])
return [
    environment: context.environment,
    variables: context.variables([STAGE_VALUE: 'stage'], [STEP_VALUE: 'step']),
    status: context.result.status
]
'''
        Script script = new GroovyShell(V3CoreTest.class.classLoader, new Binding(), configuration)
            .parse(source, 'BuildContextCpsTest.groovy')

        Map result = new Continuable(script).run(null) as Map

        assertEquals([BUILD_NUMBER: 2, BRANCH_NAME: 'main'], result.environment)
        assertEquals('stage', result.variables.STAGE_VALUE)
        assertEquals('step', result.variables.STEP_VALUE)
        assertEquals('PENDING', result.status)
    }

    @Test
    void resolvesVariablesInDocumentedOrderAndRejectsUnknownValues() {
        BuildContext context = new BuildContext('api', 'ci/project.json', [VALUE: 'env'], [VALUE: 'global'],
            [VALUE: 'runtime'], [VALUE: 'project'])
        assertEquals('step', context.variables([VALUE: 'stage'], [VALUE: 'step']).VALUE)

        VariableResolver resolver = new VariableResolver()
        assertEquals('project/result', resolver.resolve('${VALUE}/result', context.variables()))
        assertEquals([CACHE: 'registry.example/app:buildcache', IMAGE: 'registry.example/app'],
            resolver.resolveVariableMap([
                CACHE: '${IMAGE}:buildcache',
                IMAGE: 'registry.example/app'
            ], [IMAGE: 'environment.example/app'], 'variables'))
        try {
            resolver.resolve('${MISSING}', context.variables())
            fail('Expected unresolved variable failure')
        } catch (V3ConfigException error) {
            assertTrue(error.message.contains('MISSING'))
        }
    }

    @Test
    void evaluatesStructuredConditionsWithoutRunningCode() {
        ConditionEvaluator evaluator = new ConditionEvaluator()
        Map variables = [BRANCH: 'main', DEPLOY: true]
        assertTrue(evaluator.evaluate([all: [
            [variable: 'BRANCH', operator: 'in', value: ['main', 'release']],
            [variable: 'DEPLOY', operator: 'equals', value: true]
        ]], variables))
        assertFalse(evaluator.evaluate([variable: 'BRANCH', operator: 'matches', value: 'feature/.+'], variables))
    }

    @Test
    void mergesTemplateStagesById() {
        Map merged = new ConfigMerger().merge(
            [variables: [JAVA: 21], stages: [[id: 'test', name: 'Test', timeoutMinutes: 10, steps: []]]],
            [variables: [EXTRA: true], stages: [[id: 'test', timeoutMinutes: 20]]]
        )
        assertEquals(21, merged.variables.JAVA)
        assertEquals(true, merged.variables.EXTRA)
        assertEquals('Test', merged.stages[0].name)
        assertEquals(20, merged.stages[0].timeoutMinutes)
    }

    @Test
    void ordersMergedTemplateStagesExplicitly() {
        ConfigMerger merger = new ConfigMerger()
        Map merged = merger.merge(
            [stages: [[id: 'install'], [id: 'test'], [id: 'coverage']]],
            [stages: [[id: 'pods'], [id: 'test', name: 'Combined tests']],
                stageOrder: ['install', 'pods', 'test', 'coverage']]
        )

        Map ordered = merger.orderStages(merged)

        assertEquals(['install', 'pods', 'test', 'coverage'], ordered.stages.collect { it.id })
        assertEquals('Combined tests', ordered.stages[2].name)
    }

    @Test
    void validatesImageDigestAndPodSecurity() {
        String digest = 'sha256:' + ('a' * 64)
        assertEquals("ghcr.io/acme/app@${digest}".toString(), ImageReference.withDigest('ghcr.io/acme/app:42', digest))
        new PodSecurityValidator().validate('spec:\n  containers: []\n', ['privileged\\s*:\\s*true'])
        try {
            new PodSecurityValidator().validate('securityContext:\n  privileged: true\n', ['privileged\\s*:\\s*true'])
            fail('Expected unsafe Pod failure')
        } catch (V3ConfigException error) {
            assertTrue(error.message.contains('禁止'))
        }
    }

    @Test
    void convertsXcodeCoverageAndRejectsInvalidValues() {
        String xml = XcodeCoverageConverter.toCobertura([targets: [[name: 'AppTests', files: [[
            name: 'App.swift', path: 'Sources/App.swift', executableLines: 4, coveredLines: 3
        ]]]]])
        assertTrue(xml.contains('line-rate="0.75"'))
        assertTrue(xml.contains('filename="Sources/App.swift"'))

        try {
            XcodeCoverageConverter.toCobertura([targets: [[name: 'AppTests', files: [[
                path: 'Sources/App.swift', executableLines: 1, coveredLines: 2
            ]]]]])
            fail('Expected invalid coverage failure')
        } catch (V3ConfigException error) {
            assertTrue(error.message.contains('coveredLines'))
        }
    }

    @Test
    void keepsV3ExampleConfigPathsResolvableFromRepositoryRoot() {
        File repositoryRoot = new File('..').canonicalFile
        List<File> jenkinsfiles = []
        repositoryRoot.eachFileRecurse { file ->
            if (file.name == 'Jenkinsfile.v3') jenkinsfiles.add(file)
        }

        assertFalse(jenkinsfiles.isEmpty())
        jenkinsfiles.each { file ->
            def matcher = file.getText('UTF-8') =~ /configFiles:\s*\[\s*'([^']+)'\s*\]/
            assertTrue("${file} must declare one config file", matcher.find())
            assertTrue("${file} references a missing config file", new File(repositoryRoot, matcher.group(1)).isFile())
        }
    }
}
