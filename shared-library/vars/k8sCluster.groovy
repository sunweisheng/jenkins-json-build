/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-29
*/

/**
 * K8S集群构建（最多时涉及gitParameter、checkboxParameter、choice3个构建参数）注意：
 * 因为https://issues.jenkins-ci.org/browse/JENKINS-56943，podTemplateFile参数不能和gitParameter插件同时使用
 * @param config 传入projectURL参数代表多项目构建，传入podTemplateFile代表以文件方式加载Pod模版，传入podTemplate参数代表以模版内容方式加载Pod模版
 * @param successProcess 构建成功后处理函数
 */
void call(Map<String,String> config = new HashMap<>(), Closure successProcess = {}){
	if(!config.containsKey('podTemplateFile') && !config.containsKey('podTemplate')){
		config.put('podTemplateFile', 'KubernetesPod.yaml')
	}
	String projectURL = config.getOrDefault('projectURL','')
	String podTemplateFile = config.getOrDefault('podTemplateFile','')
	String podTemplate = config.getOrDefault('podTemplate','')
	if(config.containsKey('podTemplateFile')){
		//模版文件方式创建Pod
		if(config.containsKey('projectURL')){
			//多项目构建
			pipeline {
				agent {
					kubernetes {
						yamlFile podTemplateFile
					}
				}
				parameters { //定义构建参数
					checkboxParameter name: 'project-list', format: 'YAML', uri: projectURL
					choice choices: ['-'], description: '请选择部署方式', name: 'deploy-choice'
				}
				stages {
					stage('初始化') {
						steps {
							container('docker-build'){
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
					}
					stage('单元测试') {
						steps {
							container('docker-build'){
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
					}
					stage('代码检查') {
						steps {
							container('docker-build'){
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
					}
					stage('编译构建') {
						steps {
							container('docker-build'){
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
					}
					stage('部署') {
						steps {
							container('docker-build'){
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
				}
				post {
					failure {
						container('docker-build'){
							script{
								runWrapper.postFailure(ex)
							}
						}
					}
					success{
						container('docker-build'){
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
		}else{
			//单项目构建
			pipeline {
				agent {
					kubernetes {
						yamlFile podTemplateFile
					}
				}
				parameters { //定义构建参数
					choice choices: ['-'], description: '请选择部署方式', name: 'deploy-choice'
				}
				stages {
					stage('初始化') {
						steps {
							container('docker-build'){
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
					}
					stage('单元测试') {
						steps {
							container('docker-build'){
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
					}
					stage('代码检查') {
						steps {
							container('docker-build'){
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
					}
					stage('编译构建') {
						steps {
							container('docker-build'){
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
					}
					stage('部署') {
						steps {
							container('docker-build'){
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
				}
				post {
					failure {
						container('docker-build'){
							script{
								runWrapper.postFailure(ex)
							}
						}
					}
					success{
						container('docker-build'){
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
	}else if(config.containsKey('podTemplate')){
		//模版内容方式创建Pod
		if(config.containsKey('projectURL')){
			//多项目构建
			pipeline {
				agent {
					kubernetes {
						yaml podTemplate
					}
				}
				parameters { //定义构建参数
					gitParameter branchFilter: 'origin/(.*)', defaultValue: 'master', name: 'branch_name', type: 'PT_BRANCH', description: '请选择要构建的分支'
					checkboxParameter name: 'project-list', format: 'YAML', uri: projectURL
					choice choices: ['-'], description: '请选择部署方式', name: 'deploy-choice'
				}
				stages {
					stage('初始化') {
						steps {
							container('docker-build'){
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
					}
					stage('单元测试') {
						steps {
							container('docker-build'){
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
					}
					stage('代码检查') {
						steps {
							container('docker-build'){
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
					}
					stage('编译构建') {
						steps {
							container('docker-build'){
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
					}
					stage('部署') {
						steps {
							container('docker-build'){
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
				}
				post {
					failure {
						container('docker-build'){
							script{
								runWrapper.postFailure(ex)
							}
						}
					}
					success{
						container('docker-build'){
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
		}else{
			//单项目构建
			pipeline {
				agent {
					kubernetes {
						yaml podTemplate
					}
				}
				parameters { //定义构建参数
					gitParameter branchFilter: 'origin/(.*)', defaultValue: 'master', name: 'branch_name', type: 'PT_BRANCH', description: '请选择要构建的分支'
					choice choices: ['-'], description: '请选择部署方式', name: 'deploy-choice'
				}
				stages {
					stage('初始化') {
						steps {
							container('docker-build'){
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
					}
					stage('单元测试') {
						steps {
							container('docker-build'){
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
					}
					stage('代码检查') {
						steps {
							container('docker-build'){
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
					}
					stage('编译构建') {
						steps {
							container('docker-build'){
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
					}
					stage('部署') {
						steps {
							container('docker-build'){
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
				}
				post {
					failure {
						container('docker-build'){
							script{
								runWrapper.postFailure(ex)
							}
						}
					}
					success{
						container('docker-build'){
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
	}
}

