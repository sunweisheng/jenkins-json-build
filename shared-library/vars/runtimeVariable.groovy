import java.util.regex.Matcher
import java.util.regex.Pattern

import com.bluersw.jenkins.libraries.utils.HttpRequest
import net.sf.json.JSONObject

import static com.bluersw.jenkins.libraries.model.Constants.FILE_SEPARATOR
import static com.bluersw.jenkins.libraries.model.Constants.FIND_VARIABLE_PATTERN

/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/

/**
 * 获得变量的值
 * @param varNodeValue 变量节点的内容
 * @param envVars Jenkins环境变量
 * @return 通过http、file或执行脚本获得变量值
 */
String getVarValue(String varNodeValue, Map<String,String> envVars) {
	//此时可以使用Jenkins环境变量，所以需要对节点内容中引用环境变量的地方进行赋值操作
	varNodeValue = evnVariableAllocation(varNodeValue, envVars)
	//默认使用普通字符串
	String format = 'string'
	//尝试获取path属性值
	String attrPath = getPathAttribute(varNodeValue)
	if(attrPath != '') {
		varNodeValue = varNodeValue.replace("@path[${attrPath}]", '')
		//如果有path属性则说明是数据格式为json
		format = 'json'
	}
	//根据节点内容判断数据源（http、file、script）
	String dataSource = getDataSourceType(varNodeValue)

	if(format == 'string'){
		return stringFormat(dataSource, varNodeValue)
	}else{
		return jsonFormat(dataSource, varNodeValue, attrPath)
	}
}

/**
 * 处理json格式的数据源
 * @param dataSource 数据源类型
 * @param varNodeValue 变量节点的内容
 * @param attrPath path属性值
 * @return 通过获得数据源和path属性找到变量值内容
 */
private String jsonFormat(String dataSource, String varNodeValue, String attrPath) {
	String value = ''
	JSONObject jsonObject = null
	if(dataSource == 'http'){
		String response = HttpRequest.getResponse(varNodeValue)
		jsonObject = readJSON(text: response)
	}

	if(dataSource == 'file'){
		jsonObject = readJSON(file:varNodeValue)
	}

	if(dataSource == 'script'){
		def result = runStdoutScript(varNodeValue)
		jsonObject = readJSON(text: result.toString().trim())
	}
	if(jsonObject != null){
		//循环path属性找到Json格式中的String节点
		String[] pathArray = attrPath.split('\\\\')
		def temp = jsonObject
		//根据path数据定义的路径找到最后一个String类型的数据
		for(int i=0; i<pathArray.length; i++) {
			if (pathArray[i] != '') {
				if (temp == null) {
					break
				}
				temp = temp[pathArray[i]]
				if (temp instanceof String) {
					value = temp
					break
				}
			}
		}
	}
	return value
}

/**
 * 处理普通字符串的数据源格式
 * @param dataSource 数据源类型
 * @param varNodeValue 变量节点的内容
 * @return 通过数据源返回变量值内容
 */
private String stringFormat(String dataSource, String varNodeValue) {
	if(dataSource == 'http'){
		return HttpRequest.getResponse(varNodeValue)
	}

	if(dataSource == 'file'){
		return readFile(file:varNodeValue)
	}

	if(dataSource == 'script'){
		def result = runStdoutScript(varNodeValue)
		if(result != null){
			return result.toString().trim()
		}else{
			return ''
		}
	}
}

/**
 * 分辨变量节点内容中是哪种数据源（http、file、script）
 * @param varNodeValue 变量节点内容
 * @return 数据源（http、file、script）
 */
private static String getDataSourceType(String varNodeValue) {
	varNodeValue = varNodeValue.toLowerCase()
	//以http或https开头
	if (varNodeValue.startsWith('http')) {
		return 'http'
	}//以"//"、"/"、"./"、"."开头被视为文件路径
	else if (varNodeValue.startsWith(FILE_SEPARATOR) || varNodeValue.startsWith(".${FILE_SEPARATOR}")) {
		return 'file'
	}//第二个字符是":"被视为文件路径
	else if (varNodeValue.substring(1, 1) == ':') {
		return 'file'
	}
	else {
		//其他情况默认都是脚本
		return 'script'
	}
}

/**
 * 如果变量节点内容中含@path属性返回属性值
 * @param varNodeValue 变量节点内容
 * @return @path属性的属性值
 */
private static String getPathAttribute(String varNodeValue){
	String attrPath = ''
	//匹配 @Path[/node1/node2/node3]模式，分组path是/node1/node2/node3
	Pattern patternPath = Pattern.compile('@path\\[(?<path>.*?)]')
	//匹配@Path属性
	Matcher matcherPath = patternPath.matcher(varNodeValue)
	if (matcherPath.find()) {
		attrPath = matcherPath.group('path')
	}
	return attrPath
}

/**
 * 绑定Jenkins环境变量，因为运行时变量是在加载Jenkins环境变量之后发生，所以可以引用Jenkins环境变量
 * @param varNodeValue 变量节点内容
 * @param envVars Jenkins环境变量
 * @return 绑定Jenkins环境变量之后的变量节点内容
 */
private static String evnVariableAllocation(String varNodeValue, Map<String,String> envVars){
	Matcher varMatcher = FIND_VARIABLE_PATTERN.matcher(varNodeValue)
	while (varMatcher.find()) {
		String key = varMatcher.group('key')
		if (envVars.containsKey(key)) {
			//将变量名称替换为变量值内容
			varNodeValue = varNodeValue.replace("\${${key}}", envVars[key])
		}
	}
	return varNodeValue
}

