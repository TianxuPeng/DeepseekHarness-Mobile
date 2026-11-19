# Harness for Android

把 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 的 Node 引擎与 Web 界面，
封装成一个**可独立安装的安卓 App**：不需要电脑、不需要 Termux、不需要在手机上装 Node。

App 首次启动时把内置的 Node 运行时与 Harness 引擎解压到应用私有目录，在本机回环地址启动引擎，
再用内置 WebView 打开界面。所有会话、凭证、工作区都留在手机本地。

## 特性

- **自包含 APK**：内置 Node 运行时（arm64-v8a）与 Harness 引擎，离线安装即用。
- **独立 App 界面**：WebView 外壳，有自己的图标与任务栈，顶部状态栏让位给系统。
- **账号登录**：登录弹窗在 App 内完成，支持手机号 + 验证码。
- **余额与峰谷**：标题栏右侧显示账户余额与当前峰/谷时段；余额读取平台账户钱包，登录后自动刷新（每 5 分钟）。
- **用量页**：点余额标签从右上角放大为全屏 WebView，直接打开 DeepSeek 平台的用量页。
- **动态端口**：引擎使用 `--port 0` 由系统分配端口，避免与同机其他实例冲突。

## 架构

```
APK
├── lib/arm64-v8a/          Node 运行时（node 改名 libnode.so，其余按 SONAME 保留）
├── assets/engine.zip       打包后的 node_modules（首次启动解压）
├── assets/cacert.pem       Mozilla CA 根证书（Termux 版 Node 的默认路径在此 App 内不存在）
└── classes.dex             WebView 外壳：解压、启动引擎、解析 token、显示界面
```

启动流程：解压（仅在首次）→ 启动 `libnode.so --expose-internals .../bin.js web --no-open --port 0`
→ 从 stdout 解析带 token 的地址 → WebView 打开。

## 快速开始

从 GitHub Releases 下载 `Harness-v0.2.apk`，安装到 arm64 Android 8.0+ 设备：

```bash
adb install -r Harness-v0.2.apk
```

首次启动需要 1–2 分钟解压（约 7.5 万个文件），之后打开登录页，用 DeepSeek 账号登录即可。

## 从源码构建

构建需要 Android SDK、JDK 17，以及一台 arm64 Android 设备（或 arm64 Linux）来产出 Node 运行时与
`engine.zip`——这两部分体积大，不放进仓库。

详见 [docs/BUILD.md](docs/BUILD.md)。

## 目录

```
android/                 Android 工程（manifest、Java、资源）
assets/                  字体与 CA 证书（构建时打进 APK）
build/                   构建脚本（PowerShell + Python）
tools/                   在手机上导出运行时/引擎的脚本
runtime/                 放置 Node 运行时（git 忽略，见 BUILD.md）
docs/                    构建与排错文档
```

## 隐私与安全

- 仓库**不包含**任何账号、API Key、签名密钥或用户数据。
- App 不采集、不上传任何信息；余额查询直接由手机访问 `platform.deepseek.com`。
- 登录凭证保存在应用私有目录（`files/home/.dsh/.credentials.yaml`），卸载即清除。
- 请勿把 `debug.keystore`、`engine.zip`、`runtime/` 提交到公开仓库。

## 第三方组件

| 组件 | 许可 |
|---|---|
| DeepSeek Harness（引擎与界面） | MIT |
| Node.js | MIT |
| OpenSSL / c-ares / ICU / SQLite / zlib | Apache-2.0 / MIT / Unicode / Public Domain / zlib |
| GNU bash（可选，用于命令工具） | GPLv3 |
| TeX Gyre Heros（标题字体，Helvetica 度量复刻） | GUST Font License |
| Mozilla CA 证书包 | MPL-2.0 |

如果公开分发包含 bash 的构建产物，请同时提供对应源码或书面要约以满足 GPLv3；不需要命令工具时可以
从运行时中去掉 bash。

## 声明

本项目是社区封装，与 DeepSeek 官方无隶属关系；DeepSeek、DeepSeek Harness 及相关标识归其所有者。
仅用于个人学习与自用，请遵守 DeepSeek 的服务条款。
