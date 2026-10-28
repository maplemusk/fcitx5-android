# fcitx5-android 构建与签名备忘（Windows）

> 记录时间：2026-10-26
> 分支：`feature/font-choice`　commit：`4bf7cb28`
> 本文件是操作备忘，不参与构建，可直接删除或移出仓库。

---

## 1. 环境清单

| 项 | 值 | 来源 |
|---|---|---|
| 项目路径 | `C:\Users\John\Downloads\fcitx5-android-fonts` | — |
| Android SDK | `D:\App\SDK` | `local.properties` 的 `sdk.dir`（**不要提交**） |
| build-tools | `36.1.0`（另有 34.0.0 / 35.0.0 / 36.0.0） | `Versions.defaultBuildTools` |
| CMake / NDK | `3.31.6` / `28.0.13004108` | `Versions.kt` |
| Gradle / AGP / Kotlin | `9.6.1` / `9.4.1` / `2.4.10` | `gradle-wrapper.properties`、`libs.versions.toml` |
| JDK | Temurin `17.0.20.1`（`C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`） | `java -version` |
| compileSdk / minSdk / targetSdk | 36 / **23** / 36 | `Versions.kt` |
| 版本名 / 版本号 | `0.1.3` / 由 ABI 计算（`calculateVersionCode`） | `Versions.kt` |
| MSYS2 | `C:\msys64`，使用 **UCRT64** 环境 | `pacman -Q` |
| 密钥库 | `C:\Users\John\keys\fcitx5-release.jks`（PKCS12, RSA 4096, 10000 天） | 自建 |
| 密钥别名 | `fcitx5` | — |
| 证书 SHA-256 | `61:06:4E:F1:F7:CF:BB:C2:A9:68:E2:70:67:36:AF:B6:1A:7E:52:4E:8B:8B:B9:86:D9:EB:5F:DE:72:26:73:2A` | `apksigner verify --print-certs` |
| 签名输出目录 | `C:\Users\John\Downloads\fcitx5-signed` | 手动签名用 |

---

## 2. 一次性准备

### 2.1 拉取子模块

```bat
git -c http.sslBackend=openssl -c http.proxy=http://192.168.3.91:1082 ^
    -c https.proxy=http://192.168.3.91:1082 ^
    submodule update --init --recursive
```

- `http.sslBackend=openssl` 是必需的：本机 Git 的 `schannel` 后端会报
  `AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS (0x8009030E)`，与代理无关（不走代理也一样报）。
- 代理用 `-c` 临时传入，不会写进 `~/.gitconfig`，用完即失效。
- 共 18 个配置项 + 1 个嵌套（`anthy-unicode`），全部到位后 `git submodule status` 每行行首为空格（`-` 表示未初始化，`+` 表示提交不符）。

### 2.2 Gettext（CMake 报 `Could NOT find Gettext`）

在 **MSYS2 UCRT64** 终端执行：

```bash
pacman -S --noconfirm mingw-w64-ucrt-x86_64-gettext mingw-w64-ucrt-x86_64-extra-cmake-modules
```

然后把 `C:\msys64\ucrt64\bin` 加入**系统** PATH（管理员 PowerShell）：

```powershell
$p = [Environment]::GetEnvironmentVariable('Path','Machine')
if ($p -split ';' -notcontains 'C:\msys64\ucrt64\bin') {
    [Environment]::SetEnvironmentVariable('Path', $p.TrimEnd(';') + ';C:\msys64\ucrt64\bin', 'Machine')
}
```

验证（**新开**终端）：

```cmd
msgfmt --version
msgmerge --version
```

> 改完 PATH 后 CMake 仍可能沿用旧缓存（`find_package` 结果会被缓存成 NOTFOUND）。
> 解决办法：`gradlew.bat cleanCxxIntermediates`（项目自带任务，删除各模块的 `.cxx`），或 `gradlew.bat clean`。

### 2.3 创建密钥库（只做一次）

```powershell
$KEYSTORE = "C:\Users\John\keys\fcitx5-release.jks"
& "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\keytool.exe" -genkeypair -v `
  -keystore $KEYSTORE -storetype PKCS12 -alias fcitx5 `
  -keyalg RSA -keysize 4096 -validity 10000 `
  -dname "CN=fcitx5-dev, OU=dev, O=dev, C=CN"
```

- PKCS12 下 **store 口令 = key 口令**，`keytool` 只提示两次即可。
- 口令请用纯 ASCII；含中文/全角符号会叠加控制台编码问题。
- **这个文件丢了就无法再覆盖更新应用**，务必离线备份，且不要提交进仓库。

---

## 3. 构建

### 3.1 直接构建（产物未签名）

```bat
gradlew.bat assembleRelease
```

产物（每个 4 个 ABI，`isUniversalApk = false`）：

```
app\build\outputs\apk\release\org.fcitx.fcitx5.android-<git短哈希>-<abi>-release-unsigned.apk
plugin\rime\build\outputs\apk\release\org.fcitx.fcitx5.android.plugin.rime-<git短哈希>-<abi>-release-unsigned.apk
```

- `<abi>` ∈ `arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64`
- release 变体默认 `isMinifyEnabled = true`、`isShrinkResources = true`
- debug 变体 `applicationIdSuffix = ".debug"`，即包名为 `org.fcitx.fcitx5.android.debug`，与 release 互不通用

### 3.2 内置签名构建（推荐，无需手工 apksigner）

项目已内置签名支持，见 `build-logic/convention/src/main/kotlin/ProjectExtensions.kt` 的
`fromProjectEnv()`：release 构建类型会自动挂载 `signingConfig`。
配置来源优先级：**环境变量 > Gradle 属性**（`Utils.kt` 的 `epn()`）。

```powershell
$env:SIGN_KEY_FILE  = "C:\Users\John\keys\fcitx5-release.jks"
$env:SIGN_KEY_ALIAS = "fcitx5"
$env:SIGN_KEY_PWD   = Read-Host "Keystore password"    # 仅存于当前会话

gradlew.bat assembleRelease

Remove-Item Env:SIGN_KEY_FILE, Env:SIGN_KEY_ALIAS, Env:SIGN_KEY_PWD
```

| 环境变量 | Gradle 属性 | 含义 |
|---|---|---|
| `SIGN_KEY_FILE` | `-PsignKeyFile=` | 密钥库路径（也可用 `SIGN_KEY_BASE64` 传 base64） |
| `SIGN_KEY_PWD` | `-PsignKeyPwd=` | 口令（store 与 key 共用） |
| `SIGN_KEY_ALIAS` | `-PsignKeyAlias=` | 别名 |

产物**不再带 `-unsigned` 后缀**，APK 已对齐并签名（AGP 自动处理 zipalign 与 v1/v2/v3）。

> 不要把口令写进 `gradle.properties` 或命令行 `-P`（会进 shell 历史），用环境变量。

### 3.3 只构建需要的 ABI（缩短构建时间）

```bat
gradlew.bat assembleRelease -PbuildABI=arm64-v8a
```

多个 ABI 用逗号分隔：`-PbuildABI=arm64-v8a,x86_64`（也可用环境变量 `BUILD_ABI`）。

---

## 4. 手动签名（已有 unsigned 产物时）

适用于「产物已经构建好、不想重新编译」的场景。**顺序不能反：先 zipalign，再 apksigner**（签名后对齐会破坏签名）。

```powershell
$SDK      = "D:\App\SDK"
$BT       = "$SDK\build-tools\36.1.0"
$KEYSTORE = "C:\Users\John\keys\fcitx5-release.jks"
$ALIAS    = "fcitx5"
$OUT      = "C:\Users\John\Downloads\fcitx5-signed"
$KT       = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\keytool.exe"
New-Item -ItemType Directory -Force $OUT | Out-Null

# 口令：输入一次，转成环境变量（避免交互提示读到空串）
$sec = Read-Host "Keystore password" -AsSecureString
$env:KS_PASS = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
                 [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))

# 先确认口令正确
& $KT -list -keystore $KEYSTORE -storetype PKCS12 "-storepass:env" KS_PASS
if ($LASTEXITCODE -ne 0) { Write-Error "口令错误，停止"; return }

Set-Location C:\Users\John\Downloads\fcitx5-android-fonts
$fail = 0
foreach ($f in Get-ChildItem app\build\outputs\apk\release, plugin\rime\build\outputs\apk\release -Filter *-unsigned.apk) {
    $name    = $f.BaseName -replace '-unsigned$', ''
    $aligned = Join-Path $OUT "$name-aligned.apk"
    $signed  = Join-Path $OUT "$name-signed.apk"

    & "$BT\zipalign.exe" -p -f 4 $f.FullName $aligned | Out-Null
    if ($LASTEXITCODE -ne 0) { "FAIL(zipalign) $name"; $fail++; continue }

    & "$BT\apksigner.bat" sign --ks $KEYSTORE --ks-key-alias $ALIAS `
        --ks-pass env:KS_PASS --key-pass env:KS_PASS `
        --min-sdk-version 23 --out $signed $aligned | Out-Null
    if ($LASTEXITCODE -ne 0) { "FAIL(sign) $name"; $fail++; continue }

    "OK  $name"
}
"failed = $fail"
Remove-Item Env:KS_PASS
```

关键点：

- `--min-sdk-version 23`：`minSdk = 23`，该 API 级别只认 **v1 (JAR) 签名**，不写可能缺少 v1。
- `--ks-pass env:VAR` / `--key-pass env:VAR`：**不要**用 `pass:明文`，特殊字符会被 PowerShell 吃掉。
- 不要用 `ForEach-Object` 管道包住 `apksigner.bat`：PowerShell 5.1 对管道内原生程序的 stdin 处理会出问题，交互式口令提示会拿到空串，报
  `keystore password was incorrect ... BadPaddingException`。用 `foreach` 语句块。

---

## 5. 验证（必做）

```powershell
$BT = "D:\App\SDK\build-tools\36.1.0"
function CertSha256($apk) {
    (& "$BT\apksigner.bat" verify --print-certs $apk 2>&1 |
        Select-String 'SHA-256 digest' | Select-Object -First 1) -replace '^.*SHA-256 digest:\s*', ''
}

$app  = CertSha256 "$OUT\org.fcitx.fcitx5.android-4bf7cb28-arm64-v8a-release-signed.apk"
$rime = CertSha256 "$OUT\org.fcitx.fcitx5.android.plugin.rime-4bf7cb28-arm64-v8a-release-signed.apk"
"app : $app"; "rime: $rime"
if ($app -and ($app -eq $rime)) { 'MATCH' } else { 'MISMATCH' }
```

预期（本次已实测通过）：

- 两者 SHA-256 均为 `61064ef1f7cfbbc2a968e2706736afb61a7e524e8b8bb986d9eb5fde7226732a` → `MATCH`
- `apksigner verify --verbose` 显示：

```
Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Signer #1 certificate DN: CN=fcitx5-dev, OU=dev, O=dev, C=CN
Signer #1 key algorithm: RSA
Signer #1 key size (bits): 4096
```

---

## 6. 安装

```bat
adb install -r "...\org.fcitx.fcitx5.android-<hash>-arm64-v8a-release-signed.apk"
adb install -r "...\org.fcitx.fcitx5.android.plugin.rime-<hash>-arm64-v8a-release-signed.apk"
```

- **先装主程序，再装插件**。
- APK 已按 ABI 拆分，只能装设备对应的那个（手机通常是 `arm64-v8a`）。
- 若设备上已有官方签名版本，会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，必须先
  `adb uninstall org.fcitx.fcitx5.android`——**会清空应用数据**（输入方案、配置全部丢失），操作前先备份。

---

## 7. 原理：为什么插件必须与主程序同证书

不是应用层代码在比对签名，而是 **Android 权限系统**在拦：

`app/src/main/AndroidManifest.xml` 中声明了两个 `protectionLevel="signature"` 的权限：

```xml
<permission android:name="${applicationId}.permission.IPC"    android:protectionLevel="signature" />
<permission android:name="${applicationId}.permission.PLUGIN" android:protectionLevel="signature" />
```

插件通过 `lib:plugin-base` 走 IPC 与主程序通信，必须持有这两个权限；而 `signature` 级别权限**只授予与声明方同证书签名的应用**。
因此签名不一致时插件装得上、但连不通主程序，且没有开关可以绕过。

结论：**主程序与所有插件必须用同一个密钥库、同一别名签名**。

---

## 8. 故障排查

| 症状 | 原因 | 处理 |
|---|---|---|
| `Could NOT find Gettext (missing: GETTEXT_MSGMERGE_EXECUTABLE ...)` | UCRT64 的 gettext 未装，或 PATH 未生效，或 CMake 缓存了 NOTFOUND | 装 `mingw-w64-ucrt-x86_64-gettext`；系统 PATH 加 `C:\msys64\ucrt64\bin`；`gradlew.bat cleanCxxIntermediates` 后重建 |
| `keystore password was incorrect ... BadPaddingException` | 口令打错；或 apksigner 的交互提示读到了空串 | 先用 `keytool -list -storepass:env` 验证口令；签名时改用 `--ks-pass env:VAR --key-pass env:VAR` |
| `apksigner: command not found` | build-tools 不在 PATH | 用完整路径 `D:\App\SDK\build-tools\36.1.0\apksigner.bat` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 与已安装版本签名不同 | `adb uninstall <包名>` 后重装（会丢数据） |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | 装了错误 ABI 的 APK | 换对应 ABI 的包 |
| 插件装了但输入法列表里没有 / 无法加载 | 证书与主程序不一致；或先装了插件 | 核对 §5 的 SHA-256；卸载后按「先 app 后插件」重装 |
| `apksigner verify` 里 v1 为 false | 手动签名时漏了 `--min-sdk-version 23` | 重新签名并显式指定 |
| `git: schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS` | 本机 schannel 后端不可用（与代理无关） | 任意 git 网络命令加 `-c http.sslBackend=openssl` |
| `sh.exe: couldn't create signal pipe, Win32 error 5` | 仅在 **DSH 沙箱**内运行 `git submodule` / MSYS2 `bash.exe` 时出现（沙箱禁止命名管道） | 换普通终端执行，或用 `git submodule--helper update --init --recursive` 绕过 shell 脚本 |
| Gradle 找不到 SDK | `local.properties` 缺失 | 在项目根建 `local.properties`，内容 `sdk.dir=D:\\App\\SDK`（此文件不入库） |

---

## 9. 安全与维护

- 密钥库与口令：**离线备份**，不提交、不外发。若本仓库纳入版本控制，请自行把 `*.jks` / `*.keystore` / `keystore.properties` 加入 `.gitignore`。
- 命令行里不写明文口令；用 `Read-Host -AsSecureString` 或环境变量，用完 `Remove-Item Env:...`。
- 定期确认证书未变：`apksigner verify --print-certs` 的 SHA-256 应始终等于 §1 表中值。
- 换密钥 = 换应用身份：老用户无法覆盖升级，只能卸载重装。
