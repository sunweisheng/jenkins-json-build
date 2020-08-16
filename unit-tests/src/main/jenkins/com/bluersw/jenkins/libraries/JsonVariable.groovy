@Library('shared-library') _

pipeline {
	parameters { //定义构建参数
		choice choices: ['部署全部'], description: '请选择要部署的项目', name: 'Deploy_Choice'
	}
	agent any
	stages {
		stage('RuntimeVariable变量') {
			steps {
				script{
					runWrapper.loadJSON('/json/json-variable.json')
					runWrapper.runSteps('测试变量')
				}
			}
		}
	}
}