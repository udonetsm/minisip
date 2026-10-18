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

## 2. Подробная спецификация пакетов и классов

### 2.1 Пакет `ru.minisip.app` (Пользовательский интерфейс и сервисы)

#### [`MainActivity.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/MainActivity.java)
- **Управление страницами**: Использует `ViewPager2` с адаптером `Pages`.
- **Динамическое создание страниц**: При прокрутке на страницу с индексом `position >= PageStore.count()` вызывается диалог `showCustomConfirmDialog()`. В случае согласия пользователя значение `PageStore.count` увеличивается в `SharedPreferences`, вызвав обновление `ViewPager2`.
- **Флаги заблокированного экрана**: Метод `updateLockScreenFlags()` вызывает `setShowWhenLocked(active)` и `setTurnScreenOn(active)`, что позволяет отображать интерфейс входящего/активного звонка поверх заблокированного экрана Android.

#### [`SettingsFragment.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/SettingsFragment.java)
- **Сборка UI без XML**: Весь UI создается программно в методе `onCreateView()` с использованием Material Design 3 компонентов.
- **Интерактивная история звонков (`updateCallHistory`)**: Группирует события лога вызовов в хронологические сессии, строит цветовую цепочку событий (`getStatusColor`: Оранжевый — Dial, Желтый — Ringing, Зеленый — Connected, Фиолетовый — Transfer, Красный — Ended/Error), поддерживает скроллинг, копирование и подстановку номера в поле набора по долгому нажатию.
- **Выбор приложений VPN (`pickApps`)**: Сканирует установленные приложения через `PackageManager.getInstalledPackages()` (согласно объявлению `<queries>` в `AndroidManifest.xml`) и позволяет сформировать список пакетов для раздельного туннелирования.
- **Окно перевода вызова (`showRefersDialog`)**: Формирует интерактивный список контакта перевода (`PageStore.Refer`) с полями редактирования Login и Name, кнопками добавления, удаления и быстрого слепого перевода (`↪️`).
- **Drag & Drop карточек (`setupCardDragAndDrop`)**: Реализует перетаскивание блоков с установкой прозрачности `alpha = 0.2` и сохранением кастомного порядка в `SharedPreferences` под ключом `blockOrder`.
- **Показ кнопки удаления (`revealDeleteRunnable`)**: Отслеживает натяжение вниз на дне `NestedScrollView` в течение 1 секунды и отображает скрытую кнопку **Delete Page 🗑**.

#### [`PageStore.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/PageStore.java)
- **Хранение настроек**: Метод `prefs(c, page)` возвращает `cfg` для первой страницы и `cfg1`, `cfg2`... для последующих.
- **Удаление профилей (`deletePage`)**: При удалении страницы сдвигает содержимое всех последующих файлов `SharedPreferences` на 1 позицию влево, очищая последний файл и уменьшая значение `count`.

#### [`CallService.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/CallService.java)
- **Фоновый сервис**: Запускается с типом `foregroundServiceType="microphone"`, создавая системное уведомление высокого приоритета. Гарантирует работу микрофона и динамика при заблокированном экране.

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
   - В ответ от АТС приходит `401 Unauthorized` или `407 Proxy Authentication Required`, содержащий заголовок `WWW-Authenticate`.
   - Парсится параметр `nonce`, `realm`, `qop`, и вызывается класс `Digest` для вычисления дайджест-ответа.
   - Повторно отправляется `REGISTER` с заголовком `Authorization: Digest username=..., realm=..., nonce=..., uri=..., response=...`.
   - При получении `200 OK` устанавливается таймер повторной регистрации на половину времени `Expires`.

2. **Процесс исходящего вызова (`call()`)**:
   - Создается случайный `Call-ID`, генерируется `Branch` для заголовка `Via`.
   - Через `Sdp.build()` формируется SDP-тело с предложением кодеков PCMU/PCMA и локальным RTP-портом.
   - Отправляется `INVITE`. Получив `180 Ringing`, включается КПВ. При получении `200 OK` парсится SDP сервера, запускается `Media.start()`, и отправляется пакет `ACK`.

3. **Перевод вызова (`transfer()`)**:
   - Формируется SIP-запрос `REFER` согласно RFC 3515 с заголовком `Refer-To: <sip:target@host>`.

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
- Инициализирует аппаратные эффекты: `AcousticEchoCanceler` (эхоподавление) и `NoiseSuppressor` (шумоподавление).
- Метод `spk(boolean)` управляет переключением громкой связи через `AudioManager.setSpeakerphoneOn()` и режим `AudioManager.MODE_IN_COMMUNICATION`.

---

### 2.4 Пакет `ru.minisip.vpn` (IKEv2 / IPsec VPN)

#### [`TunService.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/TunService.java)
- Является подклассом `android.net.VpnService`.
- Формирует виртуальный сетевой интерфейс `tun0` через `VpnService.Builder`: задает локальный IP-адрес, маску сети, MTU (1400) и добавляет разрешенные пакеты приложений через `addAllowedApplication()`.
- Вычитывает необработанные IP-пакеты из `ParcelFileDescriptor` TUN-интерфейса и передает их в `Tunnel.java`.

#### [`Ike.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Ike.java) & [`Tunnel.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Tunnel.java)
Полная реализация стека IKEv2 (RFC 7296) и IPsec ESP (RFC 4303) в пользовательском пространстве:
1. **IKE_SA_INIT**: Обмен Diffie-Hellman публичными ключами (`DhGroup.java`), генерация nonce, вычисление мастер-ключа `SKEYSEED` и ключей шифрования `SK_e`, `SK_a`.
2. **IKE_AUTH**: Передача зашифрованной полезной нагрузки с данными аутентификации EAP MS-CHAPv2 (`Mschap.java`) или сертификатов X.509 (`Certs.java`), согласование селекторов трафика `TSi`/`TSr` и создание `CHILD_SA`.
3. **ESP Обработка (`Esp.java`)**: Качественное шифрование/расшифрование данных по алгоритмам AES-CBC / AES-GCM и проверка целостности HMAC-SHA1/SHA256.

#### [`NetWatch.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/NetWatch.java)
- Регулярно отправляет ICMP/HTTP эхо-запросы через виртуальный интерфейс.
- При потере пакетов инициирует процедуры MOBIKE или IKE Rekey.

---

### 2.5 Пакет `ru.minisip.screen` (Управление экраном)

#### [`ScreenImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/screen/ScreenImpl.java)
- Использует `PROXIMITY_SCREEN_OFF_WAKE_LOCK` для автоматического гашения экрана, когда смартфон приложен к уху.
- Использует `PARTIAL_WAKE_LOCK` (`cpuLock`) для предотвращения засыпания ЦП во время вызова.
- Использует `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` для принудительного включения экрана при поступившем или принятом вызове.

---

### 2.6 Пакеты `ru.minisip.update` и `ru.minisip.log`

#### [`UpdateImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/update/UpdateImpl.java)
- Выполняет HTTP-запросы к GitHub Releases API (`Http.java`).
- Сравнивает время релиза `published_at` с системным `lastUpdateTime` установленного приложения.
- При запуске удаляет старые файлы APK из кэша (`cleanUpdates`).
- Проверяет разрешение `pm.canRequestPackageInstalls()`. При отсутствии вызывает `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`, после чего запускает установщик через `FileProvider` (`content://${applicationId}.fileprovider`).

#### [`LogBufferImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/log/LogBufferImpl.java)
- Потокобезопасный кольцевой буфер фиксированного размера (500 записей) с поддержкой listeners, форматирования временных меток и сохранения в `SharedPreferences`.

---

## 3. Системные требования и спецификации RFC

### 3.1 Поддерживаемые стандарты RFC
- **SIP**: RFC 3261 (Core SIP), RFC 3515 (SIP REFER), RFC 4566 (SDP), RFC 7616 / 2617 (HTTP Digest Auth).
- **Media**: RFC 3550 (RTP), ITU-T G.711 (PCMA/PCMU).
- **VPN / IPsec**: RFC 7296 (IKEv2), RFC 4303 (ESP), RFC 2759 (MS-CHAPv2).

### 3.2 Требования к среде исполнения
- **Android SDK**: Min SDK 24 (Android 7.0), Target SDK 34 (Android 14).
- **Память**: Фиксированные кольцевые буферы предотвращают утечки памяти OOM.
- **Сетевые разрешения**: Объявление `<queries>` с фильтром `MAIN/LAUNCHER` для работы списка приложений VPN на Android 11+.
