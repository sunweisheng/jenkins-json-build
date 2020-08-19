import com.bluersw.jenkins.libraries.model.Step
import org.jenkinsci.plugins.workflow.cps.EnvActionImpl

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

