# AdSkip 技术说明

> 对应版本：**AdSkip 1.4.2**（versionCode 10）  
> 更新日期：2026-11-12

本文记录项目的实际技术信息：代码原创性、参考项目、依赖版本、构建环境、验证状态和产物信息。面向 GitHub 仓库的技术说明，不替代 [README.md](README.md)。

---

## 1. 基本信息

| 项目 | 值 |
| --- | --- |
| 应用名 | AdSkip |
| applicationId | `com.adship.adskip` |
| Debug applicationId | `com.adship.adskip.debug` |
| versionName / versionCode | `1.4.2` / `10` |
| minSdk | 24（Android 7.0） |
| compileSdk / targetSdk | 35 / 35 |
| 开发语言 | Java（source/target 17） |
| UI | Android Framework Views |
| 运行时第三方库 | 无 |
| AndroidX / Kotlin / Compose | 未使用 |
| 主要功能 | DNS 去广告、UDP/DoH/DoT、规则包、全 App 无障碍广告识别、带来源 App/时间的拦截记录、规则导入 |

---

## 2. 代码原创性

- 本项目的 Java 源码为独立编写，未复制、反编译或修改任何第三方 App 的源码。
- 未引入第三方 Java/Kotlin 运行时库、AAR、JAR 或 native 库。
- 参考过同类开源项目的产品思路、协议用法和规则格式，但未复制其代码。
- 内置 `blocklist.dat` 是 [StevenBlack/hosts](https://github.com/StevenBlack/hosts) 的衍生数据，按 MIT 许可署名。
- 国内规则包为独立整理，参考公开中文广告过滤列表做域名交叉核对，未整表打包其规则文件。

### 参考项目（仅列名称）

- [nineaiyu/dns-override](https://github.com/nineaiyu/dns-override)
- [pass-with-high-score/blockads-android](https://github.com/pass-with-high-score/blockads-android)
- [georgechou0517/DNSSwitch](https://github.com/georgechou0517/DNSSwitch)
- [cccoddad/SplashGuard](https://github.com/cccoddad/SplashGuard)
- [AdAway/AdAway](https://github.com/AdAway/AdAway)
- [julian-klode/dns66](https://github.com/julian-klode/dns66)
- [privacy-protection-tools/anti-AD](https://github.com/privacy-protection-tools/anti-AD)
- [AdguardTeam/AdguardFilters](https://github.com/AdguardTeam/AdguardFilters)
- [StevenBlack/hosts](https://github.com/StevenBlack/hosts)（基础域名库数据来源，MIT）

未复制任何 GPL 项目的源码或规则文本。

---

## 3. 依赖版本

### 运行时

**无第三方依赖。**

`app/build.gradle`：

```gradle
dependencies {
}
```

没有 AndroidX、Kotlin 标准库、OkHttp、Gson、Room、Compose、广告/统计/崩溃 SDK。使用的都是 Android Framework 和 Java 标准库。

### 构建期

| 组件 | 版本 |
| --- | --- |
| JDK | 18.0.2.1（本机验证；要求 JDK 17+） |
| Java source/target | 17 |
| Gradle | 8.7 |
| Android Gradle Plugin | 8.6.1 |
| compileSdk | 35 |
| targetSdk | 35 |
| minSdk | 24 |
| Build-Tools | 35.0.0 |
| Android SDK Platform | 35 |
| Gradle Wrapper | 官方 `gradle-8.7-bin.zip` |

关键 Gradle 配置：

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=false
android.nonTransitiveRClass=true
```

---

## 4. 实际构建环境

| 项 | 值 |
| --- | --- |
| 操作系统 | Windows |
| JDK | `C:\Program Files\Java\jdk-18.0.2.1` |
| Android SDK | `D:\DeepSeekH\adpuls\.toolchain\sdk` |
| Gradle | `D:\DeepSeekH\adpuls\.toolchain\gradle-8.7` |
| Gradle User Home | `D:\DeepSeekH\adpuls\.toolchain\gradle-home` |
| 构建命令 | `gradlew.bat :app:assembleRelease --no-daemon` |
| 构建结果 | `BUILD SUCCESSFUL` |

`local.properties`、`.toolchain/`、`app/build/` 均未提交到源码包；换机器构建时使用自己的 SDK 路径即可。

---

## 5. 验证记录

已完成：

- `assembleDebug`、`assembleRelease` 构建成功；
- Android Lint：0 errors；
- `DnsCodec` JVM 自测通过；
- 78,225 条域名库加载/匹配通过；
- 推荐版 129 条、激进版 43 条、白名单 33 条规则包加载与去重通过；
- hosts / AdGuard / 纯域名导入解析通过；
- 300 条记录环形缓冲测试通过；
- 广告关键字匹配（跳过/关闭/广告/更多等）测试通过；
- UDP、阿里 DoH、腾讯 DoH、阿里 DoT、腾讯 DoT 网络冒烟测试通过；
- APK v2 签名验证通过。

未完成：

- 当前构建环境没有连接 Android 真机/模拟器；
- VPN 授权与 TUN 建立、无障碍点击、日志页面、文件选择器尚未做真机运行验证；
- 不同厂商（MIUI/HyperOS/ColorOS 等）的后台限制表现尚未验证。

---

## 6. 发布状态

- 当前提供的是 **release APK**；
- 使用项目专用 release keystore 签名（非 debug keystore）；
- 证书 DN：`CN=AdSkip, OU=Mobile, O=AdSkip, L=Beijing, ST=Beijing, C=CN`；
- 证书 SHA-256：`0f55f53dfc6286d6ba5253ffb67cd361a163e1e1f2ba33e62968a76b23a59938`；
- RSA 4096 位，有效期至 2054-03-30；
- APK Signature Scheme v2；
- 包名 `com.adship.adskip`，不带 `.debug` 后缀；
- 不是 Google Play / 应用商店上架版本，属于自签名 release；
- keystore 与密码只放在 `dist/AdSkip-release-signing.zip`，**不要提交到 GitHub**。

---

## 7. 当前产物

| 文件 | 说明 |
| --- | --- |
| `dist/AdSkip-1.4.2-release.apk` | 正式 release 证书签名 APK |
| `dist/AdSkip-1.4.2-source.zip` | 完整源码、规则包、Gradle Wrapper |
| `dist/AdSkip-release-signing.zip` | release keystore、密码与签名说明（不要提交 Git） |
| `dist/SHA256SUMS.txt` | 产物 SHA-256 |

APK：

- 大小：703,150 字节（约 687 KB）
- SHA-256：`06506a00c821e1c79d06b86353f8e46c2e5ffe046c974b7ce371f879ccce5c0d`

---

## 8. 已知限制

1. DNS 只能看域名，不能看 URL 路径。
2. B站视频内嵌/服务端拼接广告无法通过 DNS 去掉；带「广告/推广」标记和关闭/三点菜单的卡片会尝试无障碍关闭，命中率取决于控件树。
3. 知乎回答页的小 X 按钮会尝试点击（9 秒冷却），但只对控件树中可见、尺寸较小的「×/关闭」有效。
4. 知乎严选没有独立域名（`yanxuan.zhihu.com` 不存在），和正文共用域名，DNS 无法单独隐藏。
5. 无障碍默认只在 App 启动后 5 秒内识别；持续扫描广告卡片需要手动开启，默认关闭，避免文件管理器等界面被误点。仍可能误判自绘控件。
6. 没有按应用排除；银行类 App 可能受 VPN 影响。
7. 仅 IPv4 DNS，客户端 TCP 53 未实现。
8. 拦截记录只保存在内存，最多 300 条。
9. 文件导入上限 4 MB。
10. Private DNS 强制 DoT 时可能绕过本地 DNS。
11. 小米/HyperOS 等厂商可能限制 `specialUse` 前台服务。

---

## 9. 许可

- 项目代码：MIT License，见 [LICENSE](LICENSE)。
- 内置域名库：StevenBlack/hosts，MIT License。
- 第三方说明：见 [NOTICE.md](NOTICE.md)。
