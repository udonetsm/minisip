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
- `String toggleVpnFromTile(Context ctx)`: Переключает VPN на уровне приложения из плитки Quick Settings без открытия Activity. Возвращает сообщение об ошибке либо `null` при успехе.
- `String connectVpnPage(Context ctx, int page)`: Подключает VPN-профиль указанной страницы настроек. Возвращает сообщение об ошибке либо `null` при успехе. Используется `TileProxyActivity` при открытии выбора профиля из плитки.
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

#### [`TileProxyActivity.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/TileProxyActivity.java)
- `void onCreate(Bundle b)`: Невидимая proxy-activity, запускаемая из плитки Quick Settings. Выбирает режим: показ списка профилей (`EXTRA_PICK == true` либо action `android.service.quicksettings.action.QS_TILE_PREFERENCES`) или прямой переключатель.
- `void onActivityResult(int req, int res, Intent data)`: Продолжает сценарий после возврата системного диалога согласия VPN; при отказе — закрывает activity.
- `void runToggle()`: Вызывает `App.toggleVpnFromTile` и закрывается; Toast показывает сообщение об ошибке.
- `void showPicker()`: Перечисляет все сконфигурированные VPN-профили (`PageStore.configuredVpnPages`) и подключает выбранный через `App.connectVpnPage`.
- `EXTRA_PICK` (константа `"pick"`): если `true` — или action `QS_TILE_PREFERENCES` — activity открывает выбор профиля вместо прямого переключения.

#### [`VpnTileService.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/VpnTileService.java)
- `void onStartListening()`: Обновляет подпись и состояние плитки (`STATE_ACTIVE`, пока `App.vpnUp || App.vpnConnecting`, иначе `STATE_INACTIVE`).
- `void onClick()`: Обработка нажатия на плитку Quick Settings.
  - Если `VpnService.prepare(this) == null` (согласие уже получено) — вызывает `App.toggleVpnFromTile(this)` напрямую, шторка Quick Settings не закрывается. Плитка переключается оптимистично и синхронизируется через 1.5 с.
  - Иначе — запускает `TileProxyActivity` через `startActivityAndCollapse`, чтобы система показала диалог согласия VPN (Android 14+ использует `PendingIntent`-перегрузку).

#### Справочник управления: кнопки, жесты и события (`ru.minisip.app`)

Таблица — авторитетная шпаргалка по всем интерактивным элементам Android-интерфейса MiniSIP. Она дополняет списки методов выше и должна синхронизироваться с `SettingsFragment.onCreateView`, `MainActivity` и `PageStore`.

##### Блок SIP

| Элемент | Триггер | Что делает |
|---------|---------|-----------|
| Поле `Server` | ввод | Автосохраняется в настройки страницы. Парсится `SettingsFragment.doConnect()` как `host[:port]` (порт по умолчанию `5060`). |
| Поля `Login` / `Password` | ввод | Автосохраняются. У `Password` есть end-icon для показа пароля. |
| Экспандер **SIP settings** | тап | Показывает/скрывает `server`, `login`, `pass`; иконка меняется `ic_expand_more` ⇄ `ic_expand_less`. |
| **Connect** | тап | Валидирует `server` / `login`, вызывает `App.connect(...)`. Неактивна, если сессией владеет другая страница или идёт звонок. |
| **Disconnect** | тап | Вызывает `App.disconnect(page)`; активна только при регистрации/подключении. |
| **Refers** | тап | Открывает редактор контактов blind transfer (`showRefersDialog`). |
| Переключатель `Log` (SIP) | переключение | Включает живое зеркалирование `App.sipLog()` в `EditText` лога. |
| Иконка сохранения (SIP, `ic_save`) | тап | Диалог: **Copy to clipboard** или **Save to file** (`LogFile.save`). |
| Иконка очистки (SIP, `ic_delete`) | тап | Диалог подтверждения → `App.sipLog().clear()`. |
| `▲` / `▼` (SIP) | тап | Прокрутка `EditText` лога в начало / конец. |

##### Блок Dialer

| Элемент | Триггер | Что делает |
|---------|---------|-----------|
| Поле `Number` | ввод | Динамически масштабирует текст между 10sp и 24sp (`fitNumberText()`). |
| Поле `Number` | долгое нажатие 1 с | Очищает поле (`clearNumberRunnable`). |
| Круглая кнопка **Call / Hangup** | тап | Если `App.state == IDLE` → `doCall()` (проверка `RECORD_AUDIO`, применение `speaker`/`muted`, вызов `App.dial`). Иначе → `App.hangup(page)`. Иконка меняется `ic_call` (синяя) ⇄ `ic_call_end` (красная) по `App.state`. |
| **Mute** (`ic_mic` ⇄ `ic_mic_off`) | тап | Переключает `prefs.muted`; вызывает `App.setMute(...)`, если активный звонок принадлежит этой странице. Форма кнопки — outline-pill ⇄ filled-rounded (`applyToggleLook`). |
| **Spk** (`ic_volume_up`) | тап | Переключает `prefs.speaker`; вызывает `App.setSpeaker(...)` при том же условии владения (`applySpkLook`). |
| Заголовок сессии истории | тап | Разворачивает/сворачивает горизонтальную шкалу-доты. Состояние запоминается по ключу сессии в `expandedSessions`. |
| Заголовок сессии истории | долгое нажатие | Копирует номер в поле `Number` и показывает Toast «Number inserted». |
| Иконка копирования (`ic_copy`) | тап | Копирует всю сводку сессии (`Number:` + строки событий) в буфер обмена. |
| Точка статуса в истории | тап | Открывает диалог с полным текстом события и кнопками **Copy** / **Close**. |
| `▲` / `▼` (история звонков) | тап | Плавно прокручивает список (`smoothScrollTo`). |
| Иконка сохранения (история, `ic_save`) | тап | Диалог: **Copy to clipboard** или **Save to file**. |
| Иконка очистки (история, `ic_delete`) | тап | Диалог подтверждения → `App.callLog().clear()`. |

##### Блок VPN

| Элемент | Триггер | Что делает |
|---------|---------|-----------|
| Экспандер **VPN settings** | тап | Показывает/скрывает основные поля VPN + **Apps using VPN** и **Advanced VPN settings**. |
| Поля `VPN server` / `VPN login` / `VPN password` | ввод | Автосохраняются. У `VPN password` есть переключатель показа. |
| Экспандер **Advanced VPN settings** | тап | Показывает/скрывает PSK, CA-certificate, healthcheck, ping interval. |
| `VPN pre-shared key` | ввод | Опциональный PSK для IKEv2; переключатель показа включён. |
| `VPN CA certificate (PEM)` | ввод, многострочное (до 4 строк) | Опциональный PEM CA-bundle; пусто → системное trust-store. |
| `Healthcheck address` | ввод | IPv4 или hostname. Пусто или `0` → healthcheck самого VPN-сервера. Домены резолвятся в IPv4 в фоновом потоке (`vpnStart`). |
| `Ping interval (sec)` | числовой ввод | Допустимо 5..9 или `0` (по умолчанию 5). Некорректные значения прерывают подключение со статусом. |
| **Apps using VPN** | тап | Диалог split-tunneling (`pickApps()`); подпись кнопки — `MiniSIP + N`, где N — число выбранных пакетов. |
| **VPN connect** | тап | Валидирует поля, вызывает `App.vpnConsent(...)`; после согласия `vpnStart()` (при необходимости резолвит healthcheck) → `App.vpnConnect`. При активном подключении подпись расширяется `MOBIKE: Active / Not supported` и `DPD: <state>`. |
| **VPN disconnect** | тап | Вызывает `App.vpnDisconnect(page)`. |
| Переключатель `Log` / save / clear / `▲` / `▼` (VPN) | как в SIP-блоке | То же поведение через `App.vpnLog()`. |

##### Нижний ряд, карточки и жесты уровня приложения

| Элемент / жест | Триггер | Что делает |
|----------------|---------|-----------|
| Метка версии `vX.Y.Z` | долгое нажатие ≥ 2 с | Открывает выбор канала обновлений (Stable / Pre-release) с кнопкой **Check**. |
| Метка версии | движение ≥ 20 dp от центра во время долгого нажатия | Отменяет таймер диалога канала. |
| Переключатель `Log` (версия) | переключение | Живая область лога `App.updateLog()`. |
| Кнопка **Delete Page** | натяжение вниз в самом низу в течение 1 с (`dispatchTouchEvent`, при `PageStore.count > 1`) | Кнопка проявляется; тап → диалог подтверждения → `MainActivity.pageDeleted(page)`. Скрыта, если страница одна. |
| Заголовок карточки SIP / Call / VPN | долгое нажатие | Старт drag-and-drop (alpha карточки падает до 0.2). Порядок сохраняется под ключом `blockOrder`. |
| Свайп вправо, когда последняя карточка сконфигурирована | свайп | Диалог **Create New Card** (`onPageSelected`); на **Yes** вызывается `PageStore.setCount`, адаптер `Pages` обновляется. |
| `App.onCreate` | один раз | Регистрирует `ConnectivityManager.registerDefaultNetworkCallback` → `App.onNetwork()` перерегистрирует SIP при смене сети. |
| Разрешения mic / notifications | первый запуск | Запрашиваются один раз (`RECORD_AUDIO`, на Android 13+ ещё `POST_NOTIFICATIONS`). |
| Оптимизация батареи | первый запуск после разрешений | Диалог `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (`askBatteryOnce`), пропускается, если уже разрешено. |
| Плитка Quick Settings | Android 13+, первый запуск | `StatusBarManager.requestAddTileService` один раз предлагает добавить плитку MiniSIP VPN (`tileOffered`). |
| `CallService` | переход состояния звонка в `CALLING`/`TALK` | Запуск foreground-сервиса (`begin`); `FOREGROUND_SERVICE_TYPE_MICROPHONE` держит микрофон на заблокированном экране. |
| Флаги локскрина | активный звонок | `setShowWhenLocked` / `setTurnScreenOn` переключаются по `appChangeListener`. |

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
