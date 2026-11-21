# Google One Offer Lab

**读取 Google One 界面从不渲染的订阅优惠 —— 通过 Play Billing，在已 root 的 Android 模拟器上。**

> 语言：[English](README.md) · [Português (BR)](README.pt-BR.md) · **中文（本文件）**
> 教程：[docs/TUTORIAL.zh-CN.md](docs/TUTORIAL.zh-CN.md) · [EN](docs/TUTORIAL.md) · [PT-BR](docs/TUTORIAL.pt-BR.md)

---

## 这是什么

Google Play 的 `BillingClient.queryProductDetailsAsync()` 会返回某个订阅商品**全部**的 offer。Google One 应用只渲染其中一个。其余的——免费试用、折扣引导价、地区变体——都不可见。

本仓库记录一种可复现的方法：读取完整 offer 列表，并为指定的 offer 拉起 Play 收银台。它是**实验笔记**，不是产品：没有机器人、没有账号农场、不自动下单。

两个操作，刻意分开：

| 操作 | 作用 | 风险 |
|---|---|---|
| **查询** | 询问 Play 某个 SKU 列表存在哪些 offer，输出每个定价阶段 | 无 —— 纯读取 |
| **拉起收银台** | 为某个 `offerToken` 打开 Play 购买页 | 部分 SKU 会消耗试用资格 |

代码从不确认购买。确认由人完成，且需要账号已绑定付款方式。

---

## 为什么这件事不简单

这里没有"隐藏 API"。障碍是架构性的：

1. Billing **绑定调用方包名**。第三方应用无法查询 Google One 的 SKU。
2. Play 会校验**调用方签名**。为了注入代码而重签名 Google One，会让所有查询返回 `code=2`（`An internal error occurred`），GMS 同时记录 `GoogleCertificatesRslt: not allowed`。
3. 因此唯一路径是**在 Google 原始签名的正版应用进程内**执行代码 —— 这需要 root（`frida-server` 必须对其它 UID 使用 `ptrace`）。

**没有 root，就没有注入。没有原始签名，就没有 billing。** 不存在同时绕过两者的捷径。

---

## 快速开始

```bash
# 0) 主机端：frida 必须是 16.x（17.x 在这些环境下 Java 桥会坏）
pip install frida==16.7.19 frida-tools==13.7.1

# 1) 已 root 且开启 ADB 的模拟器（实测：LDPlayer 14 / Android 14 x86_64）
adb connect 127.0.0.1:5555
adb -s 127.0.0.1:5555 shell "su -c id"     # 期望输出 uid=0(root)

# 2) 推送并启动 frida-server（注意设备 ABI）
adb -s 127.0.0.1:5555 push frida-server /data/local/tmp/frida-server
adb -s 127.0.0.1:5555 shell "su -c 'chmod 755 /data/local/tmp/frida-server'"
adb -s 127.0.0.1:5555 shell "su -c 'nohup /data/local/tmp/frida-server -D >/dev/null 2>&1 &'"
adb -s 127.0.0.1:5555 forward tcp:27042 tcp:27042

# 3) 构建 probe dex（billing 5.2.1 + 我们的 Java 代码）
bash scripts/build-dex.sh
adb -s 127.0.0.1:5555 push out/classes.dex /data/local/tmp/probe.dex
adb -s 127.0.0.1:5555 shell "su -c 'chmod 444 /data/local/tmp/probe.dex'"   # Android 10+ 拒绝可写 dex

# 4) 打开 Google One 并查询
adb -s 127.0.0.1:5555 shell "am start -n com.google.android.apps.subscriptions.red/.LauncherActivity"
PID=$(adb -s 127.0.0.1:5555 shell "pidof com.google.android.apps.subscriptions.red" | tr -d '\r')
frida -H 127.0.0.1:27042 -p $PID -l scripts/probe_run.js
adb -s 127.0.0.1:5555 logcat -s ENIPROBE
```

完整步骤与所有踩过的坑：**[docs/TUTORIAL.zh-CN.md](docs/TUTORIAL.zh-CN.md)**。

---

## 输出示例（某个地区、某个账号）

```
Q[g1.2tb.ai.subs_variant] code=0 n=1
  PROD g1.2tb.ai.subs_variant | Google AI Pro (5 TB)
  offers=5
    fase[0] Grátis       micros=0           periodo=P1Y  ciclos=1  modo=2   ← 12 个月免费试用
    fase[1] R$ 96,99     micros=96990000    periodo=P1M  ciclos=0  modo=1
Q[g1.ai.ultra.3months_ip_90p.acq] code=0 n=1
  PROD g1.ai.ultra.3months_ip_90p.acq | Google AI Ultra (30 TB)
  offers=2
    fase[0] R$ 99,99     micros=99990000    periodo=P1M  ciclos=3  modo=2   ← 3 个月折扣引导价
    fase[1] R$ 999,90    micros=999900000   periodo=P1M  ciclos=0  modo=1
```

如何阅读每个阶段：

| 字段 | 含义 |
|---|---|
| `priceAmountMicros == 0` | 免费阶段（试用） |
| `billingCycleCount == N` | 该阶段计费 N 个周期 |
| `billingCycleCount == 0` | 无限循环 |
| `recurrenceMode == 1` | `INFINITE_RECURRING` |
| `recurrenceMode == 2` | `FINITE_RECURRING`（试用或引导价） |
| `basePlanId` + `offerId` | 定位 offer；`offerToken` 是收银台需要的值 |

---

## 值得探测的 SKU 家族

```
g1.<容量>                              g1.20tb, g1.30tb, g1.2tb
g1.play.<容量>                         g1.play.20tb          （应用界面用这个）
g1.<容量>.ai[.subs_variant]            g1.2tb.ai.subs_variant ← 带试用的变体
g1.<容量>.<N>months_eft                g1.2tb.12months_eft
g1.<产品>.<N>months_ip_<P>p[.acq]      g1.ai.ultra.3months_ip_90p.acq ← 引导价
```

`eft` = 延长免费试用 · `ip` = 折扣引导价 · `.acq` = 获取变体（往往是唯一能解析的那个）。

同一个产品可能有多个 ID，**显示名称相同但价格不同** —— 永远按 `basePlanId` + 定价阶段比较，不要看名字。

候选列表见 `src/candidates.txt`。

---

## 仓库结构

```
README.md · README.pt-BR.md · README.zh-CN.md
docs/TUTORIAL.md · docs/TUTORIAL.pt-BR.md · docs/TUTORIAL.zh-CN.md
src/Probe.java            # 查询 + activatePromo（整个引擎）
src/candidates.txt        # 约 70 个候选 SKU
scripts/probe_run.js      # 注入 → 列出 offer
scripts/promo.js          # 注入 → 为某个 offer 打开收银台
scripts/build-dex.sh      # javac + d8 → out/classes.dex
LICENSE                   # MIT
```

---

## 成本、风险与实话

- **违反 Google Play 服务条款。** 账号可能被封。请用一次性账号。
- **即使免费试用也必须有付款方式。** 低额度的虚拟卡是理智选择。
- **真正的陷阱是自动续费**，不是收银台：引导价期结束后会恢复原价。取消路径：*Play → 头像 → 付款和订阅 → 订阅*。
- **资格按账号计算**：很多 SKU 上，`launchBillingFlow` 返回 `code=0` 就算没确认也会消耗试用。先查询，再决定。
- 全部是**客户端行为**：不触碰服务端数据，也不针对 Google 基础设施做攻击。它只是读取 API 本来就返回的内容，并打开应用本来会打开的同一个收银台。
- 商业分发类似工具的人，卖的通常是**成品账号**而不是软件。代码是其中诚实的一部分，围绕它的生态不是。

---

## 许可证

MIT —— 见 [LICENSE](LICENSE)。不提供任何担保。仅用于你拥有的账号与设备。
