# SparkPulse

Dashboard Android nativa per il monitoraggio della DGX Spark.

## Build ARM64

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=/home/jagones/Repositories/android-dev/android-sdk
./gradlew clean testDebugUnitTest assembleDebug
```

Il contratto di stato è `GET http://<host>:8787/status.json`; lo schema è definito nel task coordinator [JAG-16](/JAG/issues/JAG-16). L'host predefinito è `100.102.61.23` — l'indirizzo **Tailscale** della DGX Spark (non `127.0.0.1`, che funzionerebbe solo sulla DGX stessa), modificabile dalla schermata.

L'app usa traffico HTTP in chiaro perché l'endpoint DGX contrattuale è HTTP su rete Tailscale privata.

## SparkPulse 1.2

- Polling configurabile a 1, 2 o 5 secondi (predefinito: 2 secondi).
- Grafici Canvas per utilizzo GPU, temperatura GPU e memoria UMA; lo storico viene richiesto con `GET /api/history?window_s=300`.
- Cambio modello tramite `GET /api/models` e `POST /api/commands`; l'app chiede conferma prima dello switch e aggiorna lo stato ogni 2 secondi.
- I timeout HTTP restano di 4 secondi; errori di telemetria o dei comandi non sostituiscono l'ultimo snapshot di stato reale.

## SparkPulse 1.5.1

Correzioni di configurazione e cold start per il tab **FORGE** (SparkForge `:8790`).

- **Impostazioni FORGE** (icona ⚙ nel tab FORGE): host + token, con bottone
  **Test connessione** che interroga `GET /api/selfcheck` e mostra l'esito
  (✅ verde / ⛔ rosso) con la causa in chiaro. Il pannello salva host/token in
  `DataStore/SharedPreferences` (`forge_host`, `forge_token`); il token di build
  arriva da `local.forge.properties` (gitignored).
- **Host predefinito = IP Tailscale della DGX** (`100.102.61.23`), mai
  `127.0.0.1`: così il telefono sul tailnet raggiunge SparkForge senza
  port-forwarding.
- **UX cold start**: sugli eventi SSE `model.loading` / `model.ready` l'app
  mostra "LLM in avvio…" nell'header finché il modello non è caldo. Il client
  non interrompe mai una run sotto i 120 s (SSE senza deadline, REST 300 s) per
  non uccidere un modello freddo che si carica per minuti.
- **Errori espliciti** al posto della "chat morta": 401/403 (token da
  riallineare), timeout, DNS non risolto, connessione rifiutata.
- **Quick-action "Dove sei"**: esegue prima `/api/selfcheck` e riporta l'esito,
  poi invoca il tool `self` via agent loop.

Test unitari aggiunti: `ForgeSelfcheckTest` (parsing selfcheck + classificazione
degli errori 401/timeout/DNS/refused) e `ForgeTimeoutTest` (guardia sul timeout
cold start ≥ 120 s).
