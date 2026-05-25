# LoRemote Android — InstructionForAgent

## Репозиторий
https://github.com/BotoVed/LoRemoteApp (branch: main)
Часть экосистемы [LoRemote](https://github.com/BotoVed/LoRemote)

## Что делает
BLE-клиент для управления устройствами LoRemote через Meshtastic-сетку (T114 шлюз).
Подключается к T1000-E шлюзу по BLE, обменивается пакетами протокола LoRemote (portnum 256).

## Структура проекта
- `app/src/main/proto/mesh.proto` — protobuf-схема для Meshtastic BLE API
- `app/src/main/java/com/loremote/app/ble/BleManager.kt` — BLE-менеджер
- `app/src/main/java/com/loremote/app/ui/MainActivity.kt` — UI
- `app/src/main/java/com/loremote/app/protocol/` — Protocol (encode/decode), Packet (OutPacket)
- `app/src/main/java/com/loremote/app/protocol/DeliveryQueue.kt` — очередь доставки с ретраями

## Текущий статус
- [x] BLE подключение к T1000-E (AleX_c891, MAC EF:A6:95:F2:C8:91)
- [x] Meshtastic handshake (startConfig → FromRadio loop → Ready)
- [x] MTU 247, все три characteristic найдены
- [x] PING/ALL отправляются в ToRadio
- [x] Данные от T1000-E приходят (60-88 байт)
- [x] Приём наших пакетов (PRIVATE_APP port 256)

## Известные проблемы
- `portnum=0` при парсинге → фикс в v0.2.2: парсить FromRadio, не MeshPacket напрямую
- Данные от T1000-E приходят, но парсились как raw bytes до v0.2.2

## TODO
- [ ] Улучшенная обработка ошибок BLE
- [ ] Поддержка дополнительных типов пакетов
- [ ] Настройки шлюза T114
- [ ] Интеграция с UI для управления устройствами

## Сборка
```bash
cd ~/loremote-android
git pull origin main
export ANDROID_HOME=~/android-sdk
/tmp/gradle-8.6/bin/gradle assembleDebug --no-daemon
```

## Установка и тест
```bash
~/android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
~/android-sdk/platform-tools/adb logcat -s "BleManager" "MainActivity" -c
~/android-sdk/platform-tools/adb logcat -s "BleManager" "MainActivity"
```

## Коммит
```bash
git add -A
git commit -m "v0.2.2 — fix FromRadio parsing, wrap ToRadio with portnum 256"
git push origin main
```

## Константы
```kotlin
const val GATEWAY_NODE_NUM = 0x077ccb09  // T114 node ID = 125747977
const val LOREMOTE_PORT    = 256          // PRIVATE_APP
```
