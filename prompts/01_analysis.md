# Промпт 1: Анализ текущего кода и графа зависимостей

## Контекст проекта `minisip`

### Файлы проекта по пакетам:
1. `ru.minisip.app`:
   - `App.java`: Точка входа, оркестрация компонентов.
   - `MainActivity.java`, `SettingsFragment.java`: UI приложения.
   - `CallService.java`: Фоновая служба звонков.
   - `PageStore.java`: Хранение UI-настроек.
2. `ru.minisip.sip`:
   - `Sip.java` (интерфейс), `SipImpl.java` (реализация)
   - `Digest.java`, `Msg.java`, `Sdp.java`: Протокол SIP/SDP/Digest auth.
3. `ru.minisip.media`:
   - `Media.java` (интерфейс), `MediaImpl.java` (реализация)
   - `AndroidAudio.java`, `Audio.java`, `G711.java`: Аудио кодеки и запись/воспроизведение.
4. `ru.minisip.net`:
   - `Udp.java` (интерфейс), `UdpImpl.java` (реализация)
   - `Binder.java` (интерфейс), `AndroidBinder.java` (привязка к сети Android)
5. `ru.minisip.screen`:
   - `Screen.java` (интерфейс), `ScreenImpl.java` (управление экраном/блокировкой)
6. `ru.minisip.vpn`:
   - `Vpn.java` (интерфейс), `VpnImpl.java` (реализация)
   - `Ike.java`, `Tunnel.java`, `Esp.java`, `Certs.java`, `Crypto.java`, `Mschap.java`, `AndroidTun.java`, `Tun.java`, `TunService.java`, `NetWatch.java`: IKEv2/IPsec VPN клиент.

### Граф зависимостей текущего кода:
```
[app] (App, CallService, MainActivity)
 ├──> [sip] (Sip)
 ├──> [media] (Media)
 ├──> [net] (Udp, Binder)
 ├──> [screen] (Screen)
 └──> [vpn] (Vpn)

[sip] (SipImpl)
 ├──> [media] (Media interface)
 └──> [net] (Udp interface)

[media] (MediaImpl)
 └──> [net] (Udp interface)

[net], [screen], [vpn] — автономные модули/пакеты
```

---

## Задание для ИИ:
1. Проанализируй текущий граф зависимостей и роли каждого файла.
2. Опиши кратко текущую архитектуру (сильные стороны и узкие места).
3. Дай ответ коротко, без лишней вводной информации (экономия токенов).
