/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/
import com.bluersw.jenkins.libraries.model.Step

/**
 * 使用jacoco插件分析单元测试覆盖度并判断是否通过
 * @param Step jacoco分析步骤
 */
void call(Step step) {
	// 获取配置的阀值预设值
	def lineCoverage = step.getStepPropertyValue('LineCoverage')
	def instructionCoverage = step.getStepPropertyValue('InstructionCoverage')
	def methodCoverage = step.getStepPropertyValue('MethodCoverage')
	def branchCoverage = step.getStepPropertyValue('BranchCoverage')
	def classCoverage = step.getStepPropertyValue('ClassCoverage')
	def complexityCoverage = step.getStepPropertyValue('ComplexityCoverage')
	// 要扫描的和不扫描的文件配置
	def inclusionPattern = step.getStepPropertyValue('InclusionPattern')
	def exclusionPattern = step.getStepPropertyValue('ExclusionPattern')

	lineCoverage = lineCoverage == '' ? "0" : lineCoverage
	instructionCoverage = instructionCoverage == '' ? "0" : instructionCoverage
	methodCoverage = methodCoverage == '' ? "0" : methodCoverage
	branchCoverage = branchCoverage == '' ? "0" : branchCoverage
	classCoverage = classCoverage == '' ? "0" : classCoverage
	complexityCoverage = complexityCoverage == '' ? "0" : complexityCoverage

	// 获取配置的到达阀值后的警告级别 FAILURE, UNSTABLE, NONE
	def failPrompt = step.getStepPropertyValue('FailPrompt')
	if (failPrompt == '') {
		failPrompt = 'NONE'
	}
	def changeBuildStatus = failPrompt == 'FAILURE' || failPrompt == 'UNSTABLE'
	// 获取配置的jacoco扫描的class,默认为全部,JavaWeb 通常为**/${自己项目的Service层}/target/classes
	def classPattern = step.getStepPropertyValue('classPattern')
	//添加其他选项
	def execPattern = step.getStepPropertyValue('execPattern')

	classPattern = classPattern == '' ? '' : classPattern
	execPattern = execPattern == '' ? '' : execPattern

	// 要扫描的和不扫描的文件配置非空判断
	inclusionPattern = inclusionPattern == '' ? "" : inclusionPattern
	exclusionPattern = exclusionPattern == '' ? "" : exclusionPattern

	if (classPattern == '' && execPattern == '') {
		jacoco(
				changeBuildStatus: changeBuildStatus,
				maximumLineCoverage: lineCoverage,
				maximumInstructionCoverage: instructionCoverage,
				maximumMethodCoverage: methodCoverage,
				maximumBranchCoverage: branchCoverage,
				maximumClassCoverage: classCoverage,
				maximumComplexityCoverage: complexityCoverage,
				inclusionPattern : inclusionPattern,
				exclusionPattern : exclusionPattern
		)
	} else if (classPattern != '' && execPattern == '') {
		jacoco(
				changeBuildStatus: changeBuildStatus,
				classPattern: classPattern,
				maximumLineCoverage: lineCoverage,
				maximumInstructionCoverage: instructionCoverage,
				maximumMethodCoverage: methodCoverage,
				maximumBranchCoverage: branchCoverage,
				maximumClassCoverage: classCoverage,
				maximumComplexityCoverage: complexityCoverage,
				inclusionPattern : inclusionPattern,
				exclusionPattern : exclusionPattern
		)
	} else if (classPattern == '' && execPattern != '') {
		jacoco(
				changeBuildStatus: changeBuildStatus,
				execPattern: execPattern,
				maximumLineCoverage: lineCoverage,
				maximumInstructionCoverage: instructionCoverage,
				maximumMethodCoverage: methodCoverage,
				maximumBranchCoverage: branchCoverage,
				maximumClassCoverage: classCoverage,
				maximumComplexityCoverage: complexityCoverage,
				inclusionPattern : inclusionPattern,
				exclusionPattern : exclusionPattern
		)
	} else if (classPattern != '' && execPattern != '') {
		jacoco(
				changeBuildStatus: changeBuildStatus,
				classPattern: classPattern,
				maximumLineCoverage: lineCoverage,
				maximumInstructionCoverage: instructionCoverage,
				maximumMethodCoverage: methodCoverage,
				maximumBranchCoverage: branchCoverage,
				maximumClassCoverage: classCoverage,
				maximumComplexityCoverage: complexityCoverage,
				execPattern: execPattern,
				inclusionPattern : inclusionPattern,
				exclusionPattern : exclusionPattern
		)
	}
	def jacocoReportUrl = "${JENKINS_URL}job/${JOB_NAME}/${BUILD_NUMBER}/"
	println("覆盖率统计：${jacocoReportUrl}")

	if (currentBuild.result == 'UNSTABLE' && failPrompt == 'FAILURE')
		throw new Exception("单元测试覆盖率未达到设定值")
}

