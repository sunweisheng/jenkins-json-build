import groovy.transform.Field

/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/
@Field public Exception ex

def call(){
	pipeline {
		agent { label params['agent-name'] }
		parameters { //定义构建参数
			agentParameter name:'agent-name'
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
					runWrapper.postSuccess()
				}
			}
		}
	}
}

