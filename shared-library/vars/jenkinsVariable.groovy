/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/
import com.cloudbees.groovy.cps.NonCPS
import hudson.EnvVars
import org.jenkinsci.plugins.workflow.cps.EnvActionImpl

/**
 * 打印构建过程中的环境变量
 */
@NonCPS
void printVars() {
	//env 是 org.jenkinsci.plugins.workflow.cps.EnvActionImpl类型
	if (env != null && env instanceof EnvActionImpl) {
		EnvVars env = env.getEnvironment()
		env.keySet().each { println("${it}:${env[it]}") }

		//当前构建对象
		def build = $build()
		def userName = "Jenkins"
		//获得出发构建的中文姓名
		if (build.getCause(Cause.UserIdCause) != null) {
			userName = build.getCause(Cause.UserIdCause).getUserName()
		}
		println(userName)
	}
}

/**
 * 获得Jenkins构建过程中的环境变量含构建参数变量
 * @return 环境变量Map
 */
Map<String,String> getEnvironment() {
	Map<String, String> envMap = new LinkedHashMap<>()
	//判断是否是开发环境，因为在正是Jenkins构建环境中env是org.jenkinsci.plugins.workflow.cps.EnvActionImpl类型
	if (env != null && env instanceof EnvActionImpl) {
		EnvVars envVars = env.getEnvironment()
		Set<String> set = envVars.keySet()
		for (int i = 0; i < set.size(); i++) {
			envMap.put(set[i], envVars[set[i]])
			//println(set[i] + ":" + envVars[set[i]])
		}

		//当前构建对象
		def build = $build()
		String REAL_USER_NAME = "Jenkins"
		//获得出发构建的中文姓名
		if (build.getCause(Cause.UserIdCause) != null) {
			REAL_USER_NAME = build.getCause(Cause.UserIdCause).getUserName()
		}
		envMap.put("REAL_USER_NAME",REAL_USER_NAME)

		envMap.put('WORKSPACE',"${WORKSPACE}")
	}

	return envMap
}