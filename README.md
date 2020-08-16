# Jenkins Json Build

此项目是依靠Jenkins共享类库（Shared Libraries）机制，使用JSON配置文件驱动构建过程，以便于剥离Jenkinsfile脚本的的编写和抽象复用Jenkins构建的执行过程。

## 内容列表

1. [准备工作](#准备工作)
1. [Json文档格式及运行方式](#json文档格式及运行方式)

## 准备工作

1. [安装Jenkins](https://github.com/sunweisheng/Jenkins/blob/master/Install-Jenkins.md)
1. [了解Jenkins共享类库](https://www.jenkins.io/doc/book/pipeline/shared-libraries/)
1. [安装 Gitlab](https://github.com/sunweisheng/Kvm/blob/master/Install-Gitlab.md)
1. [了解共享类库项目构成](https://github.com/sunweisheng/Jenkins/blob/master/Global-Shared-Library.md)【非必须】

## 创建Jenkins流水线任务

在Jenkins系统中新建一个流水线任务：
![project doc image](docs/images/jenkins-json-build-01.png)
任务配置中将定义修改为pipeline script from SCM，SCM修改为Git，Repository URL修改为项目仓库地址[http://git.bluersw.com/dev/gitlab.git]，取消最下面的轻量级检出复选框（因为以后会和Git Parameter插件冲突），Additional Behaviours中选择高级的克隆行为，将克隆和拉取操作的超市时间（分钟）设定为10分钟（因为有的项目比较大首次克隆会比较慢）：
![project doc image](docs/images/jenkins-json-build-02.png)
最后保存退出。

共享类库设置如下：shared-library是共享类库的名字，以后在构建脚本中会用到，[http://git.bluersw.com/jenkins/shared-libraries.git]是共享类库的仓库地址。
![project doc image](docs/images/jenkins-json-build-03.png)

## Json文档格式及运行方式
