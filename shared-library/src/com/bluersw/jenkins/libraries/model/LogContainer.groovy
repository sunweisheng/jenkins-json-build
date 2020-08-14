/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/
package com.bluersw.jenkins.libraries.model

import java.util.Queue
import java.util.concurrent.atomic.AtomicInteger

import com.bluersw.jenkins.libraries.model.LogType
import com.cloudbees.groovy.cps.NonCPS

/**
 * 收集日志类（因为在执行src目录下代码无法实时输出日志）
 * @auther SunWeiSheng
 */
class LogContainer {
	private static Queue<LogInfo> queue = new LinkedList<>()

	/**
	 * 追加日志
	 * @param logType 日志类型
	 * @param message 日志内容
	 */
	@NonCPS
	static void append(LogType logType, String message) {
		def date = new Date().format("HH:mm:ss:SSS")
		queue.offer(new LogInfo(logType, "${date}-${message}"))
	}

	/**
	 * 获得指定日志类型的开始标记和结束标记之间的日志信息，标记检索是一条日志的从后往前检索（endsWith）
	 * @param startTag 开始标记
	 * @param endTag 结束标记
	 * @param logType 日志类型
	 * @return 日志信息（筛选出的所有信息）
	 */
	static String getLogByTag(String startTag,String endTag, LogType logType){
		boolean findTag = false
		Object[] array = null
		StringBuilder builder = new StringBuilder()
		array = queue.toArray()
		for (Object o in array) {
			LogInfo logInfo = (LogInfo) o
			//如果以开始标记结尾
			if(logInfo.message.endsWith(startTag)){
				findTag = true
			}
			if(findTag) {
				if (logInfo.type >= logType) {
					builder.append(logInfo)
				}
			}
			//如果以结束标记结尾
			if(logInfo.message.endsWith(endTag)){
				findTag = false
			}
		}
		return builder.toString()
	}

	/**
	 * 根据日志类型获得所有日志信息
	 * @param logType 日志类型
	 * @return 日志信息
	 */
	static String getLog(LogType logType) {
		Object[] array = null
		StringBuilder builder = new StringBuilder()
		array = queue.toArray()
		for (Object o in array) {
			LogInfo logInfo = (LogInfo) o
			if (logInfo.type >= logType) {
				builder.append(logInfo)
			}
		}
		return builder.toString()
	}

	/**
	 * 日志信息类
	 * @auther SunWeiSheng
	 */
	static class LogInfo {
		LogType type
		String message

		LogInfo(LogType logType, String message) {
			this.type = logType
			this.message = message
		}

		@NonCPS
		@Override
		String toString() {
			return "${type}:${message}\n"
		}
	}
}
