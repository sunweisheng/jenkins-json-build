# Jenkins V3 platform

This directory contains the test-platform baseline for Jenkins Json Build V3.

- Jenkins LTS: `2.568.2`
- Jenkins Helm Chart: `5.9.49`
- Controller: official Java 25 image pinned to a multi-architecture digest
- Inbound agent: official Java 25 image pinned to a multi-architecture digest
- Application build/runtime default: OpenJDK 21
- Controller: one StatefulSet replica, persistent Jenkins Home, zero executors
- Builds: dynamic Kubernetes Pods over WebSocket
- Static Mac/Windows nodes: SSH Launcher or installation-specific launcher

The image digests were checked against the official image manifests on 2026-08-06. Recheck them before an upgrade; do not replace them with `latest`.

## Install order

1. Back up Jenkins Home and restore it into an isolated namespace.
2. Create the `jenkins-admin` Secret and the existing `jenkins-home` PVC.
3. Set `CI_NAMESPACE` and `JENKINS_HOST`, then run `render-values.sh`.
4. Install `deployer-rbac.yaml.tpl` after replacing `APP_NAMESPACE`.
5. Install Chart `5.9.49` with the generated `values.yaml`.
6. Create a GitHub Organization Folder or Multibranch Pipeline. Git Parameter is only for old jobs.

V3.2.0 固定增加以下插件：

- `http_request:1.25`
- `ssh-slaves:3.1097.v868116049892`
- `agent-server-parameter:1.21.v71e7962a_b_456`
- `custom-checkbox-parameter:1.72.v6074130b_6587`
- `coverage:3.3325.v2f3dd167a_b_e5`

平台同时固定 Pipeline Declarative 的兼容版本，避免只升级部分传递依赖后导致插件加载失败：

- `pipeline-model-api:2.2277.v00573e73ddf1`
- `pipeline-model-definition:2.2277.v00573e73ddf1`
- `pipeline-model-extensions:2.2277.v00573e73ddf1`
- `pipeline-stage-step:322.vecffa_99f371c`
- `pipeline-stage-tags-metadata:2.2277.v00573e73ddf1`
- `pipeline-input-step:534.v352f0a_e98918`
- `joda-time-api:2.14.0-149.v1c3ce991d1b_9`

已存在且设置 `initializeOnce: true` 的 Jenkins 不会自动补装新增插件。升级时先备份 Jenkins Home 并完成隔离恢复检查，再渲染 values 和执行 Helm dry-run。临时设置 `initializeOnce: false` 完成插件安装，确认插件版本、任务配置和 Controller 重启正常后恢复 `initializeOnce: true`。不要在没有可恢复备份时直接修改运行中的 Jenkins PVC。

The controller service account can provision build Pods but cannot read Kubernetes Secrets. Ordinary build Pods use `jenkins-build` without an API token. The default Java deployment Pod mounts the registry config only in rootless BuildKit and a short-lived projected Kubernetes token only in Helm; the Kaniko compatibility template keeps the same separation. The deployer role intentionally has no Secret permissions and Helm uses ConfigMaps for release storage.

The bundled rootless BuildKit Pod requires Kubernetes 1.30 or later, an admission policy that permits the container-level `Unconfined` seccomp and AppArmor settings, and nodes with unprivileged user namespaces enabled. It remains non-privileged and does not mount a Docker socket. Image names, executables, credential mount paths, cache references, and resource limits are template variables so installations can use private registries or internally maintained images without editing the shared library.

With `agent.restrictedPssSecurityContext: true`, the Kubernetes plugin adds restricted container security fields but does not choose numeric user or group IDs. The standard V3 Pipeline YAML supplies Pod-level UID/GID `1000:1000` and `fsGroup: 1000`, which also apply to the automatically injected `jnlp` container when it has no container-level identity override. Chart values `agent.runAsUser: 1000` and `agent.runAsGroup: 1000` are recommended when the Chart's own default PodTemplate is used, but Pipeline `podTemplate(yaml: ...)` definitions do not reliably inherit that default template; those Chart values are not a substitute for the V3 Pod security context. Projects using `agent.yaml` or `agent.yamlFile` must carry the same Pod-level identity and shared-volume permissions in their own YAML.

## Backup and restore rehearsal

Stop new builds, record the installed Chart and plugin versions, and create a storage-level snapshot of the Jenkins Home volume. Restore that snapshot to a different PVC and isolated namespace, then verify login, credentials metadata, shared-library loading, Multibranch discovery, a Java build, and a controller restart. An archive command alone is not considered a completed rehearsal.

Do not upgrade the production release until the restored controller passes those checks. Keep the old release values, plugin files, image digests, and volume snapshot together so rollback does not mix versions. The standard template uses `containerCap: 2`, which limits the cloud to two concurrent Kubernetes Agent Pods but does not control their node placement. Use node labels, affinity, taints, or an equivalent scheduling policy to keep the two Pods on different 4 GB worker nodes. Kubernetes and Mac acceptance builds remain serial on a 16 GB host.
