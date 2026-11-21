# Contributing

Short version: keep it honest and keep it small.

- **No credentials, ever.** No accounts, no cookies, no cards, no tokens, no serials in code, logs or examples. Use `<account>@gmail.com` style placeholders.
- **No personal data** in issues or PRs. Mask e-mails, names, device serials and offer tokens before pasting logs.
- **No automation of purchases.** The tool may open Play's checkout; a human confirms. PRs that auto-confirm get closed.
- **Document the failure, not just the win.** A SKU that returns `n=0` is information. Add it to `src/candidates.txt` comments instead of deleting the attempt.
- Keep `Probe.java` stdlib-only and free of reflection where possible — it has to survive ART changes.

Translations: if you touch `README.md` or `docs/TUTORIAL.md`, update `README.pt-BR.md` / `README.zh-CN.md` / `docs/TUTORIAL.*` in the same PR.
