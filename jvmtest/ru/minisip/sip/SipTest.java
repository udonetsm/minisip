package ru.minisip.sip;

import ru.minisip.media.Media;
import ru.minisip.net.Udp;

/** Клиентская сторона сценария; серверная — jvmtest/fake_server.py. Печатает события "EV ...". */
public final class SipTest {

    static final class FakeMedia implements Media {
        public int open() { return 40100; }

        public boolean start(String host, int port, int payload) {
            System.out.println("MEDIA start " + host + " " + port + " " + payload);
            return true;
        }

        public void stop() { System.out.println("MEDIA stop"); }
    }

    static int ended;
    static boolean calledFirst;

    public static void main(String[] args) throws Exception {
        Sip sip = Sip.create(Udp.create(), new FakeMedia());
        sip.setListener(new Sip.Listener() {
            public void onRegistered(boolean ok, String info) {
                System.out.println("EV registered " + ok + " " + info);
                if (ok && !calledFirst) {
                    calledFirst = true;
                    sip.call("1000");
                }
            }

            public void onIncoming(String from) {
                System.out.println("EV incoming " + from);
                sip.answer();
            }

            public void onRinging() {
                System.out.println("EV ringing");
                if (ended == 2) sip.hangup();                 // третий вызов: отменяем до ответа
            }

            public void onConnected() {
                System.out.println("EV connected");
                if (ended == 0) {                             // первый вызов: через 400 мс отбой
                    new Thread(() -> {
                        try {
                            Thread.sleep(400);
                        } catch (InterruptedException ignored) {
                        }
                        sip.hangup();
                    }).start();
                }
            }

            public void onEnded(String reason) {
                System.out.println("EV ended " + reason);
                ended++;
                if (ended == 2) sip.call("2000");
                if (ended == 3) {
                    System.out.println("DONE");
                }
            }
        });
        sip.register("127.0.0.1", 5099, "alice", "secret");
        Thread.sleep(60000);                                  // страховка
    }
}
