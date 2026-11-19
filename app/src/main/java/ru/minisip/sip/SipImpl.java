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

import ru.minisip.log.LogSink;
import ru.minisip.media.Media;
import ru.minisip.net.Udp;

/**
 * Single-threaded SIP client implementation executing on dedicated "sip" thread.
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
    private final Map<String, Tx> bg = new HashMap<>();
    private volatile Listener lis = new Listener() {
    };
    private volatile LogSink sink = s -> {
    };
    // socket and server configuration
    private String host, user, pass, ip;
    private int port, lport;
    // registration
    private String regId, regTag, regBranch;
    private int regCseq;
    private boolean regAuth;
    private Tx regTx;
    private ScheduledFuture<?> regTimer;
    // call state and background transactions (BYE, CANCEL)
    private Call call;
    private Call closedCall;
    private ScheduledFuture<?> closedTimer;
    // recently sent INVITE branches; stray 2xx handling per RFC 3261 §9.1
    private final java.util.LinkedHashSet<String> sentInvites = new java.util.LinkedHashSet<>();
    private final Map<String, IncomingInvite> incomingInvites = new java.util.LinkedHashMap<String, IncomingInvite>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, IncomingInvite> eldest) {
            return size() > 16;
        }
    };

    private static final class IncomingInvite {
        final Msg req;
        final String h;
        final int p;
        boolean finalResponseSent;

        IncomingInvite(Msg req, String h, int p) {
            this.req = req;
            this.h = h;
            this.p = p;
        }
    }

    SipImpl(Udp udp, Media media) {
        this.udp = udp;
        this.media = media;
    }

    /**
     * Тело message/sipfrag: "SIP/2.0 200 OK" -> код.
     */
    private static int sipfragCode(String body) {
        if (body == null) return 0;
        String line = body.split("\\r?\\n", 2)[0].trim();
        String[] p = line.split(" ", 3);
        if (p.length < 2 || !p[0].startsWith("SIP/")) return 0;
        try {
            return Integer.parseInt(p[1]);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String sipfragReason(String body) {
        if (body == null) return "";
        String line = body.split("\\r?\\n", 2)[0].trim();
        String[] p = line.split(" ", 3);
        return p.length > 2 ? p[2] : "";
    }

    // ================= Public API =================

    private static String msg(String first, String body, List<String> hs) {
        StringBuilder b = new StringBuilder(first).append("\r\n");
        for (String h : hs) b.append(h).append("\r\n");
        b.append("Content-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length)
                .append("\r\n\r\n").append(body);
        return b.toString();
    }

    /**
     * Header list builder omitting null items.
     */
    private static List<String> hdrs(String... a) {
        List<String> l = new ArrayList<>();
        for (String s : a) if (s != null) l.add(s);
        return l;
    }

    /**
     * Guard wrapper suppressing uncaught runtime exceptions.
     */
    private static void guard(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            // packet discarded
        }
    }

    private static String id() {
        return String.format("%016x", RND.nextLong());
    }

    private static String branch() {
        return "z9hG4bK" + id();
    }

    /**
     * Tracks an outbound INVITE branch to accept its stray responses (§9.1).
     */
    private void trackInvite(String viaHdr) {
        String vk = viaKey(viaHdr);
        if (sentInvites.size() >= 16) {
            java.util.Iterator<String> it = sentInvites.iterator();
            it.next();
            it.remove();
        }
        sentInvites.add(vk);
    }

    private static void addBounded(java.util.LinkedHashSet<String> set, String key) {
        if (set.size() >= 16) {
            java.util.Iterator<String> it = set.iterator();
            it.next();
            it.remove();
        }
        set.add(key);
    }

    private static String viaKey(Msg m) {
        return m == null ? null : viaKey(m.get("via"));
    }

    private static String viaKey(String v) {
        if (v == null) return null;
        String b = Msg.param(v, "branch");
        String[] parts = v.trim().split("\\s+");
        if (b == null || parts.length < 2) return null;
        String sb = parts[1].split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        if (sb.isEmpty()) return null;
        int closeBracket = sb.indexOf(']');
        int colon = closeBracket >= 0 ? sb.indexOf(':', closeBracket) : sb.indexOf(':');
        if (colon < 0) sb = sb + ":5060";
        return b.trim().toLowerCase(java.util.Locale.ROOT) + "|" + sb;
    }

    private static String viaKeyFromData(byte[] data) {
        String s = new String(data, StandardCharsets.UTF_8);
        for (String line : s.split("\r?\n")) {
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("via:") || lower.startsWith("v:")) {
                int c = line.indexOf(':');
                return viaKey(line.substring(c + 1).trim());
            }
        }
        return null;
    }

    @Override
    public void setListener(Listener l) {
        lis = l;
    }

    @Override
    public void setLogSink(LogSink s) {
        sink = s == null ? x -> {
        } : s;
    }

    /**
     * Single debug log entry point. Passwords and credentials omitted.
     */
    private void log(String m) {
        sink.log(m);
    }

    // ================= Registration =================

    @Override
    public void register(String h, int p, String u, String pw) {
        post(() -> {
            log("register " + h + ":" + p + " " + u);
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
            log("unregister");
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
            log("INVITE → " + number.trim());
            if (call != null) return;
            if (ip == null) {
                log("call failed: no network");
                lis.onEnded("no network");
                return;
            }
            String n = number.trim().replace("#", "%23");
            int rtp = media.open();
            if (rtp < 0) {
                lis.onEnded("no free RTP port");
                return;
            }
            Call k = new Call();
            k.id = id() + "@" + ip;
            k.ltag = id();
            k.cseq = 1;
            k.inviteCseq = 1;
            k.branch = branch();
            trackInvite(via(k.branch));
            k.rtp = rtp;
            k.uri = n.startsWith("sip:") ? n : "sip:" + (n.contains("@") ? n : n + "@" + host);
            k.from = "<sip:" + user + "@" + host + ">;tag=" + k.ltag;
            k.dh = host;
            k.dp = port;
            call = k;
            k.tx = new Tx(invite(k), host, port, () -> end(k, "no answer"));
            k.tx.start();
        });
    }

    @Override
    public void transfer(String target) {
        post(() -> {
            Call k = call;
            if (k == null || k.state != CONNECTED) {
                log("transfer: no active call");
                return;
            }
            if (k.referCseq > 0) {
                log("transfer already in progress");
                return;
            }
            String t = target == null ? "" : target.trim();
            if (t.isEmpty()) return;
            String uri = t.startsWith("sip:") ? t
                    : "sip:" + (t.contains("@") ? t : t + "@" + host);
            k.referCseq = ++k.cseq;
            log("REFER → " + uri);
            Tx tx = new Tx(refer(k, uri), k.dh, k.dp, () -> {
                log("transfer: no response");
                k.referCseq = 0;
            });
            bg.put(k.id + "|" + k.referCseq + "|REFER", tx);
            tx.start();
        });
    }

    @Override
    public void hangup() {
        post(() -> {
            Call k = call;
            if (k == null) return;
            if (k.state == CONNECTED) bye(k);
            else cancel(k);
            end(k, "ended");
        });
    }

    private void startReg() {
        cancelReg();
        lport = udp.open(0, this::onPacket);
        ip = lport > 0 ? udp.localIp(host, port) : null;
        if (ip == null) {
            lis.onRegistered(false, "no network or server not found (" + udp.error() + ")");
            regTimer = ex.schedule(() -> guard(this::startReg), 20, TimeUnit.SECONDS);
            return;
        }
        regId = id() + "@" + ip;
        regTag = id();
        regCseq = 0;
        doRegister();
    }

    // ================= Responses =================

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
        regBranch = branch();
        String aor = "<sip:" + user + "@" + host + ">";
        String m = msg("REGISTER sip:" + host + " SIP/2.0", "", hdrs(
                "Via: " + via(regBranch), "Max-Forwards: 70",
                "From: " + aor + ";tag=" + regTag, "To: " + aor,
                "Call-ID: " + regId, "CSeq: " + regCseq + " REGISTER",
                "Contact: " + contact(), "Expires: 120", "User-Agent: minisip", auth));
        regTx = new Tx(m, host, port, () -> regFail("server not responding", true));
        regTx.start();
    }

    private void onRegResp(Msg m) {
        if (!regId.equals(m.get("call-id"))) return;
        String vk = viaKey(m);
        if (vk == null || !vk.equals(viaKey(via(regBranch)))) return;
        int c = m.status();
        if (c < 200) return;
        if (regTx != null) regTx.stop();
        if (c == 401 || c == 407) {
            log(c + " received");
            String a = regAuth ? null : authHeader(m, "REGISTER", "sip:" + host);
            if (a == null) {
                regFail("invalid login or password", false);
                return;
            }
            regAuth = true;
            log("authorized");
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
            log("registered ok (expires=" + exp + ")");
            lis.onRegistered(true, "");
            regTimer = ex.schedule(() -> guard(this::doRegister), Math.max(20, exp / 2),
                    TimeUnit.SECONDS);
        } else {
            regFail(c + " " + m.reason(), c >= 500);
        }
    }

    // ================= Server Requests =================

    private void regFail(String why, boolean retry) {
        log("register failed: " + why);
        lis.onRegistered(false, why);
        if (retry) regTimer = ex.schedule(() -> guard(this::startReg), 20, TimeUnit.SECONDS);
    }

    // ================= Message Construction =================

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
        } else if (cm.equals("REFER")) {
            onReferResp(m);
        } else if (m.status() >= 200) {          // BYE, CANCEL
            Tx t = bg.get(m.key());
            if (t != null) {
                String reqVk = viaKeyFromData(t.data);
                String respVk = viaKey(m);
                if (reqVk == null || reqVk.equals(respVk)) {
                    bg.remove(m.key());
                    t.stop();
                }
            }
        }
    }

    private void onInviteResp(Msg m) {
        int c = m.status();
        Call k = call;
        boolean mine = k != null && k.id.equals(m.get("call-id")) && k.inviteCseq == m.cseq() && "INVITE".equalsIgnoreCase(m.cseqMethod());
        if (!mine) {
            stray(m);
            return;
        }
        String vk = viaKey(m);
        if (vk == null || !vk.equals(viaKey(via(k.branch)))) return;
        if (c < 200) {
            k.tx.stopRetx();
            if (c == 100) log("100 Trying");
            if ((c == 180 || c == 183) && !k.ringing) {
                k.ringing = true;
                log(c + " Ringing");
                String[] s = Sdp.parse(m.body);
                if (s != null) {
                    media.start(s[0], Integer.parseInt(s[1]), Integer.parseInt(s[2]));
                }
                lis.onRinging();
            }
            return;
        }
        if (k.state == CONNECTED) {              // retransmitted 200 OK — retransmit ACK
            if (c < 300) {
                String kt = Msg.param(k.to, "tag");
                String mt = Msg.param(m.get("to"), "tag");
                if (kt != null && kt.equals(mt)) {
                    send(ack2xx(k), k.dh, k.dp);
                } else {
                    stray2xx(m);
                }
            }
            return;
        }
        k.tx.stop();
        if (c == 401 || c == 407) {
            send(ackFail(k, m), host, port);
            String a = k.auth != null ? null : authHeader(m, "INVITE", k.uri);
            if (a == null) {
                end(k, "authentication error");
                return;
            }
            k.auth = a;
            k.cseq++;
            k.inviteCseq = k.cseq;
            k.branch = branch();
            trackInvite(via(k.branch));
            k.tx = new Tx(invite(k), host, port, () -> end(k, "no answer"));
            k.tx.start();
        } else if (c >= 300) {
            sentInvites.remove(viaKey(via(k.branch)));
            send(ackFail(k, m), host, port);
            log("call failed: " + c + " " + m.reason());
            end(k, c + " " + m.reason());
        } else {                                  // 2xx
            sentInvites.remove(viaKey(via(k.branch)));
            k.to = m.get("to");
            String ct = m.get("contact");
            k.target = ct != null ? Msg.uri(ct) : k.uri;
            List<String> rr = new ArrayList<>(m.all("record-route"));
            Collections.reverse(rr);
            k.route = rr;
            updateDest(k);
            send(ack2xx(k), k.dh, k.dp);
            log("200 OK");
            log("ACK");
            String[] s = Sdp.parse(m.body);
            boolean ok = s != null
                    && media.start(s[0], Integer.parseInt(s[1]), Integer.parseInt(s[2]));
            if (!ok) {
                bye(k);
                end(k, "media error");
                return;
            }
            k.state = CONNECTED;
            lis.onConnected();
        }
    }

    /**
     * Handles response for ended call (canceled or race condition).
     */
    private void stray(Msg m) {
        int c = m.status();
        if (c < 200) return;
        String vk = viaKey(m);
        if (vk == null || !sentInvites.contains(vk)) return;
        sentInvites.remove(vk);
        String b = Msg.param(m.get("via"), "branch");
        if (c >= 300) {
            String ruri = Msg.uri(m.get("to"));
            if (ruri != null) {
                send(msg("ACK " + ruri + " SIP/2.0", "", hdrs(
                                "Via: " + via(b), "Max-Forwards: 70",
                                "From: " + m.get("from"), "To: " + m.get("to"),
                                "Call-ID: " + m.get("call-id"), "CSeq: " + m.cseq() + " ACK")),
                        host, port);
            }
            return;
        }
        stray2xx(m);
    }

    /**
     * Handles stray or forked 2xx response by acknowledging and terminating with BYE.
     */
    private void stray2xx(Msg m) {
        String ruri = Msg.uri(m.get("contact"));
        if (ruri == null) ruri = Msg.uri(m.get("to"));
        if (ruri == null) return;
        List<String> rr = new ArrayList<>(m.all("record-route"));
        Collections.reverse(rr);
        String dest = rr.isEmpty() ? ruri : Msg.uri(rr.get(0));
        if (dest == null) dest = ruri;
        String th = host;
        int tp = port;
        if (dest.startsWith("sip:")) {
            String[] hp = splitSipUri(dest);
            th = hp[0];
            try {
                tp = Integer.parseInt(hp[1]);
            } catch (NumberFormatException e) {
                tp = 5060;
            }
        }
        List<String> ack = hdrs("Via: " + via(branch()), "Max-Forwards: 70");
        for (String r : rr) ack.add("Route: " + r);
        ack.addAll(hdrs("From: " + m.get("from"), "To: " + m.get("to"),
                "Call-ID: " + m.get("call-id"), "CSeq: " + m.cseq() + " ACK"));
        send(msg("ACK " + ruri + " SIP/2.0", "", ack), th, tp);
        List<String> bye = hdrs("Via: " + via(branch()), "Max-Forwards: 70");
        for (String r : rr) bye.add("Route: " + r);
        bye.addAll(hdrs("From: " + m.get("from"), "To: " + m.get("to"),
                "Call-ID: " + m.get("call-id"), "CSeq: " + (m.cseq() + 1) + " BYE"));
        send(msg("BYE " + ruri + " SIP/2.0", "", bye), th, tp);
    }

    /**
     * Splits a SIP URI into {host, port} per RFC 3261 §19.1.1.
     */
    private static String[] splitSipUri(String uri) {
        String u = uri;
        int c = u.indexOf(':');
        if (c >= 0) u = u.substring(c + 1);
        int at = u.lastIndexOf('@');
        if (at >= 0) u = u.substring(at + 1);
        int semi = u.indexOf(';');
        if (semi >= 0) u = u.substring(0, semi);
        int closeBracket = u.indexOf(']');
        int col = closeBracket >= 0 ? u.indexOf(':', closeBracket) : u.lastIndexOf(':');
        String host = col >= 0 ? u.substring(0, col) : u;
        String port = col >= 0 ? u.substring(col + 1) : "5060";
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return new String[]{host, port};
    }

    /**
     * Sets in-dialog destination per RFC 3261 §12.2.1.1 / §18.1: top Route
     * if the route set is non-empty, otherwise the remote target.
     */
    private void updateDest(Call k) {
        String ref = k.route.isEmpty() ? k.target : k.route.get(0);
        String uri = Msg.uri(ref);
        if (uri == null || !uri.startsWith("sip:")) return;
        String[] hp = splitSipUri(uri);
        k.dh = hp[0];
        try {
            k.dp = Integer.parseInt(hp[1]);
        } catch (NumberFormatException e) {
            k.dp = 5060;
        }
    }

    /**
     * Extracts the branch parameter from a raw serialized SIP request.
     */
    private static String extractBranch(byte[] data) {
        String s = new String(data, StandardCharsets.UTF_8);
        int v = s.indexOf("Via:");
        if (v < 0) v = s.indexOf("via:");
        if (v < 0) return null;
        int b = s.indexOf("branch=", v);
        if (b < 0) return null;
        int start = b + 7;
        int end = start;
        while (end < s.length()) {
            char c = s.charAt(end);
            if (c == ';' || c == ',' || c == '\r' || c == '\n' || c == '>') break;
            end++;
        }
        return start < end ? s.substring(start, end) : null;
    }

    private void onReq(Msg m, String h, int p) {
        Call k = null;
        if (inDialog(m, call)) k = call;
        else if (inDialog(m, closedCall)) k = closedCall;
        boolean same = k != null;
        if (!"ACK".equals(m.method()) && !m.method().equalsIgnoreCase(m.cseqMethod())) {
            send(response(m, 400, "Bad Request", null, null, ""), h, p);
            return;
        }
        if (same && !"ACK".equals(m.method()) && !"CANCEL".equals(m.method())) {
            if (m.cseq() < k.remoteCseq || (m.cseq() == k.remoteCseq && k.remoteMethod != null && !m.method().equalsIgnoreCase(k.remoteMethod))) {
                send(response(m, 500, "Server Internal Error", null, null, ""), h, p);
                return;
            }
            k.remoteCseq = m.cseq();
            k.remoteMethod = m.method();
        }
        switch (m.method()) {
            case "INVITE":
                String ik = viaKey(m);
                if (ik != null) incomingInvites.put(ik, new IncomingInvite(m, h, p));
                send(response(m, 180, "Ringing", null, null, ""), h, p);
                break;
            case "ACK":
                if (same && k.tx != null) k.tx.stop();
                break;
            case "BYE":
                if (!same) {
                    send(response(m, 481, "Call/Transaction Does Not Exist", null, null, ""), h, p);
                    break;
                }
                send(response(m, 200, "OK", null, null, ""), h, p);
                end(k, "remote party ended call");
                break;
            case "CANCEL":
                String ck = viaKey(m);
                IncomingInvite inv = ck != null ? incomingInvites.get(ck) : null;
                if (inv != null) {
                    if (!inv.finalResponseSent) {
                        inv.finalResponseSent = true;
                        send(response(inv.req, 487, "Request Terminated", null, null, ""), inv.h, inv.p);
                    }
                    send(response(m, 200, "OK", null, null, ""), h, p);
                } else {
                    send(response(m, 481, "Call/Transaction Does Not Exist", null, null, ""), h, p);
                }
                break;
            case "OPTIONS":
                send(response(m, 200, "OK", null,
                        hdrs("Allow: INVITE, ACK, CANCEL, BYE, NOTIFY, OPTIONS"), ""), h, p);
                break;
            case "NOTIFY":
                if (!same) {
                    send(response(m, 481, "Call/Transaction Does Not Exist", null, null, ""), h, p);
                    break;
                }
                send(response(m, 200, "OK", null, null, ""), h, p);
                if ("refer".equals(Msg.eventName(m.get("event")))) {
                    int code = sipfragCode(m.body);
                    if (code >= 200) onReferFinal(k, code, sipfragReason(m.body));
                }
                break;
            default:
                send(response(m, 405, "Method Not Allowed", null,
                        hdrs("Allow: INVITE, ACK, CANCEL, BYE, NOTIFY, OPTIONS"), ""), h, p);
        }
    }

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
        log("CANCEL");
        String m = msg("CANCEL " + k.uri + " SIP/2.0", "", hdrs(
                "Via: " + via(k.branch), "Max-Forwards: 70",
                "From: " + k.from, "To: <" + k.uri + ">",
                "Call-ID: " + k.id, "CSeq: " + k.cseq + " CANCEL"));
        reliable(m, k.id + "|" + k.cseq + "|CANCEL", k.dh, k.dp);
    }

    private void bye(Call k) {
        int n = ++k.cseq;
        log("BYE");
        List<String> l = hdrs("Via: " + via(branch()), "Max-Forwards: 70");
        addRoutes(k, l);
        l.addAll(hdrs("From: " + k.from, "To: " + k.to, "Call-ID: " + k.id,
                "CSeq: " + n + " BYE", "User-Agent: minisip"));
        reliable(msg("BYE " + reqUri(k) + " SIP/2.0", "", l), k.id + "|" + n + "|BYE", k.dh, k.dp);
    }

    private String ack2xx(Call k) {
        List<String> l = hdrs("Via: " + via(branch()), "Max-Forwards: 70");
        addRoutes(k, l);
        l.addAll(hdrs("From: " + k.from, "To: " + k.to, "Call-ID: " + k.id,
                "CSeq: " + k.inviteCseq + " ACK"));
        return msg("ACK " + reqUri(k) + " SIP/2.0", "", l);
    }

    /**
     * REFER request in dialog context.
     */
    private String refer(Call k, String target) {
        List<String> l = hdrs("Via: " + via(branch()), "Max-Forwards: 70");
        addRoutes(k, l);
        l.addAll(hdrs("From: " + k.from, "To: " + k.to, "Call-ID: " + k.id,
                "CSeq: " + k.referCseq + " REFER", "Contact: " + contact(),
                "Refer-To: <" + target + ">", "User-Agent: minisip"));
        return msg("REFER " + reqUri(k) + " SIP/2.0", "", l);
    }

    /**
     * 202 Accepted response handler.
     */
    private void onReferResp(Msg m) {
        Call k = call;
        if (k == null || !k.id.equals(m.get("call-id")) || k.referCseq != m.cseq()) return;
        int c = m.status();
        if (c < 200) return;
        Tx t = bg.get(m.key());
        if (t == null) return;
        String reqVk = viaKeyFromData(t.data);
        String respVk = viaKey(m);
        if (reqVk != null && !reqVk.equals(respVk)) return;
        bg.remove(m.key());
        t.stop();
        if (c >= 300) {
            log("transfer failed: " + c + " " + m.reason());
            k.referCseq = 0;
            return;
        }
        log("202 Accepted");
        k.referTimer = ex.schedule(() -> guard(() -> {
            if (k.referCseq == 0) return;
            log("transfer: no NOTIFY");
            onReferFinal(k, 0, "no notify");
        }), 32, TimeUnit.SECONDS);
    }

    /**
     * Final NOTIFY refer handler.
     */
    private void onReferFinal(Call k, int code, String reason) {
        if (k.referCseq == 0) return;
        k.referCseq = 0;
        if (k.referTimer != null) {
            k.referTimer.cancel(false);
            k.referTimer = null;
        }
        if (call != k) return;
        String msg = code == 0 ? "transfer " + reason
                : code < 300 ? "transferred"
                  : "transfer failed: " + code + " " + reason;
        log(msg);
        bye(k);
        end(k, msg);
    }

    /**
     * ACK for non-2xx INVITE response, echoing original INVITE fields (RFC 3261 §17.1.1.3).
     */
    private String ackFail(Call k, Msg r) {
        return msg("ACK " + k.uri + " SIP/2.0", "", hdrs(
                "Via: " + via(k.branch), "Max-Forwards: 70",
                "From: " + k.from, "To: " + r.get("to"),
                "Call-ID: " + k.id, "CSeq: " + k.cseq + " ACK"));
    }

    private String response(Msg req, int code, String reason, String tag, List<String> extra,
                            String body) {
        List<String> l = new ArrayList<>();
        for (String v : req.all("via")) l.add("Via: " + v);
        l.add("From: " + req.get("from"));
        String to = req.get("to");
        if (code > 100 && Msg.param(to, "tag") == null) {
            to += ";tag=" + (tag != null ? tag : id());
        }
        l.add("To: " + to);
        l.add("Call-ID: " + req.get("call-id"));
        l.add("CSeq: " + req.get("cseq"));
        l.add("User-Agent: minisip");
        if (extra != null) l.addAll(extra);
        return msg("SIP/2.0 " + code + " " + reason, body, l);
    }

    private String via(String branch) {
        return "SIP/2.0/UDP " + ip + ":" + lport + ";branch=" + branch + ";rport";
    }

    private String contact() {
        return "<sip:" + user + "@" + ip + ":" + lport + ";transport=udp>";
    }

    /**
     * Matches in-dialog request against active call using Call-ID and both tags (RFC 3261 §12.2.2).
     */
    private boolean inDialog(Msg m, Call k) {
        if (k == null || !k.id.equals(m.get("call-id"))) return false;
        String rtag = Msg.param(k.to, "tag");
        if (rtag == null || !rtag.equals(Msg.param(m.get("from"), "tag"))) return false;
        return k.ltag.equals(Msg.param(m.get("to"), "tag"));
    }

    // ================= Internal Utilities =================

    private String authHeader(Msg m, String method, String uri) {
        boolean proxy = m.status() == 407;
        for (String ch : m.all(proxy ? "proxy-authenticate" : "www-authenticate")) {
            String a = Digest.answer(ch, user, pass, method, uri);
            if (a != null) return (proxy ? "Proxy-Authorization: " : "Authorization: ") + a;
        }
        return null;
    }

    private void end(Call k, String reason) {
        if (call != k) return;
        call = null;
        closedCall = k;
        if (closedTimer != null) closedTimer.cancel(false);
        closedTimer = ex.schedule(() -> {
            closedCall = null;
            closedTimer = null;
        }, 32, TimeUnit.SECONDS);
        if (k.tx != null) k.tx.stop();
        if (k.referTimer != null) {
            k.referTimer.cancel(false);
            k.referTimer = null;
        }
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

    private static final class Call {
        int state;
        String id, ltag, branch, uri, from, to, target, auth;
        int cseq, inviteCseq, rtp;
        List<String> route = new ArrayList<>();
        String dh;          // in-dialog target host
        int dp;
        int remoteCseq;
        String remoteMethod;
        boolean ringing;
        Tx tx;
        int referCseq;
        ScheduledFuture<?> referTimer;
    }

    /**
     * Retransmission transaction handler with exponential backoff timers (0.5s..4s).
     */
    private final class Tx {
        final byte[] data;
        final String h;
        final int p;
        final Runnable onTimeout;
        int delay = 500;
        int spent;
        ScheduledFuture<?> f;
        boolean done;
        boolean retxStopped;

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

        /**
         * Stops Timer A retransmission on 1xx but keeps Timer B (64*T1) running (RFC 3261 §17.1.1.2).
         */
        void stopRetx() {
            if (done || retxStopped) return;
            retxStopped = true;
            if (f != null) f.cancel(false);
            long rem = Math.max(0, 32000 - spent);
            f = ex.schedule(() -> guard(() -> {
                if (done) return;
                done = true;
                bg.values().remove(this);
                if (onTimeout != null) onTimeout.run();
            }), rem, TimeUnit.MILLISECONDS);
        }
    }

    private static boolean hasLr(String r) {
        if (r == null) return false;
        String s = r.toLowerCase(java.util.Locale.ROOT);
        int i = s.indexOf(";lr");
        if (i < 0) return false;
        int end = i + 3;
        return end == s.length() || s.charAt(end) == ';' || s.charAt(end) == '=' || s.charAt(end) == '>' || s.charAt(end) == ' ';
    }

    private static String reqUri(Call k) {
        if (!k.route.isEmpty() && !hasLr(k.route.get(0))) {
            return Msg.uri(k.route.get(0));
        }
        return k.target;
    }

    private static void addRoutes(Call k, List<String> l) {
        if (k.route.isEmpty()) return;
        if (hasLr(k.route.get(0))) {
            for (String r : k.route) l.add("Route: " + r);
        } else {
            for (int i = 1; i < k.route.size(); i++) l.add("Route: " + k.route.get(i));
            l.add("Route: <" + k.target + ">");
        }
    }
}

