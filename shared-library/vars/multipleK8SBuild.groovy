import groovy.transform.Field

/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/
@Field public Exception ex

def call(String projectListURL){
	pipeline {
		agent {
			kubernetes {
				yamlFile 'KubernetesPod.yaml'
			}
		}
		parameters { //定义构建参数
			choice choices: ['-'], description: '请选择部署方式', name: 'deploy-choice'
			checkboxParameter name: 'project-list', format: 'YAML', uri: projectListURL
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
						runWrapper.postSuccess()
					}
				}
			}
		}
	}
}

