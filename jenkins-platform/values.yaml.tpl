controller:
  image:
    registry: docker.io
    repository: jenkins/jenkins
    tag: "2.568.2-jdk25@sha256:731295021178803629eed771b57cbb4809a0bf76b1b2ef4d7497305a1aa80cef"
    pullPolicy: IfNotPresent

  replicas: 1
  numExecutors: 0
  javaOpts: "-Xms512m -Xmx1536m -Duser.timezone=Asia/Shanghai"
  resources:
    requests:
      cpu: 500m
      memory: 1Gi
    limits:
      cpu: "2"
      memory: 2Gi

  serviceType: ClusterIP
  servicePort: 8080
  jenkinsUrl: https://${JENKINS_HOST}

  admin:
    createSecret: false
    existingSecret: jenkins-admin

  installPlugins:
    - kubernetes:4540.v612369217f87
    - workflow-aggregator:608.v67378e9d3db_1
    - workflow-multibranch:841.vec5b_9e1806ec
    - pipeline-groovy-lib:798.v5cc688825312
    - git:5.10.1
    - configuration-as-code:2111.v475308a_6c93b_
    - pipeline-utility-steps:3.810.va_7672d206740
    - github-branch-source:1983.vfa_27ed961853
    - credentials-binding:728.v902a_273b_8947
    - config-file-provider:1013.v73c323e52b_1f
    - http_request:1.25
    - junit:1416.vd753e036de5e
    - jacoco:3.3.7
    - sonar:2.18.3
    - ssh-slaves:3.1097.v868116049892
    - agent-server-parameter:1.21.v71e7962a_b_456
    - custom-checkbox-parameter:1.69.v27b_2c5306e46
    - coverage:3.3325.v2f3dd167a_b_e5
  installLatestPlugins: false
  installLatestSpecifiedPlugins: false
  initializeOnce: true
  overwritePlugins: false

  JCasC:
    defaultConfig: true
    overwriteConfiguration: false
    configScripts:
      v3-security: |
        jenkins:
          disableRememberMe: true
          remotingSecurity:
            enabled: true
        security:
          apiToken:
            creationOfLegacyTokenEnabled: false
            tokenGenerationOnCreationEnabled: false
      v3-shared-library: |
        unclassified:
          globalLibraries:
            libraries:
              - name: "jenkins-json-build"
                defaultVersion: "v3.2.0"
                implicit: false
                allowVersionOverride: true
                includeInChangesets: true
                retriever:
                  modernSCM:
                    libraryPath: "shared-library"
                    scm:
                      git:
                        remote: "https://github.com/sunweisheng/jenkins-json-build.git"
                        traits:
                          - cloneOptionTrait:
                              extension:
                                shallow: true
                                noTags: false
                                timeout: 10

  sidecars:
    configAutoReload:
      enabled: false

agent:
  enabled: true
  namespace: ${CI_NAMESPACE}
  serviceAccount: jenkins-build
  jenkinsUrl: http://jenkins.${CI_NAMESPACE}.svc.cluster.local:8080
  websocket: true
  podRetention: Never
  containerCap: 2
  showRawYaml: false
  privileged: false
  hostNetworking: false
  restrictedPssSecurityContext: true
  image:
    registry: docker.io
    repository: jenkins/inbound-agent
    tag: "jdk25@sha256:a95513bf791abd2279535ed78bcf5695cd3d910fab0edeeda3d049cccbe2a4ac"

persistence:
  enabled: true
  existingClaim: jenkins-home

rbac:
  create: true
  readSecrets: false

serviceAccount:
  create: true
  name: jenkins

serviceAccountAgent:
  create: true
  name: jenkins-build
  automountServiceAccountToken: false
