p = "app/src/main/java/ru/minisip/sip/SipImpl.java"
s = open(p, encoding="utf-8").read()

def rep(old, new, name):
    global s
    assert old in s, "NOT FOUND: " + name
    s = s.replace(old, new, 1)

# 1) поля в Call
rep(
"        boolean ringing;\n        Tx tx;\n    }",
"""        boolean ringing;
        Tx tx;
        int referCseq;
        ScheduledFuture<?> referTimer;
    }""", "Call fields")

# 2) transfer() перед hangup()
rep(
"""    @Override
    public void hangup() {""",
"""    @Override
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
            log("REFER \\u2192 " + uri);
            Tx tx = new Tx(refer(k, uri), k.dh, k.dp, () -> {
                log("transfer: no response");
                k.referCseq = 0;
            });
            bg.put(k.id + "|" + k.referCseq + "|REFER", tx);
            tx.start();
        });
    }

    @Override
    public void hangup() {""", "transfer insert")

# 3) onResp: REFER
rep(
"""        } else if (cm.equals("INVITE")) {
            onInviteResp(m);
        } else if (m.status() >= 200) {          // BYE, CANCEL""",
"""        } else if (cm.equals("INVITE")) {
            onInviteResp(m);
        } else if (cm.equals("REFER")) {
            onReferResp(m);
        } else if (m.status() >= 200) {          // BYE, CANCEL""", "onResp REFER")

# 4) onReq: NOTIFY + расширить Allow
rep(
"""            case "OPTIONS":
                send(response(m, 200, "OK", null,
                        hdrs("Allow: INVITE, ACK, CANCEL, BYE, OPTIONS"), ""), h, p);
                break;
            default:""",
"""            case "OPTIONS":
                send(response(m, 200, "OK", null,
                        hdrs("Allow: INVITE, ACK, CANCEL, BYE, NOTIFY, REFER, OPTIONS"), ""), h, p);
                break;
            case "NOTIFY":
                send(response(m, 200, "OK", null, null, ""), h, p);
                if (same && "refer".equals(Msg.eventName(m.get("event")))) {
                    int code = sipfragCode(m.body);
                    if (code >= 200) onReferFinal(k, code, sipfragReason(m.body));
                }
                break;
            default:""", "onReq NOTIFY")

# 5) новые методы перед ackFail
rep(
"""    /** ACK на финальный не-2xx ответ собирается из самого ответа. */
    private String ackFail(Msg r) {""",
"""    /** REFER в рамках диалога: Refer-To как sip-URI, CSeq из k.referCseq. */
    private String refer(Call k, String target) {
        List<String> l = hdrs("Via: " + via(branch()), "Max-Forwards: 70");
        for (String r : k.route) l.add("Route: " + r);
        l.addAll(hdrs("From: " + k.from, "To: " + k.to, "Call-ID: " + k.id,
                "CSeq: " + k.referCseq + " REFER", "Contact: " + contact(),
                "Refer-To: <" + target + ">", "User-Agent: minisip"));
        return msg("REFER " + k.target + " SIP/2.0", "", l);
    }

    /** 202 Accepted \\u2014 \\u043f\\u0440\\u0438\\u043d\\u044f\\u043b\\u0438 \\u043a \\u0438\\u0441\\u043f\\u043e\\u043b\\u043d\\u0435\\u043d\\u0438\\u044e; \\u0436\\u0434\\u0451\\u043c NOTIFY refer. */
    private void onReferResp(Msg m) {
        Call k = call;
        if (k == null || !k.id.equals(m.get("call-id")) || k.referCseq != m.cseq()) return;
        int c = m.status();
        if (c < 200) return;
        Tx t = bg.remove(m.key());
        if (t != null) t.stop();
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

    /** \\u0424\\u0438\\u043d\\u0430\\u043b\\u044c\\u043d\\u044b\\u0439 NOTIFY refer (\\u0438\\u043b\\u0438 \\u0442\\u0430\\u0439\\u043c\\u0430\\u0443\\u0442): BYE \\u0438 onEnded. */
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

    /** \\u0422\\u0435\\u043b\\u043e message/sipfrag: "SIP/2.0 200 OK" \\u2192 \\u043a\\u043e\\u0434. */
    private static int sipfragCode(String body) {
        if (body == null) return 0;
        String line = body.split("\\\\r?\\\\n", 2)[0].trim();
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
        String line = body.split("\\\\r?\\\\n", 2)[0].trim();
        String[] p = line.split(" ", 3);
        return p.length > 2 ? p[2] : "";
    }

    /** ACK на финальный не-2xx ответ собирается из самого ответа. */
    private String ackFail(Msg r) {""", "methods insert")

# 6) end(): отмена referTimer
rep(
"""        call = null;
        if (k.tx != null) k.tx.stop();
        media.stop();
        lis.onEnded(reason);""",
"""        call = null;
        if (k.tx != null) k.tx.stop();
        if (k.referTimer != null) {
            k.referTimer.cancel(false);
            k.referTimer = null;
        }
        media.stop();
        lis.onEnded(reason);""", "end cancel timer")

open(p, "w", encoding="utf-8").write(s)
print("ok")
