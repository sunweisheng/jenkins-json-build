package com.bluersw.jenkins.libraries.v3

import com.cloudbees.groovy.cps.NonCPS
import java.net.URI
import java.util.regex.Matcher
import java.util.regex.Pattern

class V3Pipeline implements Serializable {
    private static final String DEFAULTS_RESOURCE = 'com/bluersw/jenkins/libraries/v3/defaults.json'
    private static final Pattern VARIABLE = Pattern.compile(/\$\{([A-Za-z_][A-Za-z0-9_.-]*)\}/)
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile(/[A-Za-z_][A-Za-z0-9_]*/)
    private static final Pattern BUILDKIT_IDENTIFIER = Pattern.compile(/[A-Za-z0-9][A-Za-z0-9_.-]*/)

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
        if (applyParameters(plans.collect { it.config as Map })) {
            return [:]
        }
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
            base = merger.merge(base, loadTemplate(templateName, []))
        }
        Map override = new LinkedHashMap(config)
        override.remove('extends')
        return merger.merge(base, override)
    }

    private Map loadTemplate(String templateName, List<String> parents) {
        if (parents.contains(templateName)) {
            throw new V3ConfigException("模板继承存在循环: ${(parents + templateName).join(' -> ')}")
        }
        String resource = (defaults.templates as Map)[templateName]?.toString()
        if (!resource) throw new V3ConfigException("引用了未知模板 ${templateName}")
        Map configured = parseJson(steps.libraryResource(resource), resource)
        Map base = [:]
        List<String> chain = new ArrayList<String>(parents)
        chain.add(templateName)
        for (String parent : stringList(configured.extends)) {
            base = merger.merge(base, loadTemplate(parent, chain))
        }
        Map override = new LinkedHashMap(configured)
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
        boolean optionProvided = options.containsKey('projectIds') || options.containsKey('projects')
        List<String> selected = stringList(options.projectIds ?: options.projects)
        String parameterName = projects.selectionParameter?.toString() ?: defaults.projects.selectionParameter.toString()
        boolean parameterProvided = parameterPresent(parameterName)
        if (!optionProvided && selected.isEmpty()) {
            Object parameterValue = parameterValue(parameterName)
            selected = stringList(parameterValue)
        }
        List<String> available = items.collect { it.id?.toString() }
        if (available.any { !it }) {
            throw new V3ConfigException('项目清单中的 id 不能为空')
        }
        if (selected.isEmpty() && (optionProvided || parameterProvided)) {
            throw new V3ConfigException('项目选择结果为空，请至少选择一个项目')
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

    private void runNpmStep(BuildContext context, Map config, Map stageVariables) {
        String shell = config.shell?.toString() ?: defaults.npm.shell.toString()
        List<String> command = [config.executable?.toString() ?: defaults.npm.executable.toString(), required(config, 'command')]
        command.addAll(stringList(config.arguments))
        runCommandStep(context, [type: 'command', shell: shell, script: commandLine(command, shell),
            workDir: config.workDir, environment: config.environment], stageVariables)
        verifyArtifacts(config.artifacts)
    }

    private void runGradleStep(BuildContext context, Map config, Map stageVariables) {
        List<String> tasks = stringList(config.tasks)
        if (tasks.isEmpty()) throw new V3ConfigException('gradle.tasks 不能为空')
        String shell = config.shell?.toString() ?: defaults.gradle.shell.toString()
        List<String> command = [config.executable?.toString() ?: defaults.gradle.executable.toString()]
        command.addAll(tasks)
        command.addAll(stringList(config.arguments))
        runCommandStep(context, [type: 'command', shell: shell, script: commandLine(command, shell),
            workDir: config.workDir, environment: config.environment], stageVariables)
        verifyArtifacts(config.artifacts)
    }

    private void runMsBuildStep(BuildContext context, Map config, Map stageVariables) {
        String shell = config.shell?.toString() ?: defaults.msbuild.shell.toString()
        List<String> command = [config.executable?.toString() ?: defaults.msbuild.executable.toString(),
            required(config, 'project')]
        List<String> targets = stringList(config.targets)
        if (!targets.isEmpty()) command.add("/t:${targets.join(';')}")
        (config.properties as Map ?: [:]).each { key, value -> command.add("/p:${key}=${value}") }
        command.addAll(stringList(config.arguments))
        runCommandStep(context, [type: 'command', shell: shell, script: commandLine(command, shell),
            workDir: config.workDir, environment: config.environment], stageVariables)
        verifyArtifacts(config.artifacts)
    }

    private void runCoverageStep(BuildContext context, Map config, Map stageVariables) {
        List<Map> reports = mapList(config.reports, 'coverage.reports')
        if (reports.isEmpty()) throw new V3ConfigException('coverage.reports 不能为空')
        List<Map> tools = []
        for (int index = 0; index < reports.size(); index++) {
            Map report = reports[index]
            String format = required(report, 'format').toUpperCase(Locale.ENGLISH)
            if (!(defaults.coverage.formats as List).contains(format)) {
                throw new V3ConfigException("coverage.reports[${index}].format 不支持 ${format}")
            }
            Map tool = [parser: format, pattern: required(report, 'pattern')]
            if (report.id) tool.id = report.id.toString()
            tools.add(tool)
        }
        Map arguments = copyWithoutControlKeys(config)
        arguments.remove('reports')
        arguments.tools = tools
        steps.recordCoverage(arguments)
    }

    private void runXcodeBuildStep(BuildContext context, Map config, Map stageVariables) {
        String action = required(config, 'action')
        if (!(defaults.xcodebuild.actions as List).contains(action)) {
            throw new V3ConfigException("xcodebuild.action 不支持 ${action}")
        }
        if (config.resultBundlePath && booleanValue(config.cleanResultBundle, false)) {
            String resultBundlePath = requireWorkspaceRelativePath(config.resultBundlePath.toString(),
                'xcodebuild.resultBundlePath')
            if (!resultBundlePath.endsWith('.xcresult')) {
                throw new V3ConfigException('启用 cleanResultBundle 时，xcodebuild.resultBundlePath 必须以 .xcresult 结尾')
            }
            runCommandStep(context, [type: 'command', shell: 'sh',
                script: commandLine([defaults.xcodebuild.resultBundleCleanupExecutable.toString(), '-rf', '--', resultBundlePath], 'sh'),
                workDir: config.workDir, environment: config.environment], stageVariables)
        }
        List<String> command = [config.executable?.toString() ?: defaults.xcodebuild.executable.toString()]
        if (action == 'exportArchive') {
            command.addAll(['-exportArchive', '-archivePath', required(config, 'archivePath'),
                '-exportPath', required(config, 'exportPath'), '-exportOptionsPlist', required(config, 'exportOptionsPlist')])
        } else {
            boolean workspace = config.workspace?.toString()?.trim() as boolean
            boolean project = config.project?.toString()?.trim() as boolean
            if (workspace == project) throw new V3ConfigException('xcodebuild 必须且只能设置 workspace 或 project')
            command.addAll([workspace ? '-workspace' : '-project', workspace ? config.workspace.toString() : config.project.toString(),
                '-scheme', required(config, 'scheme')])
            if (config.configuration) command.addAll(['-configuration', config.configuration.toString()])
            if (config.destination) command.addAll(['-destination', config.destination.toString()])
            if (config.derivedDataPath) command.addAll(['-derivedDataPath', config.derivedDataPath.toString()])
            if (config.resultBundlePath) command.addAll(['-resultBundlePath', config.resultBundlePath.toString()])
            if (action == 'test' && booleanValue(config.enableCodeCoverage, true)) command.addAll(['-enableCodeCoverage', 'YES'])
            if (action == 'archive') command.addAll(['-archivePath', required(config, 'archivePath')])
            command.add(action)
        }
        if (booleanValue(config.allowProvisioningUpdates, false)) command.add('-allowProvisioningUpdates')
        command.addAll(stringList(config.arguments))
        runCommandStep(context, [type: 'command', shell: 'sh', script: commandLine(command, 'sh'),
            workDir: config.workDir, environment: config.environment], stageVariables)
        verifyArtifacts(config.artifacts)
    }

    private void runXcodeCoverageStep(BuildContext context, Map config, Map stageVariables) {
        String resultBundle = required(config, 'resultBundlePath')
        String outputFile = config.outputFile?.toString() ?: defaults.xcodeCoverage.outputFile.toString()
        String executable = config.executable?.toString() ?: defaults.xcodeCoverage.executable.toString()
        List<String> command = [executable, 'xccov', 'view', '--report', '--json', resultBundle]
        Closure readCoverage = { invokeShell('sh', [script: commandLine(command, 'sh'), returnStdout: true], []) }
        String json = runWithEnvironmentAndDirectory(config, readCoverage).toString()
        Object parsed = parseJsonValue(json, resultBundle)
        if (!(parsed instanceof Map)) throw new V3ConfigException('xcodeCoverage 的 xccov 输出必须是 JSON 对象')
        String xml = XcodeCoverageConverter.toCobertura(parsed as Map, stringList(config.includeTargets),
            stringList(config.excludePatterns))
        steps.writeFile(file: outputFile, text: xml, encoding: 'UTF-8')
        Map coverage = copyWithoutControlKeys(config)
        ['resultBundlePath', 'outputFile', 'executable', 'includeTargets', 'excludePatterns', 'environment', 'workDir'].each {
            coverage.remove(it)
        }
        coverage.type = 'coverage'
        coverage.reports = [[format: 'COBERTURA', pattern: outputFile]]
        runCoverageStep(context, coverage, stageVariables)
    }

    private void runAppleSigningStep(BuildContext context, Map config, Map stageVariables) {
        String certificateId = required(config, 'certificateCredentialsId')
        String passwordId = required(config, 'certificatePasswordCredentialsId')
        List<String> profileIds = stringList(config.provisioningProfileCredentialsIds ?: config.provisioningProfileCredentialsId)
        if (profileIds.isEmpty()) throw new V3ConfigException('appleSigning.provisioningProfileCredentialsIds 不能为空')

        List bindings = [
            steps.file(credentialsId: certificateId, variable: 'V3_APPLE_CERTIFICATE'),
            steps.string(credentialsId: passwordId, variable: 'V3_APPLE_CERTIFICATE_PASSWORD')
        ]
        for (int index = 0; index < profileIds.size(); index++) {
            bindings.add(steps.file(credentialsId: profileIds[index], variable: "V3_APPLE_PROFILE_${index}"))
        }
        steps.withCredentials(bindings) {
            String workspace = steps.pwd().toString()
            String stateDirectory = "${workspace}/.jenkins-json-build/apple-signing"
            String keychain = "${stateDirectory}/build.keychain-db"
            String setup = appleSigningSetupScript(stateDirectory, keychain, profileIds.size())
            String cleanup = appleSigningCleanupScript(stateDirectory, keychain)
            try {
                steps.sh(script: setup)
                steps.withEnv(["V3_APPLE_KEYCHAIN=${keychain}", "OTHER_CODE_SIGN_FLAGS=--keychain ${keychain}"]) {
                    executeSteps(config.steps as List, context, stageVariables)
                }
            } finally {
                steps.sh(script: cleanup)
            }
        }
    }

    private static String commandLine(List<String> command, String shell) {
        List<String> escaped = []
        for (Object value : command) {
            if (shell in ['powershell', 'pwsh']) {
                escaped.add(ShellEscaper.powershell(value))
            } else if (shell == 'bat') {
                escaped.add(ShellEscaper.batch(value))
            } else {
                escaped.add(ShellEscaper.posix(value))
            }
        }
        String line = escaped.join(' ')
        return shell in ['powershell', 'pwsh'] ? "& ${line}" : line
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
            if (config.script) {
                runCommandStep(context, [type: 'command', shell: config.shell ?: 'sh', script: config.script,
                    workDir: config.workDir, environment: config.environment], stageVariables)
            } else {
                Map mavenConfig = [type: 'maven', goals: config.goals ?: ['sonar:sonar'], arguments: config.arguments ?: [],
                    executable: config.executable, workDir: config.workDir, settings: config.settings]
                runMavenStep(context, mavenConfig, stageVariables)
            }
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
        Map registeredBuilders = defaults.containerImage.builders as Map
        Map builderDefaults = registeredBuilders[builder] as Map
        if (builderDefaults == null) {
            throw new V3ConfigException("containerImage.builder 不支持 ${builder}，可用值: ${registeredBuilders.keySet().join(', ')}")
        }
        Map buildConfig = merger.merge(builderDefaults, config)
        List<String> destinations = stringList(config.destinations ?: config.destination)
        if (destinations.isEmpty()) {
            throw new V3ConfigException('containerImage.destinations 不能为空')
        }
        String digestFile = config.digestFile?.toString() ?: defaults.containerImage.digestFile.toString()
        Closure build = {
            String methodName = required(builderDefaults, 'handler')
            String digest = this."${methodName}"(context, buildConfig, stageVariables, destinations, digestFile).toString()
            String reference = ImageReference.withDigest(destinations[0], digest)
            context.setRuntimeVariable(config.digestVariable?.toString() ?: defaults.containerImage.digestVariable.toString(), digest)
            context.setRuntimeVariable(config.referenceVariable?.toString() ?: defaults.containerImage.referenceVariable.toString(), reference)
            context.outputs.image = [digest: digest, reference: reference, destinations: destinations]
        }

        if (config.workDir) {
            steps.dir(config.workDir.toString()) { build.call() }
        } else {
            build.call()
        }
    }

    private String runKanikoContainerImage(BuildContext context, Map config, Map stageVariables,
                                           List<String> destinations, String digestFile) {
        ensureParentDirectory(context, digestFile, stageVariables)
        List<String> command = [required(config, 'executor'), '--context', config.context?.toString() ?: '.',
            '--dockerfile', required(config, 'dockerfile'), '--digest-file', digestFile]
        for (String destination : destinations) {
            command.addAll(['--destination', destination])
        }
        if (booleanValue(config.cache, false)) {
            command.add('--cache=true')
        }
        (config.buildArgs as Map ?: [:]).each { key, value -> command.add("--build-arg=${key}=${value}") }
        command.addAll(stringList(config.arguments))
        runCommandStep(context, [type: 'command', shell: 'sh', script: command.collect { ShellEscaper.posix(it) }.join(' '),
            workDir: null], stageVariables)
        return ImageReference.requireDigest(steps.readFile(file: digestFile).toString())
    }

    private String runBuildKitContainerImage(BuildContext context, Map config, Map stageVariables,
                                              List<String> destinations, String digestFile) {
        digestFile = requireWorkspaceRelativePath(digestFile, 'containerImage.digestFile')
        String metadataFile = requireWorkspaceRelativePath(required(config, 'metadataFile'), 'containerImage.metadataFile')
        ensureParentDirectory(context, digestFile, stageVariables)
        ensureParentDirectory(context, metadataFile, stageVariables)
        String dockerfile = required(config, 'dockerfile').replace('\\', '/')
        int separator = dockerfile.lastIndexOf('/')
        String dockerfileDirectory = separator >= 0 ? dockerfile.substring(0, separator) : '.'
        String dockerfileName = separator >= 0 ? dockerfile.substring(separator + 1) : dockerfile

        List<String> cacheFrom = stringList(config.cacheFrom)
        List<String> cacheTo = stringList(config.cacheTo)
        if (config.containsKey('cache') && !booleanValue(config.cache, true) && (!cacheFrom.isEmpty() || !cacheTo.isEmpty())) {
            throw new V3ConfigException('containerImage.cache 为 false 时不能配置 cacheFrom 或 cacheTo')
        }

        List<String> command = [required(config, 'executor'), 'build', '--frontend', required(config, 'frontend'),
            '--local', "context=${config.context?.toString() ?: '.'}", '--local', "dockerfile=${dockerfileDirectory}",
            '--opt', "filename=${dockerfileName}"]
        List<String> platforms = stringList(config.platforms)
        if (!platforms.isEmpty()) command.addAll(['--opt', "platform=${platforms.join(',')}"])
        if (config.target) command.addAll(['--opt', "target=${config.target}"])
        (config.buildArgs as Map ?: [:]).each { key, value -> command.addAll(['--opt', "build-arg:${key}=${value}"]) }
        if (config.containsKey('cache') && !booleanValue(config.cache, true)) {
            command.add('--no-cache')
        } else {
            for (String source : cacheFrom) command.addAll(['--import-cache', source])
            for (String destination : cacheTo) command.addAll(['--export-cache', destination])
        }

        Map secretSetup = buildKitSecretSetup(config)
        command.addAll(secretSetup.arguments as List<String>)
        command.addAll(['--output', "type=image,name=${destinations.join(',')},push=true", '--metadata-file', metadataFile])
        command.addAll(stringList(config.arguments))
        List<String> scriptLines = new ArrayList<String>(secretSetup.lines as List<String>)
        scriptLines.add(command.collect { ShellEscaper.posix(it) }.join(' '))
        String script = scriptLines.join('\n')
        runCommandStep(context, [type: 'command', shell: 'sh', script: script, workDir: null], stageVariables)

        Object parsed = parseJsonValue(steps.readFile(file: metadataFile).toString(), metadataFile)
        if (!(parsed instanceof Map)) {
            throw new V3ConfigException("BuildKit 元数据 ${metadataFile} 必须是 JSON 对象")
        }
        String metadataKey = required(config, 'metadataDigestKey')
        String digest = ImageReference.requireDigest((parsed as Map)[metadataKey]?.toString())
        steps.writeFile(file: digestFile, text: "${digest}\n", encoding: 'UTF-8')
        return digest
    }

    private Map buildKitSecretSetup(Map config) {
        List<Map> secrets = mapList(config.secrets, 'containerImage.secrets')
        List<Map> sshEntries = mapList(config.ssh, 'containerImage.ssh')
        if (secrets.isEmpty() && sshEntries.isEmpty()) return [lines: [], arguments: []]

        String configuredDirectory = requireWorkspaceRelativePath(required(config, 'secretDirectory'), 'containerImage.secretDirectory')
        String directory = configuredDirectory.startsWith('./') ? configuredDirectory : "./${configuredDirectory}"
        String cleanup = "rm -rf ${ShellEscaper.posix(directory)}"
        List<String> lines = ['set +x', 'umask 077', cleanup,
            "mkdir -p ${ShellEscaper.posix(directory)}", "trap ${ShellEscaper.posix(cleanup)} EXIT",
            "trap 'exit 1' HUP INT TERM"]
        List<String> arguments = []
        for (int index = 0; index < secrets.size(); index++) {
            Map secret = secrets[index]
            String id = requireBuildKitIdentifier(required(secret, 'id'), "containerImage.secrets[${index}].id")
            boolean fromEnvironment = secret.envVariable != null
            boolean fromFile = secret.fileVariable != null
            if (fromEnvironment == fromFile) {
                throw new V3ConfigException("containerImage.secrets[${index}] 必须且只能设置 envVariable 或 fileVariable")
            }
            String path = "${directory}/secret-${index}"
            if (fromEnvironment) {
                String variable = requireEnvironmentName(secret.envVariable, "containerImage.secrets[${index}].envVariable")
                lines.add(requireBoundEnvironment(variable, "BuildKit secret ${id}"))
                lines.add("printf %s \"\$${variable}\" > ${ShellEscaper.posix(path)}")
                lines.add("chmod 600 ${ShellEscaper.posix(path)}")
            } else {
                String variable = requireEnvironmentName(secret.fileVariable, "containerImage.secrets[${index}].fileVariable")
                lines.add(requireAbsoluteEnvironmentPath(variable, "BuildKit secret ${id}"))
                lines.add(requireReadableEnvironmentFile(variable, "BuildKit secret ${id}"))
                lines.add("ln -s \"\$${variable}\" ${ShellEscaper.posix(path)}")
            }
            arguments.addAll(['--secret', "id=${id},src=${path}"])
        }
        for (int index = 0; index < sshEntries.size(); index++) {
            Map ssh = sshEntries[index]
            String id = requireBuildKitIdentifier(ssh.id?.toString() ?: 'default', "containerImage.ssh[${index}].id")
            String variable = requireEnvironmentName(required(ssh, 'variable'), "containerImage.ssh[${index}].variable")
            String path = "${directory}/ssh-${index}"
            lines.add(requireAbsoluteEnvironmentPath(variable, "BuildKit SSH ${id}"))
            lines.add("ln -s \"\$${variable}\" ${ShellEscaper.posix(path)}")
            arguments.addAll(['--ssh', "${id}=${path}"])
        }
        return [lines: lines, arguments: arguments]
    }

    private void ensureParentDirectory(BuildContext context, String path, Map stageVariables) {
        String normalized = path.replace('\\', '/')
        if (!normalized.contains('/')) return
        String directory = normalized.substring(0, normalized.lastIndexOf('/'))
        if (!directory) return
        runCommandStep(context, [type: 'command', shell: 'sh', script: "mkdir -p ${ShellEscaper.posix(directory)}"], stageVariables)
    }

    private static List<Map> mapList(Object configured, String location) {
        if (configured == null) return []
        if (!(configured instanceof List)) throw new V3ConfigException("${location} 必须是数组")
        List<Map> result = []
        for (int index = 0; index < (configured as List).size(); index++) {
            Object value = (configured as List)[index]
            if (!(value instanceof Map)) throw new V3ConfigException("${location}[${index}] 必须是对象")
            result.add(new LinkedHashMap(value as Map))
        }
        return result
    }

    private static String requireEnvironmentName(Object value, String location) {
        String name = value?.toString()?.trim()
        if (!name || !ENVIRONMENT_NAME.matcher(name).matches()) {
            throw new V3ConfigException("${location} 不是有效的环境变量名")
        }
        return name
    }

    private static String requireBuildKitIdentifier(Object value, String location) {
        String id = value?.toString()?.trim()
        if (!id || !BUILDKIT_IDENTIFIER.matcher(id).matches()) {
            throw new V3ConfigException("${location} 只能包含字母、数字、点、下划线和连字符")
        }
        return id
    }

    private static String requireWorkspaceRelativePath(String value, String location) {
        String path = value.replace('\\', '/').trim()
        List<String> segments = path.tokenize('/')
        if (!path || path.startsWith('/') || path == '.' || segments.contains('..')) {
            throw new V3ConfigException("${location} 必须是工作区内的相对路径")
        }
        return path
    }

    private static String requireBoundEnvironment(String variable, String description) {
        return "if [ \"\${${variable}+x}\" != x ]; then echo ${ShellEscaper.posix(description + ' 未绑定环境变量 ' + variable)} >&2; exit 1; fi"
    }

    private static String requireReadableEnvironmentFile(String variable, String description) {
        return "if [ ! -r \"\$${variable}\" ]; then echo ${ShellEscaper.posix(description + ' 对应文件不可读')} >&2; exit 1; fi"
    }

    private static String requireAbsoluteEnvironmentPath(String variable, String description) {
        return "case \"\$${variable}\" in /*) ;; *) echo ${ShellEscaper.posix(description + ' 必须使用绝对路径')} >&2; exit 1 ;; esac"
    }

    private static String appleSigningSetupScript(String stateDirectory, String keychain, int profileCount) {
        List<String> lines = ['#!/bin/bash', 'set -eu', 'set +x', 'umask 077',
            "STATE=${ShellEscaper.posix(stateDirectory)}", "KEYCHAIN=${ShellEscaper.posix(keychain)}",
            'rm -rf "$STATE"', 'mkdir -p "$STATE"',
            'security list-keychains -d user > "$STATE/original-keychains"',
            'KEYCHAIN_PASSWORD="$(uuidgen)-$(uuidgen)"',
            'security create-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN"',
            'security set-keychain-settings -lut 21600 "$KEYCHAIN"',
            'security unlock-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN"',
            'security import "$V3_APPLE_CERTIFICATE" -k "$KEYCHAIN" -P "$V3_APPLE_CERTIFICATE_PASSWORD" -T /usr/bin/codesign -T /usr/bin/security',
            'security set-key-partition-list -S apple-tool:,apple: -s -k "$KEYCHAIN_PASSWORD" "$KEYCHAIN"',
            'ORIGINAL_KEYCHAINS="$(tr \'\\n\' \' \' < "$STATE/original-keychains")"',
            'eval "security list-keychains -d user -s \\"$KEYCHAIN\\" $ORIGINAL_KEYCHAINS"',
            'PROFILE_DIRECTORY="$HOME/Library/MobileDevice/Provisioning Profiles"',
            'mkdir -p "$PROFILE_DIRECTORY"', ': > "$STATE/created-profiles"']
        for (int index = 0; index < profileCount; index++) {
            String variable = "V3_APPLE_PROFILE_${index}"
            lines.add("security cms -D -i \"\$${variable}\" > \"\$STATE/profile-${index}.plist\"")
            lines.add("PROFILE_UUID=\"\$(/usr/libexec/PlistBuddy -c 'Print UUID' \"\$STATE/profile-${index}.plist\")\"")
            lines.add("case \"\$PROFILE_UUID\" in *[!A-Fa-f0-9-]*|'') echo '描述文件 UUID 无效' >&2; exit 1 ;; esac")
            lines.add("PROFILE_TARGET=\"\$PROFILE_DIRECTORY/\$PROFILE_UUID.mobileprovision\"")
            lines.add("if [ ! -e \"\$PROFILE_TARGET\" ]; then cp \"\$${variable}\" \"\$PROFILE_TARGET\"; printf '%s\\n' \"\$PROFILE_TARGET\" >> \"\$STATE/created-profiles\"; fi")
        }
        return lines.join('\n')
    }

    private static String appleSigningCleanupScript(String stateDirectory, String keychain) {
        return ['#!/bin/bash', 'set +e', 'set +x', "STATE=${ShellEscaper.posix(stateDirectory)}",
            "KEYCHAIN=${ShellEscaper.posix(keychain)}",
            'if [ -s "$STATE/original-keychains" ]; then ORIGINAL_KEYCHAINS="$(tr \'\\n\' \' \' < "$STATE/original-keychains")"; eval "security list-keychains -d user -s $ORIGINAL_KEYCHAINS"; fi',
            'security delete-keychain "$KEYCHAIN" >/dev/null 2>&1 || true',
            'if [ -f "$STATE/created-profiles" ]; then while IFS= read -r PROFILE; do [ -n "$PROFILE" ] && rm -f "$PROFILE"; done < "$STATE/created-profiles"; fi',
            'rm -rf "$STATE"'].join('\n')
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
                validateStaticAgentRequirements(agent, context)
                checkoutSource(config)
                initializeWorkspaceVariables(context)
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
        if (agent.containsKey('environment')) {
            if (!(agent.environment instanceof Map)) {
                throw new V3ConfigException('Kubernetes Agent environment 必须是对象')
            }
            List environmentVariables = []
            (agent.environment as Map).each { key, value ->
                String name = key.toString()
                if (!ENVIRONMENT_NAME.matcher(name).matches()) {
                    throw new V3ConfigException("Kubernetes Agent environment 含有无效变量名 ${name}")
                }
                if (value == null) {
                    throw new V3ConfigException("Kubernetes Agent environment.${name} 不能为空")
                }
                environmentVariables.add(steps.envVar(key: name, value: value.toString()))
            }
            if (!environmentVariables.isEmpty()) arguments.envVars = environmentVariables
        }
        steps.podTemplate(arguments) {
            steps.node(environmentValue('POD_LABEL')?.toString() ?: '') {
                checkoutSource(config)
                initializeWorkspaceVariables(context)
                body.call()
            }
        }
    }

    private void validateStaticAgentRequirements(Map agent, BuildContext context) {
        if (agent.requirements == null) return
        if (!(agent.requirements instanceof Map)) {
            throw new V3ConfigException('agent.requirements 必须是对象')
        }
        Map requirements = agent.requirements as Map
        String expectedOs = requirements.os?.toString()?.toLowerCase(Locale.ENGLISH)
        if (expectedOs && !['linux', 'macos', 'windows'].contains(expectedOs)) {
            throw new V3ConfigException('agent.requirements.os 只能是 linux、macos 或 windows')
        }
        boolean unix = steps.isUnix()
        if (expectedOs == 'windows' && unix) {
            throw new V3ConfigException('所选 Agent 不是 Windows，已在源码检出前停止')
        }
        if (expectedOs && expectedOs != 'windows' && !unix) {
            throw new V3ConfigException("所选 Agent 不是 ${expectedOs}，已在源码检出前停止")
        }

        List<String> architectures = stringList(requirements.architectures ?: requirements.architecture)
        List<String> tools = stringList(requirements.tools)
        if (unix) {
            List<String> lines = ['set -eu']
            if (expectedOs) {
                String kernel = expectedOs == 'macos' ? 'Darwin' : 'Linux'
                lines.add("[ \"\$(uname -s)\" = ${ShellEscaper.posix(kernel)} ] || { echo ${ShellEscaper.posix('所选 Agent 操作系统不符合要求')} >&2; exit 1; }")
            }
            if (!architectures.isEmpty()) {
                if (architectures.any { !BUILDKIT_IDENTIFIER.matcher(it).matches() }) {
                    throw new V3ConfigException('agent.requirements.architectures 含有无效值')
                }
                lines.add("case \"\$(uname -m)\" in ${architectures.join('|')}) ;; *) echo ${ShellEscaper.posix('所选 Agent CPU 架构不符合要求')} >&2; exit 1 ;; esac")
            }
            for (String tool : tools) {
                lines.add("command -v ${ShellEscaper.posix(tool)} >/dev/null 2>&1 || { echo ${ShellEscaper.posix('所选 Agent 缺少工具 ' + tool)} >&2; exit 1; }")
            }
            if (lines.size() > 1) {
                runCommandStep(context, [type: 'command', shell: 'sh', script: lines.join('\n')], [:])
            }
            return
        }

        List<String> lines = ["\$ErrorActionPreference = 'Stop'"]
        if (!architectures.isEmpty()) {
            String values = architectures.collect { ShellEscaper.powershell(it.toUpperCase(Locale.ENGLISH)) }.join(', ')
            lines.add("if (@(${values}) -notcontains \$env:PROCESSOR_ARCHITECTURE.ToUpperInvariant()) { throw '所选 Agent CPU 架构不符合要求' }")
        }
        for (String tool : tools) {
            lines.add("if (-not (Get-Command ${ShellEscaper.powershell(tool)} -ErrorAction SilentlyContinue)) { throw ${ShellEscaper.powershell('所选 Agent 缺少工具 ' + tool)} }")
        }
        if (lines.size() > 1) {
            runCommandStep(context, [type: 'command', shell: 'powershell', script: lines.join('\n')], [:])
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
            steps.checkout(sourceControl())
        }
    }

    private Object sourceControl() {
        if (options.containsKey('scm')) {
            if (options.scm == null) throw new V3ConfigException('jenkinsJsonBuild.scm 不能为空')
            return options.scm
        }
        return steps.scm
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
                    Object configuredTimeout = definition.containsKey('timeoutSeconds') ? definition.timeoutSeconds :
                        (options.containsKey('httpTimeoutSeconds') ? options.httpTimeoutSeconds : defaults.http.timeoutSeconds)
                    def response = steps.httpRequest(url: url, httpMode: 'GET', validResponseCodes: '200', quiet: true,
                        consoleLogResponseBody: false,
                        timeout: integerValue(configuredTimeout, "runtimeVariables.${name}.timeoutSeconds", 1))
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

    private boolean applyParameters(List<Map> configs) {
        Map<String, Map> definitions = new LinkedHashMap<String, Map>()
        for (Map config : configs) {
            for (Map parameter : config.parameters ?: []) {
                definitions[required(parameter, 'name')] = parameter
            }
        }
        if (definitions.isEmpty()) return false
        List jenkinsParameters = []
        for (Map parameter : definitions.values()) {
            String name = parameter.name.toString()
            boolean hasCurrentValue = parameterPresent(name)
            Object currentValue = hasCurrentValue ? parameterValue(name) : null
            String type = parameter.type?.toString() ?: 'string'
            switch (type) {
                case 'string':
                    jenkinsParameters.add(steps.string(name: name,
                        defaultValue: hasCurrentValue ? currentValue?.toString() ?: '' : parameter.defaultValue?.toString() ?: '',
                        description: parameter.description?.toString() ?: '', trim: booleanValue(parameter.trim, true)))
                    break
                case 'boolean':
                    jenkinsParameters.add(steps.booleanParam(name: name,
                        defaultValue: hasCurrentValue ? booleanValue(currentValue, false) : booleanValue(parameter.defaultValue, false),
                        description: parameter.description?.toString() ?: ''))
                    break
                case 'choice':
                    List<String> choices = stringList(parameter.choices)
                    if (choices.isEmpty()) throw new V3ConfigException("参数 ${name} 的 choices 不能为空")
                    String preferred = hasCurrentValue ? currentValue?.toString() : parameter.defaultValue?.toString()
                    if (preferred && choices.contains(preferred)) {
                        choices = [preferred] + choices.findAll { it != preferred }
                    }
                    jenkinsParameters.add(steps.choice(name: name, choices: choices,
                        description: parameter.description?.toString() ?: ''))
                    break
                case 'agentServer':
                    jenkinsParameters.add(steps.agentParameter(name: name,
                        defaultValue: hasCurrentValue ? currentValue?.toString() ?: '' : parameter.defaultValue?.toString() ?: ''))
                    break
                case 'customCheckbox':
                    jenkinsParameters.add(customCheckboxParameter(parameter, hasCurrentValue, currentValue))
                    break
                case 'multiChoice':
                    if (parameter.provider?.toString() == 'customCheckbox') {
                        jenkinsParameters.add(customCheckboxParameter(parameter, hasCurrentValue, currentValue))
                    } else {
                        jenkinsParameters.add(steps.string(name: name,
                            defaultValue: hasCurrentValue ? currentValue?.toString() ?: '' : stringList(parameter.defaultValue).join(','),
                            description: parameter.description?.toString() ?: '', trim: true))
                    }
                    break
                default:
                    throw new V3ConfigException("不支持的参数类型 ${type}")
            }
        }
        List<String> missing = definitions.keySet().findAll { !parameterPresent(it) }.collect { it.toString() }
        steps.properties([steps.parameters(jenkinsParameters)])
        if (!missing.isEmpty()) {
            steps.currentBuild.result = 'NOT_BUILT'
            steps.echo("构建参数已初始化，请重新进入 Build with Parameters。新增参数: ${missing.join(', ')}")
            return true
        }
        return false
    }

    private Object customCheckboxParameter(Map parameter, boolean hasCurrentValue, Object currentValue) {
        String name = parameter.name.toString()
        String format = parameter.format?.toString() ?: 'JSON'
        if (!['JSON', 'YAML'].contains(format)) {
            throw new V3ConfigException("参数 ${name} 的 format 只能是 JSON 或 YAML")
        }
        String content = parameter.pipelineSubmitContent?.toString()
        String uri = parameter.uri?.toString()
        if ((content?.trim() ? true : false) == (uri?.trim() ? true : false)) {
            throw new V3ConfigException("参数 ${name} 必须且只能设置 pipelineSubmitContent 或 uri")
        }
        String protocol = parameter.protocol?.toString() ?: 'HTTP_HTTPS'
        if (!['HTTP_HTTPS', 'FILE_PATH'].contains(protocol)) {
            throw new V3ConfigException("参数 ${name} 的 protocol 只能是 HTTP_HTTPS 或 FILE_PATH")
        }
        Map arguments = [name: name, description: parameter.description?.toString() ?: '', protocol: protocol, format: format]
        if (content?.trim()) {
            arguments.pipelineSubmitContent = content
        } else {
            arguments.uri = uri
        }
        ['displayNodePath', 'valueNodePath', 'checkedNodePath'].each { field ->
            if (parameter[field]?.toString()?.trim()) arguments[field] = parameter[field].toString()
        }
        boolean setDefault = hasCurrentValue || parameter.containsKey('defaultValue')
        String defaultValue = hasCurrentValue ? currentValue?.toString() ?: '' :
            (parameter.defaultValue instanceof Collection ? stringList(parameter.defaultValue).join(',') :
                parameter.defaultValue?.toString() ?: '')
        if (!setDefault) return steps.checkboxParameter(arguments)
        return checkboxParameterWithDefault(arguments, defaultValue)
    }

    @NonCPS
    private Object checkboxParameterWithDefault(Map arguments, String defaultValue) {
        try {
            ClassLoader loader = this.class.classLoader
            Class definitionType = loader.loadClass('com.bluersw.CheckboxParameterDefinition')
            Class protocolType = loader.loadClass('com.bluersw.source.Protocol')
            Class formatType = loader.loadClass('com.bluersw.analyze.Format')
            Object protocol = Enum.valueOf(protocolType as Class<Enum>, arguments.protocol.toString())
            Object format = Enum.valueOf(formatType as Class<Enum>, arguments.format.toString())
            def constructor = definitionType.constructors.find { it.parameterTypes.size() == 9 }
            if (constructor == null) throw new IllegalStateException('未找到兼容的构造函数')
            Object definition = constructor.newInstance(arguments.name.toString(), arguments.description.toString(),
                protocol, format, arguments.uri?.toString() ?: '', arguments.displayNodePath?.toString(),
                arguments.valueNodePath?.toString(), null, arguments.pipelineSubmitContent?.toString())
            if (arguments.checkedNodePath) definition.setCheckedNodePath(arguments.checkedNodePath.toString())
            definition.setDefaultValue(defaultValue)
            return definition
        } catch (Throwable error) {
            throw new V3ConfigException('Custom Checkbox Parameter 插件不可用或版本不兼容，需要 1.72.v6074130b_6587', error)
        }
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

    private Object runWithEnvironmentAndDirectory(Map config, Closure command) {
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
        return action.call()
    }

    private void verifyArtifacts(Object configured) {
        for (String pattern : stringList(configured)) {
            def matches = steps.findFiles(glob: pattern)
            if (matches == null || matches.size() == 0) {
                throw new V3ConfigException("构建后未找到产物 ${pattern}")
            }
        }
    }

    private String readSource(String source, Collection trustedHosts) {
        if (source.startsWith('resource:')) {
            return steps.libraryResource(source.substring('resource:'.length()))
        }
        if (source.startsWith('https://')) {
            requireTrustedHttps(source, trustedHosts)
            Object configuredTimeout = options.containsKey('httpTimeoutSeconds') ? options.httpTimeoutSeconds : defaults.http.timeoutSeconds
            def response = steps.httpRequest(url: source, httpMode: 'GET', validResponseCodes: '200', quiet: true,
                consoleLogResponseBody: false,
                timeout: integerValue(configuredTimeout, 'httpTimeoutSeconds', 1))
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
                    if (booleanValue(options.checkout, true)) steps.checkout(sourceControl())
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

    private boolean parameterPresent(String name) {
        try {
            return steps.params instanceof Map && (steps.params as Map).containsKey(name)
        } catch (Throwable ignored) {
            return parameterValue(name) != null
        }
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
