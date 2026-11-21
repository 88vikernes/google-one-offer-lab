# Google One Offer Lab

**Reading subscription offers that the Google One UI never renders — through Play Billing, on a rooted Android emulator.**

> Languages: **English (this file)** · [Português (BR)](README.pt-BR.md) · [中文](README.zh-CN.md)
> Tutorial: [docs/TUTORIAL.md](docs/TUTORIAL.md) · [PT-BR](docs/TUTORIAL.pt-BR.md) · [中文](docs/TUTORIAL.zh-CN.md)

---

## What this is

Google Play's `BillingClient.queryProductDetailsAsync()` returns **every** offer attached to a subscription product. The Google One app renders exactly one of them. The rest — free trials, intro prices, regional variants — stay invisible.

This repository documents a reproducible way to read that full list, and how to open Play's checkout for a specific offer. It is a **lab notebook**, not a product: no bot, no account farm, no automation of purchases.

Two operations, deliberately separated:

| Operation | What it does | Risk |
|---|---|---|
| **Query** | asks Play which offers exist for a list of SKUs, logs every pricing phase | none — read-only |
| **Launch checkout** | opens Play's purchase sheet for one `offerToken` | consumes eligibility on some SKUs |

The checkout is never confirmed by code. A human confirms it, with a payment method already on file.

---

## Why it is not trivial

Nothing here is a "hidden API". The obstacle is architectural:

1. Billing is **bound to the calling package**. A third-party app cannot query Google One's SKUs.
2. Play validates the **caller's signature**. Re-signing Google One to inject code makes every query fail with `code=2` (`An internal error occurred`), and GMS logs `GoogleCertificatesRslt: not allowed`.
3. Therefore the only path is to run code **inside the genuine, Google-signed app** — which requires root (`frida-server` needs `ptrace` on another UID).

**No root, no injection. No original signature, no billing.** There is no shortcut around both.

---

## Quick start

```bash
# 0) host side: frida must be 16.x (17.x breaks the Java bridge on these setups)
pip install frida==16.7.19 frida-tools==13.7.1

# 1) rooted emulator with ADB enabled (tested: LDPlayer 14 / Android 14 x86_64)
adb connect 127.0.0.1:5555
adb -s 127.0.0.1:5555 shell "su -c id"     # expect uid=0(root)

# 2) push and start frida-server (match the device ABI)
adb -s 127.0.0.1:5555 push frida-server /data/local/tmp/frida-server
adb -s 127.0.0.1:5555 shell "su -c 'chmod 755 /data/local/tmp/frida-server'"
adb -s 127.0.0.1:5555 shell "su -c 'nohup /data/local/tmp/frida-server -D >/dev/null 2>&1 &'"
adb -s 127.0.0.1:5555 forward tcp:27042 tcp:27042

# 3) build the probe dex (billing 5.2.1 + our Java code)
bash scripts/build-dex.sh
adb -s 127.0.0.1:5555 push out/classes.dex /data/local/tmp/probe.dex
adb -s 127.0.0.1:5555 shell "su -c 'chmod 444 /data/local/tmp/probe.dex'"   # Android 10+ refuses writable dex

# 4) open Google One and query
adb -s 127.0.0.1:5555 shell "am start -n com.google.android.apps.subscriptions.red/.LauncherActivity"
PID=$(adb -s 127.0.0.1:5555 shell "pidof com.google.android.apps.subscriptions.red" | tr -d '\r')
frida -H 127.0.0.1:27042 -p $PID -l scripts/probe_run.js
adb -s 127.0.0.1:5555 logcat -s ENIPROBE
```

Full walkthrough, including every trap we hit: **[docs/TUTORIAL.md](docs/TUTORIAL.md)**.

---

## Sample output (one region, one account)

```
Q[g1.2tb.ai.subs_variant] code=0 n=1
  PROD g1.2tb.ai.subs_variant | Google AI Pro (5 TB)
  offers=5
    fase[0] Grátis       micros=0           periodo=P1Y  ciclos=1  modo=2   ← 12-month trial
    fase[1] R$ 96,99     micros=96990000    periodo=P1M  ciclos=0  modo=1
Q[g1.ai.ultra.3months_ip_90p.acq] code=0 n=1
  PROD g1.ai.ultra.3months_ip_90p.acq | Google AI Ultra (30 TB)
  offers=2
    fase[0] R$ 99,99     micros=99990000    periodo=P1M  ciclos=3  modo=2   ← 3-month intro price
    fase[1] R$ 999,90    micros=999900000   periodo=P1M  ciclos=0  modo=1
```

Reading the phases:

| Field | Meaning |
|---|---|
| `priceAmountMicros == 0` | free phase (trial) |
| `billingCycleCount == N` | N cycles charged in that phase |
| `billingCycleCount == 0` | infinite recurring |
| `recurrenceMode == 1` | `INFINITE_RECURRING` |
| `recurrenceMode == 2` | `FINITE_RECURRING` (trial or intro price) |
| `basePlanId` + `offerId` | identifies the offer; `offerToken` is what checkout needs |

---

## SKU families worth probing

```
g1.<size>                              g1.20tb, g1.30tb, g1.2tb
g1.play.<size>                         g1.play.20tb          (what the app UI links to)
g1.<size>.ai[.subs_variant]            g1.2tb.ai.subs_variant ← variant that carries trials
g1.<size>.<N>months_eft                g1.2tb.12months_eft
g1.<product>.<N>months_ip_<P>p[.acq]   g1.ai.ultra.3months_ip_90p.acq ← intro price
```

`eft` = extended free trial · `ip` = intro price · `.acq` = acquisition variant (often the only one that resolves).

The same product can appear under several IDs with **identical display names and different prices** — always compare by `basePlanId` + pricing phases, never by name.

A ready-made candidate pool lives in `src/candidates.txt`.

---

## Repository layout

```
README.md · README.pt-BR.md · README.zh-CN.md
docs/TUTORIAL.md · docs/TUTORIAL.pt-BR.md · docs/TUTORIAL.zh-CN.md
src/Probe.java            # query + activatePromo (the whole engine)
src/candidates.txt        # ~70 SKU candidates
scripts/probe_run.js      # inject → list offers
scripts/promo.js          # inject → open checkout for one offer
scripts/build-dex.sh      # javac + d8 → out/classes.dex
LICENSE                   # MIT
```

---

## Costs, risks, honesty

- **This violates the Google Play Terms of Service.** Accounts can be suspended. Use a disposable one.
- **A payment method is required** even for a free trial. A low-limit virtual card is the sane choice.
- **Auto-renewal is the real trap**, not the checkout: a 3-month intro price becomes full price afterwards. Cancel in *Play → avatar → Payments & subscriptions → Subscriptions*.
- **Eligibility is per account**: on many SKUs, `launchBillingFlow` returning `code=0` burns the trial even if the sheet is dismissed. Query first, decide after.
- Everything is **client-side**: no server data is touched, no exploit against Google infrastructure. It reads what the API already returns and opens the same sheet the app would open.
- The people distributing similar tools commercially are usually selling **pre-made accounts**, not software. The code is the honest part; the ecosystem around it is not.

---

## License

MIT — see [LICENSE](LICENSE). No warranty. Use on accounts and devices you own.
