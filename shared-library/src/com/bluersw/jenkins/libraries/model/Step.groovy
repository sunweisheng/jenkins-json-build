/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/
package com.bluersw.jenkins.libraries.model

import java.util.Queue

import com.bluersw.jenkins.libraries.model.LogContainer
import com.bluersw.jenkins.libraries.model.LogType
import com.bluersw.jenkins.libraries.model.StepType
import com.bluersw.jenkins.libraries.model.Command

/**
 * 构建步骤
 * @auther SunWeiSheng
 */
class Step {
	String name
	StepType stepType
	HashMap<String, String> stepProperty = new HashMap<>()
	Queue<Command> commandQueue = new LinkedList<>()

	/**
	 * 构造函数
	 * @param name 步骤名称
	 * @param stepType 步骤类型
	 */
	Step(String name, StepType stepType) {
		this.name = name
		this.stepType = stepType
	}

	/**
	 * 根据属性名称获得步骤的属性值如果没有返回''值
	 * @param propertyName 属性名称
	 * @return 属性值如果没有返回''值
	 */
	String getStepPropertyValue(String propertyName) {
		String value = this.stepProperty.getOrDefault(propertyName, '')
		LogContainer.append(LogType.DEBUG, "读取${this.name}节点${propertyName}属性，返回值：${value}")
		return value
	}

	/**
	 * 设置步骤属性
	 * @param propertyName 属性名称
	 * @param value 属性值
	 */
	void setStepProperty(String propertyName, String value) {
		if (this.stepProperty.containsKey(propertyName)) {
			LogContainer.append(LogType.WARNING, "节点：${this.name}，名称为：${propertyName}的属性已经存在，该属性的内容被覆盖为：${value}")
		}
		this.stepProperty.put(propertyName, value)
		LogContainer.append(LogType.DEBUG, "设置${this.name}节点${propertyName}属性，值：${value}")
	}

	/**
	 * 追加命令脚本
	 * @param name 命令名称
	 * @param command 命令内容
	 */
	void appendCommand(String name, String command) {
		this.commandQueue.offer(new Command(name, command))
		LogContainer.append(LogType.DEBUG, "添加${this.name}节点${name}的命令，内容：${command}")
	}

	/**
	 * 该步骤对象内是否含有要执行的命令对象
	 * @return 是否含有要执行的命令对象
	 */
	boolean containsCommands() {
		if (this.commandQueue.size() > 0) {
			return true
		}
		else {
			return false
		}
	}
}
