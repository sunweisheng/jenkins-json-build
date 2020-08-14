/*
* Copyright (C) MIT License
*
* @version 2.0
* @date 2020-8-15
*/
def call(){
	pipeline {
		agent any
		stages {
			stage('build') {
				steps {
					echo "java"
				}
			}
		}
	}
}

