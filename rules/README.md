# 国内 App 广告规则策略

这三个文件是 AdSkip 的**自定义规则包**，可以：

1. 在 App 的「自定义规则」页面点击「载入推荐版 / 追加激进版 / 载入核心白名单」直接使用；
2. 在 1.2.0+ 点击「从文件导入拦截规则 / 白名单」，直接选择 `.txt` 文件；
3. 或者把 `.txt` 文件里的内容复制到对应输入框。

## 文件说明

| 文件 | 用途 | 建议 |
| --- | --- | --- |
| `cn-ads-recommended.txt` | 第三方广告 SDK、广告交易平台、已确认的广告子域 | **先启用这个**，覆盖面广、对核心功能影响小 |
| `cn-ads-aggressive.txt` | 统计、归因、推送相关域名，以及更多广告位 | 推荐版启用后仍有广告，再追加；可能影响推送/统计 |
| `cn-allowlist.txt` | 微信、QQ、支付、电商、内容、推送等核心 API | 只在某个 App 登录/支付/加载异常时启用 |

## 推荐使用顺序

1. 打开 AdSkip →「过滤」→「自定义规则」。
2. 点击 **载入推荐版**，再点击 **保存规则**。
3. 回到主页，停止防护再重新启动（或点「重启防护」）。
4. 打开常用 App 测试：
   - 开屏广告是否减少；
   - 登录、支付、图片/视频加载是否正常；
   - 消息推送是否正常。
5. 如果广告还在，再点 **追加激进版**，保存并重启防护。
6. 如果某个 App 无法登录/支付/加载：
   - 先到 `cn-allowlist.txt` 找对应域名，点击 **载入核心白名单**；
   - 如果仍不行，把该 App 的关键 API 域名加入白名单（一行一个）。
   - **不要**把 `qq.com`、`taobao.com`、`bytedance.com` 这种大父域加入白名单，否则会把它的广告子域一起放行。

## 为什么推荐版和激进版分开

DNS 层拦截只能看域名，不能看 URL 路径或 App 内页面。很多“统计/推送”域名和广告域名共用同一套基础设施：

- `umeng.com` 既可能用于统计，也可能用于推送；
- `e.qq.com`、`ta.qq.com` 是腾讯统计/广告测量；
- `getui.com`、`jpush.cn` 是推送服务；
- `appsflyer.com`、`adjust.com` 是广告归因。

所以：

- **推荐版**只放高置信广告 SDK / 广告位，尽量不影响功能；
- **激进版**才加入统计、归因、推送相关域名，换取更高的广告命中率。

## 规则语法

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

## 已知覆盖范围

推荐版覆盖的广告 SDK / 平台包括：

- 穿山甲 / Pangle / 字节跳动
- 腾讯优量汇 / 广点通
- 百度百青藤
- 阿里妈妈 / Tanx
- 快手联盟
- 微信 / QQ 广告位
- 爱奇艺 / 优酷广告位
- 美团 / 拼多多 / 微博广告位
- AppLovin、ironSource、Unity Ads、Vungle、Chartboost、InMobi、Mintegral、Pangle、Mobvista、Liftoff、Moloco、Digital Turbine、Fyber、LoopMe、PubMatic、Criteo、Taboola、Outbrain、Amazon Ads、Google AdMob/AdX 等国际 SDK

激进版还会覆盖：

- Umeng、CNZZ、TalkingData、GrowingIO、Sensors Data
- AppsFlyer、Adjust、Branch、Kochava、Singular
- 部分厂商广告、视频/音乐/资讯广告位

## 重要限制

1. **DNS 不能去掉所有广告**。YouTube 视频内嵌广告、抖音/小红书信息流中与内容同域的广告、已经硬编码 IP 的广告，DNS 层无法区分。
2. **开屏广告**通常由广告 SDK 域名提供，推荐版命中率较高；但部分 App 会把广告素材放在自己的 CDN 域名上，无法安全拦截。
3. **拦截统计/推送域名可能导致**：消息推送延迟或收不到、App 统计不准、广告归因失败、部分活动页面异常。
4. **同一时间只能运行一个 VPN**。
5. 系统「私人 DNS」请设为关闭/自动，否则可能绕过本地 DNS。

## 数据来源与许可

规则由本项目根据公开的中文广告过滤列表和实际 DNS 记录整理、去重、分类，采用 MIT 许可。

参考来源：

- [anti-AD](https://github.com/privacy-protection-tools/anti-AD)（MIT）
- [AdBlock-CN](https://github.com/Mircc/sgmodule)
- [StevenBlack/hosts](https://github.com/StevenBlack/hosts)（MIT）
- [AdGuard Chinese Filter](https://github.com/AdguardTeam/AdguardFilters)（GPL-3.0，仅作域名事实核对，未复制规则文本）

域名本身是事实性信息；本项目没有复制上述项目的规则文件全文。
