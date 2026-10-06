package ru.minisip.sip;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import ru.minisip.media.Media;
import ru.minisip.net.Udp;

/**
 * SIP поверх UDP. Вся логика крутится в одном потоке "sip" — блокировок нет.
 * Упрощения: все in-dialog запросы уходят на сервер регистрации (или на адрес, откуда пришёл
 * входящий INVITE); авторизация только Digest-MD5; без STUN/ICE.
 */
final class SipImpl implements Sip {

    private static final SecureRandom RND = new SecureRandom();
    private static final int CONNECTED = 2;

    private final Udp udp;
    private final Media media;
    private final ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sip");
        t.setDaemon(true);
        return t;
    });
    private volatile Listener lis = new Listener() {};

    // настройки и сокет
    private String host, user, pass, ip;
    private int port, lport;

    // регистрация
    private String regId, regTag;
    private int regCseq;
    private boolean regAuth;
    private Tx regTx;
    private ScheduledFuture<?> regTimer;

    // вызов (не больше одного) и фоновые транзакции (BYE, CANCEL)
    private Call call;
    private final Map<String, Tx> bg = new HashMap<>();

    SipImpl(Udp udp, Media media) {
        this.udp = udp;
        this.media = media;
    }

    private static final class Call {
        int state;
        String id, ltag, branch, uri, from, to, target, auth;
        int cseq, rtp;
        List<String> route = new ArrayList<>();
        String dh;          // куда слать запросы внутри диалога
        int dp;
        boolean ringing;
        Tx tx;
    }

    /** Повторная отправка по таймерам SIP: 0.5, 1, 2, 4, 4... секунд, всего 32 с. */
    private final class Tx {
        final byte[] data;
        final String h;
        final int p;
        final Runnable onTimeout;
        int delay = 500;
        int spent;
        ScheduledFuture<?> f;
        boolean done;

        Tx(String msg, String h, int p, Runnable onTimeout) {
            this.data = msg.getBytes(StandardCharsets.UTF_8);
            this.h = h;
            this.p = p;
            this.onTimeout = onTimeout;
        }

        void start() {
            udp.send(data, h, p);
            next();
        }

        private void next() {
            f = ex.schedule(() -> guard(() -> {
                if (done) return;
                spent += delay;
                if (spent >= 32000) {
                    done = true;
                    bg.values().remove(this);
                    if (onTimeout != null) onTimeout.run();
                    return;
                }
                udp.send(data, h, p);
                delay = Math.min(delay * 2, 4000);
                next();
            }), delay, TimeUnit.MILLISECONDS);
        }

        void stop() {
            done = true;
            if (f != null) f.cancel(false);
        }
    }

    // ================= публичный API =================

    @Override
    public void setListener(Listener l) {
        lis = l;
    }

    @Override
    public void register(String h, int p, String u, String pw) {
        post(() -> {
            host = h;
            port = p;
            user = u;
            pass = pw;
            startReg();
        });
    }

    @Override
    public void unregister() {
        post(() -> {
            cancelReg();
            if (ip != null && user != null && host != null) {
                regCseq++;
                String aor = "<sip:" + user + "@" + host + ">";
                String m = msg("REGISTER sip:" + host + " SIP/2.0", "", hdrs(
                        "Via: " + via(branch()), "Max-Forwards: 70",
                        "From: " + aor + ";tag=" + regTag, "To: " + aor,
                        "Call-ID: " + regId, "CSeq: " + regCseq + " REGISTER",
                        "Contact: " + contact(), "Expires: 0", "User-Agent: minisip"));
                send(m, host, port);
            }
            if (call != null) hangup();
            udp.close();
            ip = null;
            lport = 0;
        });
    }

    @Override
    public void call(String number) {
        post(() -> {
            if (call != null) return;
            if (ip == null) {
                lis.onEnded("нет сети");
                return;
            }
            String n = number.trim().replace("#", "%23");
            int rtp = media.open();
            if (rtp < 0) {
                lis.onEnded("нет свободного RTP-порта");
                return;
            }
            Call k = new Call();
            k.id = id() + "@" + ip;
            k.ltag = id();
            k.cseq = 1;
            k.branch = branch();
            k.rtp = rtp;
            k.uri = n.startsWith("sip:") ? n : "sip:" + (n.contains("@") ? n : n + "@" + host);
            k.from = "<sip:" + user + "@" + host + ">;tag=" + k.ltag;
            k.dh = host;
            k.dp = port;
            call = k;
            k.tx = new Tx(invite(k), host, port, () -> end(k, "нет ответа"));
            k.tx.start();
        });
    }

    @Override
    public void hangup() {
        post(() -> {
            Call k = call;
            if (k == null) return;
            if (k.state == CONNECTED) bye(k);
            else cancel(k);
            end(k, "завершён");
        });
    }

    // ================= регистрация =================

    private void startReg() {
        cancelReg();
        lport = udp.open(0, this::onPacket);
        ip = lport > 0 ? udp.localIp(host, port) : null;
        if (ip == null) {
            lis.onRegistered(false, "нет сети или не найден сервер (" + udp.error() + ")");            regTimer = ex.schedule(() -> guard(this::startReg), 20, TimeUnit.SECONDS);
            return;
        }
        regId = id() + "@" + ip;
        regTag = id();
        regCseq = 0;
        doRegister();
    }

    private void cancelReg() {
        if (regTx != null) regTx.stop();
        if (regTimer != null) regTimer.cancel(false);
    }

    private void doRegister() {
        regCseq++;
        regAuth = false;
        sendReg(null);
    }

    private void sendReg(String auth) {
        if (regTx != null) regTx.stop();
        String aor = "<sip:" + user + "@" + host + ">";
        String m = msg("REGISTER sip:" + host + " SIP/2.0", "", hdrs(
                "Via: " + via(branch()), "Max-Forwards: 70",
                "From: " + aor + ";tag=" + regTag, "To: " + aor,
                "Call-ID: " + regId, "CSeq: " + regCseq + " REGISTER",
                "Contact: " + contact(), "Expires: 120", "User-Agent: minisip", auth));
        regTx = new Tx(m, host, port, () -> regFail("сервер не отвечает", true));
        regTx.start();
    }

    private void onRegResp(Msg m) {
        int c = m.status();
        if (c < 200) return;
        if (regTx != null) regTx.stop();
        if (c == 401 || c == 407) {
            String a = regAuth ? null : authHeader(m, "REGISTER", "sip:" + host);
            if (a == null) {
                regFail("неверный логин или пароль", false);
                return;
            }
            regAuth = true;
            regCseq++;
            sendReg(a);
        } else if (c < 300) {
            int exp = 120;
            String e = Msg.param(m.get("contact"), "expires");
            if (e == null) e = m.get("expires");
            try {
                if (e != null) exp = Integer.parseInt(e.trim());
            } catch (NumberFormatException ignored) {
            }
            lis.onRegistered(true, "");
            regTimer = ex.schedule(() -> guard(this::doRegister), Math.max(20, exp / 2),
                    TimeUnit.SECONDS);
        } else {
            regFail(c + " " + m.reason(), c >= 500);
        }
    }

    private void regFail(String why, boolean retry) {
        lis.onRegistered(false, why);
        if (retry) regTimer = ex.schedule(() -> guard(this::startReg), 20, TimeUnit.SECONDS);
    }

    // ================= ответы на наши запросы =================

    private void onPacket(byte[] d, String h, int p) {
        post(() -> {
            Msg m = Msg.parse(d);
            if (m == null) return;
            if (m.isResp()) onResp(m);
            else onReq(m, h, p);
        });
    }

    private void onResp(Msg m) {
        String cm = m.cseqMethod();
        if (cm.equals("REGISTER")) {
            onRegResp(m);
        } else if (cm.equals("INVITE")) {
            onInviteResp(m);
        } else if (m.status() >= 200) {          // BYE, CANCEL
            Tx t = bg.remove(m.key());
            if (t != null) t.stop();
        }
    }

    private void onInviteResp(Msg m) {
        int c = m.status();
        Call k = call;
        boolean mine = k != null && k.id.equals(m.get("call-id")) && k.cseq == m.cseq();
        if (!mine) {
            stray(m);
            return;
        }
        if (c < 200) {
            k.tx.stop();
            if ((c == 180 || c == 183) && !k.ringing) {
                k.ringing = true;
                String[] s = Sdp.parse(m.body);
                if (s != null) {
                    media.start(s[0], Integer.parseInt(s[1]), Integer.parseInt(s[2]));
                }
                lis.onRinging();
            }
            return;
        }
        if (k.state == CONNECTED) {              // повтор 200 OK — повторяем ACK
            if (c < 300) send(ack2xx(k), k.dh, k.dp);
            return;
        }
        k.tx.stop();
        if (c == 401 || c == 407) {
            send(ackFail(m), host, port);
            String a = k.auth != null ? null : authHeader(m, "INVITE", k.uri);
            if (a == null) {
                end(k, "ошибка авторизации");
                return;
            }
            k.auth = a;
            k.cseq++;
            k.branch = branch();
            k.tx = new Tx(invite(k), host, port, () -> end(k, "нет ответа"));
            k.tx.start();
        } else if (c >= 300) {
            send(ackFail(m), host, port);
            end(k, c + " " + m.reason());
        } else {                                  // 2xx
            k.to = m.get("to");
            String ct = m.get("contact");
            k.target = ct != null ? Msg.uri(ct) : k.uri;
            List<String> rr = new ArrayList<>(m.all("record-route"));
            Collections.reverse(rr);
            k.route = rr;
            send(ack2xx(k), k.dh, k.dp);
            String[] s = Sdp.parse(m.body);
            boolean ok = s != null
                    && media.start(s[0], Integer.parseInt(s[1]), Integer.parseInt(s[2]));
            if (!ok) {
                bye(k);
                end(k, "ошибка медиа");
                return;
            }
            k.state = CONNECTED;
            lis.onConnected();
        }
    }

    /** Ответ на вызов, которого у нас уже нет (отменили, гонка CANCEL/200): добиваем без состояния. */
    private void stray(Msg m) {
        int c = m.status();
        if (c < 200) return;
        if (c >= 300) {
            send(ackFail(m), host, port);
            return;
        }
        String ct = m.get("contact");
        String target = ct != null ? Msg.uri(ct) : Msg.uri(m.get("to"));
        List<String> rr = new ArrayList<>(m.all("record-route"));
        Collections.reverse(rr);
        for (String method : new String[]{"ACK", "BYE"}) {
            List<String> l = hdrs("Via: " + via(branch()), "Max-Forwards: 70");
            for (String r : rr) l.add("Route: " + r);
            l.addAll(hdrs("From: " + m.get("from"), "To: " + m.get("to"),
                    "Call-ID: " + m.get("call-id"),
                    "CSeq: " + (method.equals("ACK") ? m.cseq() : m.cseq() + 1) + " " + method));
            send(msg(method + " " + target + " SIP/2.0", "", l), host, port);
        }
    }

    // ================= запросы от сервера =================

    private void onReq(Msg m, String h, int p) {
        Call k = call;
        boolean same = k != null && k.id.equals(m.get("call-id"));
        switch (m.method()) {
            case "INVITE":
                send(response(m, 486, "Busy Here", null, null, ""), h, p);
                break;
            case "ACK":
                if (same && k.tx != null) k.tx.stop();
                break;
            case "BYE":
                send(response(m, 200, "OK", null, null, ""), h, p);
                if (same) end(k, "собеседник завершил");
                break;
            case "CANCEL":
                send(response(m, 200, "OK", null, null, ""), h, p);
                break;
            case "OPTIONS":
                send(response(m, 200, "OK", null,
                        hdrs("Allow: INVITE, ACK, CANCEL, BYE, OPTIONS"), ""), h, p);
                break;
            default:
                send(response(m, 405, "Method Not Allowed", null,
                        hdrs("Allow: INVITE, ACK, CANCEL, BYE, OPTIONS"), ""), h, p);
        }
    }

    // ================= построение сообщений =================

    private String invite(Call k) {
        String sdp = Sdp.make(ip, k.rtp, 8, 0);
        return msg("INVITE " + k.uri + " SIP/2.0", sdp, hdrs(
                "Via: " + via(k.branch), "Max-Forwards: 70",
                "From: " + k.from, "To: <" + k.uri + ">",
                "Call-ID: " + k.id, "CSeq: " + k.cseq + " INVITE",
                "Contact: " + contact(), "User-Agent: minisip", k.auth,
                "Content-Type: application/sdp"));
    }

    private void cancel(Call k) {
        String m = msg("CANCEL " + k.uri + " SIP/2.0", "", hdrs(
                "Via: " + via(k.branch), "Max-Forwards: 70",
                "From: " + k.from, "To: <" + k.uri + ">",
                "Call-ID: " + k.id, "CSeq: " + k.cseq + " CANCEL"));
        reliable(m, k.id + "|" + k.cseq + "|CANCEL", k.dh, k.dp);
    }

    private void bye(Call k) {
        int n = ++k.cseq;
        List<String> l = hdrs("Via: " + via(branch()), "Max-Forwards: 70");
        for (String r : k.route) l.add("Route: " + r);
        l.addAll(hdrs("From: " + k.from, "To: " + k.to, "Call-ID: " + k.id,
                "CSeq: " + n + " BYE", "User-Agent: minisip"));
        reliable(msg("BYE " + k.target + " SIP/2.0", "", l), k.id + "|" + n + "|BYE", k.dh, k.dp);
    }

    private String ack2xx(Call k) {
        List<String> l = hdrs("Via: " + via(branch()), "Max-Forwards: 70");
        for (String r : k.route) l.add("Route: " + r);
        l.addAll(hdrs("From: " + k.from, "To: " + k.to, "Call-ID: " + k.id,
                "CSeq: " + k.cseq + " ACK"));
        return msg("ACK " + k.target + " SIP/2.0", "", l);
    }

    /** ACK на финальный не-2xx ответ собирается из самого ответа. */
    private String ackFail(Msg r) {
        return msg("ACK " + Msg.uri(r.get("to")) + " SIP/2.0", "", hdrs(
                "Via: " + r.get("via"), "Max-Forwards: 70",
                "From: " + r.get("from"), "To: " + r.get("to"),
                "Call-ID: " + r.get("call-id"), "CSeq: " + r.cseq() + " ACK"));
    }

    private String response(Msg req, int code, String reason, String tag, List<String> extra,
                            String body) {
        List<String> l = new ArrayList<>();
        for (String v : req.all("via")) l.add("Via: " + v);
        l.add("From: " + req.get("from"));
        String to = req.get("to");
        if (tag != null && Msg.param(to, "tag") == null) to += ";tag=" + tag;
        l.add("To: " + to);
        l.add("Call-ID: " + req.get("call-id"));
        l.add("CSeq: " + req.get("cseq"));
        l.add("User-Agent: minisip");
        if (extra != null) l.addAll(extra);
        return msg("SIP/2.0 " + code + " " + reason, body, l);
    }

    private static String msg(String first, String body, List<String> hs) {
        StringBuilder b = new StringBuilder(first).append("\r\n");
        for (String h : hs) b.append(h).append("\r\n");
        b.append("Content-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length)
                .append("\r\n\r\n").append(body);
        return b.toString();
    }

    /** Список заголовков без null-элементов. */
    private static List<String> hdrs(String... a) {
        List<String> l = new ArrayList<>();
        for (String s : a) if (s != null) l.add(s);
        return l;
    }

    private String via(String branch) {
        return "SIP/2.0/UDP " + ip + ":" + lport + ";branch=" + branch + ";rport";
    }

    private String contact() {
        return "<sip:" + user + "@" + ip + ":" + lport + ";transport=udp>";
    }

    private String authHeader(Msg m, String method, String uri) {
        boolean proxy = m.status() == 407;
        for (String ch : m.all(proxy ? "proxy-authenticate" : "www-authenticate")) {
            String a = Digest.answer(ch, user, pass, method, uri);
            if (a != null) return (proxy ? "Proxy-Authorization: " : "Authorization: ") + a;
        }
        return null;
    }

    // ================= служебное =================

    private void end(Call k, String reason) {
        if (call != k) return;
        call = null;
        if (k.tx != null) k.tx.stop();
        media.stop();
        lis.onEnded(reason);
    }

    private void reliable(String m, String key, String h, int p) {
        Tx t = new Tx(m, h, p, null);
        bg.put(key, t);
        t.start();
    }

    private void send(String m, String h, int p) {
        udp.send(m.getBytes(StandardCharsets.UTF_8), h, p);
    }

    private void post(Runnable r) {
        ex.execute(() -> guard(r));
    }

    /** Исключение в обработчике (кривой пакет, ошибка в UI-колбэке) не должно ронять приложение. */
    private static void guard(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            // пакет отбрасывается
        }
    }

    private static String id() {
        return String.format("%016x", RND.nextLong());
    }

    private static String branch() {
        return "z9hG4bK" + id();
    }
}
