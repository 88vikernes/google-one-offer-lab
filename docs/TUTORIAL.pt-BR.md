# Tutorial — lendo as ofertas escondidas do Google One

[English](TUTORIAL.md) · **Português (BR) (este arquivo)** · [中文](TUTORIAL.zh-CN.md)

Da máquina limpa até a lista de ofertas, incluindo cada armadilha que nos custou tempo. Testado em **Windows 11 + LDPlayer 14 (Android 14, x86_64)**, com conta sem assinatura ativa.

---

## 0. O que você precisa

| Item | Observação |
|---|---|
| Emulador Android com root | LDPlayer 14, MuMu 12, AVD com rootAVD — qualquer um com `su` |
| ADB ligado no emulador | LDPlayer: *Configurações → Outros → "Abrir conexão"* |
| Google One instalado **pela Play Store** | `com.google.android.apps.subscriptions.red` |
| Conta Google logada no Play | **com forma de pagamento vinculada** e sem assinatura Google One ativa |
| Host: Python 3.9+ | `pip install frida==16.7.19 frida-tools==13.7.1` |
| Host: JDK 17 + `d8` | só para gerar nosso dex — ver §3 |
| `android.jar` (API 34) | `platform-34-ext*.zip` em dl.google.com/android/repository |

> **A conta é o recurso escasso.** Vários SKUs permitem uma única tentativa. Use conta descartável, não a principal.

---

## 1. Subir o emulador

```bash
ldconsole.exe modify --index 0 --root 1 --memory 4096 --cpu 4
ldconsole.exe launch --index 0

adb connect 127.0.0.1:5555
adb devices -l
adb -s 127.0.0.1:5555 shell "su -c id"                    # uid=0(root)  ← obrigatório
adb -s 127.0.0.1:5555 shell getprop ro.product.cpu.abi     # x86_64 / arm64-v8a
```

Se o `adb devices` fica vazio com o emulador visivelmente aberto, o toggle de ADB está desligado — o `connect` responde `cannot connect` ou deixa a entrada como `offline`.

---

## 2. frida-server no dispositivo

Tem que casar **exatamente** com o frida do host.

```bash
# use o ABI que apareceu acima
curl -sL -o fs.xz https://github.com/frida/frida/releases/download/16.7.19/frida-server-16.7.19-android-x86_64.xz
python -c "import lzma,pathlib; pathlib.Path('frida-server').write_bytes(lzma.decompress(pathlib.Path('fs.xz').read_bytes()))"

adb -s 127.0.0.1:5555 push frida-server /data/local/tmp/frida-server
adb -s 127.0.0.1:5555 shell "su -c 'chmod 755 /data/local/tmp/frida-server'"
adb -s 127.0.0.1:5555 shell "su -c 'nohup /data/local/tmp/frida-server -D >/dev/null 2>&1 &'"
adb -s 127.0.0.1:5555 forward tcp:27042 tcp:27042
adb -s 127.0.0.1:5555 shell "su -c 'pidof frida-server'"
```

> No Git Bash, prefixe com `export MSYS_NO_PATHCONV=1`, senão `/data/local/tmp` é reescrito como caminho Windows e o `push` vai para lugar nenhum.

---

## 3. Gerar o dex do probe

**Não** usamos o `module.apk` que circula em repos parecidos. Aquele arquivo é um `classes.dex` solto do Play Billing 5.2.1, e instanciar `BillingClient` a partir dele derruba o processo:

```
Fatal signal 11 ... tid (PlayBillingLibr)
#00 pc <unknown>
#01 art_quick_generic_jni_trampoline
#03 com.android.billingclient.api.zzap.zzd
```

Em vez disso, compile o **AAR oficial** junto com a nossa classe:

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

`chmod 444` não é enfeite. Android 10+ recusa dex em arquivo gravável:

```
java.lang.SecurityException: Writable dex file '...' is not allowed
```

(`scripts/build-dex.sh` faz tudo isso de uma vez.)

---

## 4. Consultar as ofertas

```bash
adb -s 127.0.0.1:5555 shell "am start -n com.google.android.apps.subscriptions.red/.LauncherActivity"
PID=$(adb -s 127.0.0.1:5555 shell "pidof com.google.android.apps.subscriptions.red" | tr -d '\r')
adb -s 127.0.0.1:5555 logcat -c
frida -H 127.0.0.1:27042 -p $PID -l scripts/probe_run.js
adb -s 127.0.0.1:5555 logcat -s ENIPROBE
```

Primeiras linhas esperadas:

```
=== inicio v2 ===
setup code=0 msg=
isReady=true
feat SUBS=0/  PRICE_CHANGE=0/  PRODUCT_DETAILS=0/
Q[g1.2tb.ai.subs_variant] code=0 n=1
  PROD g1.2tb.ai.subs_variant | Google AI Pro (5 TB)
  offers=5
```

`setup code=0` é o jogo inteiro: o serviço de billing do Play aceitou uma conexão de dentro do processo do Google One. Se aqui vier `code=2`, quase certamente você está num app **reassinado** — ver §7.

---

## 5. Abrir o caixa de uma oferta

```bash
frida -H 127.0.0.1:27042 -p $PID -l scripts/promo.js   # edite SKU e ciclos dentro do arquivo
```

`promo.js` chama `Probe.activatePromo(activity, "<SKU>", <ciclos>)`, que escolhe a oferta cuja **primeira fase** tem `billingCycleCount == ciclos` e o menor `priceAmountMicros`, e chama `launchBillingFlow` na thread de UI.

Resultado no logcat:

```
offer id=introprice 1a fase=R$ 99,99 ciclos=3 modo=2
offer id=null       1a fase=R$ 999,90 ciclos=0 modo=1
ESCOLHIDA id=introprice
LAUNCH_PROMO code=0
```

A tela do Play aparece. Ela vai pedir forma de pagamento se a conta não tiver. **Nada é cobrado até uma pessoa tocar em confirmar.**

---

## 6. Decidir o que vale pagar

Compare as fases, não o texto de marketing:

- `micros=0` → teste grátis. Olhe `billingCycleCount`/`billingPeriod` para o tamanho.
- `mode=2` com preço > 0 → preço introdutório. Veja quantos ciclos dura.
- A **segunda** fase é o que você paga depois, para sempre, até cancelar.

Exemplo real de uma região: o mesmo produto de 5 TB tinha `12months-eft` (12 meses grátis), três variantes de 6 meses, uma de 3 meses e o plano base sem oferta. A tela do próprio app mostrava apenas a de 3 meses com desconto.

---

## 7. Solução de problemas

| Sintoma | Causa | Correção |
|---|---|---|
| `setup code=0` mas toda query `code=2 "An internal error occurred"` | app reassinado com chave estranha (ou repack) | rodar dentro do app **original** — root, não repack |
| `java.lang.SecurityException: Writable dex file` | dex em caminho gravável | `chmod 444` ou `InMemoryDexClassLoader` |
| `Fatal signal 11` em `PlayBillingLibr` | `module.apk` de terceiros | compilar o AAR você mesmo (§3) |
| `ReferenceError: 'Java' is not defined` | runtime frida 17 sem bridge empacotado | usar frida **16.7.19** |
| `Fatal signal 11` em `libart.so` no `Java.perform` | bridge do frida 17 nesse ART | idem: 16.x |
| `monkey: inaccessible or not found` | Android 14 não traz `monkey` | `am start -n <pkg>/<Activity>` |
| `adb devices` vazio | toggle de ADB off, ou tela bloqueada | ligar o toggle; `input keyevent 224` |
| `pidof` vazio após `am start` | app morreu ou device sujo | `adb logcat` procurando `FATAL`; `adb reboot` |
| Queries passam a retornar `code=2` após algumas bateladas | heurística de padrão/rate no serviço | esperar, ou consultar menos SKUs |

---

## 8. Etiqueta e autopreservação

- Uma conta, um aparelho, seu cartão. Nunca o de outra pessoa.
- Consulte antes de abrir o caixa. `code=0` no `launchBillingFlow` pode queimar a elegibilidade mesmo se você fechar a tela.
- Cancele a assinatura na mesma semana em que começou, se você só queria o teste. Lembrete no calendário, não na memória.
- Mantenha a conta descartável. Se ela for suspensa, o laboratório não é afetado — a técnica está na API, não na conta.

---

## 9. O que isso não faz

- Não cria oferta que a conta/região não tem. `n=0` significa "este product ID não resolve aqui" — injeção nenhuma muda isso.
- Não pula o checkout. O Play continua validando elegibilidade no servidor; algumas ofertas falham na confirmação com *"você não é elegível para o teste grátis"*.
- Não sobrevive a app reassinado. Billing é preso à assinatura do pacote. Essa é a restrição mais importante do documento inteiro.
