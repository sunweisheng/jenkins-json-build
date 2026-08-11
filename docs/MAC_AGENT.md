# Mac Agent 安装说明

V3.2.0 的 iOS 和 React Native iOS 模板只在静态 Mac Agent 上运行。推荐节点配置：

- 节点名：`mac-m2-16g`
- 标签：`macos arm64 ios xcode mac-m2-16g`
- 远程目录：`/Users/sunweisheng/jenkins-agent`
- 执行器：`1`
- 使用方式：只运行匹配标签的任务
- 启动方式：SSH Launcher

## 安装工具

先从 App Store 或 Apple Developer 下载当前稳定版完整 Xcode，并安装需要的 iOS Simulator Runtime。Command Line Tools 不能代替完整 Xcode。

```bash
sudo xcode-select --switch /Applications/Xcode.app/Contents/Developer
sudo xcodebuild -license accept
xcodebuild -runFirstLaunch
```

Apple Silicon Homebrew 环境安装其余工具：

```bash
brew install openjdk@21 node@24 cocoapods watchman ruby
gem install --user-install bundler
```

实际使用版本必须记录到验收结果中：

```bash
xcodebuild -version
xcrun simctl list runtimes
/opt/homebrew/opt/openjdk@21/bin/java -version
node --version
npm --version
pod --version
bundle --version
watchman --version
```

2026-08-11 测试环境 `192.168.0.5` 的实际版本：

| 工具 | 版本 |
| --- | --- |
| Xcode | `26.6` |
| iOS Simulator Runtime | `26.5` |
| OpenJDK | `21.0.12` |
| Node.js | `24.19.0` |
| CocoaPods | `1.17.0` |
| Bundler | `4.0.16` |
| Watchman | `2026.07.27.00` |

## SSH 凭据

不要把 Mac 登录密码保存到仓库、JCasC 或脚本。创建 Jenkins 专用 Ed25519 密钥，私钥作为 Jenkins 的“SSH Username with private key”凭据，公钥加入 Mac 用户的 `~/.ssh/authorized_keys`。

Jenkins Controller 还要保存 Mac 的 SSH 主机公钥。SSH Launcher 选择已知主机文件或手工提供主机密钥，不使用“不检查主机密钥”模式。

SSH Launcher 的 Java 路径固定为：

```text
/opt/homebrew/opt/openjdk@21/bin/java
```

节点首次上线后依次验证：工具版本、iOS Simulator 可用、断开后自动重连、Jenkins 重启后自动恢复。构建期间只使用一个执行器，并监控：

```bash
memory_pressure
```

Kubernetes Agent 与 Mac Agent 的真实构建串行执行，避免 16 GB 主机同时承受 Kubernetes Pod 和 Xcode 构建压力。

测试节点 `mac-m2-16g` 已按上述名称、标签、远程目录和单执行器配置上线；工具检查、SSH 主机密钥校验、断线重连和 Jenkins Controller 重启后的自动恢复已验证。原生 iOS 与 React Native iOS 的真实构建结果记录在版本说明中。

## 源码检出前检查

iOS 项目配置中保留下面的要求：

```json
{
  "agent": {
    "type": "static",
    "label": "macos && ios",
    "requirements": {
      "os": "macos",
      "architectures": ["arm64"],
      "tools": ["xcodebuild", "xcrun"]
    }
  }
}
```

如果误选 Linux/Kubernetes 节点，流水线会在源码检出前停止。
