# 构建说明

## 前置

- Windows + PowerShell 5.1+（脚本使用 `aapt2` / `d8` / `apksigner` 命令行）
- Android SDK：build-tools 35、platform android-35
- JDK 17（`javac`、`keytool`；`d8`/`apksigner` 需要 Java 11+）
- 一台 arm64 Android 设备（用于导出 Node 运行时与引擎），或同等架构的 Linux

仓库不含 Node 运行时（约 92 MB）与 `engine.zip`（约 140 MB），需要自行生成一次。

## 1. 生成 Node 运行时

在手机上安装 Termux（GitHub release 的 arm64 debug 版），执行：

```sh
pkg install -y nodejs-lts
bash tools/export-runtime.sh
```

把导出的目录拉到电脑，放到仓库的 `runtime/`：

```powershell
adb pull /data/data/com.termux/files/home/runtime-export .\runtime
```

需要的文件清单见 `runtime/README.md`。注意 `libnode.so` 是 `node` 改名而来，
`libbash.so` 是 `bash` 改名而来——`lib*.so` 才会被 PackageManager 当作原生库解压到可执行目录。

## 2. 生成 engine.zip

```sh
# 手机 Termux
npm install -g @deepseek-ai/dsh@latest --omit=optional --ignore-scripts
bash tools/pack-engine.sh
# 电脑
adb exec-out run-as com.termux cat /data/data/com.termux/files/home/engine.tar > engine.tar
python build/make_engine_zip.py engine.tar assets/engine.zip
```

`engine.zip` 会排除 `npm` 与 `corepack` 两个子目录，其余保持 `node_modules/` 结构。

## 3. 生成签名密钥

```powershell
powershell -ExecutionPolicy Bypass -File build/make-keystore.ps1
```

生成的 `build/debug.keystore` 已被 `.gitignore` 忽略，**不要提交**。正式发布请换成自己的密钥，
并妥善保管。

## 4. 构建

```powershell
$env:ANDROID_HOME = "C:\Users\you\AppData\Local\Android\Sdk"
powershell -ExecutionPolicy Bypass -File build/build-apk.ps1 `
    -AndroidSdk $env:ANDROID_HOME `
    -JreHome "C:\Program Files\Java\jdk-17" `
    -VersionName v0.2 -VersionCode 2
```

产物：`out/Harness-v0.2.apk`（arm64-v8a，Android 8.0+）。

## 5. 安装

```powershell
adb install -r out/Harness-v0.2.apk
```

首次启动会解压约 7.5 万个文件（1–2 分钟），之后启动不再解压。

## 常见问题

- **`javac` 报找不到 `LambdaMetafactory`**：不要用 `-bootclasspath android.jar`，用
  `-classpath`（脚本已按此处理），并确保使用 JDK 8+ 的 `javac`。
- **引擎启动报 `SELF_SIGNED_CERT_IN_CHAIN`**：`assets/cacert.pem` 没有打进 APK，或没有设置
  `SSL_CERT_FILE` / `NODE_EXTRA_CA_CERTS`。Termux 版 Node 默认从 `$PREFIX/etc/tls/cert.pem`
  读根证书，App 内没有这个路径。
- **`EADDRINUSE`**：引擎固定端口被占用。本项目的壳使用 `--port 0` 由系统分配端口，不需要固定端口。
- **WebView 里点登录没反应**：需要实现 `onCreateWindow` 处理 `window.open` 弹窗，否则登录页不会出现。
