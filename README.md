# SparkPulse

Dashboard Android nativa per il monitoraggio della DGX Spark.

## Build ARM64

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=/home/jagones/Repositories/android-dev/android-sdk
./gradlew clean testDebugUnitTest assembleDebug
```

Il contratto di stato è `GET http://<host>:8787/status.json`; lo schema è definito nel task coordinator [JAG-16](/JAG/issues/JAG-16). L'host predefinito è `100.102.61.23`, modificabile dalla schermata.

L'app usa traffico HTTP in chiaro perché l'endpoint DGX contrattuale è HTTP su rete Tailscale privata.

## SparkPulse 1.2

- Polling configurabile a 1, 2 o 5 secondi (predefinito: 2 secondi).
- Grafici Canvas per utilizzo GPU, temperatura GPU e memoria UMA; lo storico viene richiesto con `GET /api/history?window_s=300`.
- Cambio modello tramite `GET /api/models` e `POST /api/commands`; l'app chiede conferma prima dello switch e aggiorna lo stato ogni 2 secondi.
- I timeout HTTP restano di 4 secondi; errori di telemetria o dei comandi non sostituiscono l'ultimo snapshot di stato reale.
