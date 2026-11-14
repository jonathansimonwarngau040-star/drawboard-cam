# Drawboard-Kamera

Fotos mit dem Handy aufnehmen → landen **nicht** in der Galerie → automatisch über die TUHH-Cloud an den PC → dort in die Zwischenablage und (wenn Drawboard vorne ist) direkt eingefügt.

## Download
Unter **Releases → „latest“**:
- `DrawboardKamera.apk` → aufs Handy
- `DrawboardKamera.exe` → auf den PC

## Einrichtung
1. **Handy:** APK installieren, öffnen, Kamera erlauben, ⚙ → „Mit TUHH-Cloud verbinden“ → im Browser anmelden → „Zugriff gewähren“ → zurück zur App.
2. **PC:** `DrawboardKamera.exe` starten (Windows-Warnung ggf. mit „Weitere Informationen → Trotzdem ausführen“). Beim ersten Start öffnet sich der TUHH-Login im Browser. Danach sitzt ein blaues Kamera-Symbol unten rechts in der Taskleiste und startet ab jetzt unsichtbar mit Windows.

**Einstellungen am PC:** Startmenü → „Drawboard-Kamera“, oder Klick aufs Taskleisten-Symbol. Dort: Modus (automatisch einfügen / nur Zwischenablage / aus), Autostart, Cloud, „Einfügen testen“ und die letzten Ereignisse. Das Fenster öffnet sich nie von selbst.

Der Cloud-Ordner `DrawboardCam` wird automatisch angelegt.

## Verhalten
| Situation | Was passiert |
|---|---|
| Foto aufgenommen, Internet da | Upload in `DrawboardCam` |
| Kein Internet | Foto bleibt nur auf dem Handy (kein automatischer zweiter Versuch; manuell über das Vorschaubild → „An PC senden“) |
| PC: Drawboard ist das aktive Fenster | Zwischenablage + Strg+V |
| PC: Drawboard nicht aktiv / nicht offen | nur Zwischenablage |
| Foto älter als 10 Minuten, wenn der PC es sieht | wird nicht eingefügt, nur aus der Cloud entfernt |

Nach dem Abholen wird das Foto aus der Cloud gelöscht; das Original bleibt auf dem Handy.

## Sicherheit für Drawboard
Das PC-Programm ist komplett eigenständig: kein Eingriff in Drawboard, niedrige Priorität, Strg+V nur wenn keine Maus-/Stift- oder Strg/Shift/Alt-Taste gedrückt ist. Stürzt es ab, läuft Drawboard ganz normal weiter.

Zugangsdaten: Es wird jeweils ein eigenes App-Passwort erstellt (widerrufbar in der TUHH-Cloud unter Einstellungen → Sicherheit), am Handy verschlüsselt, am PC mit Windows-DPAPI gespeichert.
