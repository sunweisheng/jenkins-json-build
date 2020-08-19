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
 * 分析jest产生的覆盖率报告并判断是否通过
 * @param step jest覆盖率分析步骤
 */
void call(Step step) {
	//判断是否是开发环境
	if (env != null && !(env instanceof EnvActionImpl)){
		return
	}

	String lcovReportDir = step.getStepPropertyValue('LcovReportDir')
	if (lcovReportDir == '')
		throw new Exception('jest覆盖率分析时，CoverageAnalysisType节点下的子节点LcovReportDir未配置。')

	if (lcovReportDir.lastIndexOf('/') != lcovReportDir.size() - 1)
		lcovReportDir = lcovReportDir + '/'

	def file = readFile(file: "${lcovReportDir}index.html")

	//匹配 90.01% </span>中的90.01
	Pattern groupPattern = Pattern.compile('(?<num>[0-9]+\\.*[0-9]*)% </span>')
	Matcher matcher = groupPattern.matcher(file)
	List listValue = new LinkedList<>()

	//index 0:Statements 1:Branches 2:Functions 3:Lines
	while (matcher.find()) {
		def group = matcher.group('num')
		if (group.indexOf(".") != -1) {
			listValue.add(Float.parseFloat(group))
			continue
		}
		listValue.add(Integer.parseInt(group))
	}

	def statements = step.getStepPropertyValue('Statements')
	def branches = step.getStepPropertyValue('Branches')
	def functions = step.getStepPropertyValue('Functions')
	def lines = step.getStepPropertyValue('Lines')

	statements = statements == '' ? "0" : statements
	branches = branches == '' ? "0" : branches
	functions = functions == '' ? "0" : functions
	lines = lines == '' ? "0" : lines
	if (listValue[0] < Integer.parseInt(statements))
		throw new Exception("jest覆盖率分析时覆盖率不达,标要求Statements达到${statements}%，实际${listValue[0]}%")
	if (listValue[1] < Integer.parseInt(branches))
		throw new Exception("jest覆盖率分析时覆盖率不达标,要求Branches达到${branches}%，实际${listValue[1]}%")
	if (listValue[2] < Integer.parseInt(functions))
		throw new Exception("jest覆盖率分析时覆盖率不达标,要求Functions达到${functions}%，实际${listValue[2]}%")
	if (listValue[3] < Integer.parseInt(lines))
		throw new Exception("jest覆盖率分析时覆盖率不达标,要求Lines达到${lines}%，实际${listValue[3]}%")

	println("单元测试覆盖率检查通过：Statements：${listValue[0]}% Branches：${listValue[1]}% Functions：${listValue[2]}% Lines：${listValue[3]}%")

}