/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/
/**
 * 执行脚本命令并返回标准输出结果
 * @param script 脚本命令
 * @return 标准输出结果
 */
def call(String script){

	String UNIX_PREFIX = '#!/bin/bash -il\n '
	String UNIX_PREFIX_KEY_WORD = '#!/bin/bash'

	def result
	//判断操作系统
	if(isUnix()) {
		//加载/etc/profile
		if (script.indexOf(UNIX_PREFIX_KEY_WORD) == -1) {
			script = UNIX_PREFIX + script
		}
		result = sh(script: script, returnStdout: true)
	}
	else{
		result = bat(script: script, returnStdout: true)
	}
	return result
}

