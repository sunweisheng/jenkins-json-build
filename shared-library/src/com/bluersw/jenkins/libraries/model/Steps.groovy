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
import com.bluersw.jenkins.libraries.model.Step
import com.cloudbees.groovy.cps.NonCPS

/**
 * 构建步骤集合
 * @auther SunWeiSheng
 */
class Steps {
	private String name
	HashMap<String, String> stepsProperty = new HashMap<>()
	Queue<Step> stepQueue = new LinkedList<>()
	static final String IS_RUN_KEY_NAME = 'Run'

	/**
	 * 构造函数
	 * @param name 构建步骤集合名称
	 */
	Steps(String name) {
		this.name = name
	}

	/**
	 * 获取构建集合名称
	 * @return 构建集合名称
	 */
	String getStepsName() {
		return this.name
	}

	/**
	 * 构建步骤集合是否含有构建步骤
	 * @return 是否含有构建步骤
	 */
	boolean isValid() {
		return this.stepQueue.size() > 0 || this.stepsProperty.size() > 0
	}

	/**
	 * 设置构建步骤集合属性
	 * @param propertyName 属性名称
	 * @param value 属性值
	 */
	void setStepsProperty(String propertyName, String value) {
		if (this.stepsProperty.containsKey(propertyName)) {
			LogContainer.append(LogType.WARNING, "节点：${this.name}，名称为：${propertyName}的属性已经存在，该属性的内容被覆盖为：${value}")
		}
		this.stepsProperty.put(propertyName, value)
		LogContainer.append(LogType.DEBUG, "设置${this.name}节点${propertyName}属性，值：${value}")
	}

	/**
	 * 根据属性名称获取构建步骤集合属性值，如果没有返回''值
	 * @param propertyName 属性名称
	 * @return 构建步骤集合属性值，如果没有返回''值
	 */
	String getStepsPropertyValue(String propertyName) {
		String value = this.stepsProperty.getOrDefault(propertyName, '')
		LogContainer.append(LogType.DEBUG, "读取${this.name}节点${propertyName}属性，返回值：${value}")
		return value
	}

	/**
	 * 追加构建步骤对象
	 * @param step 构建步骤对象
	 */
	void append(Step step) {
		this.stepQueue.offer(step)
		LogContainer.append(LogType.DEBUG, "${this.name}节点：添加${step.name}步骤，类型为：${step.stepType}")
	}

	/**
	 * 该步骤集合是否参与构建任务的执行，有时需要暂停某个构建步骤集合，如要暂停在json中的构建步骤配置增加"Run"："false"内容
	 * @return 该步骤集合是否参与构建任务的执行
	 */
	boolean isRun() {
		if (!this.stepsProperty.containsKey(IS_RUN_KEY_NAME)) {
			return true
		}
		else {
			return stepsProperty[IS_RUN_KEY_NAME] as boolean
		}
	}
}
