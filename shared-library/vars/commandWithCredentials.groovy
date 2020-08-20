/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/
import com.bluersw.jenkins.libraries.model.Step
import org.jenkinsci.plugins.workflow.cps.EnvActionImpl

/**
 * 使用用户名和密码的脚本命令，必须有CredentialsId属性，该属性的值是在Jenkins中定义的凭据，在命令中$password代表密码，$username代表用户名
 * @param step 需要使用凭据的命令步骤
 */
void call(Step step){
	//判断是否是开发环境
	if (env != null && !(env instanceof EnvActionImpl)){
		return
	}

	String credentialsId = step.getStepPropertyValue('CredentialsId')
	if(credentialsId == ''){
		throw new Exception("${step.name}步骤，没有找到CredentialsId节点。")
	}

	for(int i=0; i<step.commandQueue.size(); i++){
		withCredentials([usernamePassword(credentialsId: credentialsId, passwordVariable: 'password', usernameVariable: 'username')]) {
			def result = runStatusScript(step.commandQueue[i].command)

			if (result != null && result != 0) {
				throw new Exception("[${step.commandQueue[i].name}]执行返回非0，返回[${result}]")
			}else {
				println("[${step.commandQueue[i].name}]执行成功。")
			}
		}
	}
}

