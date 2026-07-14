# Restart ADB and list devices (helps after Wi-Fi / wireless-debug drops).
adb kill-server
adb start-server
Start-Sleep -Seconds 2
adb devices
