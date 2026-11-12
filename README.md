# AdSkip

**可配置 DNS 的 Android 免 Root 去广告工具**

[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)](#)
[![Version](https://img.shields.io/badge/version-1.4.2-38E8A4)](#)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![Runtime deps](https://img.shields.io/badge/runtime%20deps-0-brightgreen)](#)

本地 `VpnService` 只接管 DNS · UDP / DoH / DoT 上游 · 国内广告规则包 · 可选无障碍跳过开屏广告

[下载 APK](dist/AdSkip-1.4.2-release.apk) · [源码包](dist/AdSkip-1.4.2-source.zip) · [技术说明](PROJECT_INFO.md)

</div>

---

## 简介

AdSkip 是一个不依赖 Root 的 Android 去广告 App。

它通过系统 `VpnService` 建立一个**只接管 DNS 的分离隧道**：

- 只把虚拟 DNS 地址 `10.111.222.2/32` 加入 VPN 路由；
- 其他上网流量完全不进入 TUN，不拖慢网速，也不需要用户态 TCP/IP 栈；
- 命中广告/追踪域名时，本地返回 `0.0.0.0`（A）或 `::`（AAAA）；
- 未命中时，转发到用户选择的 **UDP DNS / DoH / DoT** 上游；
- 可选无障碍服务，以保守策略识别所有 App 的启动广告、推广卡片并尝试跳过/关闭。

> 这是 DNS 级 + 开屏点击级去广告，不是 HTTPS 中间人，不破解、不修改任何第三方 App。

---

## 功能

| 功能 | 说明 |
| --- | --- |
| 免 Root | 使用 Android 官方 `VpnService`，首次启动由系统弹窗授权 |
| DNS 可配置 | UDP 53、DoH（RFC 8484）、DoT（RFC 7858） |
| 常用预设 | 阿里、腾讯、Cloudflare、Google，也可完全自定义 |
| 广告拦截 | 内置 78,225 条基础域名规则，父域匹配子域 |
| 国内规则包 | 推荐版 129 条 / 激进版 43 条 / 核心白名单 33 条，一键载入 |
| 开屏广告跳过 | 可选无障碍服务，保守识别所有 App 的启动广告、推广卡片 |
| 拦截记录 | 最近 300 条 DNS 查询，区分拦截/放行，可清空 |
| 导入规则 | 支持 hosts、纯域名、`*.`、`||domain^`，UTF-8/UTF-16/GBK |
| 通知栏 | 常驻状态通知，可直接停止 |
| 开机自启 | 可选，受 Android 与厂商后台策略影响 |
| 零运行时依赖 | 纯 Java + 系统 API，release APK 约 687 KB |

---

## 下载与安装

### 直接安装

- [AdSkip-1.4.2-release.apk](dist/AdSkip-1.4.2-release.apk) — 正式 release 证书签名，可直接安装
- [AdSkip-1.4.2-source.zip](dist/AdSkip-1.4.2-source.zip) — 完整源码、规则包、Gradle Wrapper
- [AdSkip-release-signing.zip](dist/AdSkip-release-signing.zip) — release keystore 与签名配置
- [SHA256SUMS.txt](dist/SHA256SUMS.txt)

```bash
adb install -r dist/AdSkip-1.4.2-release.apk
```

也可以把 APK 传到手机后点击安装（需要允许“安装未知应用”）。

> 包名已改为 **`com.adship.adskip`**，这是独立新包，不会覆盖旧的 AdPass/AdPuls，需要单独安装。
>
> release keystore 和密码在 `dist/AdSkip-release-signing.zip` 中，**请立即离线备份，不要上传 GitHub**。丢失后无法给已安装用户推送同签名更新。

### 系统要求

- Android 7.0（API 24）及以上；
- 首次使用需要允许 VPN；
- Android 13+ 建议允许通知；
- 使用开屏跳过功能需要手动开启无障碍权限。

---

## 快速开始

1. 安装并打开 AdSkip。
2. 在「DNS 上游」中选择 `UDP`、`DoH` 或 `DoT`。
3. 选择预设，或输入自定义地址：
   - UDP：`223.5.5.5:53`（仅接受 IP）
   - DoH：`https://dns.alidns.com/dns-query`
   - DoT：`dns.alidns.com:853`
4. 点击「启动防护」，在系统弹窗中允许 VPN。
5. 打开「自定义规则」→「载入推荐版」→「保存规则」→ 重启防护。
6. 如果还有广告，再追加「激进版」。
7. 需要停止时，点击 App 内按钮或通知栏「停止」。

> **重要：** 请把系统「私人 DNS / Private DNS」设为“关闭”或“自动”。
> 如果系统强制使用某个 DoT 主机，DNS 可能绕过本地 VPN，导致拦截失效。

---

## DNS 上游

| 模式 | 地址示例 | 说明 |
| --- | --- | --- |
| UDP | `223.5.5.5:53` | 延迟最低，适合国内网络 |
| DoH | `https://dns.alidns.com/dns-query` | DNS over HTTPS，加密传输 |
| DoT | `dns.alidns.com:853` | DNS over TLS，加密传输 |

内置预设：

- **UDP**：阿里 `223.5.5.5`、腾讯 `119.29.29.29`、Cloudflare `1.1.1.1`、Google `8.8.8.8`
- **DoH**：AliDNS、DNSPod、Cloudflare、Google
- **DoT**：AliDNS、DNSPod、Cloudflare、Google

DoH/DoT 的域名会通过 `223.5.5.5 / 1.1.1.1 / 8.8.8.8` 做 bootstrap，避免“解析 DoH 域名又回到自己”的死循环。

---

## 规则包

在「过滤」→「自定义规则」中：

| 规则包 | 数量 | 用途 | 建议 |
| --- | --- | --- | --- |
| 推荐版 | 129 条 | 广告 SDK、广告交易平台、已确认广告子域 | 先启用 |
| 激进版 | 43 条 | 统计、归因、推送相关域名 | 推荐版不够再追加 |
| 核心白名单 | 33 条 | 微信、QQ、支付、电商、内容、推送 API | App 异常时使用 |

规则文件在仓库 [`rules/`](rules/) 目录，也可以从 App 内一键载入。

### 自定义规则语法

```text
# 注释
ads.example.com
*.tracker.example.net
||metrics.example.org^
0.0.0.0 hosts-style.example.com
```

- 一行一个域名；
- `example.com` 会匹配 `a.example.com`、`b.a.example.com`；
- 支持 `*.` 和 AdGuard 的 `||domain^`；
- 支持标准 hosts 行；
- `#` 或 `!` 开头是注释。

### 从文件导入

「自定义规则」页面支持：

- **从文件导入拦截规则**
- **从文件导入白名单**

支持 UTF-8 / UTF-16 / GBK，单文件最大 4 MB，自动去重。

导入后仍需点击「保存规则」，并重启防护。

---

## 广告自动跳过（可选无障碍）

在主页「开屏广告跳过」卡片中开启开关，然后到系统无障碍设置里启用 **AdSkip**。

**不限定 App 名单**：所有第三方 App 都会尝试识别，但采用了**保守策略**。

识别逻辑：

1. App 启动后 **5 秒**内，只点击明确的「跳过」「跳过广告」「关闭广告」「Skip」；
2. **不会**在启动阶段点击普通「关闭」，避免关掉 B站播放器、退出确认框等；
3. 页面中出现严格的「广告 / 推广 / 赞助」标记时，才在标记同一卡片范围内查找关闭按钮或「更多 / 三个点」菜单；
4. 打开「更多」菜单后，点击「不感兴趣」「我不想看」「关闭广告」「减少推荐」等选项；
5. 如果菜单里没有这些选项，会自动返回并**冷却 10 秒**，不会反复打开菜单；
6. 每个前台 App 最多执行 4 次操作，最多打开 2 次广告菜单，避免循环；
7. **默认只在 App 启动后的 5 秒内工作**，不会扫描文件管理器等普通界面；
8. 需要处理信息流里的广告卡片时，才手动打开「持续识别广告卡片」（默认关闭，可能误点）。

覆盖场景举例：

- 知乎、番茄小说、B站等启动广告；
- B站推广卡片：识别「广告」标记 + 三个点 → 菜单 → 「不感兴趣」→ 原因「我不想看」→「确定」；
- B站视频下方广告：识别广告标记后点关闭；
- 知乎回答页推广：识别小尺寸「× / 关闭」按钮，9 秒冷却，尽量只点小图标；
- 其他 App 的推广卡片、信息流广告、开屏广告。

**知乎严选：** 没有可用的独立域名（`yanxuan.zhihu.com` 不存在），它和知乎正文/推荐共用域名，DNS 无法单独隐藏；只能在出现小 X 按钮时尝试关闭，不能保证。

> Android 13+ 侧载 APK 如果提示“受限制的设置”，请到：
> **设置 → 应用 → AdSkip → 右上角 ⋮ → 允许受限制的设置**，再开启无障碍。

**注意：** 只有控件树里能看到文字/描述时才有效；自绘按钮、纯图片按钮可能识别不到。B站视频内嵌广告、番茄信息流广告仍需实际测试。无障碍服务不保存、不上传屏幕内容。

---

## 拦截记录

主页「过滤」→「拦截记录」：

- 最近 300 条 DNS 查询；
- 显示来源 App（如 B站、知乎、番茄小说）、完整日期时间和拦截/放行结果；
- 默认只看拦截记录；
- 打开「显示放行记录」可查看全部查询，方便排查 App API 是否被误拦；
- 显示时间、域名、A/AAAA 类型；
- 支持一键清空。

来源 App 优先通过 Android 10+ 的 socket UID 解析；如果系统 DNS 持有 socket，会使用无障碍前台 App 兜底；两者都拿不到时显示「系统 DNS」或「未知应用」。

记录只保存在内存中，App 进程重启后清空，不会上传。

---

## 工作原理

```text
App / 浏览器
   │  DNS 查询（UDP 53）
   ▼
Android 系统解析器
   │  使用 VPN 下发的 DNS：10.111.222.2
   ▼
本地 TUN（只路由 10.111.222.2/32）
   │
   ├─ 命中广告域名 ──► 本地构造 0.0.0.0 / :: 应答
   │
   └─ 未命中 ──► UDP / DoH / DoT 上游
                    │
                    ▼
              真实 DNS 应答
```

关键点：

- `addRoute("10.111.222.2", 32)` 让非 DNS 流量完全不进 VPN；
- 上游 socket 调用 `VpnService.protect()`，确保走物理网络；
- 响应 IP/UDP 校验和、端口交换、DNS 头部在本地构造；
- DoH/DoT 使用 bootstrap 直连，避免解析回环。

---

## 构建

### 环境要求

| 组件 | 版本 |
| --- | --- |
| JDK | 17+（本机用 18.0.2.1 验证） |
| Gradle | 8.7（Wrapper 自动下载） |
| Android Gradle Plugin | 8.6.1 |
| compileSdk / targetSdk | 35 / 35 |
| minSdk | 24 |
| Build-Tools | 35.0.0 |
| 运行时第三方库 | 无 |
| AndroidX / Kotlin | 未使用 |

### Windows

```bat
set JAVA_HOME=C:\Program Files\Java\jdk-18.0.2.1
set ANDROID_HOME=D:\path\to\android-sdk
gradlew.bat assembleDebug
```

### Linux / macOS

```bash
export JAVA_HOME=/path/to/jdk-17-or-18
export ANDROID_HOME=/path/to/android-sdk
chmod +x gradlew
./gradlew assembleDebug
```

产物：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 正式签名打包

仓库根目录的 `keystore.properties` 指向 release keystore（该文件和 `.toolchain/` 均不会提交到 Git）：

```bat
gradlew.bat assembleRelease
```

产物：

```text
app/build/outputs/apk/release/app-release.apk
```

> release APK 使用项目专用 release 证书签名；正式发布前请备份 `adskip-release.jks` 和密码。

### Android Studio

直接用 Android Studio 打开项目根目录，等待 Gradle Sync 完成后点击 Run。

### GitHub Actions

[`.github/workflows/build.yml`](.github/workflows/build.yml) 会在 push / PR / 手动触发时构建 debug APK，并上传为 Actions Artifact。

> 国内下载 Gradle 较慢时，可临时把 `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 换成腾讯镜像，构建完成后再改回官方地址。

---

## 项目结构

```text
app/src/main/java/com/adship/adskip/
├── MainActivity.java        # 主界面、VPN 授权、配置、无障碍入口
├── RulesActivity.java       # 自定义规则、规则包、文件导入
├── LogsActivity.java        # 拦截/放行记录
├── DnsVpnService.java       # VpnService、TUN 读写、IPv4/UDP 应答
├── DnsCodec.java            # DNS 报文解析与应答构造
├── DnsUpstream.java         # UDP / DoH / DoT / TLS / bootstrap
├── Blocklist.java           # hosts / 域名规则加载与父域匹配
├── RulePacks.java           # 内置规则包与文本导入
├── AdSkipService.java       # 可选无障碍开屏跳过
├── DnsConfig.java           # 配置、地址校验
├── FilterStats.java         # 计数 + 300 条记录缓冲
└── BootReceiver.java        # 可选开机自启
```

---

## 验证状态

- `assembleDebug` / `assembleRelease`：**BUILD SUCCESSFUL**
- Android Lint：**0 errors**
- `DnsCodec` 自测：通过
- 78,225 条域名库加载/匹配：通过
- 国内规则包 129 / 43 / 33 条加载去重：通过
- hosts / AdGuard / 纯域名导入：通过
- 300 条记录环形缓冲：通过
- 广告关键字匹配（跳过/关闭/广告/更多等）：通过
- UDP、阿里 DoH、腾讯 DoH、阿里 DoT、腾讯 DoT 网络冒烟测试：通过
- Cloudflare DoH 在当前网络超时（网络可达性问题，不是代码路径问题）
- APK：release 证书签名，约 687 KB

> 当前构建环境没有连接 Android 真机/模拟器，VPN、无障碍点击、日志页面和文件选择器尚未做真机运行验证。建议安装后实际测试。

详细版本、依赖、构建环境和产物校验值见 [PROJECT_INFO.md](PROJECT_INFO.md)。

---

## 已知限制

1. **只能拦截 DNS 层面的广告。** YouTube 视频内嵌广告、抖音/微信信息流中与内容同域的广告、硬编码 IP 的广告无法通过 DNS 拦截。
2. **B站视频内嵌/服务端拼接的广告**无法通过 DNS 去除；带「广告/推广」标记和关闭/三点菜单的卡片会尝试无障碍关闭，能否命中取决于 B站当前控件树。
3. **番茄小说大量广告是自有/字节系接口**，与正文同域；社区方案是“按 App 全拦截 + 白名单”，本项目暂未实现按应用路由。
4. **知乎开屏广告**走 `api.zhihu.com/commercial_api` 路径，和正文 API 同域，DNS 无法只拦路径。
5. **无障碍是保守识别**：启动阶段只点明确的跳过/关闭广告，不点普通「关闭」；推广卡片必须有广告标记。仍可能误判，且只能处理控件树中带文字/描述的按钮，自绘控件可能识别不到。
6. **没有按应用排除**，银行类 App 检测到 VPN 时可能拒绝运行。
7. **仅 IPv4 DNS**，未添加 IPv6 VPN 地址。
8. **客户端 TCP 53 未实现**；极少数超大 UDP 应答会被截断为 TC。
9. **Private DNS 可能绕过**，请设为关闭/自动。
10. **厂商后台限制**：小米/HyperOS 可能限制 `specialUse` 前台服务，需要允许自启动、关闭电池优化。

---

## 隐私

- 没有账号、统计 SDK、广告 SDK、崩溃上报。
- 规则与配置只保存在本机 `SharedPreferences`。
- 只有未被拦截的 DNS 查询会发送到你选择的上游 DNS。
- 自定义规则不会上传到任何服务器。
- 无障碍服务读取当前窗口控件，用于查找广告的跳过/关闭/更多按钮；不保存、不上传屏幕内容。
- 拦截记录只保存在内存中，最多 300 条。

---

## 参考项目

项目在设计、协议实现和规则整理过程中参考过以下开源项目，**未复制其源码**：

- [nineaiyu/dns-override](https://github.com/nineaiyu/dns-override)
- [pass-with-high-score/blockads-android](https://github.com/pass-with-high-score/blockads-android)
- [georgechou0517/DNSSwitch](https://github.com/georgechou0517/DNSSwitch)
- [cccoddad/SplashGuard](https://github.com/cccoddad/SplashGuard)
- [AdAway/AdAway](https://github.com/AdAway/AdAway)
- [julian-klode/dns66](https://github.com/julian-klode/dns66)
- [privacy-protection-tools/anti-AD](https://github.com/privacy-protection-tools/anti-AD)
- [AdguardTeam/AdguardFilters](https://github.com/AdguardTeam/AdguardFilters)

内置基础域名库来自 [StevenBlack/hosts](https://github.com/StevenBlack/hosts)（MIT）。详见 [NOTICE.md](NOTICE.md)。

---

## License

本项目代码使用 [MIT License](LICENSE)。

Android 是 Google LLC 的商标；本项目与 Google 无关联。
