#!/bin/sh
# Проверки без Android SDK: слои, G.711/RTP, Digest/SDP, SIP-сценарий против скриптового сервера,
# привязка сокетов к интерфейсу, крипто/ESP и IKEv2-туннель против независимого скриптового ответчика.
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/ru/minisip
OUT=$(mktemp -d)
python3 tools/check_imports.py
javac -d "$OUT" $(find jvmtest/stubs -name '*.java') \
      $SRC/net/*.java $SRC/media/*.java $SRC/sip/*.java $SRC/vpn/*.java $SRC/app/PageStore.java \
      jvmtest/ru/minisip/media/*.java jvmtest/ru/minisip/sip/*.java \
      jvmtest/ru/minisip/net/*.java jvmtest/ru/minisip/vpn/*.java jvmtest/ru/minisip/app/*.java

java -cp "$OUT" ru.minisip.sip.DigestTest
java -cp "$OUT" ru.minisip.sip.MsgTest
java -cp "$OUT" ru.minisip.sip.SdpTest
java -cp "$OUT" ru.minisip.media.MediaTest
java -cp "$OUT" ru.minisip.net.NetTest
java -cp "$OUT" ru.minisip.vpn.CryptoTest
java -cp "$OUT" ru.minisip.vpn.MschapTest
java -cp "$OUT" ru.minisip.vpn.CertsTest
java -cp "$OUT" ru.minisip.app.PageStoreTest
python3 jvmtest/fake_ike.py "$OUT"
python3 jvmtest/fake_server.py "$OUT"
