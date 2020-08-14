/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/
/**
 * 执行脚本命令返回执行状态（0代表成功，非0代表失败）
 * @param script 脚本命令
 * @return 执行状态（0代表成功，非0代表失败）
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
		result = sh(script: script, returnStatus: true)
	}
	else{
		result = bat(script: script, returnStatus: true)
	}
	return result
}

