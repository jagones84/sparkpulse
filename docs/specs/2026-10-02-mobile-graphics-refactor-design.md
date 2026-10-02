# Refactor grafico "Dark Sparkforge" — app SparkPulse (2026-10-02, JAG-97)

## Obiettivo
Applicare alla app mobile SparkPulse (mirror del harness SparkForge) la stessa
identità visiva della WebUI, eliminando la duplicazione dei token colore e
introducendo componenti UI condivisi. **Nessun** cambiamento a logica, endpoint,
ViewModel, contratti SSE o flussi: solo presentazione.

## Configurazione scelta
"Dark Sparkforge" (coerente al 100% con la WebUI PC).

## Token canonici (sorgente di verità: `ForgeTheme.kt`)
| token | hex | uso | WebUI var |
|---|---|---|---|
| Ink | #06070C | sfondo app | --bg |
| Panel | #121722 | superficie card | --bg-2 |
| PanelRaised | #161B25 | superfici elevate | --bg-3 |
| TextMain | #DFE4FF | testo primario | --txt |
| TextMuted | #7C86AD | testo tenue | --dim |
| Line | #1E2431 | bordi | --line-2 |
| Mint | #4ADE80 | ok | --ok |
| Blue | #5AC8FA | info | --info |
| Amber | #FFB020 | warn | --warn |
| Coral | #F87171 | err | --err |
| Violet | #B56CFF | accento 2 | --acc2 |
| Accent | #8B7BF0 | brand | --accent |
| You | #8FB6FF | accento utente | --you |

## Architettura
1. **Nuovo `ForgeTheme.kt`** — definisce i token (visibilità `internal`) + costanti di forma (raggi card/pill).
2. **`ForgeScreen.kt`** — rimuove le definizioni locali `F*` e le rimpiazza con
   **alias** ai token canonici (`internal val FMint = ForgeMint`, …). Zero riscrittura
   delle ~2100 righe che usano `F*`; i valori restano single-sourced.
3. **`MainActivity.kt`** — rimuove la palette privata duplicata; usa i token canonici.
4. **Componenti condivisi** (in `ForgeTheme.kt`):
   - `ForgeCard` — Panel + bordo Line + raggio 18dp (sostituisce le card ripetute).
   - `GradientTitle` — testo a gradiente Accent→Violet→Blue.
   - `StatusPill(text, tint)` — pill di stato (IDLE/LIVE/ONLINE/RETRY).
   - `AccentPill(text, selected)` — pill a gradiente per tab e azioni primarie.
   Applicati a PULSE, FORGE, COMMAND e ai pannelli (Graph/Sessions/Settings/Self).

## Dettagli visivi
- Titoli a gradiente: "DGX Spark" (PULSE), "⚡ SPARKFORGE" (FORGE).
- Tab bar segmented con pill a gradiente sul tab attivo.
- System bars = Ink; overscroll scuro.
- Icone tab: 📊 PULSE · ⚡ FORGE · ⌘ COMMAND.
- Metric tile con dot di accento + bordo; barre con riempimento a gradiente.

## Fuori scope
- Nessuna modifica a `StatusRepository`/`StatusViewModel`/`ForgeSse`, endpoint,
  contratti o test esistenti (salvo eventuali nuovi test UI/parse).

## Verifica
- `./gradlew testDebugUnitTest assembleDebug` verde.
- APK installato su oneplus-15r; screenshot cold-start di PULSE, FORGE, COMMAND
  e di un pannello.
- `versionCode 41 / versionName 1.6.33`.
