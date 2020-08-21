/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/
package com.bluersw.jenkins.libraries.utils

import java.util.regex.Matcher

import com.bluersw.jenkins.libraries.model.LogContainer
import com.bluersw.jenkins.libraries.model.LogType
import com.cloudbees.groovy.cps.NonCPS
import net.sf.json.JSONObject

import static com.bluersw.jenkins.libraries.model.Constants.GLOBAL_VARIABLE_NODE_NAME
import static com.bluersw.jenkins.libraries.model.Constants.LOCAL_VARIABLE_NODE_NAME
import static com.bluersw.jenkins.libraries.model.Constants.FIND_VARIABLE_PATTERN
import static com.bluersw.jenkins.libraries.model.Constants.JUDGE_VARIABLE_PATTERN

/**
 * 处理Json文档，扩展了变量的概念，文档内可以定义全局变量和局部变量，节点的值可引用变量进行赋值。
 * @auther SunWeiSheng
 */
class JSONExtend{
	private String text
	private JSONObject jsonObject
	private LinkedHashMap<String, String> globalVariable = new LinkedHashMap<>()
	private LinkedHashMap<String, LinkedHashMap<String, String>> localVariable = new LinkedHashMap<>()
	private Map<String, String> envVars


	/**
	 * 构建函数
	 * @param text json文件内容
	 * @param envVars Jenkins构建环境变量
	 */
	JSONExtend(String text, Map<String, String> envVars){
		this.envVars = envVars == null ? new LinkedHashMap<String, String>() : envVars
		this.text = text
		this.jsonObject = JSONObject.fromObject(this.text)
		setEnvVarsForJSONObject(this.jsonObject, this.envVars)
		setEnvVarsForGlobalVariable(this.globalVariable, this.envVars)
		analyzeJSONObject(this.jsonObject, '')
	}



	@NonCPS
	JSONObject getJsonObject() {
		return jsonObject
	}

	@NonCPS
	LinkedHashMap<String, String> getGlobalVariable() {
		return globalVariable
	}

	@NonCPS
	LinkedHashMap<String, LinkedHashMap<String, String>> getLocalVariable() {
		return localVariable
	}

	/**
	 * 为Json文件对象设置环境变量
	 * @param jsonObject Json文件对象
	 * @param envVars 环境变量
	 */
	@NonCPS
	private static void setEnvVarsForJSONObject(JSONObject jsonObject, Map<String, String> envVars) {
		JSONObject gv = null
		if (!jsonObject.containsKey(GLOBAL_VARIABLE_NODE_NAME)) {
			jsonObject.accumulate(GLOBAL_VARIABLE_NODE_NAME, new JSONObject())
		}

		gv = jsonObject[GLOBAL_VARIABLE_NODE_NAME] as JSONObject
		envVars.each { if (it.value != null) (gv.accumulate(it.key, it.value)) }
	}

	/**
	 * 为全局变量集合设置环境变量（因为有的Json文件没有NODE_NAME_GLOBAL_VARIABLE节点）
	 * @param globalVariable 全局变量集合
	 * @param envVars 环境变量
	 */
	@NonCPS
	private static void setEnvVarsForGlobalVariable(LinkedHashMap<String, String> globalVariable, Map<String, String> envVars) {
		envVars.each { if (it.value != null) { globalVariable.put(it.key, it.value) } }
	}

	/**
	 * 设置局部变量名称和值内容
	 * @param xpath 变量的路径
	 * @param varName 变量名称
	 * @param value 变量值
	 */
	@NonCPS
	private void setLocalVariable(String xpath, String varName, String value) {
		//得到变量的作用域
		String scope = xpath.substring(0, xpath.indexOf(LOCAL_VARIABLE_NODE_NAME) - 1)
		if (!localVariable.containsKey(scope)) {
			localVariable.put(scope, new LinkedHashMap<>())
		}
		//如果变量内含其他变量的引用
		if (JUDGE_VARIABLE_PATTERN.matcher(value).find()) {
			//分解作用域层次
			List<String> scopeList = splitScopeLevel(xpath)
			//循环作用域尝试为变量赋值
			for (String scopeKey in scopeList) {
				if (localVariable.containsKey(scopeKey)) {
					value = transformVariableValue(varName, value, localVariable[scopeKey])
					//判断是否存在需要赋值的变量，如果没有就跳出循环，加快运行速度
					if (!JUDGE_VARIABLE_PATTERN.matcher(value).find()) {
						break
					}
				}
			}
			//遍历全局变量尝试赋值
			if (JUDGE_VARIABLE_PATTERN.matcher(value).find()) {
				value = transformVariableValue(varName, value, globalVariable)
			}
		}
		//存储局部变量和变量值
		localVariable[scope].put(varName, value)
	}

	/**
	 * 设置全局变量
	 * @param varName 变量名
	 * @param value 变量值
	 */
	@NonCPS
	private void setGlobalVariable(String varName, String value) {
		globalVariable.put(varName, transformVariableValue(varName, value, globalVariable))
	}

	/**
	 * 转换变量的值，如果变量值内含其他变量引用则进行赋值，否则返回变量值的原始值，如果找不到引用变量的值也返回原始内容
	 * @param varName 变量名称
	 * @param value 变量值内容（可能包含其他变量名称）
	 * @param range 检索变量值的集合
	 * @return 变量值内容中包含其他变量名称进行赋值替换后返回
	 */
	@NonCPS
	private static String transformVariableValue(String varName, String value, LinkedHashMap<String, String> range) {
		//判断自引用情况
		if (value.indexOf("\${${varName}}") != -1) {
			throw new IllegalArgumentException("变量定义的值内容中包含自身变量的引用，这会引起死循环赋值。")
		}
		//对引用的变量名称进行分组匹配
		Matcher varMatcher = FIND_VARIABLE_PATTERN.matcher(value)

		while (varMatcher.find()) {
			String key = varMatcher.group('key')
			if (range.containsKey(key)) {
				//将变量名称替换为变量值内容
				value = value.replace("\${${key}}", range[key])
			}
		}
		return value
	}

	/**
	 * 拆分作用域层次
	 * @param xpath 节点路径
	 * @return 作用域层次集合（由近及远）
	 */
	@NonCPS
	private static List<String> splitScopeLevel(String xpath) {
		//分解作用域层次
		String scope = xpath
		List<String> scopeList = new ArrayList<>()
		while (scope.indexOf('/') != -1) {
			scopeList.add(scope)
			scope = scope.substring(0, scope.lastIndexOf('/'))
		}
		return scopeList
	}

	/**
	 * 转换节点的值，节点值可能包含变量引用，如能找到变量的值则进行替换赋值，否则原样返回
	 * @param xpath 节点路径
	 * @param nodeValue 节点值（可能包含变量引用）
	 * @return 节点值如果含变量引用，将变量赋值后返回
	 */
	@NonCPS
	private String transformNodeValue(String xpath, String nodeValue) {
		if (JUDGE_VARIABLE_PATTERN.matcher(nodeValue).find()) {
			//分解作用域层次
			List<String> scopeList = splitScopeLevel(xpath)
			//对引用的变量名称进行分组匹配
			Matcher varMatcher = FIND_VARIABLE_PATTERN.matcher(nodeValue)
			while (varMatcher.find()) {
				String key = varMatcher.group('key')
				//循环作用域尝试为变量赋值
				for (String scopeKey in scopeList) {
					if (localVariable.containsKey(scopeKey)) {
						if (localVariable[scopeKey].containsKey(key)) {
							nodeValue = nodeValue.replace("\${${key}}", localVariable[scopeKey][key])
						}
						//判断是否存在需要赋值的变量，如果没有就跳出循环，加快运行速度
						if (!JUDGE_VARIABLE_PATTERN.matcher(nodeValue).find()) {
							break
						}
					}
				}
				//遍历全局变量尝试赋值
				if (JUDGE_VARIABLE_PATTERN.matcher(nodeValue).find()) {
					if (globalVariable.containsKey(key))
						nodeValue = nodeValue.replace("\${${key}}", globalVariable[key])
				}
			}
		}
		return nodeValue
	}


	/**
	 * 对JSON文件进行分析处理
	 * @param o JSON文档节点
	 * @param xpath 文档内节点路径，以"/"分割
	 */
	@NonCPS
	private void analyzeJSONObject(Object o, String xpath) {
		Iterator<String> entrys = null;
		//根节点和[]内的元素都是JSONObject类型
		if (o instanceof JSONObject) {
			entrys = ((JSONObject) o).entrySet().iterator()
		}
		else if (o instanceof Map.Entry) {
			Map.Entry entry = (Map.Entry) o
			//记录节点路径，节点之间用"/"分割
			xpath = xpath + '/' + entry.key.toString()
			if (entry.value instanceof String) {
				//如果是全局变量节点
				if (xpath.indexOf(GLOBAL_VARIABLE_NODE_NAME) != -1) {
					setGlobalVariable(entry.key.toString(), entry.value.toString())
				}//如果是局部变量节点
				else if (xpath.indexOf(LOCAL_VARIABLE_NODE_NAME) != -1) {
					setLocalVariable(xpath, entry.key.toString(), entry.value.toString())
				}
				//如果节点的值含变量引用，赋值后返回新的节点值内容
				entry.value = transformNodeValue(xpath, entry.value.toString())

				//添加日志
				LogContainer.append(LogType.DEBUG, "${xpath} - ${entry.value.toString()}")
			}
			else {
				entrys = entry.value.iterator()
			}
		}
		if (entrys != null) {
			while (entrys.hasNext()) {
				//递归遍历
				analyzeJSONObject(entrys.next(), xpath)
			}
		}
	}
}
