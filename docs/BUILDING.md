# 构建、测试与签名

## 环境

| 工具 | 版本 |
| --- | --- |
| JDK | 17 |
| Android SDK | Platform 35，Build Tools 35.0.0 |
| Gradle | 8.13，通过仓库 Wrapper 使用 |
| Android Gradle Plugin | 8.9.2 |
| Kotlin | 2.1.20 |

可以用 Android Studio 打开仓库根目录。首次构建需要下载工具和依赖。
包名为 `cn.liuheo.twofauth`，最低支持 Android API 26。
版本从 `0.1.0`（`versionCode = 1`）开始。此包名作为新应用安装，不继承旧包名应用的连接信息。

## 测试与 Debug 构建

Windows：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

macOS / Linux：

```sh
chmod +x gradlew
./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。
单元测试和 Lint 报告在 `app/build/reports/`。

连接 Android 设备或启动模拟器后，可执行 `connectedDebugAndroidTest` 运行 Android 测试。
测试使用合成账号与图标样本，不需要真实 Token。界面截图测试会临时取消截图保护；这一行为只在独立测试包中存在。

自动测试不代替真实 API 和设备安全流程的验收。发布前还需在真机验证服务连接、账号增删改查、扫码、生物识别解锁、截图保护及覆盖升级。

## Release 构建

Release 关闭调试，启用 R8 代码压缩和资源裁剪：

```sh
./gradlew lintRelease assembleRelease
```

Windows 使用 `gradlew.bat` 执行相同任务。
未提供签名配置时生成 `app/build/outputs/apk/release/app-release-unsigned.apk`，不能直接安装。

签名方式：

1. 使用 Android Studio 的 **Generate Signed Bundle / APK**，选择自己的长期发行密钥。
2. 命令行构建时，通过环境变量提供以下配置，再执行 `assembleRelease`。

| 环境变量 | 用途 |
| --- | --- |
| `POCKET_RELEASE_KEYSTORE` | Keystore 文件路径 |
| `POCKET_RELEASE_STORE_PASSWORD` | Keystore 密码 |
| `POCKET_RELEASE_KEY_ALIAS` | 签名密钥别名 |
| `POCKET_RELEASE_KEY_PASSWORD` | 签名密钥密码 |

不要把密钥或密码提交到仓库、写进构建脚本或打印到日志。CI 中应使用受保护的 Secrets，并限制发布工作流权限。

## 升级与发行

- 相同包名和签名证书才能覆盖升级。不同机器自行生成的 Debug 密钥不保证相同。
- 更换签名通常需要卸载旧应用，会清除本机连接信息；应先确认能够重新取得服务器地址和 Token。
- 卸载应用不删除服务器账号，但也不撤销 Token；撤销访问需在网页端操作。
- 发行密钥应离线备份并长期保管。后续版本需继续使用同一发行签名。
- 签名后使用 Android SDK 的 `apksigner verify --verbose --print-certs` 检查 APK，并在设备上验证安装及关键流程。
- `android.yml` 用于日常测试和 Debug 构建，`release.yml` 用于版本标签触发的签名发行。

## GitHub Releases 自动发行

发行工作流在推送 `v*` 标签后执行，例如版本 `0.1.0` 对应 `v0.1.0`。标签必须与 `app/build.gradle.kts` 的 `versionName` 一致，否则停止发行。包含预发布后缀的版本会标为 Pre-release。

首次使用前，在仓库 **Settings → Secrets and variables → Actions** 配置以下 Repository Secrets：

| Secret | 内容 |
| --- | --- |
| `POCKET_RELEASE_KEYSTORE_BASE64` | 长期发行 Keystore 文件的 Base64 编码 |
| `POCKET_RELEASE_STORE_PASSWORD` | Keystore 密码 |
| `POCKET_RELEASE_KEY_ALIAS` | 密钥别名 |
| `POCKET_RELEASE_KEY_PASSWORD` | 密钥密码 |

必须使用自己生成并离线备份的长期发行密钥，不要使用 Debug 密钥。Base64 不是加密，不要将编码后的密钥提交到 Git、写入 Issue 或公开日志。请通过本机工具编码后直接存入 Secret。

确认版本和提交内容后，再创建并推送对应标签；例如首次发行：

```sh
git tag -a v0.1.0 -m "Release 0.1.0"
git push origin v0.1.0
```

工作流会运行单元测试、Release Lint、签名构建及 APK 签名检查，然后创建 GitHub Release，上传 `2FAuth-Pocket-0.1.0.apk` 和 `SHA256SUMS.txt`。用户在 Releases 页可以直接下载 APK，不需要解压。缺少签名 Secrets 时工作流失败，不会发布未签名 APK 或退回 Debug 构建。

发布页只有在整个发行流程成功后才会创建。现有标签和 Release 不会被自动覆盖；修复后重试前应核对远端状态，不要移动已经公开发布的标签。后续发行须提高 `versionCode`，并继续使用同一密钥。
