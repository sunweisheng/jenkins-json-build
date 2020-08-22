@Library('shared-library') _

pipeline {
	agent any
	parameters { //定义构建参数
		choice choices: ['-'], description: '请选择部署方式', name: 'deploy-choice'
	}
	stages {
		stage('初始化') {
			steps {
				script{
					runWrapper.loadJSON('/jenkins-project.json')
					runWrapper.runSteps('初始化')
				}
			}
		}
		stage('单元测试') {
			steps {
				script{
					runWrapper.runSteps('单元测试')
				}
			}
		}
		stage('代码检查') {
			steps {
				script{
					runWrapper.runSteps('代码检查')
				}
			}
		}
		stage('编译构建') {
			steps {
				script{
					runWrapper.runSteps('编译构建')
				}
			}
		}
		stage('部署') {
			steps {
				script{
					runWrapper.runStepForEnv('部署','deploy-choice')
				}
			}
		}
	}
}