# 📋 Промпт 1: Полный анализ архитектуры, графа зависимостей и файлов проекта MiniSIP

## 1. Контекст проекта и принципы архитектуры (KISS, DRY, SOLID, DIP)

**MiniSIP** — компактный, автономный Android-клиент для SIP/SDP/G.711 телефонии и IKEv2/IPsec VPN.

### Ключевые архитектурные правила:
1. **Zero External Dependencies (Нулевые внешние зависимости)**:
   - Нет сторонних библиотек (OkHttp, PJSIP, BouncyCastle, Dagger/Hilt, RxJava, Retrofit и т.д.).
   - Все протоколы (SIP, SDP, G.711, IKEv2, ESP, MS-CHAPv2, MD4, DH Group 14) реализованы на чистом **Java / Android SDK** (`java.net`, `java.security`, `javax.crypto`, `android.media`, `android.net.VpnService`).
2. **Инверсия зависимостей (SOLID / DIP) & Изоляция реализаций**:
   - На верхний уровень API выведены **только интерфейсы** (`Sip`, `Media`, `Udp`, `Vpn`, `Screen`, `Update`, `LogBuffer`, `LogSink`).
   - Все классы реализаций (`*Impl`, `Android*`) скрыты в пакетах (`package-private`).
   - Единственная точка композиции и связывания всех модулей — класс `App.java`.
3. **Простые типы данных между слоями**:
   - Обмен между модулями происходит исключительно через примитивы (`String`, `int`, `boolean`, `byte[]`, `short[]`), стандартные `Runnable` и простые интерфейсы-слушатели (`Listener`).

---

## 2. Структура файлов по пакетам и методы

### 📦 `ru.minisip.app` (UI и Корень Композиции)
- `App.java`: Application, корень композиции всех модулей.
  - `onCreate()`: Инициализация компонентов (`Udp`, `Media`, `Sip`, `Screen`, `Vpn`, `LogBuffer`, `Update`).
  - `busy()`: `boolean` — активны ли вызов, SIP-регистрация или VPN.
  - `allowed(page)`: `boolean` — проверка прав страницы UI на управление сессией.
  - `connect(page, host, port, user, pass)`, `disconnect(page)`: Управление SIP-регистрацией.
  - `vpnConsent(activity, reqCode)`, `vpnConnect(...)`, `vpnDisconnect(...)`: Управление VPN.
  - `dial(page, number)`, `transfer(page, target)`, `hangup(page)`: Управление вызовом и слепым переводом (Blind Transfer).
  - `setSpeaker(on)`, `setMute(mute)`, `isMuted()`: Управление голосовым трактом.
  - Обработчики `Vpn.Listener` и `Sip.Listener`.
- `MainActivity.java`: Главная Activity с `ViewPager2` карточек настроек и плашкой обновлений `UpdateBar`.
  - `onCreate()`, `pagesChanged()`, `pageDeleted()`, `showCustomConfirmDialog()`, `onActivityResult()`.
- `CallService.java`: Foreground Service для работы микрофона при погашенном экране.
  - `onStartCommand()`, `onBind()`.
- `PageStore.java`: Хранение и управление многостраничными настройками и списком контактов перевода (`Refers`) в `SharedPreferences`.
  - `prefs(c, page)`, `count()`, `setCount()`, `configured()`, `refers(c, page)`, `setRefers(c, page, list)`, `deletePage()`.
- `SettingsFragment.java`: Фрагмент карточки настроек (SIP, Звонок, VPN, Refers, Логи) с поддержкой Drag-and-Drop блоков.
  - `doConnect()`, `doCall()`, `toggleMute()`, `doVpnConnect()`, `toggleRefers()`, `renderRefers()`, `editRefer()`, `confirmTransfer()`, `pickApps()`, `pickOutput()`, `render()`.
- `UpdateBar.java`: UI-плашка прогресса скачивания и установки APK.
  - `attachInset()`, `showProgress()`, `showInstall()`, `hide()`.

### 📦 `ru.minisip.log` (Логирование)
- `LogSink.java` *(интерфейс)*: `void log(String line)` — базовая запись строки лога.
- `LogBuffer.java` *(интерфейс)*: `setEnabled()`, `clear()`, `text()`, `setListener()`, `create(maxLines)`.
- `LogBufferImpl.java`: Потокобезопасный кольцевой буфер на `ArrayDeque` с временными метками `HH:mm:ss.SSS`.
- `LogFile.java`: Сохранение текстовых логов в `Downloads/MiniSIP/logs` (MediaStore API на Android 10+, File API на Android 9-).
  - `save(ctx, prefix, text)`.

### 📦 `ru.minisip.media` (Голос и RTP)
- `Audio.java` *(интерфейс)*: `start()`, `read(short[])`, `write(short[], n)`, `stop()`, `setSpeaker()`, `setMute()`.
- `AndroidAudio.java`: Работа с `AudioRecord`, `AudioTrack`, `AudioManager`, переключение разговорного/медиа динамика.
- `G711.java`: Кодек G.711 A-law (PCMA) / µ-law (PCMU).
  - `encode(pt, pcm)`, `decode(pt, b)`.
- `Media.java` *(интерфейс)*: `open()`, `start(host, port, payload)`, `stop()`, `setSpeaker()`, `setMute()`, `create(...)`.
- `MediaImpl.java`: RTP-поток за 20 мс на пакет. Потоки `rtp-capture` (микрофон -> G.711 -> RTP) и `rtp-playback` (RTP -> G.711 -> динамик). `onPacket()` для Symmetric RTP.

### 📦 `ru.minisip.net` (Сетевой стек и UDP)
- `Binder.java` *(интерфейс)*: `bind(DatagramSocket, iface)`.
- `AndroidBinder.java`: Привязка сокетов к сети интерфейса (`Network.bindSocket`).
- `Udp.java` *(интерфейс)*: `open(port, listener)`, `send(data, host, port)`, `localIp(host, port)`, `bind(iface)`, `close()`, `create(...)`.
- `UdpImpl.java`: Асинхронный UDP-сокет с демоном приема пакетов `udp-rx`.

### 📦 `ru.minisip.screen` (Управление экраном)
- `Screen.java` *(интерфейс)*: `start()`, `stop()`, `create(ctx)`.
- `ScreenImpl.java`: Управление гашением экрана через датчик приближения (`PROXIMITY_SCREEN_OFF_WAKE_LOCK`).

### 📦 `ru.minisip.sip` (SIP/SDP Протокол)
- `Digest.java`: HTTP Digest MD5 авторизация (RFC 2617 / RFC 3261). `answer(...)`.
- `Msg.java`: Парсинг и генерация SIP-сообщений, раскрытие компактных заголовков (`r` -> `refer-to`, `o` -> `event`). `parse(...)`, `eventName(...)`.
- `Sdp.java`: Генерация и разбор SDP тело вызова для G.711. `make()`, `parse()`.
- `Sip.java` *(интерфейс)*: `setListener()`, `setLogSink()`, `register()`, `unregister()`, `call()`, `transfer(target)`, `hangup()`, `create(...)`.
- `SipImpl.java`: SIP UAC клиент. Однопоточный исполняемый контекст `ScheduledExecutorService` (`"sip"`). Поддерживает `REFER` и входящий `NOTIFY` с `message/sipfrag`.

### 📦 `ru.minisip.update` (Авто-обновление)
- `Http.java`: GET-запросы JSON и скачивание файлов с прогрессом на `HttpURLConnection`.
- `Timestamps.java`: Парсер ISO 8601 дат из GitHub API.
- `UpdateConfig.java`: Константы URL и параметров.
- `Update.java` *(интерфейс)*: `setChannel()`, `check()`, `download()`, `install()`, `setLogSink()`, `create(...)`.
- `UpdateImpl.java`: Фоновая проверка GitHub Releases, считывание `lastUpdateTime` и запуск установки через `FileProvider`.

### 📦 `ru.minisip.vpn` (IKEv2/IPsec VPN)
- `AndroidTun.java`: Создание виртуального TUN-интерфейса через `VpnService.Builder`.
- `Certs.java`: X.509 PEM/DER парсер, проверка цепочки PKIX, SAN имён и подписей AUTH (RFC 7296/7427).
- `Crypto.java`: Криптобиблиотека IPsec/IKEv2 (DH Group 14, AES-CBC, AES-GCM-16, HMAC-SHA1/SHA256, PRF). Содержит классы `Suite` и `Box`.
- `Esp.java`: Упаковка/распаковка IPsec ESP (RFC 4303) с анти-повторным окном. `wrap()`, `unwrap()`.
- `Ike.java`: IKEv2 кодировщик/декодировщик (RFC 7296, PSK, EAP-MSCHAPv2, MOBIKE RFC 4555, DPD).
- `Mschap.java`: MS-CHAPv2 (RFC 2759) и MPPE (RFC 3079) с собственной реализацией MD4.
- `NetWatch.java`: Монитор физической сети с дебаунсом изменений.
- `Tun.java` *(интерфейс)*: Внутренняя абстракция чтения/записи IP-пакетов.
- `TunService.java`: Системный `VpnService` Foreground Service.
- `Tunnel.java`: Активный VPN-сеанс (потоки `vpn`, `vpn-rx`, `vpn-tun`, `vpn-keep`, `vpn-mobike`).
- `Vpn.java` *(интерфейс)*: `consent()`, `connect()`, `disconnect()`, `setStrict()`, `create(...)`.
- `VpnImpl.java`: Оркестратор VPN с авто-восстановлением, WakeLock и подпиской на сеть.

---

## 3. Граф зависимостей текущего кода

```
[app] (App, CallService, MainActivity, SettingsFragment, PageStore, UpdateBar)
 ├──> [sip] (Sip)
 ├──> [media] (Media)
 ├──> [net] (Udp, Binder)
 ├──> [screen] (Screen)
 ├──> [vpn] (Vpn)
 ├──> [update] (Update)
 └──> [log] (LogBuffer, LogSink)

[sip] (SipImpl)
 ├──> [media] (Media interface)
 ├──> [net] (Udp interface)
 └──> [log] (LogSink)

[media] (MediaImpl)
 └──> [net] (Udp interface)

[vpn] (VpnImpl, Tunnel)
 └──> [log] (LogSink)

[update] (UpdateImpl)
 └──> [log] (LogSink)

[net], [screen], [log] — автономные нижние слои/пакеты
```

---

## 4. Сильные стороны и возможности улучшения архитектуры

### Сильные стороны:
1. **Строгая изоляция и DIP**: Все модули взаимодействуют только через интерфейсы и примитивные типы данных.
2. **Нулевая оглядка на тяжелые фреймворки**: Высокая скорость запуска, малый размер APK, минимум накладных расходов.
3. **Автономность компонентов**: Каждая подсистема (`vpn`, `sip`, `media`) может тестироваться изолированно благодаря мокам интерфейсов (`Udp`, `Audio`, `Tun`).

### Потенциальные узкие места для рефакторинга:
1. **Монолитность `App.java`**: Класс `App` агрегирует всё состояние приложения и выступает крупным контроллером.
2. **Связность внутри `vpn`**: Пакет `vpn` содержит 12 классов с высокой плотностью внутренней логики (IKE, ESP, Crypto, Tun, Service), которые можно разделить на внутренние подпакеты при расширении.
