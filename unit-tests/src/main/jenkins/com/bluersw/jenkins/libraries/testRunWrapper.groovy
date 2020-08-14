#!/usr/bin/env groovy //可以在PATH目录中查找 并用groovy解释
@Library('shared-library')

import groovy.transform.Field

pipeline {
    agent any

    stages {
        stage('初始化') {
            steps {
                script {
                    runWrapper.loadJSON('/json/basic_example.json,json/')
                    runWrapper.printLoadFactoryLog()
                    runWrapper.runSteps('Step1')
                }
            }
        }
    }
}