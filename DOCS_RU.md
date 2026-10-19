# Техническая документация разработчика MiniSIP

---

## 1. Архитектурный обзор и паттерны проектирования

MiniSIP реализован на основе декларативного разделения ответственности, где весь функциональный стек декомпозирован на независимые модули, связываемые через Java-интерфейсы.

```
                    +----------------------------------+
                    |             App.java             |
                    |    (Composition Root, Context)   |
                    +----------------------------------+
                      /       |         |        |    \
                     /        |         |        |     \
                    v         v         v        v      v
             +-------+  +-------+  +------+  +-----+  +-------+
             | Udp   |  | Media |  | Sip  |  | Vpn |  |Update |
             +-------+  +-------+  +------+  +-----+  +-------+
                 |          |          |        |         |
                 v          v          v        v         v
             UdpImpl   MediaImpl   SipImpl   VpnImpl  UpdateImpl
                            |                   |
                     AndroidAudio           TunService / Tunnel
```

### 1.1 Корень композиции (`App.java`)
Класс [`App`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/App.java) выступает синглтоном приложения (`Application`), центральным координатором потоков данных и реализации слушателей:
- `Sip.Listener`: Обработка изменений состояния SIP-стека (смена статуса регистрации, входящие вызовы, сброс звонка).
- `Vpn.Listener`: Обработка статуса IKEv2/IPsec туннеля (подключение, обрывы, статистика потерь пакетов).

### 1.2 Правило единого активного соединения (Single Active Connection)
С целью предотвращения гонки состояний и рекурсивных сетевых конфликтов в приложении действует правило:
- Переменная `App.lastPage` фиксирует индекс страницы (`SettingsFragment`), создавшей активное соединение.
- Метод `App.busy()` возвращает `true`, если активирован вызов, SIP-регистрация или VPN.
- Метод `App.allowed(int page)` блокирует попытки управления соединениями с других страниц, пока активна выбранная страница.

---

## 2. Полный каталог методов и спецификация классов

### 2.1 Пакет `ru.minisip.app` (Пользовательский интерфейс и сервисы)

#### [`App.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/App.java)
- `void addChangeListener(Runnable r)`: Регистрация слушателя изменений UI.
- `void removeChangeListener(Runnable r)`: Удаление слушателя изменений UI.
- `boolean isCallActive()`: Проверка активности звонка.
- `boolean busy()`: Проверка занятости SIP, вызова или VPN.
- `boolean allowed(int page)`: Проверка прав страницы на управление сессией.
- `void onCreate()`: Инициализация сетевых сокетов, аудио, SIP, VPN, буферов логов.
- `LogBuffer vpnLog()`: Кольцевой буфер логов VPN.
- `LogBuffer callLog()`: Кольцевой буфер логов вызова.
- `LogBuffer sipLog()`: Кольцевой буфер SIP-сигнализации.
- `LogBuffer updateLog()`: Кольцевой буфер обновлений.
- `Update update()`: Менеджер обновлений.
- `void connect(...)`: Запуск SIP-регистрации.
- `void disconnect(...)`: Сброс SIP-регистрации.
- `boolean vpnConsent(...)`: Запрос системного согласия на VPN.
- `void vpnConnect(...)`: Подключение VPN туннеля.
- `void vpnDisconnect(...)`: Отключение VPN туннеля.
- `String vpnDpd()`: Статус DPD.
- `boolean vpnMobike()`: Поддержка MOBIKE.
- `boolean isVpnStrict()`: Проверка строгого режима watchdog.
- `void setVpnStrict(boolean on)`: Установка строгого режима watchdog.
- `int getVpnLossCount()`: Счётчик потерь пингов.
- `void vpnDenied(int page)`: Обработка отказа разрешений VPN.
- `void vpnError(...)`: Обработка ошибок VPN.
- `void onUp(...)`, `void onReconnecting(...)`, `void onDown(...)`: Слушатели состояния VPN.
- `void dial(...)`, `void setSpeaker(...)`, `void setMute(...)`, `boolean isMuted()`, `void hangup(...)`, `void transfer(...)`: Управление вызовом.
- `void onRekey(...)`, `void onPingLoss(...)`, `void onRegistered(...)`, `void onRinging(...)`, `void onConnected(...)`, `void onEnded(...)`: Слушатели SIP/VPN событий.

#### [`CallService.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/CallService.java)
- `int onStartCommand(...)`: Запуск Foreground Service типа `microphone`.
- `IBinder onBind(...)`: Возвращает null.

#### [`MainActivity.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/MainActivity.java)
- `static void showCustomConfirmDialog(...)`: Диалог с кастомными кнопками.
- `void onCreate(...)`: Инициализация ViewPager2 и полосы обновлений.
- `void pagesChanged()`: Пересчёт доступных страниц.
- `void pageDeleted(int pageToDelete)`: Удаление карточки настроек.
- `void onRequestPermissionsResult(...)`: Обработка разрешений аудио.
- `void onActivityResult(...)`: Передача результата согласия VPN во фрагмент.

#### [`PageStore.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/PageStore.java)
- `static SharedPreferences prefs(...)`: Получение настроек страницы.
- `static int count(...)`, `static void setCount(...)`: Количество страниц.
- `static boolean configured(...)`: Проверка заполнения АТС.
- `static List<Refer> refers(...)`, `static void setRefers(...)`: Список контактов перевода.
- `static void deletePage(...)`: Удаление страницы со сдвигом индексов.
- `Refer(String, String)`, `String label()`: Контакт перевода.

#### [`SettingsFragment.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/SettingsFragment.java)
- `static SettingsFragment create(int page)`: Фабричный метод создания фрагмента.
- `void onCreate(...)`, `View onCreateView(...)`, `void onPause()`: Жизненный цикл фрагмента.
- `void onVpnConsent(...)`, `String appVersion()`, `void showChannelDialog()`, `void doConnect()`, `void doCall()`, `void toggleMute()`, `void doVpnConnect()`, `void vpnStart()`, `void pickApps()`, `void pickSpeaker(...)`, `void showRefersDialog()`, `void render()`, `void save(...)`, `void load(...)`: Логика UI и обработчики действий.

#### [`UpdateBar.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/UpdateBar.java)
- `void attachInset(...)`, `void setVisibility(...)`, `void showProgress(...)`, `void showInstall(...)`, `void hide()`: Управление плашкой прогресса обновлений.

---

### 2.2 Пакет `ru.minisip.log` (Логирование)

#### [`LogSink.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/log/LogSink.java)
- `void log(String line)`: Запись строки лога.

#### [`LogBuffer.java` & `LogBufferImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/log/LogBufferImpl.java)
- `static LogBuffer create(...)`, `boolean isEnabled()`, `void setEnabled(...)`, `void clear()`, `String text()`, `void setListener(...)`, `void load(...)`, `void setPersist(...)`: Потокобезопасный кольцевой буфер логов в памяти.

#### [`LogFile.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/log/LogFile.java)
- `static String save(Context ctx, String prefix, String text)`: Сохранение лога в `Documents/MiniSIP/logs`.

---

### 2.3 Пакет `ru.minisip.media` (Аудио и RTP)

#### [`Audio.java` & `AndroidAudio.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/media/AndroidAudio.java)
- `boolean start()`, `int read(...)`, `void write(...)`, `void stop()`, `void setSpeaker(...)`, `void setMute(...)`, `boolean isMuted()`: Управление аудиозаписью и воспроизведением через `AudioRecord` и `AudioTrack` с эхоподавлением.

#### [`G711.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/media/G711.java)
- `static byte encode(int pt, short pcm)`, `static short decode(int pt, byte b)`: Кодирование и декодирование G.711 A-law / u-law.

#### [`Media.java` & `MediaImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/media/MediaImpl.java)
- `static Media create(...)`, `int open()`, `boolean start(...)`, `void stop()`, `void setSpeaker(...)`, `void setMute(...)`, `boolean isMuted()`, `void onPacket(...)`: Управление потоками RTP-захвата (`rtp-capture`) и воспроизведения (`rtp-playback`).

---

### 2.4 Пакет `ru.minisip.net` (Сеть и UDP)

#### [`Binder.java` & `AndroidBinder.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/net/AndroidBinder.java)
- `boolean bind(DatagramSocket s, String iface)`: Привязка сокета к сетевому интерфейсу через `Network.bindSocket()`.

#### [`Udp.java` & `UdpImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/net/UdpImpl.java)
- `static Udp create(...)`, `int open(...)`, `boolean send(...)`, `String localIp(...)`, `void bind(...)`, `void close()`, `String error()`: Управление асинхронным UDP-сокетом и демоном приёма пакетов (`udp-rx`).

---

### 2.5 Пакет `ru.minisip.screen` (Управление экраном)

#### [`Screen.java` & `ScreenImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/screen/ScreenImpl.java)
- `static Screen create(...)`, `void start()`, `void stop()`: Управление датчиком приближения и WakeLock блокировками.

---

### 2.6 Пакет `ru.minisip.sip` (Сигнализация SIP/SDP)

#### [`Digest.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/Digest.java)
- `static String answer(...)`: Вычисление HTTP Digest MD5 авторизации.

#### [`Msg.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/Msg.java)
- `static Msg parse(...)`, `static String param(...)`, `static String uri(...)`, `static String user(...)`, `static String eventName(...)`, `int status()`, `String reason()`, `String method()`, `String get(...)`, `List<String> all(...)`, `int cseq()`, `String key()`: Разбор и генерация SIP-сообщений.

#### [`Sdp.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/Sdp.java)
- `static String make(...)`, `static String[] parse(...)`: Формирование и разбор SDP для G.711.

#### [`Sip.java` & `SipImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/SipImpl.java)
- `static Sip create(...)`, `void setListener(...)`, `void setLogSink(...)`, `void register(...)`, `void unregister()`, `void call(...)`, `void transfer(...)`, `void hangup()`: SIP-клиент (UAC) на выделенном потоке `"sip"` с конечным автоматом состояний.

---

### 2.7 Пакет `ru.minisip.update` (Автообновление)

#### [`Http.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/update/Http.java)
- `static String get(...)`, `static void download(...)`: HTTP GET запросы и скачивание файлов.

#### [`Timestamps.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/update/Timestamps.java)
- `static long parseIso8601(...)`: Парсинг ISO 8601 дат в миллисекунды UTC.

#### [`Update.java` & `UpdateImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/update/UpdateImpl.java)
- `static Update create(...)`, `void setChannel(...)`, `String channel()`, `void setListener(...)`, `void check(...)`, `void download(...)`, `void install()`, `void setLogSink(...)`: Проверка GitHub Releases, фоновое скачивание APK и установка через `FileProvider`.

---

### 2.8 Пакет `ru.minisip.vpn` (IKEv2 / IPsec VPN)

#### [`Tun.java` & `AndroidTun.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/AndroidTun.java)
- Создание и управление виртуальным TUN-интерфейсом через `VpnService.Builder`.

#### [`Certs.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Certs.java)
- Парсинг X.509 сертификатов, проверка PKIX цепочек доверия, SAN имён и подписей AUTH.

#### [`Crypto.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Crypto.java)
- Криптографические примитивы IPsec/IKEv2: DH Group 14, AES-CBC, AES-GCM-16, HMAC-SHA1/SHA256, PRF и шифрование `Crypto.Box`.

#### [`Esp.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Esp.java)
- Упаковка и распаковка пакетов IPsec ESP (RFC 4303) с антиреплей окном.

#### [`Ike.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Ike.java)
- Полная реализация IKEv2 (RFC 7296): IKE_SA_INIT, аутентификация PSK и EAP-MSCHAPv2, MOBIKE и DPD.

#### [`Tunnel.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Tunnel.java)
- Активный VPN-сеанс и рабочие потоки мониторинга связи, keepalive и авто-реконнекта.

#### [`VpnImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/VpnImpl.java)
- Оркестратор VPN с поддержкой разрешений, WakeLock, подписок на сеть и восстановления соединения.

---

## 3. Поддерживаемые стандарты RFC и требования к среде

### 3.1 Поддерживаемые стандарты RFC
- **SIP**: RFC 3261 (Core SIP), RFC 3515 (SIP REFER), RFC 4566 (SDP), RFC 7616 / 2617 (HTTP Digest Auth).
- **Media**: RFC 3550 (RTP), ITU-T G.711 (PCMA/PCMU).
- **VPN / IPsec**: RFC 7296 (IKEv2), RFC 4303 (ESP), RFC 2759 (MS-CHAPv2), RFC 4555 (MOBIKE).

### 3.2 Требования к среде исполнения
- **Android SDK**: Min SDK 24 (Android 7.0), Target SDK 34 (Android 14).
- **Память**: Фиксированные кольцевые буферы предотвращают утечки памяти OOM.
- **Сетевые разрешения**: Объявление `<queries>` с фильтром `MAIN/LAUNCHER` для работы списка приложений VPN на Android 11+.
