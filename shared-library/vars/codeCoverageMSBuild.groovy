/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/
import java.util.regex.Matcher
import java.util.regex.Pattern

import com.bluersw.jenkins.libraries.model.Step
import org.jenkinsci.plugins.workflow.cps.EnvActionImpl

/**
 * 分析MSBuild产生的覆盖率报告并判断是否通过
 * @param  step MSBuild覆盖率分析步骤
 */
void call(Step step) {
	//判断是否是开发环境
	if (env != null && !(env instanceof EnvActionImpl)){
		return
	}
	String reportDir = step.getStepPropertyValue('ReportDir')

	if(reportDir == '')
		throw new Exception('MSBuild覆盖率分析时,子节点ReportDir未配置。')

	if(reportDir.lastIndexOf('\\') != reportDir.size()-1)
		reportDir = reportDir + '\\'

	def file = readFile(file:"${reportDir}index.htm")

	//匹配<tr><th>Line coverage:</th><td>90%</td></tr>中的90
	Pattern groupPattern = Pattern.compile('<tr><th>Line coverage:</th><td>(?<num>.+?)%</td></tr>')
	Matcher matcher = groupPattern.matcher(file)

	def lines = step.getStepPropertyValue('Lines')
	lines = lines == '' ? "0" : lines
	def coverageLines

	while (matcher.find()) {
		def group = matcher.group('num')
		coverageLines = group
	}

	if(coverageLines < lines)
		throw new Exception("覆盖率分析时覆盖率不达标,要求Lines达到${lines}%，实际${coverageLines}%")

	println("单元测试覆盖率检查通过：Lines：${coverageLines}%")
}

