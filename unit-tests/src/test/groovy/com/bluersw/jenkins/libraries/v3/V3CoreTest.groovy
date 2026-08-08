package com.bluersw.jenkins.libraries.v3

import org.junit.Test

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertTrue
import static org.junit.Assert.fail

class V3CoreTest {
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
}
