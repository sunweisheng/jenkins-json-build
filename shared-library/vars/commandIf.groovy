/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/
import com.bluersw.jenkins.libraries.model.Step

/**
 * 判断带条件语句的条件是否成立，根据NotExpect或Expect进行判断，如果为true则执行脚本命令否则不执行
 * @param step 带条件语句的命令步骤
 * @return 根据NotExpect或Expect进行判断测试命令的结果是否为true
 */
boolean call(Step step){
	String testScript = step.getStepPropertyValue('TestScript')
	String notExpect = step.getStepPropertyValue('NotExpect')
	String expect = step.getStepPropertyValue('Expect')
	def testResult = null
	boolean run = false

	try{
		testResult = runStatusScript(testScript)
	}catch(ignored){}

	if(testResult != null) {
		if (notExpect != '') {
			if (testResult.toString() != notExpect) {
				run = true
			}
		}
		else if (expect != '') {
			if (testResult.toString() == expect) {
				run = true
			}
		}
	}
	return run
}

