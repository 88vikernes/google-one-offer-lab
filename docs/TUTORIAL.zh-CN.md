# 教程 — 读取 Google One 的隐藏优惠

[English](TUTORIAL.md) · [Português (BR)](TUTORIAL.pt-BR.md) · **中文（本文件）**

从干净机器到拿到 offer 列表的全过程，包含每个让我们浪费时间的坑。实测环境：**Windows 11 + LDPlayer 14（Android 14, x86_64）**，账号无任何在效订阅。

---

## 0. 需要准备

| 项目 | 说明 |
|---|---|
| 已 root 的 Android 模拟器 | LDPlayer 14、MuMu 12、用 rootAVD 处理的 AVD —— 只要有 `su` |
| 模拟器已开 ADB | LDPlayer：*设置 → 其他 → "打开连接"* |
| 从 **Play 商店**安装的 Google One | `com.google.android.apps.subscriptions.red` |
| 已在 Play 登录的 Google 账号 | **已绑定付款方式**，且当前没有 Google One 订阅 |
| 主机：Python 3.9+ | `pip install frida==16.7.19 frida-tools==13.7.1` |
| 主机：JDK 17 + `d8` | 仅用于构建我们的 dex —— 见 §3 |
| `android.jar`（API 34） | dl.google.com/android/repository 的 `platform-34-ext*.zip` |

> **账号才是稀缺资源。** 很多 SKU 只允许一次尝试。用一次性账号，别用主力号。

---

## 1. 启动模拟器

```bash
ldconsole.exe modify --index 0 --root 1 --memory 4096 --cpu 4
ldconsole.exe launch --index 0

adb connect 127.0.0.1:5555
adb devices -l
adb -s 127.0.0.1:5555 shell "su -c id"                    # uid=0(root)  ← 必须
adb -s 127.0.0.1:5555 shell getprop ro.product.cpu.abi     # x86_64 / arm64-v8a
```

如果模拟器明明开着而 `adb devices` 是空的，说明 ADB 开关没开 —— `adb connect` 会返回 `cannot connect`，或者条目停在 `offline`。

---

## 2. 在设备上运行 frida-server

必须与主机 frida 版本**完全一致**。

```bash
# 用上面看到的 ABI
curl -sL -o fs.xz https://github.com/frida/frida/releases/download/16.7.19/frida-server-16.7.19-android-x86_64.xz
python -c "import lzma,pathlib; pathlib.Path('frida-server').write_bytes(lzma.decompress(pathlib.Path('fs.xz').read_bytes()))"

adb -s 127.0.0.1:5555 push frida-server /data/local/tmp/frida-server
adb -s 127.0.0.1:5555 shell "su -c 'chmod 755 /data/local/tmp/frida-server'"
adb -s 127.0.0.1:5555 shell "su -c 'nohup /data/local/tmp/frida-server -D >/dev/null 2>&1 &'"
adb -s 127.0.0.1:5555 forward tcp:27042 tcp:27042
adb -s 127.0.0.1:5555 shell "su -c 'pidof frida-server'"
```

> Git Bash 下先 `export MSYS_NO_PATHCONV=1`，否则 `/data/local/tmp` 会被改写成 Windows 路径，`push` 会落到别的地方。

---

## 3. 构建 probe dex

我们**不用**同类仓库里流传的 `module.apk`。那个文件只是一个裸 `classes.dex`（Play Billing 5.2.1），从它实例化 `BillingClient` 会直接崩进程：

```
Fatal signal 11 ... tid (PlayBillingLibr)
#00 pc <unknown>
#01 art_quick_generic_jni_trampoline
#03 com.android.billingclient.api.zzap.zzd
```

正确做法是把**官方 AAR** 和我们自己的类一起编译：

```bash
curl -sLO https://dl.google.com/dl/android/maven2/com/android/billingclient/billing/5.2.1/billing-5.2.1.aar
unzip -o billing-5.2.1.aar classes.jar -d .

javac -source 8 -target 8 -nowarn \
      -cp "android.jar;classes.jar" -d classes src/Probe.java

java -cp r8-8.3.37.jar com.android.tools.r8.D8 \
     --min-api 29 --lib android.jar \
     --output out classes.jar classes/com/eni/*.class

adb -s 127.0.0.1:5555 push out/classes.dex /data/local/tmp/probe.dex
adb -s 127.0.0.1:5555 shell "su -c 'chmod 444 /data/local/tmp/probe.dex'"
```

`chmod 444` 不是装饰。Android 10+ 拒绝加载可写文件里的 dex：

```
java.lang.SecurityException: Writable dex file '...' is not allowed
```

（`scripts/build-dex.sh` 一次做完上面全部。）

---

## 4. 查询 offer

```bash
adb -s 127.0.0.1:5555 shell "am start -n com.google.android.apps.subscriptions.red/.LauncherActivity"
PID=$(adb -s 127.0.0.1:5555 shell "pidof com.google.android.apps.subscriptions.red" | tr -d '\r')
adb -s 127.0.0.1:5555 logcat -c
frida -H 127.0.0.1:27042 -p $PID -l scripts/probe_run.js
adb -s 127.0.0.1:5555 logcat -s ENIPROBE
```

预期的开头几行：

```
=== inicio v2 ===
setup code=0 msg=
isReady=true
feat SUBS=0/  PRICE_CHANGE=0/  PRODUCT_DETAILS=0/
Q[g1.2tb.ai.subs_variant] code=0 n=1
  PROD g1.2tb.ai.subs_variant | Google AI Pro (5 TB)
  offers=5
```

`setup code=0` 就是成败分界线：Play 的计费服务接受了来自 Google One 进程内部的连接。如果这里就是 `code=2`，几乎可以确定你面对的是**重签名**过的应用 —— 见 §7。

---

## 5. 为某个 offer 拉起收银台

```bash
frida -H 127.0.0.1:27042 -p $PID -l scripts/promo.js   # 在文件里改 SKU 和周期数
```

`promo.js` 调用 `Probe.activatePromo(activity, "<SKU>", <周期数>)`：挑出**第一个定价阶段** `billingCycleCount == 周期数` 且 `priceAmountMicros` 最低的 offer，然后在 UI 线程调用 `launchBillingFlow`。

日志结果：

```
offer id=introprice 1a fase=R$ 99,99 ciclos=3 modo=2
offer id=null       1a fase=R$ 999,90 ciclos=0 modo=1
ESCOLHIDA id=introprice
LAUNCH_PROMO code=0
```

Play 的购买页会弹出来。账号没有付款方式时会要求添加。**在人点击确认之前，不会扣任何费用。**

---

## 6. 判断什么值得买

比较定价阶段，不要看宣传语：

- `micros=0` → 免费试用。看 `billingCycleCount`/`billingPeriod` 判断时长。
- `mode=2` 且价格 > 0 → 折扣引导价。看它持续几个周期。
- **第二个**阶段才是之后一直付的价格，直到取消为止。

一个真实例子：同一个 5 TB 商品下面有 `12months-eft`（12 个月免费）、三个不同的 6 个月变体、一个 3 个月变体，以及无优惠的基础套餐。而应用界面只展示了 3 个月打折那个。

---

## 7. 排错

| 现象 | 原因 | 处理 |
|---|---|---|
| `setup code=0`，但所有查询 `code=2 "An internal error occurred"` | 应用被重签名（或 repack） | 必须在**原始签名**应用内运行 —— 靠 root，不靠 repack |
| `java.lang.SecurityException: Writable dex file` | dex 位于可写路径 | `chmod 444`，或改用 `InMemoryDexClassLoader` |
| `Fatal signal 11` 出现在 `PlayBillingLibr` 线程 | 使用了第三方的 `module.apk` | 自己编译 AAR（§3） |
| `ReferenceError: 'Java' is not defined` | frida 17 运行时没有内置 bridge | 用 frida **16.7.19** |
| `Java.perform` 时 `Fatal signal 11` 落在 `libart.so` | frida 17 bridge 与该 ART 不兼容 | 同上：16.x |
| `monkey: inaccessible or not found` | Android 14 不带 `monkey` | 用 `am start -n <pkg>/<Activity>` |
| `adb devices` 为空 | ADB 开关未开，或屏幕锁定 | 打开开关；`input keyevent 224` |
| `am start` 后 `pidof` 为空 | 应用崩了或设备状态脏 | `adb logcat` 找 `FATAL`；`adb reboot` |
| 几轮之后查询开始返回 `code=2` | 计费服务的频率/模式启发式 | 等一会儿，或减少 SKU 数量 |

---

## 8. 自律与自保

- 一个账号、一台设备、自己的卡。永远不要用别人的。
- 先查询，再决定是否拉起收银台。`launchBillingFlow` 返回 `code=0` 时，就算你关掉页面也可能已经消耗了资格。
- 只想试用的话，订阅当周就取消。用日历提醒，别靠记性。
- 让账号保持一次性的状态。被封也不影响实验 —— 技术在 API 里，不在账号里。

---

## 9. 这套方法做不到的事

- 无法凭空创造账号/地区没有的优惠。`n=0` 意味着"这个 product ID 在这里不存在"，注入再多也改不了。
- 无法跳过收银台。Play 仍在服务端校验资格；有些 offer 会在确认时失败：*"您不符合免费试用条件"*。
- 无法在重签名应用上工作。Billing 与包签名绑定 —— 这是整份文档里最重要的约束。
