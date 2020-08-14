package com.bluersw.jenkins.libraries.model

/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/
/**
 * 构建步骤类型
 * @auther SunWeiSheng
 */
enum StepType {
	/**
	 * 全局设置和变量
	 */
	GLOBAL_VARIABLE,
	/**
	 * 通过SH或BAT执行的判定执行状态，返回0代表成功
	 */
	COMMAND_STATUS,
	/**
	 * 根据模版循环创造命令、执行并判定执行状态
	 */
	COMMAND_STATUS_FOR,
	/**
	 * 通过SH或BAT执行的获得标准输出结果
	 */
	COMMAND_STDOUT,
	/**
	 * 绑定Jenkins下拉菜单构建参数控件的值
	 */
	BUILD_PARAMETER_DROP_DOWN_MENU,
	/**
	 * Jenkins junit 插件执行步骤
	 */
	JUNIT_PLUG_IN,
	/**
	 * Jenkins jenkinsPlugInJacoco 插件执行步骤
	 */
	JACOCO_PLUG_IN,
	/**
	 * SonarQube处理步骤
	 */
	SONAR_QUBE,
	/**
	 * 使用jest分析单元测试覆盖度
	 */
	JEST_COVERAGE_ANALYSIS,
	/**
	 * 使用llvm-cov工具分析单元测试覆盖度
	 */
	LLVM_COV_COVERAGE_ANALYSIS,
	/**
	 * 使用MSBuild构建后分析单元测试覆盖度
	 */
	MSBUILD_COVERAGE_ANALYSIS
}