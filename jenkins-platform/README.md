# Jenkins V3 platform

This directory contains the test-platform baseline for Jenkins Json Build V3.

- Jenkins LTS: `2.568.2`
- Jenkins Helm Chart: `5.9.49`
- Controller: official Java 25 image pinned to a multi-architecture digest
- Inbound agent: official Java 25 image pinned to a multi-architecture digest
- Application build/runtime default: OpenJDK 21
- Controller: one StatefulSet replica, persistent Jenkins Home, zero executors
- Builds: dynamic Kubernetes Pods over WebSocket

The image digests were checked against the official image manifests on 2026-08-06. Recheck them before an upgrade; do not replace them with `latest`.

## Install order

1. Back up Jenkins Home and restore it into an isolated namespace.
2. Create the `jenkins-admin` Secret and the existing `jenkins-home` PVC.
3. Set `CI_NAMESPACE` and `JENKINS_HOST`, then run `render-values.sh`.
4. Install `deployer-rbac.yaml.tpl` after replacing `APP_NAMESPACE`.
5. Install Chart `5.9.49` with the generated `values.yaml`.
6. Create a GitHub Organization Folder or Multibranch Pipeline. Git Parameter is only for old jobs.

The controller service account can provision build Pods but cannot read Kubernetes Secrets. Ordinary build Pods use `jenkins-build` without an API token. The Java deployment Pod mounts the GHCR config only in Kaniko and a short-lived projected Kubernetes token only in Helm. The deployer role intentionally has no Secret permissions and Helm uses ConfigMaps for release storage.

## Backup and restore rehearsal

Stop new builds, record the installed Chart and plugin versions, and create a storage-level snapshot of the Jenkins Home volume. Restore that snapshot to a different PVC and isolated namespace, then verify login, credentials metadata, shared-library loading, Multibranch discovery, a Java build, and a controller restart. An archive command alone is not considered a completed rehearsal.

Do not upgrade the production release until the restored controller passes those checks. Keep the old release values, plugin files, image digests, and volume snapshot together so rollback does not mix versions.
