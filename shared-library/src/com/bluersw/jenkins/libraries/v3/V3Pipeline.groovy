package com.bluersw.jenkins.libraries.v3

import java.net.URI
import java.util.regex.Matcher
import java.util.regex.Pattern

class V3Pipeline implements Serializable {
    private static final String DEFAULTS_RESOURCE = 'com/bluersw/jenkins/libraries/v3/defaults.json'
    private static final Pattern VARIABLE = Pattern.compile(/\$\{([A-Za-z_][A-Za-z0-9_.-]*)\}/)

    private final def steps
    private final Map options
    private final VariableResolver resolver = new VariableResolver()
    private final ConditionEvaluator conditionEvaluator = new ConditionEvaluator()
    private final ConfigMerger merger = new ConfigMerger()
    private Map defaults
    private Map customHandlers
    private Map<String, Map> buildResults = new LinkedHashMap<String, Map>()

    V3Pipeline(def steps, Map options = [:]) {
        this.steps = steps
        this.options = new LinkedHashMap(options ?: [:])
    }

    Map run() {
        defaults = parseJson(steps.libraryResource(DEFAULTS_RESOURCE), DEFAULTS_RESOURCE)
        customHandlers = new LinkedHashMap(options.stepHandlers ?: [:])
        List<String> configFiles = stringList(options.configFiles)
        if (configFiles.isEmpty()) {
            throw new V3ConfigException('jenkinsJsonBuild.configFiles 不能为空')
        }

        List<Map> roots = [loadMergedConfig(configFiles)]
        List<Map> plans = expandProjects(roots)
        validateUniqueProjectIds(plans)
        applyParameters(plans.collect { it.config as Map })
        executePlans(plans, executionPolicy(roots))
        return new LinkedHashMap<String, Map>(buildResults)
    }

    private Map loadMergedConfig(List configFiles, Collection trustedHosts = null) {
        Map merged = [:]
        String primarySource = configFiles[0].toString()
        for (Object path : configFiles) {
            Map current = loadConfigWithTemplate(path.toString(), trustedHosts ?: trustedHostsFromOptions())
            merged = merger.merge(merged, current)
        }
        Set<String> supported = new LinkedHashSet<String>((defaults.stepHandlers as Map).keySet().collect { it.toString() })
        supported.addAll(customHandlers.keySet().collect { it.toString() })
        new ConfigValidator(supported).validate(merged, primarySource)
        merged._configPath = primarySource
        return merged
    }

    private Map loadConfigWithTemplate(String path, Collection trustedHosts) {
        Map config = parseJson(readSource(path, trustedHosts), path)
        List<String> templateNames = stringList(config.extends)
        Map base = [:]
        for (String templateName : templateNames) {
            String resource = (defaults.templates as Map)[templateName]?.toString()
            if (!resource) {
                throw new V3ConfigException("${path} 引用了未知模板 ${templateName}")
            }
            base = merger.merge(base, parseJson(steps.libraryResource(resource), resource))
        }
        Map override = new LinkedHashMap(config)
        override.remove('extends')
        return merger.merge(base, override)
    }

    private List<Map> expandProjects(List<Map> roots) {
        List<Map> plans = []
        for (Map root : roots) {
            Map projects = root.projects instanceof Map ? new LinkedHashMap(root.projects as Map) : [:]
            List<Map> items = []
            for (Object item : projects.items ?: []) {
                items.add(new LinkedHashMap(item as Map))
            }
            if (projects.manifest) {
                items.addAll(loadProjectManifest(projects.manifest as Map, projects))
            }
            if (items.isEmpty()) {
                plans.add([id: projectId(root), config: root, variables: [:]])
                continue
            }

            List<String> selected = selectedProjectIds(projects, items)
            Map common = new LinkedHashMap(root)
            common.remove('projects')
            for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
                Map item = items[itemIndex]
                validateProjectItem(item, "projects.items[${itemIndex}]")
                String id = item.id.toString()
                if (!selected.contains(id)) {
                    continue
                }
                List hosts = trustedHostsFromOptions() + stringList(projects.trustedHosts)
                Map projectConfig = loadMergedConfig(item.configFiles as List, hosts)
                Map merged = merger.merge(common, projectConfig)
                if (item.agent instanceof Map) {
                    merged.agent = merger.merge(merged.agent as Map ?: [:], item.agent as Map)
                }
                merged._configPath = (item.configFiles as List)[0].toString()
                plans.add([id: id, config: merged, variables: new LinkedHashMap(item.variables ?: [:])])
            }
        }
        if (plans.isEmpty()) {
            throw new V3ConfigException('项目选择结果为空')
        }
        return plans
    }

    private List<Map> loadProjectManifest(Map manifest, Map projects) {
        String source = manifest.source?.toString() ?: 'file'
        String location = manifest.path?.toString() ?: manifest.url?.toString() ?: manifest.resource?.toString()
        if (!location) {
            throw new V3ConfigException('projects.manifest 缺少 path、url 或 resource')
        }
        String sourceLocation
        switch (source) {
            case 'resource':
                sourceLocation = "resource:${location}"
                break
            case 'https':
                sourceLocation = location
                break
            case 'file':
                sourceLocation = location
                break
            default:
                throw new V3ConfigException("不支持的项目清单来源 ${source}")
        }
        List trustedHosts = []
        trustedHosts.addAll(trustedHostsFromOptions())
        trustedHosts.addAll(stringList(projects.trustedHosts))
        Object parsed = parseJsonValue(readSource(sourceLocation, trustedHosts), sourceLocation)
        Object values = parsed instanceof Map ? parsed.items : parsed
        if (!(values instanceof List)) {
            throw new V3ConfigException('项目清单必须是数组或包含 items 数组的对象')
        }
        return (values as List).collect { value ->
            if (!(value instanceof Map)) {
                throw new V3ConfigException('项目清单中的项目必须是对象')
            }
            return new LinkedHashMap(value as Map)
        }
    }

    private List<String> selectedProjectIds(Map projects, List<Map> items) {
        List<String> selected = stringList(options.projectIds ?: options.projects)
        String parameterName = projects.selectionParameter?.toString() ?: defaults.projects.selectionParameter.toString()
        if (selected.isEmpty()) {
            Object parameterValue = parameterValue(parameterName)
            selected = stringList(parameterValue)
        }
        List<String> available = items.collect { it.id?.toString() }
        if (available.any { !it }) {
            throw new V3ConfigException('项目清单中的 id 不能为空')
        }
        if (selected.isEmpty()) {
            return available
        }
        List<String> unknown = selected.findAll { !available.contains(it) }
        if (!unknown.isEmpty()) {
            throw new V3ConfigException("只能选择项目清单中的编号，未知编号: ${unknown.join(', ')}")
        }
        return selected.unique()
    }

    private Map executionPolicy(List<Map> roots) {
        Map configured = roots.find { it.projects instanceof Map }?.projects as Map ?: [:]
        String execution = options.execution?.toString() ?: configured.execution?.toString() ?: defaults.projects.execution.toString()
        int maxParallel = integerValue(options.maxParallel ?: configured.maxParallel ?: defaults.projects.maxParallel, 'maxParallel', 1)
        if (!['sequential', 'parallel'].contains(execution)) {
            throw new V3ConfigException('projects.execution 只能是 sequential 或 parallel')
        }
        return [execution: execution, maxParallel: maxParallel]
    }

    private void executePlans(List<Map> plans, Map policy) {
        if (policy.execution == 'sequential' || plans.size() == 1) {
            for (Map plan : plans) {
                executeProject(plan)
            }
            return
        }
        int limit = policy.maxParallel as int
        for (int offset = 0; offset < plans.size(); offset += limit) {
            List<Map> batch = plans.subList(offset, Math.min(offset + limit, plans.size()))
            Map<String, Closure> branches = new LinkedHashMap<String, Closure>()
            for (Map sourcePlan : batch) {
                Map plan = sourcePlan
                branches[plan.id.toString()] = { executeProject(plan) }
            }
            steps.parallel(branches)
        }
    }

    private void executeProject(Map plan) {
        Map config = plan.config as Map
        Map environment = collectEnvironment(config)
        Map globalVariables = resolver.resolveVariableMap(config.variables as Map ?: [:], environment, "${plan.id}.variables")
        BuildContext context = new BuildContext(plan.id.toString(), config._configPath.toString(), environment,
            globalVariables, [:], plan.variables as Map ?: [:])
        buildResults[context.projectId] = context.result

        Map agent = config.agent instanceof Map ? config.agent as Map : defaults.agent as Map
        withAgent(agent, config, context) {
            initializeWorkspaceVariables(context)
            resolveRuntimeVariables(config.runtimeVariables, context, config)
            Throwable failure = null
            String outcome = 'success'
            try {
                runStages(config, context)
                context.result.status = 'SUCCESS'
                runPost(config, 'success', context)
                invokeCallback('success', context, null)
            } catch (Throwable error) {
                failure = error
                outcome = isCancelled(error) ? 'cancelled' : 'failure'
                context.result.status = outcome == 'cancelled' ? 'CANCELLED' : 'FAILURE'
                context.result.error = error.message ?: error.class.name
                runPost(config, outcome, context)
                invokeCallback(outcome, context, error)
            } finally {
                runPost(config, 'always', context)
                invokeCallback('always', context, failure)
            }
            if (failure != null) {
                throw failure
            }
        }
    }

    private void runStages(Map config, BuildContext context) {
        Set<String> onlyStages = new LinkedHashSet<String>(stringList(options.onlyStages ?: options.stages))
        for (Map configuredStage : config.stages as List) {
            Map stage = new LinkedHashMap(configuredStage)
            String id = stage.id.toString()
            String name = stage.name.toString()
            if (!onlyStages.isEmpty() && !onlyStages.contains(id) && !onlyStages.contains(name)) {
                context.result.stages[id] = [status: 'NOT_SELECTED']
                continue
            }
            Map inherited = context.variables()
            Map stageVariables = resolver.resolveVariableMap(stage.variables as Map ?: [:], inherited, "${context.projectId}.${id}.variables")
            boolean enabled = conditionEvaluator.evaluate(stage.condition, context.variables(stageVariables))
            if (!enabled) {
                context.result.stages[id] = [status: 'SKIPPED']
                continue
            }
            steps.stage(name) {
                context.result.stages[id] = [status: 'RUNNING']
                try {
                    Closure work = { runStageBody(stage, context, stageVariables) }
                    if (stage.agent instanceof Map) {
                        withAgent(stage.agent as Map, config, context, work)
                    } else {
                        work.call()
                    }
                    context.result.stages[id].status = 'SUCCESS'
                } catch (Throwable error) {
                    context.result.stages[id].status = isCancelled(error) ? 'CANCELLED' : 'FAILURE'
                    context.result.stages[id].error = error.message ?: error.class.name
                    throw error
                }
            }
        }
    }

    private void runStageBody(Map stage, BuildContext context, Map stageVariables) {
        Closure action = { executeSteps(stage.steps as List, context, stageVariables) }
        int retries = integerValue(stage.containsKey('retries') ? stage.retries : defaults.stage.retries, 'stage.retries', 0)
        if (retries > 0) {
            Closure attempted = action
            action = { steps.retry(retries + 1) { attempted.call() } }
        }
        int timeoutMinutes = integerValue(stage.containsKey('timeoutMinutes') ? stage.timeoutMinutes : defaults.stage.timeoutMinutes,
            'stage.timeoutMinutes', 1)
        Closure timed = action
        action = { steps.timeout(time: timeoutMinutes, unit: 'MINUTES') { timed.call() } }
        String containerName = stage.container?.toString()
        if (containerName) {
            Closure contained = action
            action = { steps.container(containerName) { contained.call() } }
        }
        action.call()
    }

    private void executeSteps(List configuredSteps, BuildContext context, Map stageVariables) {
        for (Map rawStep : configuredSteps) {
            Map inherited = context.variables(stageVariables)
            Map stepVariables = resolver.resolveVariableMap(rawStep.variables as Map ?: [:], inherited,
                "${context.projectId}.${rawStep.id ?: rawStep.type}.variables")
            Map variables = context.variables(stageVariables, stepVariables)
            Map currentStep = new LinkedHashMap(rawStep)
            Object nestedSteps = currentStep.remove('steps')
            Map stepConfig = resolver.resolve(currentStep, variables, "${context.projectId}.${rawStep.id ?: rawStep.type}") as Map
            if (nestedSteps != null) {
                stepConfig.steps = nestedSteps
            }
            Closure action = { dispatchStep(context, stepConfig, stageVariables) }
            if (stepConfig.container) {
                Closure contained = action
                action = { steps.container(stepConfig.container.toString()) { contained.call() } }
            }
            action.call()
        }
    }

    private void dispatchStep(BuildContext context, Map config, Map stageVariables) {
        String type = config.type.toString()
        if (customHandlers.containsKey(type)) {
            Closure handler = customHandlers[type] as Closure
            handler.call(context, config)
            return
        }
        String methodName = (defaults.stepHandlers as Map)[type]?.toString()
        if (!methodName) {
            throw new V3ConfigException("未注册步骤 ${type}")
        }
        this."${methodName}"(context, config, stageVariables)
    }

    private void runCommandStep(BuildContext context, Map config, Map stageVariables) {
        String scriptText = required(config, 'script')
        String shell = config.shell?.toString() ?: defaults.command.shell.toString()
        if (!(defaults.supportedShells as List).contains(shell)) {
            throw new V3ConfigException("command.shell 不支持 ${shell}")
        }
        boolean returnStdout = booleanValue(config.returnStdout, false)
        List<Integer> expectedCodes = integerList(config.expectedExitCodes)
        if (returnStdout && !expectedCodes.isEmpty()) {
            throw new V3ConfigException('command 不能同时设置 returnStdout 和 expectedExitCodes')
        }

        Closure command = {
            Object result
            switch (shell) {
                case 'bash':
                    result = invokeShell('sh', [script: "#!/usr/bin/env bash\nset -e\n${scriptText}", returnStdout: returnStdout], expectedCodes)
                    break
                case 'sh':
                    result = invokeShell('sh', [script: scriptText, returnStdout: returnStdout], expectedCodes)
                    break
                case 'bat':
                    result = invokeShell('bat', [script: scriptText, returnStdout: returnStdout], expectedCodes)
                    break
                case 'powershell':
                    result = invokeShell('powershell', [script: scriptText, returnStdout: returnStdout], expectedCodes)
                    break
                case 'pwsh':
                    result = invokeShell('pwsh', [script: scriptText, returnStdout: returnStdout], expectedCodes)
                    break
            }
            if (config.outputVariable) {
                context.setRuntimeVariable(config.outputVariable.toString(), result instanceof String ? result.trim() : result)
            }
            return result
        }
        runWithEnvironmentAndDirectory(config, command)
    }

    private Object invokeShell(String methodName, Map arguments, List<Integer> expectedCodes) {
        Map invocation = new LinkedHashMap(arguments)
        invocation.remove('returnStdout')
        if (!expectedCodes.isEmpty()) {
            invocation.returnStatus = true
            int status = steps."${methodName}"(invocation) as int
            if (!expectedCodes.contains(status)) {
                throw new V3ConfigException("命令退出码 ${status} 不在允许范围 ${expectedCodes}")
            }
            return status
        }
        if (arguments.returnStdout) {
            invocation.returnStdout = true
        }
        return steps."${methodName}"(invocation)
    }

    private void runMavenStep(BuildContext context, Map config, Map stageVariables) {
        List<String> goals = stringList(config.goals)
        if (goals.isEmpty()) {
            throw new V3ConfigException('maven.goals 不能为空')
        }
        List<String> command = []
        command.add(config.executable?.toString() ?: defaults.maven.executable.toString())
        command.addAll(goals)
        command.addAll(stringList(config.arguments))

        Closure runMaven = { String settingsPath ->
            List<String> effective = new ArrayList<String>(command)
            if (settingsPath) {
                effective.add(1, settingsPath)
                effective.add(1, '-s')
            }
            runCommandStep(context, [type: 'command', shell: config.shell ?: 'sh',
                script: effective.collect { ShellEscaper.posix(it) }.join(' '), workDir: config.workDir], stageVariables)
        }

        Object settings = config.settings
        if (settings instanceof Map && settings.configFileId) {
            String variableName = defaults.maven.settingsVariable.toString()
            def provider = steps.configFile(fileId: settings.configFileId.toString(), variable: variableName)
            steps.configFileProvider([provider]) { runMaven.call(environmentValue(variableName)?.toString()) }
        } else {
            String settingsPath = settings instanceof CharSequence ? settings.toString() : (settings instanceof Map ? settings.path?.toString() : null)
            runMaven.call(settingsPath)
        }
        verifyArtifacts(config.artifacts)
    }

    private void runJunitStep(BuildContext context, Map config, Map stageVariables) {
        steps.junit(testResults: required(config, 'testResults'), allowEmptyResults: booleanValue(config.allowEmptyResults, false),
            skipPublishingChecks: booleanValue(config.skipPublishingChecks, false))
    }

    private void runJacocoStep(BuildContext context, Map config, Map stageVariables) {
        Map arguments = copyWithoutControlKeys(config)
        steps.jacoco(arguments)
    }

    private void runSonarQubeStep(BuildContext context, Map config, Map stageVariables) {
        Closure scan = {
            Map mavenConfig = [type: 'maven', goals: config.goals ?: ['sonar:sonar'], arguments: config.arguments ?: [],
                executable: config.executable, workDir: config.workDir, settings: config.settings]
            runMavenStep(context, mavenConfig, stageVariables)
        }
        if (config.installation) {
            steps.withSonarQubeEnv(config.installation.toString()) { scan.call() }
        } else {
            scan.call()
        }
        if (booleanValue(config.qualityGate, false)) {
            int minutes = integerValue(config.qualityGateTimeoutMinutes ?: defaults.sonarqube.qualityGateTimeoutMinutes,
                'sonarqube.qualityGateTimeoutMinutes', 1)
            steps.timeout(time: minutes, unit: 'MINUTES') {
                steps.waitForQualityGate(abortPipeline: booleanValue(config.abortPipeline, true))
            }
        }
    }

    private void runArchiveStep(BuildContext context, Map config, Map stageVariables) {
        steps.archiveArtifacts(artifacts: required(config, 'artifacts'), allowEmptyArchive: booleanValue(config.allowEmptyArchive, false),
            fingerprint: booleanValue(config.fingerprint, true), onlyIfSuccessful: booleanValue(config.onlyIfSuccessful, false))
    }

    private void runCredentialsStep(BuildContext context, Map config, Map stageVariables) {
        List bindings = []
        for (Map binding : config.bindings ?: []) {
            String kind = required(binding, 'kind')
            String id = required(binding, 'credentialsId')
            switch (kind) {
                case 'usernamePassword':
                    bindings.add(steps.usernamePassword(credentialsId: id, usernameVariable: required(binding, 'usernameVariable'),
                        passwordVariable: required(binding, 'passwordVariable')))
                    break
                case 'string':
                    bindings.add(steps.string(credentialsId: id, variable: required(binding, 'variable')))
                    break
                case 'file':
                    bindings.add(steps.file(credentialsId: id, variable: required(binding, 'variable')))
                    break
                case 'sshUserPrivateKey':
                    bindings.add(steps.sshUserPrivateKey(credentialsId: id, keyFileVariable: required(binding, 'keyFileVariable'),
                        usernameVariable: binding.usernameVariable?.toString() ?: '', passphraseVariable: binding.passphraseVariable?.toString() ?: ''))
                    break
                default:
                    throw new V3ConfigException("credentials 不支持绑定类型 ${kind}")
            }
        }
        if (bindings.isEmpty()) {
            throw new V3ConfigException('credentials.bindings 不能为空')
        }
        steps.withCredentials(bindings) { executeSteps(config.steps as List, context, stageVariables) }
    }

    private void runConditionStep(BuildContext context, Map config, Map stageVariables) {
        if (conditionEvaluator.evaluate(config.when ?: config.condition, context.variables(stageVariables))) {
            executeSteps(config.steps as List, context, stageVariables)
        }
    }

    private void runRetryStep(BuildContext context, Map config, Map stageVariables) {
        int count = integerValue(config.count, 'retry.count', 1)
        steps.retry(count) { executeSteps(config.steps as List, context, stageVariables) }
    }

    private void runTimeoutStep(BuildContext context, Map config, Map stageVariables) {
        int time = integerValue(config.time, 'timeout.time', 1)
        String unit = config.unit?.toString() ?: 'MINUTES'
        steps.timeout(time: time, unit: unit) { executeSteps(config.steps as List, context, stageVariables) }
    }

    private void runSetVariableStep(BuildContext context, Map config, Map stageVariables) {
        context.setRuntimeVariable(required(config, 'name'), config.value)
    }

    private void runContainerImageStep(BuildContext context, Map config, Map stageVariables) {
        String builder = config.builder?.toString() ?: defaults.containerImage.builder.toString()
        if (builder != 'kaniko') {
            throw new V3ConfigException("containerImage.builder 尚未支持 ${builder}")
        }
        List<String> destinations = stringList(config.destinations ?: config.destination)
        if (destinations.isEmpty()) {
            throw new V3ConfigException('containerImage.destinations 不能为空')
        }
        String digestFile = config.digestFile?.toString() ?: defaults.containerImage.digestFile.toString()
        if (digestFile.contains('/')) {
            String digestDirectory = digestFile.substring(0, digestFile.lastIndexOf('/'))
            runCommandStep(context, [type: 'command', shell: 'sh', script: "mkdir -p ${ShellEscaper.posix(digestDirectory)}",
                workDir: config.workDir], stageVariables)
        }
        List<String> command = [config.executor?.toString() ?: defaults.containerImage.executor.toString(),
            '--context', config.context?.toString() ?: '.', '--dockerfile', required(config, 'dockerfile'), '--digest-file', digestFile]
        for (String destination : destinations) {
            command.addAll(['--destination', destination])
        }
        if (booleanValue(config.cache, false)) {
            command.add('--cache=true')
        }
        (config.buildArgs as Map ?: [:]).each { key, value -> command.add("--build-arg=${key}=${value}") }
        command.addAll(stringList(config.arguments))
        runCommandStep(context, [type: 'command', shell: 'sh', script: command.collect { ShellEscaper.posix(it) }.join(' '),
            workDir: config.workDir], stageVariables)

        String digest = ImageReference.requireDigest(steps.readFile(file: digestFile).toString())
        String reference = ImageReference.withDigest(destinations[0], digest)
        context.setRuntimeVariable(config.digestVariable?.toString() ?: defaults.containerImage.digestVariable.toString(), digest)
        context.setRuntimeVariable(config.referenceVariable?.toString() ?: defaults.containerImage.referenceVariable.toString(), reference)
        context.outputs.image = [digest: digest, reference: reference, destinations: destinations]
    }

    private void runHelmStep(BuildContext context, Map config, Map stageVariables) {
        String action = required(config, 'action')
        if (!(defaults.helm.actions as List).contains(action)) {
            throw new V3ConfigException("helm.action 不支持 ${action}")
        }
        String executable = config.executable?.toString() ?: defaults.helm.executable.toString()
        List<String> command = [executable, action]
        String release = config.release?.toString()
        String chart = config.chart?.toString()
        switch (action) {
            case 'lint':
                command.add(required(config, 'chart'))
                break
            case 'template':
                if (release) command.add(release)
                command.add(required(config, 'chart'))
                break
            case 'upgrade':
                command.addAll(['--install', required(config, 'release'), required(config, 'chart')])
                break
            case 'status':
                command.add(required(config, 'release'))
                break
            case 'rollback':
                command.add(required(config, 'release'))
                if (config.revision != null) command.add(config.revision.toString())
                break
        }
        addHelmOptions(command, config)
        Closure execute = {
            runCommandStep(context, [type: 'command', shell: config.shell ?: 'sh',
                script: command.collect { ShellEscaper.posix(it) }.join(' '), workDir: config.workDir], stageVariables)
        }
        if (action == 'upgrade' && booleanValue(config.rollbackOnFailure, false)) {
            try {
                execute.call()
            } catch (Throwable error) {
                Map rollback = [type: 'helm', action: 'rollback', release: release, namespace: config.namespace,
                    wait: true, timeout: config.timeout, revision: config.rollbackRevision, workDir: config.workDir]
                try {
                    runHelmStep(context, rollback, stageVariables)
                } catch (Throwable rollbackError) {
                    steps.echo("Helm 自动回退失败: ${rollbackError.message}")
                }
                throw error
            }
        } else {
            execute.call()
        }
    }

    private void addHelmOptions(List<String> command, Map config) {
        if (config.namespace) command.addAll(['--namespace', config.namespace.toString()])
        if (booleanValue(config.createNamespace, false)) command.add('--create-namespace')
        for (String valuesFile : stringList(config.valuesFiles)) command.addAll(['--values', valuesFile])
        (config.setValues as Map ?: [:]).each { key, value -> command.addAll(['--set-string', "${key}=${value}"]) }
        if (booleanValue(config.atomic, false)) command.add('--atomic')
        if (booleanValue(config.wait, false)) command.add('--wait')
        if (config.timeout) command.addAll(['--timeout', config.timeout.toString()])
        command.addAll(stringList(config.arguments))
    }

    private void runPost(Map config, String event, BuildContext context) {
        Object configured = config.post instanceof Map ? (config.post as Map)[event] : null
        if (configured instanceof List && !configured.isEmpty()) {
            executeSteps(configured as List, context, [:])
        }
    }

    private void invokeCallback(String event, BuildContext context, Throwable error) {
        Map callbacks = options.callbacks instanceof Map ? options.callbacks as Map : [:]
        Closure callback = callbacks[event] as Closure
        if (callback == null) {
            String optionName = [success: 'onSuccess', failure: 'onFailure', cancelled: 'onCancelled', always: 'onAlways'][event]
            callback = options[optionName] as Closure
        }
        if (callback != null) {
            callback.call(context, error)
        }
    }

    private void withAgent(Map configuredAgent, Map config, BuildContext context, Closure body) {
        Map agent = resolver.resolve(merger.merge(defaults.agent as Map, configuredAgent ?: [:]), context.variables(), 'agent') as Map
        String type = agent.type?.toString() ?: 'static'
        if (type == 'none') {
            body.call()
            return
        }
        if (type == 'static') {
            steps.node(agent.label?.toString() ?: '') {
                checkoutSource(config)
                body.call()
            }
            return
        }
        if (type != 'kubernetes') {
            throw new V3ConfigException("不支持的 Agent 类型 ${type}")
        }
        String yaml = kubernetesYaml(agent, context)
        new PodSecurityValidator().validate(yaml, defaults.podSecurity.forbiddenPatterns as List)
        Map arguments = [yaml: yaml, showRawYaml: false]
        if (agent.cloud) arguments.cloud = agent.cloud.toString()
        if (agent.inheritFrom) arguments.inheritFrom = agent.inheritFrom.toString()
        if (agent.serviceAccount) arguments.serviceAccount = agent.serviceAccount.toString()
        steps.podTemplate(arguments) {
            steps.node(environmentValue('POD_LABEL')?.toString() ?: '') {
                checkoutSource(config)
                body.call()
            }
        }
    }

    private String kubernetesYaml(Map agent, BuildContext context) {
        String yaml
        if (agent.yaml) {
            yaml = agent.yaml.toString()
        } else if (agent.yamlFile) {
            yaml = readSource(agent.yamlFile.toString(), trustedHostsFromOptions())
        } else if (agent.podTemplate) {
            String resource = (defaults.podTemplates as Map)[agent.podTemplate.toString()]?.toString()
            if (!resource) throw new V3ConfigException("未知 Pod 模板 ${agent.podTemplate}")
            yaml = steps.libraryResource(resource)
        } else {
            throw new V3ConfigException('Kubernetes Agent 需要 yaml、yamlFile 或 podTemplate')
        }
        return resolver.resolve(yaml, context.variables(), 'agent.podYaml').toString()
    }

    private void checkoutSource(Map config) {
        if (booleanValue(options.containsKey('checkout') ? options.checkout : config.checkout, true)) {
            steps.checkout(steps.scm)
        }
    }

    private void initializeWorkspaceVariables(BuildContext context) {
        String workspace = steps.pwd().toString()
        String path = context.configPath.replace('\\', '/')
        String parent = path.contains('/') ? path.substring(0, path.lastIndexOf('/')) : '.'
        context.globalVariables.WORKSPACE = workspace
        context.globalVariables.PROJECT_PATH = parent == '.' ? workspace : "${workspace}/${parent}".replaceAll('/+', '/')
        context.globalVariables.PROJECT_DIR = parent == '.' ? '' : parent.tokenize('/')[0]
    }

    private void resolveRuntimeVariables(Object configured, BuildContext context, Map config) {
        List<Map> definitions = normalizeRuntimeVariables(configured)
        for (Map definition : definitions) {
            String name = required(definition, 'name')
            String source = required(definition, 'source')
            Map values = context.variables()
            Object value
            switch (source) {
                case 'env':
                    value = environmentValue(definition.key?.toString() ?: name)
                    break
                case 'parameter':
                    value = parameterValue(definition.key?.toString() ?: name)
                    break
                case 'command':
                    Map command = resolver.resolve(definition, values, "runtimeVariables.${name}") as Map
                    Map invocation = [type: 'command', shell: command.shell ?: 'sh', script: required(command, 'script'),
                        workDir: command.workDir, returnStdout: true, outputVariable: name]
                    runCommandStep(context, invocation, [:])
                    value = context.runtimeVariables[name]
                    break
                case 'file':
                    String path = resolver.resolve(required(definition, 'path'), values, "runtimeVariables.${name}.path").toString()
                    value = steps.readFile(file: path)
                    break
                case 'http':
                    String url = resolver.resolve(required(definition, 'url'), values, "runtimeVariables.${name}.url").toString()
                    List hosts = trustedHostsFromOptions() + stringList(config.security instanceof Map ? config.security.trustedHttpHosts : null)
                    requireTrustedHttps(url, hosts)
                    def response = steps.httpRequest(url: url, httpMode: 'GET', validResponseCodes: '200', quiet: true,
                        consoleLogResponseBody: false)
                    value = response.content
                    break
                case 'json':
                    if (definition.containsKey('fromVariable')) {
                        String variableName = definition.fromVariable.toString()
                        if (!values.containsKey(variableName)) throw new V3ConfigException("runtimeVariables.${name} 引用了未知变量 ${variableName}")
                        value = values[variableName]
                    } else {
                        value = definition.value
                    }
                    break
                default:
                    throw new V3ConfigException("runtimeVariables.${name} 不支持来源 ${source}")
            }
            if (value == null) {
                throw new V3ConfigException("runtimeVariables.${name} 未获取到值")
            }
            if (definition.jsonPath) value = jsonPath(value, definition.jsonPath)
            if (booleanValue(definition.trim, value instanceof CharSequence)) value = value.toString().trim()
            context.setRuntimeVariable(name, value)
        }
    }

    private List<Map> normalizeRuntimeVariables(Object configured) {
        if (configured == null) return []
        if (configured instanceof List) return (configured as List).collect { new LinkedHashMap(it as Map) }
        if (configured instanceof Map) {
            return (configured as Map).collect { key, value ->
                Map definition = value instanceof Map ? new LinkedHashMap(value as Map) : [source: 'json', value: value]
                definition.name = key.toString()
                return definition
            }
        }
        throw new V3ConfigException('runtimeVariables 必须是数组或对象')
    }

    private Object jsonPath(Object value, Object configuredPath) {
        Object current = value instanceof CharSequence ? parseJsonValue(value.toString(), 'runtimeVariables.json') : value
        List path = configuredPath instanceof List ? configuredPath as List : configuredPath.toString().tokenize('.')
        for (Object segment : path) {
            if (current instanceof Map && (current as Map).containsKey(segment.toString())) {
                current = (current as Map)[segment.toString()]
            } else if (current instanceof List && segment.toString().isInteger()) {
                current = (current as List)[segment.toString().toInteger()]
            } else {
                throw new V3ConfigException("JSON 节点不存在: ${path.join('.')}")
            }
        }
        return current
    }

    private void applyParameters(List<Map> configs) {
        Map<String, Map> definitions = new LinkedHashMap<String, Map>()
        for (Map config : configs) {
            for (Map parameter : config.parameters ?: []) {
                definitions[required(parameter, 'name')] = parameter
            }
        }
        if (definitions.isEmpty()) return
        List jenkinsParameters = []
        for (Map parameter : definitions.values()) {
            String type = parameter.type?.toString() ?: 'string'
            switch (type) {
                case 'string':
                    jenkinsParameters.add(steps.string(name: parameter.name.toString(), defaultValue: parameter.defaultValue?.toString() ?: '',
                        description: parameter.description?.toString() ?: '', trim: booleanValue(parameter.trim, true)))
                    break
                case 'boolean':
                    jenkinsParameters.add(steps.booleanParam(name: parameter.name.toString(), defaultValue: booleanValue(parameter.defaultValue, false),
                        description: parameter.description?.toString() ?: ''))
                    break
                case 'choice':
                    jenkinsParameters.add(steps.choice(name: parameter.name.toString(), choices: stringList(parameter.choices),
                        description: parameter.description?.toString() ?: ''))
                    break
                case 'multiChoice':
                    if (parameter.provider?.toString() == 'customCheckbox') {
                        jenkinsParameters.add(steps.checkboxParameter(name: parameter.name.toString(),
                            format: parameter.format?.toString() ?: 'JSON', uri: required(parameter, 'uri')))
                    } else {
                        jenkinsParameters.add(steps.string(name: parameter.name.toString(),
                            defaultValue: stringList(parameter.defaultValue).join(','),
                            description: parameter.description?.toString() ?: '', trim: true))
                    }
                    break
                default:
                    throw new V3ConfigException("不支持的参数类型 ${type}")
            }
        }
        steps.properties([steps.parameters(jenkinsParameters)])
    }

    private Map collectEnvironment(Map config) {
        Set<String> names = new LinkedHashSet<String>()
        collectVariableNames(config, names)
        Map values = new LinkedHashMap()
        for (String name : names) {
            Object value = environmentValue(name)
            if (value != null) values[name] = value
            Object parameter = parameterValue(name)
            if (parameter != null) values[name] = parameter
        }
        return values
    }

    private static void collectVariableNames(Object value, Set<String> names) {
        if (value instanceof Map) {
            (value as Map).each { key, child ->
                if (key == 'variable' && child instanceof CharSequence) names.add(child.toString())
                collectVariableNames(child, names)
            }
        } else if (value instanceof Collection) {
            (value as Collection).each { collectVariableNames(it, names) }
        } else if (value instanceof CharSequence) {
            Matcher matcher = VARIABLE.matcher(value.toString())
            while (matcher.find()) names.add(matcher.group(1))
        }
    }

    private void runWithEnvironmentAndDirectory(Map config, Closure command) {
        Closure action = command
        if (config.environment instanceof Map && !(config.environment as Map).isEmpty()) {
            List<String> values = (config.environment as Map).collect { key, value -> "${key}=${value}" }
            Closure withEnvironment = action
            action = { steps.withEnv(values) { withEnvironment.call() } }
        }
        if (config.workDir) {
            Closure inDirectory = action
            action = { steps.dir(config.workDir.toString()) { inDirectory.call() } }
        }
        action.call()
    }

    private void verifyArtifacts(Object configured) {
        for (String pattern : stringList(configured)) {
            def matches = steps.findFiles(glob: pattern)
            if (matches == null || matches.size() == 0) {
                throw new V3ConfigException("Maven 构建后未找到产物 ${pattern}")
            }
        }
    }

    private String readSource(String source, Collection trustedHosts) {
        if (source.startsWith('resource:')) {
            return steps.libraryResource(source.substring('resource:'.length()))
        }
        if (source.startsWith('https://')) {
            requireTrustedHttps(source, trustedHosts)
            def response = steps.httpRequest(url: source, httpMode: 'GET', validResponseCodes: '200', quiet: true,
                consoleLogResponseBody: false)
            return response.content.toString()
        }
        try {
            return steps.readTrusted(source).toString()
        } catch (Throwable ignored) {
            try {
                return steps.readFile(file: source).toString()
            } catch (Throwable readError) {
                String content
                steps.node(options.bootstrapAgent?.toString() ?: '') {
                    if (booleanValue(options.checkout, true)) steps.checkout(steps.scm)
                    content = steps.readFile(file: source).toString()
                }
                return content
            }
        }
    }

    private static void requireTrustedHttps(String source, Collection trustedHosts) {
        URI uri
        try {
            uri = new URI(source)
        } catch (Exception error) {
            throw new V3ConfigException("无效 HTTPS 地址 ${source}", error)
        }
        if (uri.scheme != 'https' || !uri.host) {
            throw new V3ConfigException('远程配置和项目清单只允许 HTTPS')
        }
        if (!(trustedHosts ?: []).collect { it.toString().toLowerCase(Locale.ENGLISH) }.contains(uri.host.toLowerCase(Locale.ENGLISH))) {
            throw new V3ConfigException("HTTPS 主机未加入 trustedHosts: ${uri.host}")
        }
    }

    private Map parseJson(String text, String source) {
        Object value = parseJsonValue(text, source)
        if (!(value instanceof Map)) throw new V3ConfigException("${source} 的根节点必须是对象")
        return value as Map
    }

    private Object parseJsonValue(String text, String source) {
        try {
            return normalize(steps.readJSON(text: text, returnPojo: true))
        } catch (Throwable error) {
            throw new V3ConfigException("无法解析 JSON ${source}: ${error.message}", error)
        }
    }

    private static Object normalize(Object value) {
        if (value instanceof Map) {
            Map result = new LinkedHashMap()
            value.each { key, child -> result[key.toString()] = normalize(child) }
            return result
        }
        if (value instanceof Collection) return (value as Collection).collect { normalize(it) }
        return value
    }

    private Object environmentValue(String name) {
        try { return steps.env[name] } catch (Throwable ignored) { return null }
    }

    private Object parameterValue(String name) {
        try { return steps.params[name] } catch (Throwable ignored) { return null }
    }

    private List<String> trustedHostsFromOptions() {
        return stringList(options.trustedHosts)
    }

    private static Map copyWithoutControlKeys(Map source) {
        Map result = new LinkedHashMap(source)
        ['type', 'id', 'name', 'variables', 'container'].each { result.remove(it) }
        return result
    }

    private static boolean isCancelled(Throwable error) {
        return error.class.name.contains('FlowInterruptedException') || error.class.name.contains('AbortException') &&
            error.message?.toLowerCase(Locale.ENGLISH)?.contains('abort')
    }

    private static void validateUniqueProjectIds(List<Map> plans) {
        Set<String> ids = new LinkedHashSet<String>()
        for (Map plan : plans) {
            if (!ids.add(plan.id.toString())) throw new V3ConfigException("项目编号重复 ${plan.id}")
        }
    }

    private static void validateProjectItem(Map item, String location) {
        if (!item.id?.toString()?.trim()) {
            throw new V3ConfigException("${location}.id 不能为空")
        }
        if (!(item.configFiles instanceof List) || (item.configFiles as List).isEmpty()) {
            throw new V3ConfigException("${location}.configFiles 必须是非空数组")
        }
        if (item.variables != null && !(item.variables instanceof Map)) {
            throw new V3ConfigException("${location}.variables 必须是对象")
        }
        if (item.agent != null && !(item.agent instanceof Map)) {
            throw new V3ConfigException("${location}.agent 必须是对象")
        }
    }

    private static String projectId(Map config) {
        if (config.project instanceof Map && config.project.id) return config.project.id.toString()
        String source = config._configPath?.toString() ?: 'project'
        String file = source.tokenize('/\\').last()
        return file.replaceFirst(/\.[^.]+$/, '')
    }

    private static String required(Map value, String key) {
        String text = value[key]?.toString()?.trim()
        if (!text) throw new V3ConfigException("${value.type ?: '配置'}.${key} 不能为空")
        return text
    }

    private static boolean booleanValue(Object value, boolean defaultValue) {
        if (value == null) return defaultValue
        if (value instanceof Boolean) return value
        return value.toString().equalsIgnoreCase('true')
    }

    private static int integerValue(Object value, String name, int minimum) {
        try {
            int result = value.toString().toInteger()
            if (result < minimum) throw new NumberFormatException()
            return result
        } catch (Exception ignored) {
            throw new V3ConfigException("${name} 必须是不小于 ${minimum} 的整数")
        }
    }

    private static List<Integer> integerList(Object value) {
        if (value == null) return []
        Collection values = value instanceof Collection ? value as Collection : [value]
        return values.collect { integerValue(it, 'expectedExitCodes', 0) }
    }

    private static List<String> stringList(Object value) {
        if (value == null) return []
        if (value instanceof Collection) return (value as Collection).collect { it.toString().trim() }.findAll { it }
        return value.toString().split(',').collect { it.trim() }.findAll { it }
    }
}
