# GarminHudAdapter (Android)

Адаптация протокола из проекта `gabonator/Work-in-progress/GarminHud` под Android.

## Что внутри

- `GarminHudClient` — кодировщик команд HUD в байтовые пакеты (Direction, Distance, Time, Lanes, Speed Warning, Camera/GPS).
- `BluetoothRfcommTransport` — транспорт для отправки пакетов через `BluetoothSocket`.

## Быстрое использование

```java
BluetoothSocket socket = ...; // уже подключенный RFCOMM-сокет к Garmin HUD
GarminHudClient.PacketTransport transport = new BluetoothRfcommTransport(socket);
GarminHudClient hud = new GarminHudClient(transport);

hud.setDirection(
    GarminHudClient.OutAngle.RIGHT,
    GarminHudClient.OutType.LANE,
    GarminHudClient.OutAngle.AS_DIRECTION
);

hud.setDistance(450, GarminHudClient.Units.METRES, false, false);
hud.setTime(12, 34, true, false, false);
```

## Интеграция в Android приложение

1. Скопируйте `src/main/java/com/navigon/hud/*` в модуль приложения.
2. Убедитесь, что Bluetooth соединение уже установлено (pair + connect).
3. Передайте подключенный `BluetoothSocket` в `BluetoothRfcommTransport`.

## Основа

Реализация повторяет структуру пакетов, stuffing и CRC из:
- https://github.com/gabonator/Work-in-progress/tree/master/GarminHud
- `Version2/HUDInterface.cpp` и `Version2/HUDInterface.h`
