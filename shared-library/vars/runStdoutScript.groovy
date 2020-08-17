/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/

import static com.bluersw.jenkins.libraries.model.Constants.UNIX_PREFIX
import static com.bluersw.jenkins.libraries.model.Constants.UNIX_PREFIX_KEY_WORD

/**
 * 执行脚本命令并返回标准输出结果
 * @param script 脚本命令
 * @return 标准输出结果
 */
def call(String script) {
	def result
	//判断操作系统
	if (isUnix()) {
		//加载/etc/profile
		if (script.indexOf(UNIX_PREFIX_KEY_WORD) == -1) {
			script = UNIX_PREFIX + script
		}
		result = sh(script: script, returnStdout: true)
	}
	else {
		result = bat(script: script, returnStdout: true)
	}
	if (result != null) {
		result = result.toString().trim()
	}
	return result
}

