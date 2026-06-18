# Debug Notes

## Android Studio Device Explorer

Open:

```text
View > Tool Windows > Device Explorer
```

App package:

```text
com.example.ridenbmscontroller
```

Useful app-private files:

```text
/data/data/com.example.ridenbmscontroller/files/ops_logs/
/data/data/com.example.ridenbmscontroller/files/controller_history.csv
/data/data/com.example.ridenbmscontroller/shared_prefs/controller_settings.xml
```

Daily operational logs (for offline tuning analysis):

```text
ops_logs/YYYY-MM-DD_telemetry.csv
ops_logs/YYYY-MM-DD_telemetry_v2.csv   (used after same-day format upgrade)
ops_logs/YYYY-MM-DD_events.csv
```

Manage logs on the phone under **Tools > Logs**. Delete older daily files manually from that tab.

Telemetry v2 adds: `knee_probe_fast`, `vtune_probe_phase`, `hunt_lock_ticks`, `riden_pin_est_w` (output W × Vin/Vout), `riden_pout_w`, `accepted_probe_pin_w`, `vtune_descent_blocked`.

## Codex ADB Log Pull

If the phone is connected through Android Studio / ADB, Codex can read operational logs directly.

Check that the phone is visible with:

```powershell
adb devices
```

Pull today's telemetry log:

```powershell
adb shell run-as com.example.ridenbmscontroller cat files/ops_logs/$(adb shell run-as com.example.ridenbmscontroller ls files/ops_logs | findstr telemetry | sort | tail -1)
```

Or list all ops log files:

```powershell
adb shell run-as com.example.ridenbmscontroller ls -la files/ops_logs/
```

## Install And Relaunch On Phone

`installDebug` updates the APK but does not reopen the app. After installing, relaunch it:

```powershell
.\gradlew.bat installDebug
adb shell am start -n com.example.ridenbmscontroller/.MainActivity
```

The launcher icon is named **MPPT** (`app_name` in `strings.xml`).
