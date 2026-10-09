#!/usr/bin/env python3
"""Скриптовый SIP-собеседник для SipTest: независимый разбор и проверка Digest.

Запуск:  python3 jvmtest/fake_server.py <classpath>
Сам стартует Java-клиент, ведёт сценарий и сверяет события.
"""
import hashlib
import re
import socket
import subprocess
import sys
import threading
import time

SRV = ("127.0.0.1", 5099)
REALM, NONCE, USER, PW = "test", "abc123", "alice", "secret"

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(SRV)
fails = 0


def ok(cond, what):
    global fails
    print(("PASS " if cond else "FAIL ") + what)
    if not cond:
        fails += 1


def parse(data):
    head, _, body = data.decode().partition("\r\n\r\n")
    lines = head.split("\r\n")
    h = {}
    for l in lines[1:]:
        k, _, v = l.partition(":")
        h.setdefault(k.strip().lower(), []).append(v.strip())
    return lines[0], h, body


def recv(expect_first_word=None, t=6):
    sock.settimeout(t)
    data, addr = sock.recvfrom(4096)
    first, h, body = parse(data)
    if expect_first_word and not first.startswith(expect_first_word):
        ok(False, f"ждали {expect_first_word}, пришло: {first}")
    return first, h, body, addr


def md5(s):
    return hashlib.md5(s.encode()).hexdigest()


def check_auth(h, method):
    a = h.get("authorization", [None])[0]
    if not a:
        return False
    p = {m[0].lower(): m[1] or m[2] for m in re.findall(r'(\w+)=(?:"([^"]*)"|([^,\s]+))', a[6:])}
    ha1 = md5(f"{USER}:{REALM}:{PW}")
    ha2 = md5(f"{method}:{p['uri']}")
    exp = md5(f"{ha1}:{NONCE}:{p['nc']}:{p['cnonce']}:auth:{ha2}")
    return p["username"] == USER and p["response"] == exp and p["nonce"] == NONCE


def reply(req, addr, code, reason, extra=(), body="", totag=None):
    first, h, _ = req[0], req[1], req[2]
    out = [f"SIP/2.0 {code} {reason}"]
    out += ["Via: " + v for v in h["via"]]
    out.append("From: " + h["from"][0])
    to = h["to"][0]
    if totag and "tag=" not in to:
        to += ";tag=" + totag
    out.append("To: " + to)
    out.append("Call-ID: " + h["call-id"][0])
    out.append("CSeq: " + h["cseq"][0])
    out += list(extra)
    if body:
        out.append("Content-Type: application/sdp")
    out.append(f"Content-Length: {len(body)}")
    sock.sendto(("\r\n".join(out) + "\r\n\r\n" + body).encode(), addr)


def sdp(port, pts):
    maps = "".join(f"a=rtpmap:{p} {'PCMA' if p == 8 else 'PCMU'}/8000\r\n" for p in pts)
    return ("v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\n"
            f"m=audio {port} RTP/AVP {' '.join(map(str, pts))}\r\n{maps}")


# ---------- старт клиента ----------
cp = sys.argv[1]
events = []
proc = subprocess.Popen(["java", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp", cp,
                         "ru.minisip.sip.SipTest"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                        text=True, encoding="utf-8")
done = threading.Event()


def pump():
    for line in proc.stdout:
        line = line.rstrip()
        events.append(line)
        print("  client>", line)
        if line == "DONE":
            done.set()


threading.Thread(target=pump, daemon=True).start()

try:
    # ---------- 1. REGISTER с Digest ----------
    r = recv("REGISTER")
    caddr = r[3]
    ok("authorization" not in r[1], "REGISTER #1 без авторизации")
    reply(r, caddr, 401, "Unauthorized",
          [f'WWW-Authenticate: Digest realm="{REALM}", nonce="{NONCE}", qop="auth", algorithm=MD5'], totag="s0")
    r2 = recv("REGISTER")
    ok(check_auth(r2[1], "REGISTER"), "REGISTER #2: Digest верный (проверен независимо)")
    ok(r2[1]["call-id"] == r[1]["call-id"] and r2[1]["cseq"][0].startswith("2 "), "тот же Call-ID, CSeq+1")
    ok(r2[0] == "REGISTER sip:127.0.0.1 SIP/2.0", "Request-URI регистрации: " + r2[0])
    reply(r2, caddr, 200, "OK", [r2[1]["contact"][0] + ";expires=120"], totag="s0")

    # ---------- 2. исходящий вызов ----------
    inv = recv("INVITE")
    ok(inv[0] == "INVITE sip:1000@127.0.0.1 SIP/2.0", "INVITE Request-URI: " + inv[0])
    ok("m=audio" in inv[2] and "RTP/AVP 8 0" in inv[2] and "40100" in inv[2], "SDP с PCMA/PCMU и портом медиа")
    ok(inv[1]["content-length"][0] == str(len(inv[2].encode())), "Content-Length верный")
    reply(inv, caddr, 401, "Unauthorized",
          [f'WWW-Authenticate: Digest realm="{REALM}", nonce="{NONCE}", qop="auth"'], totag="s1")
    ack = recv("ACK")
    ok(ack[0] == "ACK sip:1000@127.0.0.1 SIP/2.0" and ack[1]["cseq"][0] == "1 ACK", "ACK на 401: " + ack[1]["cseq"][0])
    ok(ack[1]["via"][0] == inv[1]["via"][0], "ACK на не-2xx с той же веткой Via")
    inv2 = recv("INVITE")
    ok(check_auth(inv2[1], "INVITE"), "INVITE с Digest верный")
    ok(inv2[1]["cseq"][0] == "2 INVITE" and inv2[1]["call-id"] == inv[1]["call-id"], "CSeq 2, тот же Call-ID")
    ok(inv2[1]["via"][0] != inv[1]["via"][0], "новая ветка Via")
    reply(inv2, caddr, 180, "Ringing", totag="s2")
    time.sleep(0.2)
    reply(inv2, caddr, 200, "OK", ["Contact: <sip:1000@127.0.0.1:5099>", "Record-Route: <sip:127.0.0.1:5099;lr>"],
          sdp(40000, [8]), totag="s2")
    ack2 = recv("ACK")
    ok(ack2[0] == "ACK sip:1000@127.0.0.1:5099 SIP/2.0", "ACK на 2xx идёт на Contact: " + ack2[0])
    ok(ack2[1].get("route") == ["<sip:127.0.0.1:5099;lr>"], "Route из Record-Route")
    ok("tag=s2" in ack2[1]["to"][0] and ack2[1]["cseq"][0] == "2 ACK", "To с тегом, CSeq 2 ACK")
    bye = recv("BYE")
    ok(bye[1]["cseq"][0] == "3 BYE" and "tag=s2" in bye[1]["to"][0], "BYE: CSeq 3, To с тегом")
    reply(bye, caddr, 200, "OK")
    time.sleep(0.3)

    # ---------- 3. второй исходящий и отмена ----------
    i3 = recv("INVITE")
    ok(i3[0].startswith("INVITE sip:2000@"), "второй вызов: " + i3[0])
    reply(i3, caddr, 401, "Unauthorized", [f'WWW-Authenticate: Digest realm="{REALM}", nonce="{NONCE}", qop="auth"'], totag="s3")
    recv("ACK")
    i4 = recv("INVITE")
    reply(i4, caddr, 180, "Ringing", totag="s4")
    can = recv("CANCEL")
    ok(can[1]["via"][0] == i4[1]["via"][0] and can[1]["cseq"][0] == "2 CANCEL", "CANCEL: та же ветка Via, CSeq 2")
    ok("tag=" not in can[1]["to"][0], "CANCEL: To без тега")
    reply(can, caddr, 200, "OK")
    reply(i4, caddr, 487, "Request Terminated", totag="s4")
    a487 = recv("ACK")
    ok(a487[1]["cseq"][0] == "2 ACK" and a487[1]["via"][0] == i4[1]["via"][0], "ACK на 487")

    ok(done.wait(5), "клиент дошёл до конца сценария")
except socket.timeout:
    ok(False, "таймаут: клиент не прислал ожидаемое сообщение")
finally:
    time.sleep(0.3)
    proc.kill()

exp = [
    "EV registered true",
    "EV ringing",
    "MEDIA start 127.0.0.1 40000 8",
    "EV connected",
    "MEDIA stop",
    "EV ended ended",
    "EV ringing",
    "MEDIA stop",
    "EV ended ended",
    "DONE",
]
got = list(events)
ok(got == exp, "последовательность событий клиента")
if got != exp:
    print("  ожидали:", exp)
    print("  получили:", got)
print("SIP OK" if fails == 0 else f"SIP FAILED: {fails}")
sys.exit(1 if fails else 0)
