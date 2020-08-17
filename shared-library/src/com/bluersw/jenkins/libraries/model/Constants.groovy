/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/
package com.bluersw.jenkins.libraries.model

import java.util.regex.Pattern

/**
 * 常量数据
 * @auther SunWeiSheng
 */
class Constants {
	/**
	 * 文件分割符号，用于跨系统转换路径
	 */
	static final String FILE_SEPARATOR = System.getProperty("file.separator")
	/**
	 * 配置文件内的局部变量节点名称
	 */
	static final String LOCAL_VARIABLE_NODE_NAME = "Variable"
	/**
	 * json配置文件内定义构建步骤类型的节点类型的节点名称
	 */
	static final String STEP_TYPE_NODE_NAME = 'Type'
	/**
	 * json配置文件内全局变量节点名称
	 */
	static final String GLOBAL_VARIABLE_NODE_NAME = 'GlobalVariable'
	/**
	 * json配置文件内定义运行时变量的节点名称
	 */
	static final String RUNTIME_VARIABLE_NODE_NAME = 'RuntimeVariable'
	/**
	 * json配置文件内定义需要执行脚本的节点名称
	 */
	static final String COMMAND_SCRIPT_NODE_NAME = 'Script'
	/**
	 * json配置文件内全局变量中定义日志级别的节点名称
	 */
	static final String GLOBAL_LOG_LEVEL_NODE_NAME = 'LogLevel'
	/**
	 * 匹配变量引用的正则并且匹配的分组名为key，比如：${var}中的var
	 */
	static final Pattern FIND_VARIABLE_PATTERN = Pattern.compile('\\$\\{(?<key>.*?)}')
	/**
	 * 匹配变量的正则没有设置分组用于判断是否含有变量引用，比如：${var}
	 */
	static final Pattern JUDGE_VARIABLE_PATTERN = Pattern.compile('\\$\\{(.*?)}')
	/**
	 * shell脚本前缀，目的是加载/etc/profile环境变量
	 */
	static final String UNIX_PREFIX = '#!/bin/bash -il\n '
	/**
	 * 检查shell脚本是否添加了前缀
	 */
	static final String UNIX_PREFIX_KEY_WORD = '#!/bin/bash'
}
