# LoRemote Android — Agent Instructions

## Репозиторий
https://github.com/BotoVed/LoRemoteApp (branch: main)
Часть экосистемы [LoRemote](https://github.com/BotoVed/LoRemote)

## Окружение разработки

- **Машина**: Ubuntu 24, user: boss
- **Java**: openjdk-21 (`/usr/lib/jvm/java-21-openjdk-amd64`)
- **Android SDK**: `~/android-sdk` (SDK 34, build-tools 34.0.0)
- **Gradle**: `/tmp/gradle-8.6/bin/gradle` — НЕ системный (системный 4.4 — слишком старый)
- **ADB**: `~/android-sdk/platform-tools/adb`
- **Проект**: `~/loremote-android`

## Workflow

```
читать InstructionForAgent.md
→ писать/менять код
→ собрать: export ANDROID_HOME=~/android-sdk && /tmp/gradle-8.6/bin/gradle assembleDebug --no-daemon
→ установить: ~/android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
→ логи: ~/android-sdk/platform-tools/adb logcat -s "BleService" "BleManager" "MainActivity" "BleScanner"
→ коммит + push
→ обновить InstructionForAgent.md
```

### Шаблон коммита
```bash
git add -A
git commit -m "vX.Y.Z — описание"
git push origin main
```

## Структура проекта

```
app/src/main/java/com/loremote/app/
  ble/
    BleService.kt      — Foreground Service, держит BLE живым в фоне
    BleManager.kt      — Meshtastic BLE API (ToRadio/FromRadio/FromNum)
    BleScanner.kt      — сканирование BLE устройств
  protocol/
    Protocol.kt        — MessagePack encode/decode + wrapInToRadio()
    Packet.kt          — OutPacket, PacketType, GATEWAY_NODE_NUM
    DeliveryQueue.kt   — гарантия доставки (6 попыток, coroutines)
  state/
    DeviceStateManager.kt — локальное хранение состояний (pending/confirm/rollback)
  ui/
    MainActivity.kt    — UI: bindService + BroadcastReceiver
  App.kt
app/src/main/proto/
  mesh.proto           — FromRadio, ToRadio, MeshPacket, Data (protobuf lite)
```

## Железо

- **T1000-E** `AleX_c891` MAC: `EF:A6:95:F2:C8:91` node `!95f2c891` — BLE клиент
- **T114** node `!077ccb09` = `125747977` decimal — шлюз на стороне HA
- **HAOS**: `192.168.1.114:8123`, SSH `:222` root/775Ho
- **T114 serial**: `/dev/serial/by-id/usb-1a86_USB_Serial-if00-port0`
- **PIN паринга T1000-E**: `123456` (Meshtastic FIXED_PIN)

## Meshtastic BLE API

T1000-E использует Meshtastic BLE API, **не** Nordic UART Service:

```
Service:   6ba1b218-15a8-461f-9fa8-5dcae273eafd
ToRadio:   f75c76d2-129e-4dad-a1dd-7866124401e7  (write)
FromRadio: 2c55e69e-4993-11ed-b878-0242ac120002  (read)
FromNum:   ed9da18c-a800-4f66-a670-aa7547e34453  (notify)
```

### Handshake
```
1. connect() + requestMTU(512) → реальный MTU=247
2. enableNotifications(fromNum)
3. write(toRadio, [0x18, 0x00])   ← startConfig (want_config_id=0)
4. poll fromRadio пока не пустой
5. получить CONFIG_COMPLETE_ID (field 6) → BleState.Ready
6. при notify fromNum → читать fromRadio в цикле
```

### Отправка пакета (КЛЮЧЕВОЕ)
```
to = 0xFFFFFFFF  ← broadcast (не direct message!)
channel = 0      ← PRIMARY (LongFast, key=AQ==)
portnum = 256    ← PRIVATE_APP
```
Direct message (to=node_num) не работает — Meshtastic 2.x использует PKC шифрование для DM, у нас нет ключей. Только broadcast.

### Protobuf (mesh.proto)
```protobuf
message Data      { uint32 portnum=1; bytes payload=2; }
message MeshPacket { fixed32 from=1; fixed32 to=2; Data decoded=3; fixed32 id=9; ... }
message FromRadio  { oneof { MeshPacket packet=2; uint32 config_complete_id=6; ... } }
message ToRadio    { oneof { MeshPacket packet=1; uint32 want_config_id=3; } }
```
**fixed32** для from/to/id — не uint32! Иначе portnum=0 при парсинге.

## Наш протокол (поверх Meshtastic)

Порт: `PRIVATE_APP = 256`, сериализация: **MessagePack**

```
tp:1 CONFIRM  — HA→телефон, команда выполнена
tp:2 STATUS   — HA→телефон, состояние по запросу
tp:3 PUSH     — HA→телефон, изменение состояния
tp:4 CONFIG   — HA→телефон, конфиг (пагинация pg/pgt)
tp:5 CMD      — телефон→HA, команда
tp:6 PING     — телефон→HA / PONG с cfgh в ответ
```

### DeliveryQueue
- Ключ = `devId` (один пакет на устройство)
- Попытки 1-3: `hl=0` (прямая), 4-6: `hl=7` (через mesh)
- Подтверждение по tp:1 CONFIRM
- Без id — прямая отправка без очереди
- Дедупликация: `enqueue(devId)` удаляет старую запись
- lastAttempt — реальное время отправки (не время создания пакета)

## Foreground Service архитектура

```
BleService (LifecycleService — всегда жив, START_STICKY)
    ├── LoRemoteBleManager
    ├── BleScanner
    ├── pingLoop — каждые 60 сек если Ready
    ├── updateNotification() — статус в шторке
    └── sendBroadcast(ACTION_PACKET) → MainActivity

MainActivity
    ├── startForegroundService() + bindService()
    ├── BroadcastReceiver(ACTION_PACKET) → handlePacket()
    └── onStop(): unbind но НЕ stopService
```

### Permissions (AndroidManifest.xml)
```xml
FOREGROUND_SERVICE
FOREGROUND_SERVICE_CONNECTED_DEVICE
POST_NOTIFICATIONS
```
```xml
<service android:name=".ble.BleService"
         android:foregroundServiceType="connectedDevice"
         android:exported="false"/>
```

## Зависимости (app/build.gradle.kts)

```
AGP: 8.4.0, Kotlin: 1.9.22, JVM target: 21, Gradle: 8.6
nordic ble: 2.7.4 + ble-ktx:2.7.4
msgpack-core: 0.9.8
protobuf-kotlin-lite: 3.25.1
coroutines-android: 1.7.3
lifecycle-service: 2.7.0
lifecycle-runtime-ktx: 2.7.0
appcompat: 1.6.1, material: 1.11.0
```

## Критичные паттерны

- `LifecycleService` для BleService (не просто Service) — нужны coroutines
- `setGattCallbacks(this)` перед `connect()`
- `BleManagerCallbacks` интерфейс — не переопределять методы напрямую
- `BleState.Ready` устанавливать при `CONFIG_COMPLETE_ID`, не при пустом буфере
- Broadcast пакеты (не DM) — `to=0xFFFFFFFF, channel=0`
- `SharedPreferences("loremote")` — хранить `last_device_mac`, `last_device_name`
- `source ~/.bashrc` перед командами если нужен nvm

## Известные проблемы → решения

### Бэйджи для статусов
- `badge_yellow.xml`, `badge_neutral.xml` — для статусов SI, A, BS
- `badge_green.xml`, `badge_red.xml` — для BS (Норма/Тревога)

### Критичные паттерны
- `zonesContainer` должен быть полем класса, не локальной переменной в `onCreateView`
- `ControlFragment.onResume` — читать конфиг из SharedPreferences, не из `main.savedConfig`
- `applyConfig` сохраняет **оригинальный** JSON в SharedPreferences, не очищенный

## Известные проблемы → решения

| Проблема | Решение |
|---------|---------|
| portnum=0 при парсинге | fixed32 для from/to/id в MeshPacket, не uint32 |
| PING не уходит после Ready | Слать в state.collect{ Ready }, не в onDeviceReady |
| Direct message не доходит | Только broadcast to=0xFFFFFFFF |
| BLE рвётся в фоне | Foreground Service с START_STICKY |
| Gradle не найден | `/tmp/gradle-8.6/bin/gradle`, не системный |
| Nordic API | v2.7.4 требует BleManagerCallbacks интерфейс |
| T1000-E не виден в скане | Закрыть Meshtastic app — BLE занят |
| zonesContainer пустой после applyConfig | zonesContainer — поле класса, не локальная переменная |
| devices не попадают в зоны при `a: null` | optString("a") == "" или `"null"` — проверять оба |
| buildZones не вызывается при переключении | Читать из SharedPreferences в onResume |
| DeliveryQueue.NPE при старте | getSharedPreferences перенести в onCreate() — контекст null в конструкторе Service |
| BLUETOOTH_SCAN crash в onResume | Проверять hasBlePermissions() перед startScan() в SettingsFragment |
| Автоподключение через getRemoteDevice падает | Использовать connectToDevice() — он сохраняет MAC |
| Жёлтая иконка BLE | При fail автоконнекта иконка остаётся yellow — fix: connectTo(device.device) |
| tryAutoConnect() рвёт соединение при включении экрана | Проверять state перед connectTo(), убрать вызов из onStart() |
| retryCount=0 вызывал onFailed() после первой отправки | При retryCount=0 считать delivered после первого send |

## Статус v0.5.7 ✅ (текущий)

### Исправления
- [x] DeliveryQueue — полная переделка: loop-based, без рекурсии, oldValue/newValue
- [x] DeliveryQueue — thread-safe (Collections.synchronizedMap)
- [x] DeliveryQueue — confirmed/rollback через oldValue/newValue
- [x] DeliveryQueue — enqueue с oldValue/newValue, confirm с confirmedValues
- [x] DeliveryQueue — start() в onCreate, stop() в onDestroy
- [x] BleService — GlobalScope убран из sendFn
- [x] sendPacket — oldValue/newValue вместо stateChanges
- [x] handlePacket — id через toString() вместо as? String
- [x] SettingsFragment — ping check по state, queue refresh loop 1 сек

### Исправления
- [x] DeliveryQueue.NPE — чтение SharedPreferences перенесено из конструктора в onCreate()
- [x] BLE сканирование в SettingsFragment — только если разрешения уже получены (hasBlePermissions)
- [x] Автоподключение при запуске — вызывается из onServiceConnected()
- [x] Кнопка "Отключить" вместо "Найдено: X" в настройках
- [x] Жёлтая иконка — авто-подключение через connectToDevice()
- [x] Заголовки в Settings и Control — центрирование, стиль как в HTML
- [x] DeliveryQueue — дедупликация, FIFO, real timestamps, direct send
- [x] v0.5.3 — Integer→Long safe casts (normalize, toLong, extractStateValues)
- [x] v0.5.4 — BleService diagnostic logs (onCreate, onStartCommand)
- [x] v0.5.5 — BLE icon restore on re-bind, remove saveLastDevice from onDeviceDisconnected
- [x] v0.5.6 — tryAutoConnect() state check, retryCount=0 → delivered

### Полная версия v0.5.0 ✅
- [x] BLE сканирование + фильтрация Meshtastic устройств
- [x] Автоподключение к последнему устройству (SharedPreferences)
- [x] Meshtastic handshake (config_complete_id → Ready)
- [x] Protobuf парсинг FromRadio (fixed32, все типы handshake)
- [x] Broadcast отправка portnum=256
- [x] PING доходит до T114, плагин получает
- [x] MessagePack encode/decode
- [x] DeliveryQueue (6 попыток)
- [x] Foreground Service (BleService, LifecycleService, START_STICKY)
- [x] Ping loop каждые 60 сек в фоне
- [x] Уведомление в шторке со статусом BLE
- [x] DeviceStateManager (pending/confirm/rollback для состояний устройств)
- [x] DeliveryQueue → DeviceStateManager.onDelivered / onFailed
- [x] requestAll при подключении (BleService)
- [x] sendPacket через DeliveryQueue с stateChanges
- [x] Контроль UI: toggle серый (PENDING), красный (FAILED)
- [x] Debounce слайдеров в DevicePopupDialog (500мс)
- [x] Alarm notification при тревогах
- [x] bindService в MainActivity + BroadcastReceiver
- [x] Полная переработка UI: тёмная тема, серая палитра, цветные статусы
- [x] Header с иконками BLE/HA/шестерёнка
- [x] Bottom Navigation с двумя вкладками (Управление / Настройки)
- [x] Полный UI (зоны, карточки устройств)
  - [x] Строки устройств с toggle/значением/бейджем по типу (L, SW, C, WH, F, H, B, CV, LK, SI, A, S, BS)
  - [x] Подписи под названием (subText)
  - [x] Долгий тап → карточка, обычный тап → toggle
  - [x] Плейсхолдер при отсутствии конфига
- [x] Настройки: конфиг, BLE-сканер, PING-диагностика, Delivery Queue (retry count/interval)
- [x] DevicePopupDialog — bottom sheet с контролами по типу устройства
- [x] Alert bar для тревог
- [x] Drawable ресурсы: bg_zone_card, badge_red, badge_green, badge_yellow, badge_neutral, иконки

## TODO (следующие шаги)

- [x] BLE сканирование + фильтрация Meshtastic устройств
- [x] Автоподключение к последнему устройству (SharedPreferences)
- [x] Meshtastic handshake (config_complete_id → Ready)
- [x] Protobuf парсинг FromRadio (fixed32, все типы handshake)
- [x] Broadcast отправка portnum=256
- [x] PING доходит до T114, плагин получает
- [x] MessagePack encode/decode
- [x] Foreground Service (BleService, LifecycleService, START_STICKY)
- [x] Ping loop каждые 60 сек в фоне
- [x] Уведомление в шторке со статусом BLE
- [x] DeviceStateManager (pending/confirm/rollback для состояний устройств)
- [x] requestAll при подключении (BleService)
- [x] Контроль UI: toggle серый (PENDING), красный (FAILED)
- [x] Debounce слайдеров в DevicePopupDialog (500мс)
- [x] Alarm notification при тревогах
- [x] bindService в MainActivity + BroadcastReceiver
- [x] Полная переработка UI: тёмная тема, серая палитра, цветные статусы
- [x] Header с иконками BLE/HA/шестерёнка
- [x] Bottom Navigation с двумя вкладками (Управление / Настройки)
- [x] Полный UI (зоны, карточки устройств)
  - [x] Строки устройств с toggle/значением/бейджем по типу (L, SW, C, WH, F, H, B, CV, LK, SI, A, S, BS)
  - [x] Подписи под названием (subText)
  - [x] Долгий тап → карточка, обычный тап → toggle
  - [x] Плейсхолдер при отсутствии конфига
- [x] Настройки: конфиг, BLE-сканер, PING-диагностика
- [x] DevicePopupDialog — bottom sheet с контролами по типу устройства
- [x] Alert bar для тревог
- [x] Drawable ресурсы: bg_zone_card, badge_red, badge_green, badge_yellow, badge_neutral, иконки

## TODO (следующие шаги)

1. **Авторизация** — SHA-256 пароль, несколько пользователей
