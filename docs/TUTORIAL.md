# Tutorial — reading Google One's hidden offers

**English** · [Português (BR)](TUTORIAL.pt-BR.md) · [中文](TUTORIAL.zh-CN.md)

This walks through the whole lab from a clean machine to the offer list, including every trap that cost us time. Tested on **Windows 11 + LDPlayer 14 (Android 14, x86_64)** with an account that had no active subscription.

---

## 0. What you need

| Item | Notes |
|---|---|
| Rooted Android emulator | LDPlayer 14, MuMu 12, AVD with rootAVD — anything with `su` |
| ADB enabled on the emulator | LDPlayer: *Settings → Others → "Open connection"* |
| Google One installed **from Play Store** | `com.google.android.apps.subscriptions.red` |
| A Google account logged into Play | **with a payment method bound** and no current Google One subscription |
| Host: Python 3.9+ | `pip install frida==16.7.19 frida-tools==13.7.1` |
| Host: JDK 17 + `d8` | only to build our dex — see §3 |
| `android.jar` (API 34) | `platform-34-ext*.zip` from dl.google.com/android/repository |

> **The account is the scarce resource.** Many SKUs allow a single attempt. Use a disposable account, not your main one.

---

## 1. Bring the emulator up

```bash
ldconsole.exe modify --index 0 --root 1 --memory 4096 --cpu 4
ldconsole.exe launch --index 0

adb connect 127.0.0.1:5555
adb devices -l
adb -s 127.0.0.1:5555 shell "su -c id"                    # uid=0(root)  ← required
adb -s 127.0.0.1:5555 shell getprop ro.product.cpu.abi     # x86_64 / arm64-v8a
```

If `adb devices` shows nothing while the emulator is visibly running, the ADB toggle is off — `adb connect` will answer `cannot connect` or leave the entry `offline`.

---

## 2. frida-server on the device

Must match the host frida **exactly** (major.minor.patch is safest).

```bash
# pick the ABI you saw above
curl -sL -o fs.xz https://github.com/frida/frida/releases/download/16.7.19/frida-server-16.7.19-android-x86_64.xz
python -c "import lzma,pathlib; pathlib.Path('frida-server').write_bytes(lzma.decompress(pathlib.Path('fs.xz').read_bytes()))"

adb -s 127.0.0.1:5555 push frida-server /data/local/tmp/frida-server
adb -s 127.0.0.1:5555 shell "su -c 'chmod 755 /data/local/tmp/frida-server'"
adb -s 127.0.0.1:5555 shell "su -c 'nohup /data/local/tmp/frida-server -D >/dev/null 2>&1 &'"
adb -s 127.0.0.1:5555 forward tcp:27042 tcp:27042
adb -s 127.0.0.1:5555 shell "su -c 'pidof frida-server'"
```

> On Git Bash, prefix commands with `export MSYS_NO_PATHCONV=1`, otherwise `/data/local/tmp` is rewritten to a Windows path and `push` lands nowhere.

---

## 3. Build the probe dex

We do **not** use the `module.apk` floating around in similar repos. That file is a bare `classes.dex` of Play Billing 5.2.1, and instantiating `BillingClient` from it crashes the process:

```
Fatal signal 11 ... tid (PlayBillingLibr)
#00 pc <unknown>
#01 art_quick_generic_jni_trampoline
#03 com.android.billingclient.api.zzap.zzd
```

Instead, compile the **official AAR** together with our own class:

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

`chmod 444` is not cosmetic. Android 10+ refuses to load a dex from a writable file:

```
java.lang.SecurityException: Writable dex file '...' is not allowed
```

(`scripts/build-dex.sh` does all of the above in one shot.)

---

## 4. Query the offers

```bash
adb -s 127.0.0.1:5555 shell "am start -n com.google.android.apps.subscriptions.red/.LauncherActivity"
PID=$(adb -s 127.0.0.1:5555 shell "pidof com.google.android.apps.subscriptions.red" | tr -d '\r')
adb -s 127.0.0.1:5555 logcat -c
frida -H 127.0.0.1:27042 -p $PID -l scripts/probe_run.js
adb -s 127.0.0.1:5555 logcat -s ENIPROBE
```

Expected first lines:

```
=== inicio v2 ===
setup code=0 msg=
isReady=true
feat SUBS=0/  PRICE_CHANGE=0/  PRODUCT_DETAILS=0/
Q[g1.2tb.ai.subs_variant] code=0 n=1
  PROD g1.2tb.ai.subs_variant | Google AI Pro (5 TB)
  offers=5
```

`setup code=0` is the whole ballgame: the Play billing service accepted a connection from inside the Google One process. If you get `code=2` here, you are almost certainly inside a **re-signed** app — see §7.

---

## 5. Open the checkout for one offer

```bash
frida -H 127.0.0.1:27042 -p $PID -l scripts/promo.js   # edit SKU + cycles inside the file
```

`promo.js` calls `Probe.activatePromo(activity, "<SKU>", <cycles>)`, which picks the offer whose **first pricing phase** has `billingCycleCount == cycles` and the lowest `priceAmountMicros`, then calls `launchBillingFlow` on the UI thread.

Result in logcat:

```
offer id=introprice 1a fase=R$ 99,99 ciclos=3 modo=2
offer id=null       1a fase=R$ 999,90 ciclos=0 modo=1
ESCOLHIDA id=introprice
LAUNCH_PROMO code=0
```

Play's sheet appears. It will ask for a payment method if the account has none. **Nothing is charged until a human taps confirm.**

---

## 6. Deciding what is worth paying for

Compare the phases, not the marketing copy:

- `micros=0` → free trial. Check `billingCycleCount`/`billingPeriod` for length.
- `mode=2` with a non-zero price → intro price. Check how many cycles it lasts.
- The **second** phase is what you pay afterwards, forever, until cancelled.

Real example from one region: the same 5 TB product had `12months-eft` (free 12 months), three different 6-month variants, a 3-month variant, and a no-offer base plan. The app's own screen showed only the 3-month discounted one.

---

## 7. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `setup code=0` but all queries `code=2 "An internal error occurred"` | app re-signed with a foreign key (or a repack) | you must run inside the **original** app — root, not repack |
| `java.lang.SecurityException: Writable dex file` | dex in a writable path | `chmod 444`, or `InMemoryDexClassLoader` |
| `Fatal signal 11` in `PlayBillingLibr` | third-party `module.apk` | compile the AAR yourself (§3) |
| `ReferenceError: 'Java' is not defined` | frida 17 runtime without the bundled bridge | use frida **16.7.19** |
| `Fatal signal 11` in `libart.so` on `Java.perform` | frida 17 bridge on this ART | same: 16.x |
| `monkey: inaccessible or not found` | Android 14 ships without `monkey` | `am start -n <pkg>/<Activity>` |
| `adb devices` empty | ADB toggle off, or screen locked | enable toggle; `input keyevent 224` |
| `pidof` empty after `am start` | app crashed or device is dirty | `adb logcat` for `FATAL`; `adb reboot` |
| Queries start returning `code=2` after a few batches | rate/pattern heuristics on the billing service | wait, or query fewer SKUs |

---

## 8. Etiquette and self-preservation

- One account, one device, your own card. Never someone else's.
- Query before you launch. `code=0` from `launchBillingFlow` can burn eligibility even if you close the sheet.
- Cancel the subscription the same week you start it, if you only wanted the trial. Calendar reminder, not memory.
- Keep the account disposable. If it gets suspended, the lab is unaffected — the technique is in the API, not in the account.

---

## 9. What this cannot do

- It cannot create an offer that the account/region does not have. `n=0` means "this product ID does not resolve here" — no amount of injecting changes that.
- It cannot bypass the checkout. Play still validates eligibility server-side; some offers will fail at confirmation with *"you are not eligible for a free trial"*.
- It cannot survive a re-signed app. Billing is bound to the package signature. This is the single most important constraint in the whole document.
