# NikySearch per Android (app non ufficiale)

Un guscio WebView che apre https://nikysearch.it/ a schermo pieno: tira-giù per
aggiornare, link esterni nel browser, permesso Fotocamera solo per OrgScan
(https://nikysearch.it/orgscan/). Versione 0.5.

Copyright (C) 2026 Edefede — software libero, licenza **GNU GPL v3** (vedi `LICENSE`).
Il file `gradle/wrapper/gradle-wrapper.jar` e' il wrapper di Gradle (Apache 2.0), non e' nostro.

## Compilare

Servono JDK 17 e l'Android SDK (platform 33, build-tools 34 (minSdk 29: Android 10+)):

    echo "sdk.dir=/percorso/android-sdk" > local.properties
    ./gradlew assembleDebug

L'APK esce in `app/build/outputs/apk/debug/`. Il file scaricabile dal sito e' firmato con la
chiave di debug dell'autore: compilandolo tu otterrai un APK equivalente ma con la tua firma.

Il codice e' tutto in `app/src/main/java/it/nikysearch/app/MainActivity.java` (circa 190 righe).
