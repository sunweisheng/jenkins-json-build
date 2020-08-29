/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-29
*/

/**
 * Agent服务器构建（最多时涉及agentParameter、gitParameter、checkboxParameter、choice4个构建参数）
 * @param config 如果传入projectURL参数则代表是多项目构建反之则是单项目构建
 * @param successProcess 构建成功后处理函数
 */
void call(Map<String,String> config = new HashMap<>(), Closure successProcess = {}){
	String projectURL = config.getOrDefault('projectURL','')
	if(config.containsKey('projectURL')){
		//多项目构建
		pipeline {
			agent { label params['agent-name'] }
			parameters { //定义构建参数
				agentParameter name:'agent-name'
				gitParameter branchFilter: 'origin/(.*)', defaultValue: 'master', name: 'branch_name', type: 'PT_BRANCH', description: '请选择要构建的分支'
				checkboxParameter name: 'project-list', format: 'YAML', uri: projectURL
				choice choices: ['-'], description: '请选择部署方式', name: 'deploy-choice'
			}
			stages {
				stage('初始化') {
					steps {
						script{
							try{
								runWrapper.loadJSON(params['project-list'])
								runWrapper.runSteps('初始化')
							}catch(Exception e){
								ex = e
								throw e
							}
						}
					}
				}
				stage('单元测试') {
					steps {
						script{
							try{
								runWrapper.runSteps('单元测试')
							}catch(Exception e){
								ex = e
								throw e
							}
						}
					}
				}
				stage('代码检查') {
					steps {
						script{
							try{
								runWrapper.runSteps('代码检查')
							}catch(Exception e){
								ex = e
								throw e
							}
						}
					}
				}
				stage('编译构建') {
					steps {
						script{
							try{
								runWrapper.runSteps('编译构建')
							}catch(Exception e){
								ex = e
								throw e
							}
						}
					}
				}
				stage('部署') {
					steps {
						script{
							try{
								runWrapper.runStepForEnv('部署','deploy-choice')
							}catch(Exception e){
								ex = e
								throw e
							}
						}
					}
				}
			}
			post {
				failure {
					script{
						runWrapper.postFailure(ex)
					}
				}
				success{
					script{
						if(successProcess != null){
							successProcess(runWrapper)
						}
						runWrapper.postSuccess()
					}
				}
			}
		}
	}else{
		//单项目构建
		pipeline {
			agent { label params['agent-name'] }
			parameters { //定义构建参数
				agentParameter name:'agent-name'
				gitParameter branchFilter: 'origin/(.*)', defaultValue: 'master', name: 'branch_name', type: 'PT_BRANCH', description: '请选择要构建的分支'
				choice choices: ['-'], description: '请选择部署方式', name: 'deploy-choice'
			}
			stages {
				stage('初始化') {
					steps {
						script{
							try{
								runWrapper.loadJSON('/jenkins-project.json')
								runWrapper.runSteps('初始化')
							}catch(Exception e){
								ex = e
								throw e
							}
						}
					}
				}
				stage('单元测试') {
					steps {
						script{
							try{
								runWrapper.runSteps('单元测试')
							}catch(Exception e){
								ex = e
								throw e
							}
						}
					}
				}
				stage('代码检查') {
					steps {
						script{
							try{
								runWrapper.runSteps('代码检查')
							}catch(Exception e){
								ex = e
								throw e
							}
						}
					}
				}
				stage('编译构建') {
					steps {
						script{
							try{
								runWrapper.runSteps('编译构建')
							}catch(Exception e){
								ex = e
								throw e
							}
						}
					}
				}
				stage('部署') {
					steps {
						script{
							try{
								runWrapper.runStepForEnv('部署','deploy-choice')
							}catch(Exception e){
								ex = e
								throw e
							}
						}
					}
				}
			}
			post {
				failure {
					script{
						runWrapper.postFailure(ex)
					}
				}
				success{
					script{
						if(successProcess != null){
							successProcess(runWrapper)
						}
						runWrapper.postSuccess()
					}
				}
			}
		}
	}
}

