# Riden BMS Controller

Riden BMS Controller is an Android app for running a small solar charging station by pairing a programmable charge controller with a programmable battery BMS. It was built around a Riden DC power supply used as a solar controller and a JBD/Xiaoxiang-style BMS, but the larger idea is more general: use the BMS as the source of battery truth, and use any programmable charger or solar controller as the controllable energy source.

Instead of charging mainly by battery voltage, the app charges by battery state of charge. That matters because voltage is only an indirect clue, and it changes with current, wiring loss, load, temperature, and battery chemistry. A BMS can report SOC, current, cell voltages, cell delta, balancing state, and protection alarms directly. With that information, the controller can stop pushing the battery once the desired SOC ceiling is reached, then keep the system useful by letting solar support RV loads with only small charge/discharge bumps around the target.

The goal is simple: make solar charging more useful while being gentler on the battery.

## Features

- Android dashboard for battery, charger, solar, energy, and controller state
- BMS-based SOC charging instead of voltage-stage charging
- Configurable normal SOC ceiling for battery longevity
- Balance-day mode that periodically raises the SOC target to 100%
- Manual balance-day force/cancel control
- Maximum controller voltage limit for the Riden output
- Solar input tracking using Riden VIN feedback
- Riden falloff and recovery behavior for weak solar conditions
- BMS alarm monitoring with charge stop and delayed automatic recovery
- Dashboard indicators for SOC target, recovery mode, balancing, cell delta, and amp-hour progress
- Riden Wh tracking for solar production
- BMS Wh tracking for battery charge energy
- Solar load Wh estimate from Riden Wh minus BMS Wh
- History charts with pinch zoom, horizontal pan, and day-wide vertical scaling
- Foreground keep-alive service so the controller can continue operating while the screen is off

## Hardware Concept

The app sits between two programmable devices:

- A programmable charger, MPPT solar controller, or DC power supply, such as a Riden unit
- A smart battery BMS, such as a JBD/Xiaoxiang-compatible Bluetooth BMS

The BMS reports what the battery is actually doing. The charger or solar controller provides controllable voltage, current, output state, or charge limits depending on the hardware. The app combines those two sides into a practical battery-aware solar controller:

1. Read battery SOC, pack current, pack voltage, cell delta, balancing state, and alarms from the BMS.
2. Read solar/input/output behavior from the programmable supply.
3. Adjust the supply so solar energy is used when available.
4. Stop normal charging at the selected SOC ceiling.
5. Continue supporting loads when possible without constantly pushing the battery to full.
6. Periodically allow a 100% day so the BMS has a chance to balance the pack.

This is especially useful for lithium batteries, where living at 100% SOC every day is usually unnecessary and can reduce long-term battery life.

## Why SOC Instead Of Voltage

Traditional charger logic often tries to decide what to do from battery voltage. That can work, but it is a rough model. In a real RV or off-grid system, voltage can be misleading because the battery may be charging, discharging, resting, running loads, or sitting behind wiring that causes voltage drop.

When the BMS is available, SOC is a better control target. It lets the system say:

- Charge normally to 90-95% for daily use.
- Hold around that point while solar supports loads.
- Go to 100% only on selected balance days.
- Stop or delay charging if the BMS reports a protection alarm.

The result is a charger that behaves less like a fixed voltage source and more like a battery-aware energy manager.

## AI-Built And Adaptable

This app was created with AI assistance using ChatGPT Codex. The useful part of that is not just that this specific Riden/JBD version exists, but that the same approach can be adapted to many other charger and BMS combinations.

If you have:

- A programmable charger, MPPT controller, bench supply, or solar controller
- A smart BMS with readable telemetry
- An old Android phone
- A willingness to describe what your hardware reports and accepts

Then you can use Codex to clone this repository, inspect the code, and modify the device interface layers for your own equipment. The core idea can remain the same even if the exact hardware changes: read trustworthy battery telemetry, control the charger intelligently, and expose the result through a simple Android dashboard.

For example, a real MPPT controller with USB or Bluetooth programming may not need the Riden-specific solar falloff and recovery logic at all. Codex can remove that layer and replace the Riden adapter with whatever commands the controller supports. The important pattern is not the Riden hardware; it is the cooperation between a programmable charger/controller, a smart BMS, and an Android app that can make better decisions than either device makes alone.

No deep programming background should be required for basic adaptation. You still need to test carefully, understand your hardware limits, and verify safety behavior, but Codex can do the code reading, code changes, build fixes, and UI adjustments with you.

## Development

This is a native Android project using Kotlin and Jetpack Compose.

Build a debug APK:

```powershell
.\gradlew.bat assembleDebug
```

Open the folder in Android Studio to build, install, and debug on a phone.

## Adapting Other Hardware

The Riden-specific code is intentionally separated from the higher-level controller model:

- `app/src/main/java/com/example/ridenbmscontroller/riden/` contains the Riden USB/Modbus adapter, register addresses, scaling, and command details.
- `SolarMpptController.kt` contains the reusable control model: BMS/SOC policy, solar knee tracking, and collapse recovery.
- `BmsBleScanner.kt` contains the JBD/Xiaoxiang BLE adapter and protocol decoding.

For a programmable MPPT controller, the Riden adapter is the main piece to replace. A real MPPT may already handle panel knee tracking internally, in which case the Riden-specific falloff/recovery behavior can be removed or bypassed while keeping the BMS/SOC policy layer and Android dashboard.

## Safety

This app controls real charging hardware. Use conservative voltage and current limits, verify BMS alarm behavior, and test with supervision before leaving a system unattended. The BMS should remain the final hardware-level protection layer; this app is an additional controller, not a replacement for proper battery protection.

## Connecting to phone after phone is pared using Android Studio

**make sure wireless debugging is turned on for the phone**

```
> adb start-server
... wait
> adb devices
should show 
adb-RFCN70E0X1J-ib6pkA._adb-tls-connect._tcp    device
> adb shell settings get global device_name
should show
Nathan's Galaxy Note20 5G
```