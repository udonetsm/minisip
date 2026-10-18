# Техническая документация разработчика MiniSIP

---

## 1. Архитектурный обзор и шаблоны проектирования

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
Класс [`App`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/App.java) выступает синглтоном приложения (`Application`), центральным координатором потоков данных и реализации следующих слушателей:
- `Sip.Listener`: Обработка изменений состояния SIP-стека (смена статуса регистрации, входящие вызовы, сброс звонка).
- `Vpn.Listener`: Обработка статуса IKEv2/IPsec туннеля (подключение, обрывы, статистика потерь пакетов).

### 1.2 Правило единого активного соединения (Single Active Connection)
С целью предотвращения гонки состояний и рекурсивных сетевых конфликтов в приложении действует правило:
- Переменная `App.lastPage` фиксирует индекс страницы (`SettingsFragment`), создавшей активное соединение.
- Метод `App.busy()` возвращает `true`, если активирован вызов, SIP-регистрация или VPN.
- Метод `App.allowed(int page)` блокирует попытки управления соединениями с других страниц, пока активна выбранная страница.

---

## 2. Подробная логика работы каждого пакета и класса

### 2.1 Пакет `ru.minisip.app` (Пользовательский интерфейс и сервисы)

#### [`MainActivity.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/MainActivity.java)
- **Управление страницами**: Использует `ViewPager2` с адаптером `Pages`.
- **Динамическое создание страниц**: При прокрутке на страницу с индексом `position >= PageStore.count()` вызывается диалог `showCustomConfirmDialog()`. В случае согласия пользователя значение `PageStore.count` увеличивается в `SharedPreferences`, вызвав обновление `ViewPager2`.
- **Флаги заблокированного экрана**: Метод `updateLockScreenFlags()` вызывает `setShowWhenLocked(active)` и `setTurnScreenOn(active)`, что позволяет отображать интерфейс входящего/активного звонка поверх заблокированного экрана Android.

#### [`SettingsFragment.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/SettingsFragment.java)
- **Сборка UI без XML**: Весь UI создается программно в методе `onCreateView()` с использованием Material Design 3 компонентов (`MaterialCardView`, `TextInputEditText`, `MaterialButton`).
- **Автоматическое сохранение**: Текстовые поля снабжены слушателями `addTextChangedListener()`, вызывающими сохранение значений в `SharedPreferences` текущего профиля страницы.
- **Drag & Drop карточек**: Метод `setupCardDragAndDrop()` устанавливает `OnTouchListener` на заголовки блоков, обрабатывая координаты сдвига и вызывая `saveBlockOrder()` для сохранения кастомного порядка визуальных блоков.
- **Перевод вызовов**: Метод `showRefersDialog()` отображает интерфейс для ввода целевого номера перевода и вызывает `app.refers(target)`.
- **Выбор приложений VPN**: Метод `pickApps()` сканирует список установленных в системе приложений через `PackageManager.getInstalledPackages()` (согласно объявлению `<queries>` в `AndroidManifest.xml`) и позволяет сформировать список приложений для раздельного туннелирования.

#### [`CallService.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/CallService.java)
- **Фоновый сервис**: Запускается с типом `foregroundServiceType="microphone"`, создавая непрерывное системное уведомление `NotificationChannel` с приоритетом `HIGH`. Гарантирует захват и воспроизведение аудио во время разговора, даже когда приложение свёрнуто.

---

### 2.2 Пакет `ru.minisip.sip` (Сигнализация VoIP SIP)

#### [`SipImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/SipImpl.java)
Реализует конечный автомат (FSM) протокола SIP (RFC 3261):

```
[UNREG] ---> (REGISTER sent) ---> [REGISTRING] ---> (401/407 + Auth Header) ---> [REGISTRING] ---> (200 OK) ---> [REGISTERED]
                                                                                                                       |
[IDLE] <--- (BYE / 200 OK) <--- [TALK] <--- (200 OK + ACK) <--- [RINGING] <--- (180 Ringing) <--- (INVITE sent) <-------+
```

1. **Процесс регистрации (`reregister()`)**:
   - Формируется сообщение `REGISTER` без заголовока `Authorization`.
   - В ответ от АТС приходит ответ `401 Unauthorized` или `407 Proxy Authentication Required`, содержащий заголовок `WWW-Authenticate`.
   - Парсится параметр `nonce`, `realm`, `qop`, и вызывается класс `Digest` для вычисления дайджест-ответа.
   - Повторно отправляется `REGISTER` с заголовком `Authorization: Digest username=..., realm=..., nonce=..., uri=..., response=...`.
   - При получении `200 OK` устанавливается таймер повторной регистрации на половину времени `Expires`.

2. **Процесс исходящего вызова (`call()`)**:
   - Создается случайный `Call-ID`, генерируется `Branch` для заголовка `Via`.
   - Через `Sdp.build()` формируется SDP-тело с предложением кодеков PCMU/PCMA и локальным RTP-портом.
   - Отправляется `INVITE`. Получив `180 Ringing`, включается КПВ. При получении `200 OK` парсится SDP сервера, запускается `Media.start()`, и отправляется пакет `ACK`.

3. **Перевод вызова (`refers()`)**:
   - Формируется SIP-запрос `REFER` согласно RFC 3515.
   - Заголовок `Refer-To: <sip:target@host>` указывает целевой адрес перенаправления вызова.

#### [`Digest.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/Digest.java)
Реализует HTTP Digest Authentication (RFC 7616 / RFC 2617):
$$\text{HA1} = \text{MD5}(\text{username} : \text{realm} : \text{password})$$
$$\text{HA2} = \text{MD5}(\text{method} : \text{digestURI})$$
$$\text{Response} = \text{MD5}(\text{HA1} : \text{nonce} : \text{nc} : \text{cnonce} : \text{qop} : \text{HA2})$$

#### [`Sdp.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/Sdp.java)
Парсит и формирует структуры SDP (RFC 4566):
```text
v=0
o=- 17182910 17182910 IN IP4 192.168.1.50
s=-
c=IN IP4 192.168.1.50
t=0 0
m=audio 4000 RTP/AVP 0 8 101
a=rtpmap:0 PCMU/8000
a=rtpmap:8 PCMA/8000
a=sendrecv
```

---

### 2.3 Пакет `ru.minisip.media` (Аудио и кодеки)

#### [`MediaImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/media/MediaImpl.java)
Запускает два фоновых потока:
1. **Поток записи (Tx)**: Запрашивает PCM-сэмплы (8000 Гц, 16 бит mono) из `AndroidAudio`, кодирует их в G.711 (PCMA/PCMU) пакетами по 160 байт (20 мс звука), добавляет 12-байтовый RTP-заголовок (RFC 3550) и отправляет через `rtpUdp`.
2. **Поток приема (Rx)**: Считывает RTP-пакеты из `rtpUdp`, проверяет Sequence Number и Timestamp, извлекает 160 байт полезной нагрузки, декодирует G.711 в PCM и передает на воспроизведение в `AndroidAudio`.

#### [`AndroidAudio.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/media/AndroidAudio.java)
- Управляет классами Android `AudioRecord` и `AudioTrack`.
- Инициализирует аппаратные эффекты: `AcousticEchoCanceler` (эхоподавление) и `NoiseSuppressor` (шумоподавление), если они поддерживаются устройством.
- Метод `spk(boolean)` управляет переключением громкой связи через `AudioManager.setSpeakerphoneOn()` и режим `AudioManager.MODE_IN_COMMUNICATION`.

---

### 2.4 Пакет `ru.minisip.vpn` (IKEv2 / IPsec VPN)

#### [`TunService.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/TunService.java)
- Является подклассом `android.net.VpnService`.
- Формирует виртуальный сетевой интерфейс `tun0` через `VpnService.Builder`: задает локальный IP-адрес, маску сети, MTU (1400) и добавляет разрешенные пакеты приложений через `addAllowedApplication()`.
- Вычитывает необработанные IP-пакеты из `ParcelFileDescriptor` TUN-интерфейса и передает их в `Tunnel.java`.

#### [`Ike.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Ike.java) & [`Tunnel.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Tunnel.java)
Полная реализация стека IKEv2 (RFC 7296) и IPsec ESP (RFC 4303) в пользовательском пространстве:
1. **IKE_SA_INIT**: Обмен Diffie-Hellman публичными ключами (MODP/ECP), генерация nonce, вычисление мастер-ключа `SKEYSEED` и ключей шифрования `SK_e`, `SK_a`.
2. **IKE_AUTH**: Передача зашифрованной полезной нагрузки с данными аутентификации EAP MS-CHAPv2 (`Mschap.java`) или сертификатов X.509 (`Certs.java`), согласование селекторов трафика `TSi`/`TSr` и создание `CHILD_SA`.
3. **ESP Обработка (`Esp.java`)**: Качественное шифрование/расшифрование данных по алгоритмам AES-CBC / AES-GCM и проверка целостности HMAC-SHA1/SHA256.

#### [`NetWatch.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/NetWatch.java)
Мониторинг работоспособности VPN:
- Регулярно отправляет ICMP/HTTP эхо-запросы через виртуальный интерфейс.
- В случае потери нескольких пакетов подряд инициирует процедуру авто-восстановления туннеля (MOBIKE / IKE Rekey).

---

### 2.5 Пакет `ru.minisip.net` (Сетевое связывание)

#### [`AndroidBinder.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/net/AndroidBinder.java)
Обеспечивает предотвращение сетевых петель:
- Вызывает `VpnService.protect(socket)` для сокетов IKE (UDP 500/4500) и SIP/RTP сокетов, исключая их из туннелирования через `tun0`.

---

## 3. Системные требования и спецификации RFC

### 3.1 Поддерживаемые стандарты RFC
- **SIP**: RFC 3261 (Core SIP), RFC 3515 (SIP REFER), RFC 4566 (SDP), RFC 7616 / 2617 (HTTP Digest Auth).
- **Media**: RFC 3550 (RTP), ITU-T G.711 (PCMA/PCMU).
- **VPN / IPsec**: RFC 7296 (IKEv2), RFC 4303 (ESP), RFC 2759 (MS-CHAPv2).

### 3.2 Требования к среде исполнения
- **Android SDK**: Min SDK 24 (Android 7.0), Target SDK 34 (Android 14).
- **Память**: Накладные расходы памяти оптимизированы за счет применения кольцевых буферов фиксированного размера (`LogBuffer` емкостью 500 записей).
- **Сетевые разрешения**: `<queries>` с фильтром `MAIN/LAUNCHER` для корректной работы списка приложений VPN на Android 11+.
