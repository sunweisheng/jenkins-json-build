/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/

import java.util.regex.Matcher
import java.util.regex.Pattern

import com.bluersw.jenkins.libraries.StepFactory
import com.bluersw.jenkins.libraries.model.LogContainer
import com.bluersw.jenkins.libraries.model.LogType
import com.bluersw.jenkins.libraries.model.Step
import com.bluersw.jenkins.libraries.model.Command
import com.bluersw.jenkins.libraries.model.StepType
import com.bluersw.jenkins.libraries.model.Steps
import com.cloudbees.groovy.cps.NonCPS
import groovy.transform.Field
import net.sf.json.JSONObject

import static com.bluersw.jenkins.libraries.model.Constants.FILE_SEPARATOR
import static com.bluersw.jenkins.libraries.model.Constants.FILE_SEPARATOR

/**
 * 记录当前正在处理的StepFactory对象，比如在POST失败处理过程中使用
 */
@Field static StepFactory CURRENT_STEP_FACTORY
@Field LinkedList<StepFactory> factories
@Field String[] jsonFilePaths
@Field Map<String,String> envVars

/**
 * 加载JSON配置文件合集
 * @param jsonPaths json文件路径集合，用","分割
 */
void loadJSON(String jsonPaths) {
	this.envVars = jenkinsVariable.getEnvironment()
	this.jsonFilePaths = getJSONFilePath(jsonPaths)
	this.factories = createStepFactory(this.jsonFilePaths, this.envVars)
	CURRENT_STEP_FACTORY = null
}

/**
 * 通过环境变量的值运行指定名称的构建步骤
 * @param stepsName 步骤集合名称
 * @param envName 环境变量名称
 */
void runStepForEnv(String stepsName, String envName){
	for (int factoryIndex = 0; factoryIndex < this.factories.size(); factoryIndex++) {
		StepFactory factory = this.factories[factoryIndex]
		//记录当前正在处理的StepFactory对象，在POST失败处理过程中使用
		CURRENT_STEP_FACTORY = factory
		println("开始执行[${factory.configPath}]的[${stepsName}]")
		Steps steps = factory.getStepsByName(stepsName)
		//如果构建步骤定义为参与构建任务的执行
		if (steps != null && steps.isRun()) {
			for (int stepIndex = 0; stepIndex < steps.stepQueue.size(); stepIndex++) {
				Step step = steps.stepQueue[stepIndex]
				//只执行环境变量指定的构建步骤
				if(this.envVars.containsKey(envName) && step.name == this.envVars[envName]) {
					println("开始执行[${stepsName}]的[${step.name}]")
					runStep(step)
					println("执行[${step.name}]完成")
				}
			}
			println("执行[${stepsName}]完成")
		}
	}
}

/**
 * 运行指定构建步骤集合的全部构建步骤
 * @param stepsName 构建步骤集合名称
 */
void runSteps(String stepsName) {
	//CPS脚本不能使用for( ..in ..) 和 each() 只能用for(;;)
	for (int factoryIndex = 0; factoryIndex < this.factories.size(); factoryIndex++) {
		StepFactory factory = this.factories[factoryIndex]
		//记录当前正在处理的StepFactory对象，在POST失败处理过程中使用
		CURRENT_STEP_FACTORY = factory
		println("开始执行[${factory.configPath}]的[${stepsName}]")
		Steps steps = factory.getStepsByName(stepsName)
		//如果构建步骤定义为参与构建任务的执行
		if (steps != null && steps.isRun()) {
			for (int stepIndex = 0; stepIndex < steps.stepQueue.size(); stepIndex++) {
				Step step = steps.stepQueue[stepIndex]
				println("开始执行[${stepsName}]的[${step.name}]")
				runStep(step)
				println("执行[${step.name}]完成")
			}
			println("执行[${stepsName}]完成")
		}
	}
}

/**
 * 打印加载JSON配置文件时的日志
 */
void printLoadFactoryLog() {
	for (StepFactory factory in this.factories) {
		//打印装载配置文件日志
		LogType logLevel = factory.getLogLevel()
		println(LogContainer.getLogByTag(factory.getInitStartTag(), factory.getInitEndTag(), logLevel))
	}
}

/**
 * 构建过程失败处理
 * @param ex 失败时抛出的异常
 */
void postFailure(Exception ex) {
	StepFactory factory = CURRENT_STEP_FACTORY
	if (factory != null) {
		String errorMessage = ex.message
		String to = factory.getGlobalVariableValue('Email-TO')
		String cc = factory.getGlobalVariableValue('Email-CC')
		if(to != '') {
			def response = libraryResource 'com/bluersw/default.json'
			JSONObject defaultJson = readJSON(text: response)
			def subject = "${JOB_NAME}-第${BUILD_NUMBER}次构建失败!"
			String message = defaultJson['FailMailTemplate'].toString().replace('FailReasons', errorMessage)
			def recipient = "${to},cc:${cc}".trim()
			emailext(to: recipient, mimeType: 'text/html', subject: subject, body: message)

			def redTextErrorMessageTemplate = defaultJson['RedTextErrorMessageTemplate']
			if (redTextErrorMessageTemplate != null) {
				rtp failedAsStable: false, parserName: 'HTML', failedText: redTextErrorMessageTemplate.toString().replace('errorMessage', errorMessage)
			}
		}
	}
}

/**
 * 构建过程成功处理
 */
void postSuccess() {
	StringBuilder toBuilder = new StringBuilder()
	StringBuilder ccBuilder = new StringBuilder()

	String to = ''
	String cc = ''

	//获得所有json配置文件内设置的收件人和抄送人
	for (int factoryIndex = 0; factoryIndex < this.factories.size(); factoryIndex++) {
		to = this.factories[factoryIndex].getGlobalVariableValue('Email-TO')
		cc = this.factories[factoryIndex].getGlobalVariableValue('Email-CC')

		String[] toArray = to.split(',')
		for (int i = 0; i < toArray.size(); i++) {
			if (toArray[i] != '' && toBuilder.indexOf(toArray[i]) == -1) {
				toBuilder.append(',')
				toBuilder.append(toArray[i])
			}
		}

		String[] ccArray = cc.split(',')
		for (int i = 0; i < ccArray.size(); i++) {
			if (ccArray[i] != '' && ccBuilder.indexOf(ccArray[i]) == -1) {
				ccBuilder.append(',')
				ccBuilder.append(ccArray[i])
			}
		}
	}

	to = toBuilder.toString()
	cc = ccBuilder.toString()

	if (to != '') {
		def recipient = "${to},cc:${cc}".trim()
		def subject = "${JOB_NAME}-第${BUILD_NUMBER}次构建成功!"
		def response = libraryResource 'com/bluersw/default.json'
		JSONObject defaultJson = readJSON(text: response)
		String successMailTemplate = defaultJson['SuccessMailTemplate'].toString()
		emailext(to: recipient, mimeType: 'text/html', subject: subject, body: successMailTemplate)
	}

	println("全部构建过程执行成功！")
}

/**
 * 执行具体的构建步骤
 * @param step 构建步骤
 */
private void runStep(Step step) {
	//这里可以增加不同类型步骤的处理方法
	switch (step.stepType) {
	case (StepType.BUILD_PARAMETER_DROP_DOWN_MENU):
		bindDropDownMenu(step)
		break
	case (StepType.SONAR_QUBE):
		sonarQube(step)
		break
	case (StepType.JACOCO_PLUG_IN):
		jenkinsPlugInJacoco(step)
		break
	case (StepType.JEST_COVERAGE_ANALYSIS):
		codeCoverageJest(step)
		break
	case (StepType.LLVM_COV_COVERAGE_ANALYSIS):
		codeCoverageLlvmCov(step)
		break
	case (StepType.MSBUILD_COVERAGE_ANALYSIS):
		codeCoverageMSBuild(step)
		break
	case (StepType.JUNIT_PLUG_IN):
		jenkinsPlugInJunit(step)
		break
	default:
		break
	}
	//所有步骤内都可以含有Script执行脚本
	if (step.containsCommands()) {
		runCommand(step)
	}
}

/**
 * 将指定构建集合名称内的构建步骤名称绑定到下拉菜单中
 * @param step 下拉菜单绑定数据步骤
 */
private void bindDropDownMenu(Step step) {
	//判断类型
	if (step.stepType == StepType.BUILD_PARAMETER_DROP_DOWN_MENU) {
		//将指定构建集合名称内的构建步骤名称绑定到下拉菜单中
		String stepsName = step.getStepPropertyValue('StepsName')
		//构建参数的参数名称
		String paramName = step.getStepPropertyValue('ParamName')
		if (stepsName != '' && paramName != '') {
			ArrayList<String> values = new ArrayList<>()
			//遍历所有json配置文件
			for (int i = 0; i < this.factories.size(); i++) {
				Steps steps = this.factories[i].getStepsByName(stepsName)
				for (int k = 0; k < steps.stepQueue.size(); k++) {
					String stepName = steps.stepQueue[k].name
					//数据（构建步骤名称）是否重复
					if (!values.contains(stepName)) {
						values.add(stepName)
					}
				}
			}
			bindBuildParameter.dropDownMenu(paramName,values.toArray() as String[])
		}
	}
}

/**
 * 执行构建步骤中的命令脚本
 * @param step 可能含有执行脚本的构建步骤
 */
private void runCommand(Step step) {
	for (int i = 0; i < step.commandQueue.size(); i++) {
		Command cmd = step.commandQueue[i]
		def result = null
		println("开始执行[${cmd.name}]的${cmd.command}命令")
		if (step.stepType == StepType.COMMAND_STDOUT) {
			result = runStdoutScript(cmd.command)
			String success = step.getStepPropertyValue('Success-IndexOf')
			//如果该步骤定义了成功标示则进行检查，不含成功表示就代表执行失败
			if (success != '' && result != null && result.toString().indexOf(success) == -1) {
				throw new Exception("[${cmd.command}]执行失败，返回[${result}],没有找到成功标准[${success}]")
			}
			else {
				//如果该步骤定义了失败标示则进行检查，含失败表示就代表执行失败
				String fail = step.getStepPropertyValue('Fail-IndexOf')
				if (fail != '' && result != null && result.toString().indexOf(fail) != -1) {
					throw new Exception("[${cmd.command}]执行失败，返回[${result}]，找到失败标准[${fail}]")
				}
			}
		}
		else {
			result = runStatusScript(cmd.command)
			//不为0代表执行失败
			if (result != null && result != 0) {
				throw new Exception("[${cmd.command}]执行返回非0，返回[${result}]")
			}
		}
		println("执行完成[${result}]")
	}
}

/**
 * 创建JSON文件对应的StepFactory对象
 * @param jsonFile json配置文件
 * @param envVars Jenkins环境变量
 * @return StepFactory对象列表
 */
private LinkedList<StepFactory> createStepFactory(String[] jsonFile, Map<String,String> envVars) {
	LinkedList<StepFactory> factoryList = new LinkedList<>()
	for (String json in jsonFile) {
		Map<String, String> stepFactoryEnv = new LinkedHashMap<>()
		copyMap(envVars, stepFactoryEnv)
		bindRuntimeVariable(json, stepFactoryEnv)
		StepFactory factory = new StepFactory(json, stepFactoryEnv)
		factory.initialize()
		factoryList.add(factory)
	}
	return factoryList
}

/**
 * 在获得Jenkins全局变量之后，绑定运行时变量，此类变量在RuntimeVariables节点中定义，如果是json格式可加入@path[]属性标示节点搜索路径
 * @param jsonFile json文件路径
 * @param stepFactoryEnv jenkins环境变量
 */
private void bindRuntimeVariable(String jsonFile, Map<String,String> stepFactoryEnv) {
	JSONObject json = readJSON(file: jsonFile)
	JSONObject runtimeVariables = json['RuntimeVariables'] as JSONObject
	if (runtimeVariables != null) {
		println('发现运行时变量节点：')
		Iterator<Object> iterator = runtimeVariables.keySet().iterator()
		while (iterator.hasNext()) {
			String key = iterator.next() as String
			//运行时变量都是健值对
			if (runtimeVariables[key] instanceof String) {
				String varValue = runtimeVariable.getVarValue(runtimeVariables[key], stepFactoryEnv)
				stepFactoryEnv.put(key, varValue)
			}
		}
		println('运行时变量处理完成。')
	}
}

/**
 * Map拷贝
 * @param source 源Map
 * @param target 目标Map
 */
private static void copyMap(Map<String,String> source, Map<String,String> target) {
	Iterator<String> iterator = source.keySet().iterator()
	while (iterator.hasNext()) {
		String key = iterator.next()
		target.put(key, source[key])
	}
}

/**
 * 根据路径合集获得JSON配置文件的路径集合
 * @param jsonPaths json文件路径集合，用","分割
 * @return json配置文件路径数组
 */
private String[] getJSONFilePath(String jsonPaths) {
	String currentDirectory = pwd()
	//判断是否是开发环境
	if (currentDirectory == 'workspaceDirMocked') {
		currentDirectory = './src/main/jenkins/com/bluersw/jenkins/libraries'
	}
	String[] dirs = jsonPaths.split(',')
	for (int i = 0; i < dirs.length; i++) {
		//支持用.表示当前Jenkins的工作区的根目录
		if (dirs[i] == '.' || dirs[i] == './') {
			dirs[i] == ''
		}
		//支持用./开头表示当前Jenkins的工作区的根目录
		if (dirs[i].startsWith('./')) {
			dirs[i] = dirs[i].substring(1, dirs[i].size())
		}
		if (!dirs[i].endsWith('.json')) {
			//默认项目根目录或子项目目录下jenkins-project.json作为构建配置文件
			dirs[i] = dirs[i] + 'jenkins-project.json'
		}
		if (!dirs[i].startsWith(FILE_SEPARATOR)) {
			dirs[i] = FILE_SEPARATOR + dirs[i]
		}
		dirs[i] = currentDirectory + dirs[i]

		dirs[i] = dirs[i].replace('/', FILE_SEPARATOR)
		dirs[i] = dirs[i].replace('\\', FILE_SEPARATOR)
	}
	return dirs
}


