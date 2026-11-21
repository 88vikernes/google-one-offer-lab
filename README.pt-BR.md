# Google One Offer Lab

**Lendo ofertas de assinatura que a interface do Google One nunca mostra — pelo Play Billing, num emulador Android com root.**

> Idiomas: [English](README.md) · **Português (BR) (este arquivo)** · [中文](README.zh-CN.md)
> Tutorial: [docs/TUTORIAL.pt-BR.md](docs/TUTORIAL.pt-BR.md) · [EN](docs/TUTORIAL.md) · [中文](docs/TUTORIAL.zh-CN.md)

---

## O que é isto

O `BillingClient.queryProductDetailsAsync()` do Google Play devolve **todas** as ofertas ligadas a um produto de assinatura. O app do Google One renderiza exatamente uma. As outras — testes grátis, preços introdutórios, variantes regionais — ficam invisíveis.

Este repositório documenta uma forma reproduzível de ler a lista completa e de abrir o caixa do Play numa oferta específica. É um **caderno de laboratório**, não um produto: sem bot, sem fazenda de contas, sem compra automatizada.

Duas operações, deliberadamente separadas:

| Operação | O que faz | Risco |
|---|---|---|
| **Consulta** | pergunta ao Play quais ofertas existem para uma lista de SKUs e registra cada fase de preço | nenhum — só leitura |
| **Abrir caixa** | abre a tela de compra do Play para um `offerToken` | consome elegibilidade em alguns SKUs |

O código nunca confirma a compra. Quem confirma é uma pessoa, com forma de pagamento já cadastrada.

---

## Por que isso não é trivial

Não existe "API escondida" aqui. O obstáculo é arquitetural:

1. O billing é **vinculado ao pacote que chama**. Um app de terceiros não consulta os SKUs do Google One.
2. O Play valida a **assinatura do chamador**. Reassinar o Google One para injetar código faz toda query falhar com `code=2` (`An internal error occurred`), e o GMS registra `GoogleCertificatesRslt: not allowed`.
3. Logo, o único caminho é rodar código **dentro do app original, assinado pelo Google** — o que exige root (`frida-server` precisa de `ptrace` em outro UID).

**Sem root, sem injeção. Sem assinatura original, sem billing.** Não existe atalho que contorne as duas coisas ao mesmo tempo.

---

## Início rápido

```bash
# 0) no host: frida precisa ser 16.x (17.x quebra a ponte Java nesses ambientes)
pip install frida==16.7.19 frida-tools==13.7.1

# 1) emulador com root e ADB ligado (testado: LDPlayer 14 / Android 14 x86_64)
adb connect 127.0.0.1:5555
adb -s 127.0.0.1:5555 shell "su -c id"     # esperado: uid=0(root)

# 2) empurrar e subir o frida-server (ABI do dispositivo)
adb -s 127.0.0.1:5555 push frida-server /data/local/tmp/frida-server
adb -s 127.0.0.1:5555 shell "su -c 'chmod 755 /data/local/tmp/frida-server'"
adb -s 127.0.0.1:5555 shell "su -c 'nohup /data/local/tmp/frida-server -D >/dev/null 2>&1 &'"
adb -s 127.0.0.1:5555 forward tcp:27042 tcp:27042

# 3) gerar o dex do probe (billing 5.2.1 + nosso Java)
bash scripts/build-dex.sh
adb -s 127.0.0.1:5555 push out/classes.dex /data/local/tmp/probe.dex
adb -s 127.0.0.1:5555 shell "su -c 'chmod 444 /data/local/tmp/probe.dex'"   # Android 10+ recusa dex gravável

# 4) abrir o Google One e consultar
adb -s 127.0.0.1:5555 shell "am start -n com.google.android.apps.subscriptions.red/.LauncherActivity"
PID=$(adb -s 127.0.0.1:5555 shell "pidof com.google.android.apps.subscriptions.red" | tr -d '\r')
frida -H 127.0.0.1:27042 -p $PID -l scripts/probe_run.js
adb -s 127.0.0.1:5555 logcat -s ENIPROBE
```

Passo a passo completo, com todas as armadilhas que encontramos: **[docs/TUTORIAL.pt-BR.md](docs/TUTORIAL.pt-BR.md)**.

---

## Exemplo de saída (uma região, uma conta)

```
Q[g1.2tb.ai.subs_variant] code=0 n=1
  PROD g1.2tb.ai.subs_variant | Google AI Pro (5 TB)
  offers=5
    fase[0] Grátis       micros=0           periodo=P1Y  ciclos=1  modo=2   ← teste de 12 meses
    fase[1] R$ 96,99     micros=96990000    periodo=P1M  ciclos=0  modo=1
Q[g1.ai.ultra.3months_ip_90p.acq] code=0 n=1
  PROD g1.ai.ultra.3months_ip_90p.acq | Google AI Ultra (30 TB)
  offers=2
    fase[0] R$ 99,99     micros=99990000    periodo=P1M  ciclos=3  modo=2   ← preço introdutório de 3 meses
    fase[1] R$ 999,90    micros=999900000   periodo=P1M  ciclos=0  modo=1
```

Como ler cada fase:

| Campo | Significado |
|---|---|
| `priceAmountMicros == 0` | fase gratuita (trial) |
| `billingCycleCount == N` | N ciclos cobrados nessa fase |
| `billingCycleCount == 0` | recorrência infinita |
| `recurrenceMode == 1` | `INFINITE_RECURRING` |
| `recurrenceMode == 2` | `FINITE_RECURRING` (trial ou preço introdutório) |
| `basePlanId` + `offerId` | identifica a oferta; o `offerToken` é o que o caixa precisa |

---

## Famílias de SKU que valem sondar

```
g1.<tamanho>                            g1.20tb, g1.30tb, g1.2tb
g1.play.<tamanho>                       g1.play.20tb          (o que a UI do app usa)
g1.<tamanho>.ai[.subs_variant]          g1.2tb.ai.subs_variant ← variante que carrega trials
g1.<tamanho>.<N>months_eft              g1.2tb.12months_eft
g1.<produto>.<N>months_ip_<P>p[.acq]    g1.ai.ultra.3months_ip_90p.acq ← preço introdutório
```

`eft` = teste grátis estendido · `ip` = preço introdutório · `.acq` = variante de aquisição (muitas vezes a única que resolve).

O mesmo produto aparece com **nomes iguais e preços diferentes** — compare sempre por `basePlanId` + fases, nunca pelo nome.

Há uma lista pronta de candidatos em `src/candidates.txt`.

---

## Estrutura do repositório

```
README.md · README.pt-BR.md · README.zh-CN.md
docs/TUTORIAL.md · docs/TUTORIAL.pt-BR.md · docs/TUTORIAL.zh-CN.md
src/Probe.java            # consulta + activatePromo (o motor inteiro)
src/candidates.txt        # ~70 SKUs candidatos
scripts/probe_run.js      # injeta → lista ofertas
scripts/promo.js          # injeta → abre o caixa de uma oferta
scripts/build-dex.sh      # javac + d8 → out/classes.dex
LICENSE                   # MIT
```

---

## Custos, riscos, honestidade

- **Viola os Termos do Google Play.** Contas podem ser suspensas. Use uma descartável.
- **Forma de pagamento é obrigatória** mesmo em teste grátis. Cartão virtual com limite baixo é a escolha sensata.
- **A renovação automática é a armadilha real**, não o caixa: o preço introdutório vira preço cheio depois. Cancele em *Play → avatar → Pagamentos e assinaturas → Assinaturas*.
- **Elegibilidade é por conta**: em vários SKUs, `launchBillingFlow` com `code=0` queima o teste mesmo que a tela seja fechada sem confirmar. Consulte primeiro, decida depois.
- Tudo é **client-side**: nenhum dado de servidor é tocado, nenhum exploit contra a infraestrutura do Google. O código lê o que a API já devolve e abre a mesma tela que o app abriria.
- Quem distribui ferramentas parecidas comercialmente costuma vender **contas prontas**, não software. O código é a parte honesta; o ecossistema em volta, não.

---

## Licença

MIT — veja [LICENSE](LICENSE). Sem garantias. Use apenas em contas e aparelhos seus.
