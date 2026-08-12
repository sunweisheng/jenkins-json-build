# V3.2.0 凭据说明

凭据只保存在 Jenkins Credentials 和 Kubernetes Secret 中，不写入项目 JSON、Jenkinsfile、JCasC、Helm values、命令参数或构建日志。

## Mac Agent SSH

创建“SSH Username with private key”凭据：

- Username：Mac 上的 Jenkins Agent 专用登录用户或已授权用户
- Private Key：Jenkins 专用 Ed25519 私钥
- Passphrase：如私钥设置了密码，在 Jenkins 中单独保存

Mac 登录密码不作为长期 Jenkins 配置。公钥只加入目标用户的 `authorized_keys`，并限制文件权限。

## Apple 签名

`appleSigning` 需要三类凭据：

- P12：Secret file
- P12 密码：Secret text
- provisioning profile：每个描述文件一个 Secret file

项目 JSON 只填写凭据编号：

```json
{
  "type": "appleSigning",
  "certificateCredentialsId": "ios-p12",
  "certificatePasswordCredentialsId": "ios-p12-password",
  "provisioningProfileCredentialsIds": ["ios-appstore-profile"],
  "steps": [
    {
      "type": "xcodebuild",
      "action": "archive",
      "workspace": "App.xcworkspace",
      "scheme": "App",
      "archivePath": "build/App.xcarchive"
    }
  ]
}
```

P12、密码和描述文件由 Jenkins 临时绑定。共享类库创建独立钥匙串，结束时恢复原钥匙串列表，并删除临时钥匙串、临时状态和本次新增的描述文件。

没有真实签名材料时可以发布 V3.2.0，但必须在版本说明中标明未做真实签名验收；不得用示例凭据伪装成真实验收。

## Registry 和 Kubernetes

Registry 继续使用 `kubernetes.io/dockerconfigjson` Secret。Helm 使用短期投射的 ServiceAccount token；不要把 kubeconfig、Registry 密码或 GitHub Token放入项目 JSON。

发布前检查 Git 跟踪文件和暂存内容，确认不存在私钥、P12、mobileprovision、密码、Token、`.env` 或 Jenkins 凭据导出。
