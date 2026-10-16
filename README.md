# 📱 MiniSIP — Руководство разработчика & Полный аудит проекта

**MiniSIP** — компактное, автономное высокопроизводительное Android-приложение для SIP/SDP/G.711 VoIP-телефонии и защищённого IKEv2/IPsec (ESP) VPN-туннелирования. Проект написан на чистом **Java / Android SDK** без единой сторонней библиотеки.

---

## 📑 Содержание
1. [Архитектура и архитектурные принципы](#1-архитектура-и-архитектурные-принципы)
2. [Структура пакетов и графы зависимостей](#2-структура-пакетов-и-графы-зависимостей)
3. [Соответствие пакетов и функционала стандартам RFC](#3-соответствие-пакетов-и-функционала-стандартам-rfc)
4. [Описание директории `prompts` и файлов RFC](#4-описание-директории-prompts-и-файлов-rfc)
5. [Полный аудит приложения и проверка соответствия RFC](#5-полный-аудит-приложения-и-проверка-соответствия-rfc)
6. [Требования к разработке, стиль кода и тестирование](#6-требования-к-разработке-стиль-кода-и-тестирование)

---

## 1. Архитектура и архитектурные принципы

Приложение спроектировано по строгим архитектурным правилам **Clean Architecture**, **SOLID**, **DIP**, **KISS** и **DRY**:

1. **Zero External Dependencies (Нулевые внешние зависимости)**:
   - В проекте **отсутствуют** сторонние библиотеки (OkHttp, Retrofit, PJSIP, BouncyCastle, Dagger/Hilt, RxJava и др.).
   - Все протоколы (SIP, SDP, G.711, RTP, IKEv2, ESP, MS-CHAPv2, MD4, DH Group 14, X.509 chain verification) реализованы на стандартном **Java / Android SDK** (`java.net`, `java.security`, `javax.crypto`, `android.media`, `android.net.VpnService`).
2. **Инверсия зависимостей (DIP / SOLID)**:
   - Публичное API пакетов представлено **исключительно интерфейсами** (`Sip`, `Media`, `Udp`, `Vpn`, `Screen`, `Update`, `LogBuffer`, `LogSink`).
   - Классы реализаций (`*Impl`, `Android*`, `Tunnel`, `Ike`, `Esp`, `Digest`, `Sdp`, `Msg`, `G711` и др.) скрыты на уровне пакетов (`package-private`).
   - Модули верхнего уровня работают исключительно через абстракции.
3. **Корень композиции (Composition Root)**:
   - Единственная точка композиции и связывания всех компонентов — класс [`App.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app/App.java).
4. **Простые типы данных между слоями**:
   - Межмодульное взаимодействие использует только базовые примитивы (`String`, `int`, `boolean`, `byte[]`, `short[]`), статические и вложенные `Listener` интерфейсы, а также стандартный `Runnable`.
5. **Многопоточная модель**:
   - **`sip`**: однопоточный `ScheduledExecutorService` (поток `"sip"`) — обработка SIP-сообщений без блокировок.
   - **`media`**: потоки `"rtp-capture"` (микрофон -> G.711 -> UDP) и `"rtp-playback"` (UDP -> G.711 -> динамик).
   - **`vpn`**: потоки `"vpn"` (IKEv2 контроль), `"vpn-rx"` (приём UDP/4500 ESP), `"vpn-tun"` (чтение IP-пакетов из TUN), `"vpn-keep"` (DPD keep-alive), `"vpn-mobike"` (MOBIKE миграция), `"vpn-probe"` (healthcheck).
   - **`net`**: демон приема UDP-пакетов `"udp-rx"`.
   - **`app`**: главный поток Android (Main Looper) для обновления UI.

---

## 2. Структура пакетов и графы зависимостей

```
[app] (App, CallService, MainActivity, SettingsFragment, PageStore, UpdateBar)
 ├──> [sip]     (Sip interface)
 ├──> [media]   (Media interface)
 ├──> [net]     (Udp interface)
 ├──> [screen]  (Screen interface)
 ├──> [vpn]     (Vpn interface)
 ├──> [update]  (Update interface)
 └──> [log]     (LogBuffer, LogSink interfaces)

[sip] (SipImpl)
 ├──> [media]   (Media interface)
 ├──> [net]     (Udp interface)
 └──> [log]     (LogSink interface)

[media] (MediaImpl)
 └──> [net]     (Udp interface)

[vpn] (VpnImpl, Tunnel)
 └──> [log]     (LogSink interface)

[update] (UpdateImpl)
 └──> [log]     (LogSink interface)

[net], [screen], [log] — автономные низовые сервисы
```

### Назначение пакетов:
- [`ru.minisip.app`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/app): UI (ViewPager2, Material 3), хранилище профилей `PageStore`, служба фона `CallService`, управление жизненным циклом и корень композиции `App`.
- [`ru.minisip.sip`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip): Реализация протоколов SIP, SDP, Digest MD5, парсинг компактных заголовков, поддержка REFER/NOTIFY.
- [`ru.minisip.media`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/media): Работа со звуком Android AudioRecord/AudioTrack, кодек G.711 (PCMA/PCMU), RTP-стриминг (20 мс/пакет) и Symmetric RTP.
- [`ru.minisip.net`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/net): Сетевой стек UDP и привязка сокетов к системным сетевым интерфейсам или VPN-туннелю `tun0`.
- [`ru.minisip.vpn`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn): Полнофункциональный клиент IKEv2 / IPsec ESP (MODP 2048, AES-CBC/GCM, HMAC-SHA1/SHA256, MS-CHAPv2, MOBIKE, DPD, X.509).
- [`ru.minisip.update`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/update): Фоновая проверка и установка авто-обновлений с GitHub Releases через FileProvider.
- [`ru.minisip.log`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/log): Кольцевой потокобезопасный буфер логов и сохранение файловых логов в файловую систему / MediaStore.
- [`ru.minisip.screen`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/screen): Управление датчиком приближения для гашения экрана во время звонка.

---

## 3. Соответствие пакетов и функционала стандартам RFC

Каждая подсистема приложения построена в четком соответствии со спецификациями IETF RFC и ITU-T:

| Пакет / Подсистема | Реализованный функционал | Соответствующие RFC / Стандарты |
| :--- | :--- | :--- |
| **`ru.minisip.sip`** | SIP UAC (REGISTER, INVITE, ACK, BYE, CANCEL, OPTIONS) | [RFC 3261](/prompts/sip-rfc-s/rfc3261) |
| **`ru.minisip.sip`** | SDP Offer/Answer модель, согласование audio m-line | [RFC 3264](/prompts/sip-rfc-s/rfc3264), [RFC 4566](/prompts/sip-rfc-s/rfc4566) |
| **`ru.minisip.sip`** | Слепой перевод вызова (Blind Call Transfer) через `REFER` | [RFC 3515](/prompts/sip-rfc-s/rfc3515) |
| **`ru.minisip.sip`** | Обработка событий `NOTIFY` для подписки на `refer` | [RFC 6665](/prompts/sip-rfc-s/rfc6665) |
| **`ru.minisip.sip`** | Парсинг тела статуса перевода `message/sipfrag` | [RFC 3420](/prompts/sip-rfc-s/rfc3420) |
| **`ru.minisip.sip`** | Авторизация HTTP Digest (MD5, `qop=auth`, `nc`, `cnonce`) | [RFC 2617](/prompts/sip-rfc-s/rfc3261), [RFC 7616](/prompts/sip-rfc-s/rfc7616) |
| **`ru.minisip.media`** | Голосовой кодек G.711 (PCMA PT 8, PCMU PT 0) | [ITU-T G.711 (1988/11)](/prompts/sip-rfc-s/T-REC-G.711-198811-I!!PDF-E.pdf) |
| **`ru.minisip.media`** | Передача аудио по RTP (20 мс кадра, 160 сэмплов, 8кГц) | [RFC 3550](/prompts/sip-rfc-s/rfc3550), [RFC 3551](/prompts/sip-rfc-s/rfc3551) |
| **`ru.minisip.media`** | Symmetric RTP (динамическое обновление IP/порта NAT) | [RFC 4961](/prompts/sip-rfc-s/rfc4961) |
| **`ru.minisip.vpn`** | Протокол IKEv2 (IKE_SA_INIT, IKE_AUTH, CREATE_CHILD_SA) | [RFC 7296](/prompts/vpn-rfc-s/rfc4718.txt) (RFC 4306/5996/7296) |
| **`ru.minisip.vpn`** | Архитектура IPsec и туннельный режим ESP IPv4 | [RFC 4301](/prompts/vpn-rfc-s/rfc4301.txt), [RFC 4303](/prompts/vpn-rfc-s/rfc4303.txt) |
| **`ru.minisip.vpn`** | Инкапсуляция IPsec ESP пакетов в UDP (порт 4500) | [RFC 3948](/prompts/vpn-rfc-s/rfc3948.txt) |
| **`ru.minisip.vpn`** | Группа Диффи-Хеллмана 14 (MODP 2048-bit) | [RFC 3526](/prompts/vpn-rfc-s/rfc3526.txt) |
| **`ru.minisip.vpn`** | Шифрование AES-CBC (128/256 bit) | [RFC 3602](/prompts/vpn-rfc-s/rfc3602.txt) |
| **`ru.minisip.vpn`** | Аутентифицированное шифрование AES-GCM-16 (128/256 bit) | [RFC 5282](/prompts/vpn-rfc-s/rfc5282.txt) |
| **`ru.minisip.vpn`** | Целостность HMAC-SHA1-96 и HMAC-SHA256-128 | [RFC 2104](/prompts/vpn-rfc-s/rfc2104.txt), [RFC 2404](/prompts/vpn-rfc-s/rfc2404.txt), [RFC 4868](/prompts/vpn-rfc-s/rfc4868.txt) |
| **`ru.minisip.vpn`** | Аутентификация EAP-MSCHAPv2 и получение ключей MPPE/MSK | [RFC 2759](/prompts/vpn-rfc-s/rfc2759.txt), [RFC 3079](/prompts/vpn-rfc-s/rfc3079.txt), [RFC 3748](/prompts/vpn-rfc-s/rfc3748.txt) |
| **`ru.minisip.vpn`** | Поддержка MOBIKE (Mobility & Multihoming Protocol) | [RFC 4555](/prompts/vpn-rfc-s/rfc4555.txt) |
| **`ru.minisip.vpn`** | DPD (Dead Peer Detection) keep-alives и NAT Detection | [RFC 4718](/prompts/vpn-rfc-s/rfc4718.txt) |
| **`ru.minisip.vpn`** | Проверка сертификатов X.509, SAN (DNS, IP, Wildcard) | [RFC 5280](/prompts/vpn-rfc-s/rfc5280.txt) |
| **`ru.minisip.vpn`** | Цифровые подписи IKEv2 (RSA / ECDSA / Hash OID) | [RFC 7427](/prompts/vpn-rfc-s/rfc7427.txt) |
| **`ru.minisip.vpn`** | Проверки валидности публичных ключей Диффи-Хеллмана | [RFC 6989](/prompts/vpn-rfc-s/rfc6989.txt) |
| **`ru.minisip.vpn`** | Фрагментация длинных сообщений IKEv2 | [RFC 7383](/prompts/vpn-rfc-s/rfc7383.txt) |
| **`ru.minisip.vpn`** | Соответствие криптографическим профилям IKEv2 | [RFC 8247](/prompts/vpn-rfc-s/rfc8247.txt) |

---

## 4. Описание директории `prompts` и файлов RFC

Директория [`prompts/`](file:///home/active/android/minisip/prompts) содержит руководящие инструкции, результаты анализа архитектуры и эталонные RFC спецификации.

### Архитектурные и рефакторинговые промпты:
- [`prompts/01_analysis.md`](file:///home/active/android/minisip/prompts/01_analysis.md): Детальный первичный анализ архитектуры MiniSIP, полного графа зависимостей пакетов, описания методов каждого класса и выявления слабых/сильных сторон.
- [`prompts/02_architecture.md`](file:///home/active/android/minisip/prompts/02_architecture.md): Спецификация целевой архитектуры Clean Architecture / SOLID / DIP. Требования к сокрытию реализаций в internal/package-private и общению верхних слоёв строго через интерфейсы.
- [`prompts/03_refactoring.md`](file:///home/active/android/minisip/prompts/03_refactoring.md): Пошаговый план форматирования и рефакторинга, статус выполнения задачи, правила аккуратных минимальных диффов и запрет на повторную перевёрстку файлов.

### Спецификации SIP / Media ([`prompts/sip-rfc-s/`](file:///home/active/android/minisip/prompts/sip-rfc-s)):
- [`prompts/sip-rfc-s/T-REC-G.711-198811-I!!PDF-E.pdf`](file:///home/active/android/minisip/prompts/sip-rfc-s/T-REC-G.711-198811-I!!PDF-E.pdf): Официальный стандарт ITU-T G.711 (Импульсно-кодовая модуляция голосовых частот, A-law и u-law).
- [`prompts/sip-rfc-s/instructionPDF.md`](file:///home/active/android/minisip/prompts/sip-rfc-s/instructionPDF.md): Выжимка ключевых параметров кодека G.711 (инверсия четных бит `0x55` для A-law, смещение `0x84` для u-law).
- [`prompts/sip-rfc-s/rfc3261`](file:///home/active/android/minisip/prompts/sip-rfc-s/rfc3261): Базовый стандарт SIP 2.0 (Session Initiation Protocol).
- [`prompts/sip-rfc-s/rfc3264`](file:///home/active/android/minisip/prompts/sip-rfc-s/rfc3264): Модель Offer/Answer для протокола SDP.
- [`prompts/sip-rfc-s/rfc3420`](file:///home/active/android/minisip/prompts/sip-rfc-s/rfc3420): Тип содержимого `message/sipfrag` для передачи фрагментов SIP-сообщений.
- [`prompts/sip-rfc-s/rfc3515`](file:///home/active/android/minisip/prompts/sip-rfc-s/rfc3515): Метод `REFER` в SIP для перевода вызова.
- [`prompts/sip-rfc-s/rfc3550`](file:///home/active/android/minisip/prompts/sip-rfc-s/rfc3550): Протокол передачи реального времени RTP (Real-time Transport Protocol).
- [`prompts/sip-rfc-s/rfc3551`](file:///home/active/android/minisip/prompts/sip-rfc-s/rfc3551): Профиль RTP для аудиоконференций с минимальным управлением (Payload types 0 и 8).
- [`prompts/sip-rfc-s/rfc4566`](file:///home/active/android/minisip/prompts/sip-rfc-s/rfc4566): SDP: Session Description Protocol.
- [`prompts/sip-rfc-s/rfc4961`](file:///home/active/android/minisip/prompts/sip-rfc-s/rfc4961): Симметричный RTP (Symmetric RTP) для прохождения NAT.
- [`prompts/sip-rfc-s/rfc6665`](file:///home/active/android/minisip/prompts/sip-rfc-s/rfc6665): Уведомления о событиях в SIP (`NOTIFY`/`SUBSCRIBE`).
- [`prompts/sip-rfc-s/rfc7616`](file:///home/active/android/minisip/prompts/sip-rfc-s/rfc7616): HTTP Digest Access Authentication.

### Спецификации VPN / IPsec / IKEv2 ([`prompts/vpn-rfc-s/`](file:///home/active/android/minisip/prompts/vpn-rfc-s)):
- [`prompts/vpn-rfc-s/rfc2104.txt`](file:///home/active/android/minisip/prompts/vpn-rfc-s/rfc2104.txt): HMAC: Keyed-Hashing for Message Authentication.
- [`prompts/vpn-rfc-s/rfc2404.txt`](file:///home/active/android/minisip/prompts/vpn-rfc-s/rfc2404.txt): Использование HMAC-SHA-1-96 в ESP и AH.
- [`prompts/vpn-rfc-s/rfc2759.txt`](file:///home/active/android/minisip/prompts/vpn-rfc-s/rfc2759.txt): Протокол аутентификации Microsoft PPP CHAP Extensions v2 (MS-CHAPv2).
- [`prompts/vpn-rfc-s/rfc3079.txt`](file:///home/active/android/minisip/prompts/vpn-rfc-s/rfc3079.txt): Вычисление ключей MPPE для MS-CHAPv2.
- [`prompts/vpn-rfc-s/rfc3526.txt`](file:///home/active/android/minisip/prompts/vpn-rfc-s/rfc3526.txt): Модульные экспоненциальные группы Диффи-Хеллмана (MODP Group 14).
- [`prompts/vpn-rfc-s/rfc3602.txt`](file:///home/active/android/minisip/prompts/vpn-rfc-s/rfc3602.txt): Алгоритм шифрования AES-CBC в IPsec.
- [`prompts/vpn-rfc-s/rfc3748.txt`](file:///home/active/android/minisip/prompts/vpn-rfc-s/rfc3748.txt): Extensible Authentication Protocol (EAP).
- [`prompts/vpn-rfc-s/rfc3948.txt`](file:///home/active/android/minisip/prompts/vpn-rfc-s/rfc3948.txt): UDP-инкапсуляция пакетов IPsec ESP (порт 4500).
- [`prompts/vpn-rfc-s/rfc4106.txt`](file:///home/active/android/minisip/prompts/vpn-rfc-s/rfc4106.txt): Использование режима Galois/Counter Mode (GCM) в IPsec ESP.
- [`prompts/vpn-rfc-s/rfc4301.txt`](file:///home/active/android/minisip/prompts/vpn-rfc-s/rfc4301.txt): Архитектура безопасности для протокола IP (IPsec).
- [`prompts/vpn-rfc-s/rfc4303.txt`](file:///home/active/android/minisip/prompts/vpn-rfc-s/rfc4303.txt): IP Encapsulating Security Payload (ESP).
- [`prompts/vpn-rfc-s/rfc4555.txt`](file:///home/active/android/minisip/prompts/vpn-rfc-s/rfc4555.txt): Протокол MOBIKE (IKEv2 Mobility and Multihoming).
- [`prompts/vpn-rfc-s/rfc4718.txt`](file:///home/active/android/minisip/prompts/vpn-rfc-s/rfc4718.txt): Руководство по реализации и разъяснения IKEv2.
- [`prompts/vpn-rfc-s/rfc4868.txt`](file:///home/active/android/minisip/prompts/vpn-rfc-s/rfc4868.txt): Использование HMAC-SHA-256, HMAC-SHA-384, HMAC-SHA-512 в IPsec.
- [`prompts/vpn-rfc-s/rfc5280.txt`](file:///home/active/android/minisip/prompts/vpn-rfc-s/rfc5280.txt): Профиль сертификатов X.509 PKI и списков отзыва (CRL).
- [`prompts/vpn-rfc-s/rfc5282.txt`](file:///home/active/android/minisip/prompts/vpn-rfc-s/rfc5282.txt): Использование алгоритмов AEAD (AES-GCM) в IKEv2.
- [`prompts/vpn-rfc-s/rfc6989.txt`](file:///home/active/android/minisip/prompts/vpn-rfc-s/rfc6989.txt): Дополнительные проверки значений Диффи-Хеллмана в IKEv2.
- [`prompts/vpn-rfc-s/rfc7383.txt`](file:///home/active/android/minisip/prompts/vpn-rfc-s/rfc7383.txt): Фрагментация сообщений IKEv2.
- [`prompts/vpn-rfc-s/rfc7427.txt`](file:///home/active/android/minisip/prompts/vpn-rfc-s/rfc7427.txt): Цифровые подписи в IKEv2 (Digital Signature authentication).
- [`prompts/vpn-rfc-s/rfc8247.txt`](file:///home/active/android/minisip/prompts/vpn-rfc-s/rfc8247.txt): Требования к выбору алгоритмов для IKEv2.

---

## 5. Полный аудит приложения и проверка соответствия RFC

В ходе полного аудита исходного кода приложения выполнена проверка всех функциональных модулей на предмет соответствия RFC:

1. **Модуль SIP (`ru.minisip.sip`)**:
   - ✅ **Парсинг и генерация сообщений**: [`Msg.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/Msg.java) корректно раскрывает компактные заголовки (`i`, `f`, `t`, `v`, `m`, `c`, `l`, `r`, `o`), обрабатывает многострочные списки Via/Record-Route без нарушения кавычек и угловых скобок (`<>`).
   - ✅ **Digest Авторизация**: [`Digest.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/Digest.java) строго реализует вычисление MD5 ответа по схемам `qop=auth` и legacy RFC 2617 с генерацией случайного `cnonce` и `nc=00000001`.
   - ✅ **SDP**: [`Sdp.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/Sdp.java) создает стандартные сессионные описания для PCMA/PCMU и корректно разбирает медиа-строки `m=audio` с приоритетом PCMA (8).
   - ✅ **REFER/NOTIFY**: [`SipImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/SipImpl.java) отправляет `REFER` с заголовком `Refer-To`, обрабатывает `202 Accepted`, принимает входящий `NOTIFY` с `Event: refer` и парсит `message/sipfrag` через [`sipfragCode()`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/sip/SipImpl.java#L47).

2. **Модуль Media (`ru.minisip.media`)**:
   - ✅ **Кодек G.711**: [`G711.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/media/G711.java) точно соответствует спецификации ITU-T G.711 (маскирование `0xD5`/`0x55` для A-law, bias `0x84` для u-law, логарифмические таблицы декодирования).
   - ✅ **RTP кадрирование**: [`MediaImpl.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/media/MediaImpl.java) передает 160 сэмплов PCM за катервал 20 мс, корректно ведет инкремент номера последовательности и метки времени.
   - ✅ **Symmetric RTP**: При получении валидного пакета RTP адрес/порт назначения для исходящего потока динамически переключается на адрес источника входящего пакета.

3. **Модуль VPN (`ru.minisip.vpn`)**:
   - ✅ **Криптография**: [`Crypto.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Crypto.java) реализует DH Group 14 (MODP 2048), AES-CBC и AES-GCM-16 с правильным формированием AAD, IV и ICV. Выполняются обязательные проверки валидности публичного ключа DH ($1 < y < P-1$) по RFC 6989.
   - ✅ **EAP-MSCHAPv2**: [`Mschap.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Mschap.java) содержит встроенную реализацию MD4, генерацию NT-Response и проверку ответа сервера `S=...` по RFC 2759, а также генерацию ключей MPPE/MSK по RFC 3079.
   - ✅ **Сертификаты X.509**: [`Certs.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Certs.java) проводит валидацию цепочки X.509 через PKIX API, сопоставляет SAN (DNS, IP, Wildcard) и проверяет подписи AUTH по RFC 7427.
   - ✅ **IPsec ESP**: [`Esp.java`](file:///home/active/android/minisip/app/src/main/java/ru/minisip/vpn/Esp.java) упаковывает и распаковывает туннельные ESP-пакеты, реализует скользящее окно защита от повторов (Anti-Replay Window 64-bit).

4. **Проверка зависимостей и изоляция пакетов**:
   - Скрипт [`tools/check_imports.py`](file:///home/active/android/minisip/tools/check_imports.py) проверяет ацикличность карты зависимостей и подтверждает, что ни одна реализация (`*Impl`) не уходит за пределы своего пакета.
   - Автономные unit-тесты в [`jvmtest/`](file:///home/active/android/minisip/jvmtest/) выполняют тестирование ключевых протоколов (G.711, RTP, Digest, SDP, Crypto, ESP, MS-CHAPv2, Certs, PageStore) без необходимости запуска эмулятора.

---

## 6. Требования к разработке, стиль кода и тестирование

### 📏 Правила вёрстки и форматирования (.editorconfig)
Все файлы проекта строго следуют правилам, зафиксированным в `.editorconfig`:
- **Отступы**: 4 пробела (табы запрещены).
- **Перевод строки**: `LF` (`\n`).
- **Кодировка**: `UTF-8`.
- **Концевые пробелы**: удаляются автоматическим линтером.
- **Открывающая скобка `{`**: размещается на той же строке.

### 🚫 Правила внесения правок (для разработчиков и нейросетей)
1. **Запрет на переверстку**: Запрещено массово форматировать существующие файлы. Правки вносятся **минимальными точечными диффами**.
2. **Изоляция вёрстки и логики**: Исправление логики не должно затрагивать форматирование окружающих строк.
3. **Сохранение стиля**: Русский язык комментариев сохраняется.
4. **Снятие диффов**: Любой дифф строится от текущей свежей версии репозитория.

### 🧪 Сборка и автоматическое тестирование

1. **Сборка приложения**:
   ```bash
   ./gradlew app:assembleDebug
   ```

2. **Запуск статического анализатора зависимостей пакетов**:
   ```bash
   python3 tools/check_imports.py
   ```

3. **Запуск автономного JVM-тестирования без Android SDK**:
   ```bash
   bash jvmtest/run.sh
   ```

---
*Документация актуализирована по текущему состоянию кодовой базы MiniSIP.*
