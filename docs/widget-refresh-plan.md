# Widget-Refresh: Ursachenanalyse und Umsetzungsplan

Stand: 15. September 2026, Branch `claude/widget-usage-refresh-f6bs0m`, Basis `1b184e3`.
Bezieht sich auf Android (Glance 1.2.0, WorkManager 2.10.0).

**Umsetzungsstand:** Schritte 1 bis 7 sind auf diesem Branch umgesetzt, mit Ausnahme des
End-to-End-Tests aus Schritt 7 (`WidgetRepaintTest`), der eine Glance-Session unter Robolectric
über WorkManager treiben müsste; der App-Pfad ist stattdessen über `SyncWorkerSchedulingTest`,
`SyncWorkerPolicyTest`, `WidgetRefreshCoverageTest` und die erweiterten Builder-/Style-Tests
abgesichert. Abschnitt 6 bleibt die Anleitung für die Prüfung auf dem Gerät.

## 1. Symptom

Die Ring-Widgets zeigen den Usage-Stand vom Zeitpunkt des Platzierens. Alle Limits sind
inzwischen zurückgesetzt (100 % frei), die Widgets zeigen weiter die alten Bögen. Im Screenshot
sind vier Ringe farbig (also zum Zeitpunkt des letzten Renders *nicht* stale) und einer grau
(Slate = ERROR/STALE eines einzelnen Accounts). Das heißt: der letzte Render fand mit frischen
Daten statt, danach kam kein einziger Repaint mehr an, nicht einmal der, der die Ringe an der
Stale-Grenze grau hätte färben müssen.

## 2. Wie ein Widget heute überhaupt neu gezeichnet wird

Kein Widget deklariert `android:updatePeriodMillis` (alle vier XMLs in `res/xml/`). Das System
fordert also nie von selbst ein Update an. Jeder Repaint entsteht ausschließlich aus App-Code:

```
SyncWorker.doWork()                       (WorkManager, periodisch >= 15 min, NetworkType.CONNECTED)
  -> syncEngine.syncAll()                 (Netz)
  -> publishAfterSync()                   (PostSync.kt:27)
       -> WidgetUpdater.refreshAll()      (WidgetUpdater.kt:48)
            -> widget().updateAll(ctx)    (Glance)
                 -> GlanceAppWidgetManager.getGlanceIds(javaClass)
                 -> GlanceAppWidget.update(id)
                      -> SessionManager.startSession()
                           -> WorkManager.enqueueUniqueWork("appWidget-<id>", REPLACE, SessionWorker)
                                -> SessionWorker.doWork(): provideGlance() -> RemoteViews -> updateAppWidget()
  -> WidgetPresentationWorker.scheduleNext()   (cache-only Repaint an Stale-/Reset-Grenze, ebenfalls WorkManager)
```

Weitere Aufrufer von `refreshAll`: `UsageViewModel` (Refresh-Button, Account-Refresh, Reorder,
Icon, Intervall, Remove), `WidgetConfigActivity` (Speichern), `AddAccountViewModel` (Login),
`WidgetPresentationWorker`. Der Refresh-Button der Widgets ruft `SyncWorker.syncNow` (KEEP).

Wichtige Eigenschaften der Glance-1.2.0-Implementierung (aus den Sources verifiziert):

- Jeder Repaint ist selbst ein WorkManager-Job (`SessionWorker`), ohne Constraints, nicht
  expedited. `updateAll` liefert nur einen Enqueue, kein Ergebnis.
- Ist für ein Widget bereits eine Session offen und deren Worker RUNNING oder ENQUEUED, sendet
  `update()` nur ein `UpdateGlanceState`-Event. `provideGlance` wird dann nicht erneut ausgeführt.
  Neue Daten kommen in diesem Fall nur über den in `provideContent` gesammelten Room-Flow.
- Die Session lebt nach dem ersten erfolgreichen Render 45 s (+5 s pro Event) und wird dann
  geschlossen. Der nächste `update()` startet eine frische Session.
- Stirbt der Prozess zwischen Enqueue und Start des `SessionWorker`, wirft der Worker
  `error("No session available for key …")` (runAttemptCount == 0). Der Job endet FAILED, der
  Repaint ist verloren, nichts wird wiederholt.
- `getGlanceIds` löst den Widget-Klassennamen über eine DataStore-Zuordnung
  Receiver -> `GlanceAppWidget.canonicalName` auf, die nur in `GlanceAppWidgetReceiver.onReceive`
  geschrieben wird.

## 3. Ursachen

### U1 (Hauptursache): Es gibt keinen Update-Pfad, der unabhängig vom App-eigenen WorkManager ist

Alles hängt daran, dass ein WorkManager-Job der App im Hintergrund tatsächlich läuft: der
periodische `SyncWorker` (Netz-Constraint, Doze-Deferral, App-Standby-Bucket, OEM-Killer), und
danach nochmals der Glance-`SessionWorker`. Wird der Prozess nach dem Sync beendet, bevor der
`SessionWorker` startet, geht der Repaint still verloren (siehe oben). Wird der periodische Job
vom System aufgeschoben, passiert stundenlang gar nichts, und kein Teil des Systems fragt beim
Widget nach. Genau das ist der Zustand im Screenshot: seit dem Platzieren kein Repaint.

Belege: keine `updatePeriodMillis` in `widget_*_info.xml`; Receiver überschreiben nichts
(`UsageWidgets.kt:477-483`, `RingWidgets.kt:128-133`); `refreshAll` ist der einzige Trigger
(`WidgetUpdater.kt:40-55`).

### U2: An einer Reset-Grenze kann der Cache-Repaint die richtige Zahl gar nicht zeigen

`WidgetPresentationWorker` malt an der Reset-Grenze aus dem Cache neu. Der Cache enthält aber
den Stand *vor* dem Reset; `RingMark` zeichnet `row.remainingPercent` unverändert
(`RingWidgets.kt:116`), nur der Text wird zu "Reset due" (`RingWidgets.kt:96`). Eine korrekte
Anzeige nach einem Reset setzt einen Netz-Sync voraus, den der Presentation-Worker nicht auslöst
(`WidgetPresentationWorker.kt:31-36`). Nach jedem Reset zeigen die Ringe also zwingend eine
falsche, alte Zahl, bis irgendwann der periodische Sync läuft. Das ist unabhängig von U1 die
zweite Hälfte des beobachteten Bilds ("alles ist bei 100 %, Widget zeigt alten Verbrauch").

### U3: Fehler auf dem Widget-Pfad sind unsichtbar

`refreshAll` schluckt jede Exception ohne Log (`WidgetUpdater.kt:53`), auch
`CancellationException`. Wird der aufrufende Worker gestoppt (Netz weg, 10-Minuten-Limit, oder
`scheduleNext` mit `REPLACE` bricht einen laufenden `WidgetPresentationWorker` ab), laufen die
restlichen Iterationen mit abgebrochenem Scope einfach ins Leere. Es gibt keinen Zeitstempel
"letzter Widget-Repaint" und keinen Hinweis in der App, wenn Hintergrundarbeit vom System
eingeschränkt ist. Der Nutzer kann nicht sehen, ob der Sync lief und ob der Repaint ankam.

### U4: `syncNow` mit KEEP plus `Result.retry()` blockiert manuelle Trigger

`SyncWorker.syncNow` ist unique mit `ExistingWorkPolicy.KEEP` (`SyncWorker.kt:94-98`). Schlagen
in einem Einmal-Lauf alle Accounts fehl, liefert `doWork` `Result.retry()` (`SyncWorker.kt:48`),
der Job liegt mit exponentiellem Backoff (30 s, verdoppelnd, bis 5 h) als ENQUEUED vor, und jeder
weitere `syncNow` (App-Start, `onResume`, Widget-Refresh-Button) wird verworfen. Nicht die
Hauptursache, aber ein Verstärker: der Nutzer tippt Refresh, und nichts passiert.

### U5 (Härtung): Klassennamen der Widgets sind im Release nicht vor R8 geschützt

`getGlanceIds` vergleicht `canonicalName` der `GlanceAppWidget`-Subklassen mit der persistierten
Zuordnung. Glance' Consumer-Rules halten nur `ActionCallback`-Subklassen (`proguard.txt` im
AAR), die App-Regeln nichts Widget-bezogenes. Nach einem Update mit anders minifizierten Namen
liefert `updateAll` eine leere ID-Liste, bis der Receiver den nächsten System-Broadcast
verarbeitet. Das System sendet nach einem Paket-Update ein `APPWIDGET_UPDATE`, daher heilt das
in der Praxis schnell, kostet aber nichts, es korrekt zu machen.

### Geprüft und ausgeschlossen

- Alle vier Widgets stehen in `WidgetUpdater.allWidgets` (`WidgetRefreshCoverageTest`).
- Die drei Flows in `WidgetUpdater.observe` (Room, Room, DataStore) emittieren sofort;
  `views.first()` vor `provideContent` kann nicht dauerhaft hängen.
- Die gestern korrigierte Endlos-Recomposition (Flow wurde pro Recomposition neu erzeugt) ist
  am HEAD behoben; der Flow wird pro Session einmal erzeugt.
- Die Klassennamen-Zuordnung passt innerhalb eines Builds (Debug wie Release).

### Nicht verifizierbar ohne Gerät

Ob auf dem konkreten Handy der periodische Job lief, zeigt nur das Gerät. Siehe Abschnitt 6 für
die `dumpsys`-Kommandos. Der Plan unten macht den Refresh unabhängig davon robust und macht den
Zustand sichtbar, damit sich diese Frage künftig in der App selbst beantworten lässt.

## 4. Zielbild

Drei voneinander unabhängige Wege, die ein Widget aktuell halten, plus Sichtbarkeit:

1. App-getrieben (bestehend): Sync -> `refreshAll`.
2. System-getrieben (neu): `updatePeriodMillis` -> `onUpdate` -> Cache-Repaint und, bei altem
   Cache, `syncNow`.
3. Zeit-getrieben (bestehend, erweitert): `WidgetPresentationWorker` an Stale-/Reset-Grenzen,
   an einer Reset-Grenze zusätzlich `syncNow`.
4. Diagnose: Zeitstempel für letzten Sync und letzten Repaint, Hinweis bei
   Hintergrund-Einschränkungen, geloggte statt verschluckte Fehler.

## 5. Umsetzungsplan

Reihenfolge nach Wirkung. Schritte 1 bis 3 beheben das beobachtete Verhalten, 4 bis 6 machen es
robust und sichtbar, 7 sichert ab.

### Schritt 1: Systemgetriebenes Update aktivieren

Dateien: `res/xml/widget_compact_info.xml`, `widget_detailed_info.xml`, `widget_minimal_info.xml`,
`widget_mini_rings_info.xml`.

- `android:updatePeriodMillis="1800000"` (30 min, das Minimum, das das System ausliefert) in
  alle vier Provider-XMLs. Das System weckt die App dafür auch im Doze-Wartungsfenster und
  startet den Prozess bei Bedarf selbst.
- Kommentar in jeder XML: warum der Wert da steht und dass `onUpdate` daraus einen Sync ableitet.

Test: `WidgetRefreshCoverageTest` um einen Fall erweitern, der alle im Manifest referenzierten
Provider-XMLs liest und `updatePeriodMillis >= 1800000` verlangt.

### Schritt 2: Gemeinsamer Receiver, der aus `onUpdate` einen Sync ableitet

Dateien: neu `widget/UsageWidgetReceiver.kt`; anpassen `UsageWidgets.kt:477-483`,
`RingWidgets.kt:128-133`.

- `abstract class UsageWidgetReceiver : GlanceAppWidgetReceiver()` mit `onUpdate`-Override:
  `super.onUpdate(...)` (Glance registriert die Zuordnung und malt aus dem Cache), danach
  `SyncWorker.syncNowIfStale(context)`.
- `syncNowIfStale`: liest `repository.accountUsageOnce()` und `settings.first()`; ist das
  jüngste `fetchedAt` älter als `syncIntervalMinutes` oder ist eine `resetAt <= now` mit
  `fetchedAt < resetAt` vorhanden, `syncNow(context)`. Sonst nichts (der Platzierungs-`onUpdate`
  soll keinen doppelten Sync auslösen).
- Die vier konkreten Receiver erben von `UsageWidgetReceiver`; Manifest bleibt unverändert.
- Entscheidung festhalten: `onUpdate` fetcht nicht selbst (Launcher-Budget), sondern enqueued.

Test: Robolectric-Test, der `ACTION_APPWIDGET_UPDATE` mit `EXTRA_APPWIDGET_IDS` an einen Receiver
sendet und prüft, dass mit altem Cache ein `usage_sync_now`-Work enqueued wird und mit frischem
nicht (WorkManager-Zustand über `WorkManager.getWorkInfosForUniqueWork` oder Test-Initialisierung).

### Schritt 3: Reset-Grenze löst Sync aus, und der Cache lügt bis dahin nicht

Dateien: `widget/WidgetPresentationWorker.kt`, `widget/WidgetData.kt`, `widget/RingWidgets.kt`,
`widget/UsageWidgets.kt`, `widget/WidgetStyle.kt` (nur falls ein neuer Akzent nötig wird).

- `WidgetPresentationWorker.doWork`: vor `refreshAll` prüfen, ob der Grund der Grenze ein Reset
  war (ein Fenster mit `resetAt <= now` und `fetchedAt < resetAt`). Falls ja,
  `SyncWorker.syncNow(applicationContext)` enqueuen. Der Repaint bleibt cache-only.
- `WidgetDataBuilder.toWidgetAccount`: ein Fenster mit `resetAt <= now` und
  `snapshot.fetchedAt < resetAt` gilt als "reset elapsed, unconfirmed". Vorschlag zur
  Darstellung: `WidgetRow` bekommt `resetElapsed: Boolean`; `WidgetAccount.dataValidity` liefert
  dann STALE. Ringe und Bars zeichnen in diesem Zustand mit dem Slate-Akzent und dem Text
  "Reset due". Der alte Bogen wird nicht als aktuell verkauft, eine unbestätigte 100 % aber auch
  nicht behauptet. (Produktentscheidung: alternativ vollen Ring in Slate zeichnen. Beides ist
  besser als die heutige, farbig-selbstsichere alte Zahl.)
- `WidgetPresentationWorker.scheduleNext`: die Grenze nach einem verstrichenen Reset ist der
  Sync selbst; kein Polling. Bleibt der Sync aus, greift Schritt 1 nach 30 min.

Tests: `WidgetDataBuilderTest` (vor/an/nach `resetAt` mit `fetchedAt` davor und danach);
`WidgetPresentationWorkerTest` um eine reine Funktion `resetElapsed(accounts, now)` erweitern;
`WidgetStyleTest`, falls Farben ergänzt werden.

### Schritt 4: Fehler sichtbar machen statt verschlucken

Dateien: `widget/WidgetUpdater.kt`, `core/sync/SyncWorker.kt`, `core/settings/SettingsStore.kt`.

- `refreshAll`: `CancellationException` weiterwerfen; andere Fehler mit
  `Log.w("WidgetUpdater", "refresh of <name> failed", e)` loggen. Zusätzlich vor `updateAll` die
  ID-Liste über `GlanceAppWidgetManager.getGlanceIds` holen und bei leerer Liste trotz
  platzierter Widgets (`AppWidgetManager.getAppWidgetIds(ComponentName(receiver))` nicht leer)
  einen expliziten `ACTION_APPWIDGET_UPDATE`-Broadcast mit `EXTRA_APPWIDGET_IDS` an den eigenen
  Receiver senden. Das repariert eine verlorene Zuordnung (U5) und rendert in einem Zug.
- `SettingsStore`: zwei neue Keys `lastSyncAttemptAt`, `lastWidgetRefreshAt` (nur Zeitstempel,
  keine Inhalte). `SyncWorker.doWork` und `refreshAll` schreiben sie.
- `WidgetPresentationWorker.scheduleNext`: nicht aus dem eigenen `doWork` mit `REPLACE` den
  laufenden Worker abbrechen. Variante: `scheduleNext` erhält einen Parameter
  `fromPresentationWorker`, der `ExistingWorkPolicy.APPEND_OR_REPLACE` wählt, oder der Worker
  ruft `scheduleNext` erst als letzte Anweisung nach `refreshAll` auf und akzeptiert die
  Selbst-Cancelation bewusst (dann Kommentar). Empfehlung: erste Variante.

Test: Unit-Test, der `refreshAll` mit einem werfenden Widget aufruft und prüft, dass die
übrigen Widgets weiter aktualisiert werden und der Fehler geloggt wurde (`ShadowLog`).

### Schritt 5: `syncNow` darf nicht von einem Backoff blockiert werden

Datei: `core/sync/SyncWorker.kt`.

- Einmal-Lauf und periodischer Lauf per `inputData` unterscheiden (`KEY_ONE_SHOT`). Der
  Einmal-Lauf liefert bei Komplettfehlschlag `Result.failure()` statt `retry()`: die Fehler
  stehen bereits pro Account im Cache, und der nächste Trigger soll wieder durchkommen. Der
  periodische Lauf behält `retry()`, bekommt aber `setBackoffCriteria(LINEAR, 10 min)`, damit
  ein Backoff nie Stunden erreicht.
- `syncNow`: `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)`, damit der Lauf
  nach `onResume` oder Widget-Tipp nicht hinter Doze-Fenstern wartet. `CoroutineWorker` benötigt
  dafür `getForegroundInfo` nur auf API < 31; minSdk ist 26, also eine kleine
  `ForegroundInfo` mit stiller Notification bereitstellen oder auf API >= 31 beschränken.
  Empfehlung: expedited nur ab API 31 setzen, darunter unverändert.
- `KEEP` bleibt für den Fall "läuft gerade"; ein ENQUEUED-Backoff entsteht durch die Änderung
  oben für Einmal-Läufe nicht mehr.

Test: Robolectric mit WorkManager-Testinitialisierung: nach einem Einmal-Lauf, der `failure`
liefert, führt ein weiterer `syncNow` zu einem neuen ENQUEUED-Work.

### Schritt 6: Hintergrund-Einschränkungen in der App anzeigen

Dateien: `feature/settings/…` (bestehender Settings-Screen), ggf. `UsageViewModel.kt`.

- Abschnitt "Hintergrund-Aktualisierung" mit: letzter Sync, letzter Widget-Repaint (aus Schritt 4),
  Standby-Bucket (`UsageStatsManager.getAppStandbyBucket`, API 28+),
  `ActivityManager.isBackgroundRestricted` (API 28+),
  `PowerManager.isIgnoringBatteryOptimizations`.
- Bei Einschränkung ein Hinweis mit Button, der `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
  oder die App-Info öffnet. Keine `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`-Permission (Play-Policy).
- Optional: Hinweis auf OEM-Einstellungen (Samsung "Deep sleeping apps", Xiaomi "Autostart").

Test: Rendertest des Abschnitts mit zwei synthetischen Zuständen (eingeschränkt / frei).

### Schritt 7: Absichern

- `app/proguard-rules.pro`: `-keepnames class * extends androidx.glance.appwidget.GlanceAppWidget`
  mit Begründung (Zuordnung Receiver -> Klassenname wird in DataStore persistiert). Einzeiler,
  behebt U5 an der Wurzel.
- End-to-End-Regressionstest `WidgetRepaintTest` (Robolectric): Widget über
  `ShadowAppWidgetManager.createWidget` binden, `ACTION_APPWIDGET_UPDATE` an den Receiver
  schicken, Snapshot ändern, `refreshAll`, `SessionWorker` über WorkManager-Testtreiber laufen
  lassen, dann prüfen, dass die RemoteViews des Widgets die neue Zahl tragen. Hinweis: die App
  ist `Configuration.Provider` und initialisiert WorkManager in `onCreate` asynchron; der Test
  braucht eine Test-Application oder muss `WorkManagerTestInitHelper` vor `startupJob` setzen.
  Das ist der aufwändigste Test des Plans und der einzige, der die Kette wirklich schließt.
- `docs/widgets.md` aktualisieren: Abschnitt "Update path" mit den drei Wegen; die veraltete
  Aussage, dass nur `refreshAll` Widgets aktualisiert, ersetzen. `docs/architecture.md`
  (Zeilen 223 ff.) um Expedited/Backoff-Regeln ergänzen.

### Aufwand und Reihenfolge

| Schritt | Aufwand | Wirkung |
| --- | --- | --- |
| 1 + 2 | klein | Widgets erhalten spätestens alle 30 min einen systemgetriebenen Render und bei altem Cache einen Sync |
| 3 | mittel | Kein farbig-sicherer Alt-Wert nach einem Reset; Reset löst Sync aus |
| 4 | klein | Fehler und Zeitstempel sichtbar, verlorene Zuordnung heilt sich |
| 5 | klein | Manuelle Trigger kommen immer durch |
| 6 | mittel | Nutzer sieht, ob das Gerät die App drosselt |
| 7 | mittel | Regression geschlossen, Doku stimmt |

Empfohlene erste Auslieferung: Schritte 1, 2, 4, 5, 7 (proguard). Danach 3 und 6.

## 6. Verifikation auf dem Gerät

Vor der Änderung (Ist-Zustand belegen) und danach:

```
adb shell dumpsys jobscheduler | grep -A3 usagelimits
adb shell dumpsys appwidget | grep -B2 -A6 usagelimits
adb shell dumpsys deviceidle whitelist | grep usagelimits
adb shell am get-standby-bucket com.usagelimits
adb logcat -s UsageLimitsApp WidgetUpdater GlanceAppWidget SessionWorker WM-WorkerWrapper
```

Reproduktion der Reset-Grenze ohne Warten: `adb shell cmd jobscheduler run -f com.usagelimits <jobId>`
für den periodischen Sync, und Systemzeit über den Reset hinaus stellen. Erwartung nach dem
Plan: Ring wird an der Grenze Slate mit "Reset due", der Sync läuft, danach volle Ringe.

## 7. Offene Entscheidungen

- Darstellung nach verstrichenem Reset (Schritt 3): Slate mit altem Bogen und "Reset due", oder
  voller Slate-Ring. Empfehlung: alter Bogen in Slate, damit keine unbestätigte Zahl behauptet wird.
- `updatePeriodMillis` 30 min oder 60 min. Empfehlung: 30 min, entspricht dem Default-Intervall.
- Expedited-Sync: nur API 31+ (Empfehlung) oder mit `ForegroundInfo` auf allen Versionen.
