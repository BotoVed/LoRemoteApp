# LoRemote Android

<img src="https://github.com/BotoVed/LoRemote/raw/main/brand/icon.png" width="96" alt="LoRemote">

**Нативное Android-приложение для системы [LoRemote](https://github.com/BotoVed/LoRemote)**

Управляй умным домом через LoRa-радио — без интернета, без облаков, без подписок.

---

## Что это

LoRemote Android — мобильный клиент для системы LoRemote. Подключается к LoRa-устройству [SenseCAP T1000-E](https://wiki.seeedstudio.com/sensecap_t1000_e/) по Bluetooth и отправляет команды через mesh-сеть Meshtastic напрямую в Home Assistant.

```
[Телефон + это приложение]
        ↕ Bluetooth (BLE)
  [SenseCAP T1000-E]
        ↕ LoRa 868MHz
  [Heltec T114] ──USB──▶ [Home Assistant + плагин LoRemote]
```

---

## Возможности

- Подключение к T1000-E по BLE без интернета
- Управление всеми устройствами HA: свет, климат, замки, жалюзи, датчики и др.
- Гарантированная доставка команд — 6 попыток, автопереключение на mesh
- Логин с паролем, несколько пользователей
- Лог входящих и исходящих пакетов
- Автоматическая синхронизация конфига со шлюзом
- Работает полностью офлайн

---

## Требования

- Android 8.0 (Oreo) или новее
- Bluetooth 5.0+
- Устройство с прошивкой [Meshtastic](https://meshtastic.org/) — SenseCAP T1000-E или аналог
- Установленный плагин [LoRemote](https://github.com/BotoVed/LoRemote) в Home Assistant

---

## Установка

### APK напрямую

1. Скачать последний APK со страницы [Releases](../../releases)
2. Разрешить установку из неизвестных источников
3. Установить APK

### Сборка из исходников

```bash
# Требования: JDK 21, Android SDK 34
git clone https://github.com/BotoVed/LoRemote-Android
cd LoRemote-Android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Первый запуск

1. Установить и настроить [LoRemote плагин](https://github.com/BotoVed/LoRemote) в HACS
2. В HA: Settings → LoRemote → Экспорт конфига → скопировать `window.LORA_CONFIG`
3. Включить T1000-E, убедиться что он виден в Meshtastic
4. Открыть приложение → нажать **SCAN** → выбрать устройство → **CONNECT**
5. При запросе ввести PIN `123456` (дефолтный для T1000-E)
6. Войти под своим пользователем

---

## Стек технологий

- **Kotlin** + Coroutines + Flow
- **Nordic BLE Library** — подключение к T1000-E
- **MessagePack** (msgpack-core) — бинарный протокол пакетов
- **Meshtastic BLE API** — MeshBluetoothService (ToRadio / FromRadio / FromNum)
- **ViewBinding** — UI без лишних зависимостей
- **minSdk 26** (Android 8.0)

---

## Протокол

Приложение использует порт `PRIVATE_APP 256` сети Meshtastic. Все пакеты сериализованы в [MessagePack](https://msgpack.org/). Подробная документация протокола — в репозитории основного плагина:

→ [LoRemote Protocol](https://github.com/BotoVed/LoRemote/blob/main/InstructionForAgent.md)

---

## Экосистема LoRemote

| Компонент | Репозиторий | Описание |
|-----------|------------|---------|
| HA плагин | [LoRemote](https://github.com/BotoVed/LoRemote) | Серверная часть, HACS |
| Lovelace карточка | [LoRemote-card](https://github.com/BotoVed/LoRemote-card) | Карточка для UI HA |
| Android приложение | **этот репо** | Мобильный клиент |

---

## Железо

| Роль | Устройство |
|------|-----------|
| Шлюз (сторона HA) | Heltec Mesh Node T114 |
| Клиент (в руках) | SenseCAP Card Tracker T1000-E |

Подойдёт любое устройство с прошивкой Meshtastic на nRF52840 с поддержкой BLE.

---

## Статус

🚧 **В разработке** — первый этап: BLE подключение и протокол.

- [x] BLE сканирование и подключение к T1000-E
- [x] MessagePack encode/decode
- [x] DeliveryQueue с гарантией доставки
- [x] Debug UI (лог пакетов)
- [ ] Полный UI (зоны, карточки устройств)
- [ ] Авторизация
- [ ] Push-уведомления при тревогах
- [ ] QR-онбординг конфига

---

## Лицензия

GPLv3 © [BotoVed](https://github.com/BotoVed)
