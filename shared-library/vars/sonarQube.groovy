/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/
import com.bluersw.jenkins.libraries.model.Step
import com.bluersw.jenkins.libraries.utils.HttpRequest
import net.sf.json.JSONObject
import org.jenkinsci.plugins.workflow.cps.EnvActionImpl

/**
 * 执行SonarQube代码检查步骤
 * @param step SonarQube代码检查步骤
 */
void call(Step step) {
	//判断是否是开发环境
	if (env != null && !(env instanceof EnvActionImpl)){
		return
	}
	def scannerScript = step.getStepPropertyValue('ScannerScript')
	if (scannerScript != '') {
		def result = runStdoutScript(scannerScript)
		if (result.indexOf('EXECUTION FAILURE') != -1) {
			throw new Exception("SonarQube扫描代码，执行${scannerScript}失败。返回：${result}")
		}

		String reportTaskPath = step.getStepPropertyValue('ReportTaskPath')
		if (reportTaskPath != '') {
			//需要Pipeline Utility Steps插件
			def reportTask = readProperties(file: reportTaskPath)
			if (reportTask == null) {
				throw new Exception('SonarQube扫描阶级发生异常，未读取到ReportTaskPath配置的文件。')
			}

			String sonarServerUrl = reportTask['serverUrl']
			String ceTaskUrl = reportTask['ceTaskUrl']
			String dashboardUrl = reportTask['dashboardUrl']
			JSONObject ceTask = null

			//循环等待SonarQube服务器处理结果，超时时间设置为10分钟
			timeout(time: 10, unit: 'MINUTES') {
				waitUntil {
					String responseTask = HttpRequest.getResponse(ceTaskUrl)
					ceTask = readJSON(text: responseTask)
					println(ceTask)
					return "SUCCESS" == ceTask["task"]["status"]
				}
			}

			def projectStatusURL = "${sonarServerUrl}/api/qualitygates/project_status?analysisId=${ceTask["task"]["analysisId"]}"
			String projectStatus = HttpRequest.getResponse(projectStatusURL)
			JSONObject qualityGate = readJSON(text: projectStatus)

			println(qualityGate)
			String status = qualityGate["projectStatus"]["status"].toString()
			String gate = step.getStepPropertyValue('Gate')

			if (contrastQualityGate(status, gate)) {
				println("SonarQube质量检查通过，${dashboardUrl}")
			}
			else {
				throw new Exception("SonarQube质量检查未通过，浏览地址：${dashboardUrl}")
			}
		}
	}
}

/**
 * 比较质量结果是否满足要求
 * @param qualityGate 质量结果
 * @param claim 要求的质量结果
 * @return 是否满足
 */
private static boolean contrastQualityGate(String qualityGate, String claim) {

	String[] definition = ['OK', 'WARN', 'ERROR', 'NONE']

	int qualityGateIndex = -1
	int claimIndex = -1

	for (int i = 0; i < definition.length; i++) {
		if (definition[i] == qualityGate)
			qualityGateIndex = i
		if (definition[i] == claim)
			claimIndex = i
	}

	return qualityGateIndex <= claimIndex
}

