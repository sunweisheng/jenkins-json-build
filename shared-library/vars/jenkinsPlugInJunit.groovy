/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/
import com.bluersw.jenkins.libraries.model.Step

/**
 * 调用junit插件分析单元测试的结果
 * @param step 需要执行junit插件的构建步骤
 */
void call(Step step){
	String junitReportPath = step.getStepPropertyValue('JunitReportPath')
	if(junitReportPath == ''){
		throw new Exception('Junit处理失败，因为没有配置JunitReportPath子节点。')
	}

	println("JUnit报告路径:${junitReportPath}")
	junit(junitReportPath)

	if (currentBuild.result == 'UNSTABLE') {
		def unitTestReportUrl = "${JENKINS_URL}job/${JOB_NAME}/${BUILD_NUMBER}/testReport/"
		throw new Exception("单元测试阶段，单元测试未全部通过，失败的单元测试记录如下：${unitTestReportUrl}")
	}
}

