/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/
import java.text.DecimalFormat

import com.bluersw.jenkins.libraries.model.Step
import org.jenkinsci.plugins.workflow.cps.EnvActionImpl

/**
 * 使用llvm-cov工具分析Xcode单元测试覆盖度
 * @param step llvm-cov分析单元测试覆盖度步骤
 */
void call(Step step) {
	//判断是否是开发环境
	if (env != null && !(env instanceof EnvActionImpl)){
		return
	}

	String xcodePathScript = step.getStepPropertyValue('XcodePathScript')
	if (xcodePathScript == '')
		throw new Exception('llvm-cov处理环节出错,XcodePathScript子节点没有设置。')

	String llvmCovCommand = step.getStepPropertyValue('LlvmCovCommand')
	if (llvmCovCommand == '')
		throw new Exception('llvm-cov处理环节出错,LlvmCovCommand子节点没有设置。')

	String xcodeBuildLogPath = step.getStepPropertyValue('XcodeBuildLogPath')
	if (xcodeBuildLogPath == '')
		throw new Exception('llvm-cov处理环节出错,XcodeBuildLogPath子节点没有设置。')

	String testDeviceID = step.getStepPropertyValue('TestDeviceID')
	if (testDeviceID == '')
		throw new Exception('llvm-cov处理环节出错,TestDeviceID子节点没有设置。')

	String appName = step.getStepPropertyValue('APPName')
	if (appName == '')
		throw new Exception('llvm-cov处理环节出错,APPName子节点没有设置。')

	String fileNameContains = step.getStepPropertyValue('FileNameContains')
	if (fileNameContains == '')
		throw new Exception('llvm-cov处理环节出错,FileNameContains子节点没有设置。')

	DecimalFormat df = new DecimalFormat('#.##')

	def reachedFunctions = step.getStepPropertyValue('Functions')
	def reachedInstantiations = step.getStepPropertyValue('Instantiations')
	def reachedLines = step.getStepPropertyValue('Lines')
	def reachedRegions = step.getStepPropertyValue('Regions')

	println("预期：reachedFunctions：${reachedFunctions} reachedInstantiations：${reachedInstantiations} reachedLines：${reachedLines} reachedRegions：${reachedRegions}")

	reachedFunctions = reachedFunctions == '' ? 0 : df.parse(reachedFunctions.toString())
	reachedInstantiations = reachedInstantiations == '' ? 0 : df.parse(reachedInstantiations.toString())
	reachedLines = reachedLines == '' ? 0 : df.parse(reachedLines.toString())
	reachedRegions = reachedRegions == '' ? 0 : df.parse(reachedRegions.toString())

	def xcodePath = runStdoutScript(xcodePathScript)

	xcodePath = xcodePath.trim()

	String perfectionCommand = "${xcodePath}${llvmCovCommand}"

	println("perfectionCommand:${perfectionCommand}")

	String buildLog = readFile(xcodeBuildLogPath)

	String paramPrefix = buildLog.substring(buildLog.lastIndexOf('Test session results, code coverage, and logs:') + 46, buildLog.lastIndexOf('Logs/Test/Test-')).trim()

	println("paramPrefix:${paramPrefix}")

	String coverageProfdata = "${paramPrefix}Build/ProfileData/${testDeviceID}/Coverage.profdata"

	println("coverageProfdata:${coverageProfdata}")

	String productsBin = "${paramPrefix}Build/Products/Debug-iphonesimulator/${appName}.app/${appName}"

	println("productsBin:${productsBin}")

	String reportCommand = "${perfectionCommand} ${coverageProfdata} ${productsBin}"

	println("reportCommand:${reportCommand}")

	def reportString = runStdoutScript(reportCommand)

	def report = readJSON(text: reportString)

	println('*****************************************')

	def data = report['data']

	String version = "type:${report['type']} version:${report['version']}"

	if (version != 'type:llvm.coverage.json.export version:2.0.0')
		println("Warning：llvm.cov的版本是：${version} 不是version:2.0.0'")

	println(version)

	def files = (data['files'] as ArrayList)[0]

	def functionsCount = 0.00
	def functionsCovered = 0.00
	def functionsPercent = 0.00

	def instantiationsCount = 0.00
	def instantiationsCovered = 0.00
	def instantiationsPercent = 0.00

	def linesCount = 0.00
	def linesCovered = 0.00
	def linesPercent = 0.00

	def regionsCount = 0.00
	def regionsCovered = 0.00
	def regionsPercent = 0.00

	for (def fileInfo in files) {
		if (fileInfo['filename'].toString().indexOf(fileNameContains) != -1) {
			println('发现：' + fileInfo)

			def summary = fileInfo['summary']
			def functions = summary['functions']
			def instantiations = summary['instantiations']
			def lines = summary['lines']
			def regions = summary['regions']

			functionsCount += functions['count']
			functionsCovered += functions['covered']

			instantiationsCount += instantiations['count']
			instantiationsCovered += instantiations['covered']

			linesCount += lines['count']
			linesCovered += lines['covered']

			regionsCount += regions['count']
			regionsCovered += regions['covered']
		}
	}

	println("检索文件名包含：${fileNameContains}的文件完成。")



	functionsPercent = functionsCount == 0.00 ? 0.00 : (functionsCovered / functionsCount) * 100
	instantiationsPercent = instantiationsCount == 0.00 ? 0.00 : (instantiationsCovered / instantiationsCount) * 100
	linesPercent = linesCount == 0.00 ? 0.00 : (linesCovered / linesCount) * 100
	regionsPercent = regionsCount == 0.00 ? 0.00 : (regionsCovered / regionsCount) * 100

	println("functionsPercent:${functionsPercent} instantiationsPercent:${instantiationsPercent} linesPercent:${linesPercent} regionsPercent:${regionsPercent}")

	if (functionsPercent < reachedFunctions)
		throw new Exception("llvn-cov覆盖率分析时覆盖率不达标，要求Functions达到${reachedFunctions}%，实际${df.format(functionsPercent)}%")
	if (instantiationsPercent < reachedInstantiations)
		throw new Exception("llvn-cov覆盖率分析时覆盖率不达标,要求Instantiations达到${reachedInstantiations}%，实际${df.format(instantiationsPercent)}%")
	if (linesPercent < reachedLines)
		throw new Exception("llvn-cov覆盖率分析时覆盖率不达标,要求Lines达到${reachedLines}%，实际${df.format(linesPercent)}%")
	if (regionsPercent < reachedRegions)
		throw new Exception("llvn-cov覆盖率分析时覆盖率不达标,要求Regions达到${reachedRegions}%，实际${df.format(regionsPercent)}%")

	println("单元测试覆盖率通过Functions:${df.format(functionsPercent)}%,Instantiations:${df.format(instantiationsPercent)}%,Lines:${df.format(linesPercent)}%,Regions:${df.format(regionsPercent)}%")

}