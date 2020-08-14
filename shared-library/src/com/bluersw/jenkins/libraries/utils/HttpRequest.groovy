/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/
package com.bluersw.jenkins.libraries.utils

import java.nio.charset.Charset

import com.cloudbees.groovy.cps.NonCPS

/**
 * HTTP或HTTPS通讯类
 * @auther SunWeiSheng
 */
class HttpRequest {

	/**
	 * 获取请求URL的响应的字符串结果
	 * @param url HTTP或HTTPS的URL
	 * @return 响应的字符串结果
	 */
	@NonCPS
	static String getResponse(String url){
		HttpURLConnection connection = null
		BufferedReader bufferedReader = null
		StringBuilder builder = new StringBuilder()
		try {
			URL httpURL = new URL(url)
			connection = (HttpURLConnection) httpURL.openConnection()
			connection.setRequestMethod('GET')
			connection.connect()

			bufferedReader = new BufferedReader(new InputStreamReader(connection.getInputStream()))

			String line = ''
			while ((line = bufferedReader.readLine()) != null) {
				builder.append(line)
			}
		}
		finally {
			if(bufferedReader != null){
				bufferedReader.close()
			}

			if(connection != null){
				connection.disconnect()
			}
		}

		return new String(builder.toString().getBytes(), Charset.defaultCharset())
	}
}
