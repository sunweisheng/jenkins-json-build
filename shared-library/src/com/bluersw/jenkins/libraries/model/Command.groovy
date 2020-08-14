/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/
package com.bluersw.jenkins.libraries.model

/**
 * 命令脚本类
 * @auther SunWeiSheng
 */
class Command {
	String command
	String name

	Command(String name, String command) {
		this.command = command
		this.name = name
	}

	@Override
	String toString() {
		return "命令名称：${this.name}，命令内容：${this.command}"
	}
}
