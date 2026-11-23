# pressReadMePlease per Android

Piccola app Android (un'unica schermata) che esegue **a comando** lo stesso flusso della versione Docker,
senza bisogno di un server sempre acceso:

1. login su MLOL → Edicola → Corriere della Sera → "Sfoglia online"
2. login su PressReader
3. verifica che i giorni di accesso siano stati rinnovati (attesi: 2), poi logout

Il tutto gira in una `WebView` pilotata via JavaScript (la sfida ALTCHA di MLOL è risolta dal browser stesso).

## Uso

1. Inserisci indirizzo MLOL (`https://xxx.medialibrary.it`) e credenziali PressReader. Per MLOL: username + password, oppure spunta **Accesso con SPID** (vedi sotto) → **Salva configurazione**.
2. Premi **Rinnova ora** e lascia l'app aperta in primo piano finché non compare `OK` (o l'errore) nel log.
   Durante l'esecuzione la WebView è visibile, così puoi vedere dove si blocca.

Le credenziali sono salvate solo nelle preferenze private dell'app (backup Android disattivato).
Le notifiche Telegram dell'originale non sono incluse: l'esito è mostrato nel log dell'app.

## Compilare l'APK

- **GitHub Actions**: il workflow `android apk` (Actions → *Run workflow*) produce l'artefatto `pressreadmeplease-debug-apk`
  da scaricare e installare (serve consentire l'installazione da fonti sconosciute).
- **Locale**: apri la cartella `android/` con Android Studio, oppure `./gradlew assembleDebug`
  (serve Android SDK 34 e JDK 17) → `app/build/outputs/apk/debug/app-debug.apk`.

## Se il flusso si rompe

I selettori sono quelli di `src/mlol.py` e `src/pressreader.py`, tutti in `app/src/main/java/io/github/pressreadmeplease/Renewer.kt`.
Se MLOL/PressReader cambiano pagina, si aggiornano lì (di solito in coppia con la versione Python).

## Accesso MLOL con SPID

Spunta **Accesso con SPID (login manuale)**: utente e password MLOL non servono. Premendo **Rinnova ora**
l'app apre MLOL nella WebView e aspetta (max 5 minuti) che tu completi il login SPID a mano nella finestra;
appena rileva l'accesso prosegue da sola con PressReader. I cookie vengono conservati, quindi se la sessione
MLOL è ancora valida alla volta successiva il login SPID viene saltato.
