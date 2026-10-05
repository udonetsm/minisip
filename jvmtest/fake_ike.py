#!/usr/bin/env python3
"""Независимый IKEv2-ответчик для VpnTest (RFC 7296), на пакете cryptography.

Запуск:  python3 jvmtest/fake_ike.py <classpath>
Для каждого сценария поднимает сервер на локальных портах, запускает Java-клиент
и сверяет его события. Реализация написана отдельно от Java-кода: свои разбор, ключи, шифры.
"""
import datetime
import hashlib
import hmac
import ipaddress
import os
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import warnings

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.x509.oid import NameOID

warnings.simplefilter("ignore")
try:
    from cryptography.hazmat.decrepit.ciphers.algorithms import TripleDES
except ImportError:                                     # старые версии cryptography
    TripleDES = algorithms.TripleDES

P = int("""FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD
EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED
EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F
83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B
E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718
3995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF""".replace("\n", ""), 16)

SERVER_IP = "127.0.0.1"
CLIENT_INNER, DNS = bytes([10, 9, 0, 2]), bytes([10, 9, 0, 1])


def be(n, k):
    return n.to_bytes(k, "big")


# ---------------------------------------------------------------------------
# MD4, MS-CHAPv2 и ключи MPPE: свои, написанные по RFC 1320 / 2759 / 3079
# ---------------------------------------------------------------------------
def md4(data):
    def rol(x, n):
        x &= 0xFFFFFFFF
        return ((x << n) | (x >> (32 - n))) & 0xFFFFFFFF
    msg = data + b"\x80" + b"\0" * ((55 - len(data)) % 64) + struct.pack("<Q", len(data) * 8)
    a0, b0, c0, d0 = 0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476
    for off in range(0, len(msg), 64):
        X = struct.unpack("<16I", msg[off:off + 64])
        a, b, c, d = a0, b0, c0, d0
        for k in range(16):                                    # раунд 1, строго по RFC
            s_ = (3, 7, 11, 19)[k % 4]
            t = rol(a + ((b & c) | (~b & d)) + X[k], s_)
            a, b, c, d = d, t, b, c
        order2 = [0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15]
        for i, k in enumerate(order2):
            s_ = (3, 5, 9, 13)[i % 4]
            t = rol(a + ((b & c) | (b & d) | (c & d)) + X[k] + 0x5A827999, s_)
            a, b, c, d = d, t, b, c
        order3 = [0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15]
        for i, k in enumerate(order3):
            s_ = (3, 9, 11, 15)[i % 4]
            t = rol(a + (b ^ c ^ d) + X[k] + 0x6ED9EBA1, s_)
            a, b, c, d = d, t, b, c
        a0, b0, c0, d0 = (a0 + a) & 0xFFFFFFFF, (b0 + b) & 0xFFFFFFFF, (c0 + c) & 0xFFFFFFFF, (d0 + d) & 0xFFFFFFFF
    return struct.pack("<4I", a0, b0, c0, d0)


def nt_hash(pw):
    return md4(pw.encode("utf-16-le"))


def des7(key7, block):
    k = int.from_bytes(key7, "big")
    key8 = bytes(((k >> (49 - 7 * i)) & 0x7F) << 1 for i in range(8))
    enc = Cipher(TripleDES(key8 * 3), modes.ECB()).encryptor()
    return enc.update(block) + enc.finalize()


def ch_hash(peer, auth, user):
    return hashlib.sha1(peer + auth + user).digest()[:8]


def nt_response(auth, peer, user, pw):
    h = nt_hash(pw).ljust(21, b"\0")
    c = ch_hash(peer, auth, user)
    return b"".join(des7(h[i:i + 7], c) for i in (0, 7, 14))


def auth_response(pw, ntresp, peer, auth, user):
    phh = md4(nt_hash(pw))
    d = hashlib.sha1(phh + ntresp + b"Magic server to client signing constant").digest()
    r = hashlib.sha1(d + ch_hash(peer, auth, user) + b"Pad to make it do more than one iteration").digest()
    return "S=" + r.hex().upper()


M2 = b"On the client side, this is the send key; on the server side, it is the receive key."
M3 = b"On the client side, this is the receive key; on the server side, it is the send key."


def mschap_msk(pw, ntresp):
    master = hashlib.sha1(md4(nt_hash(pw)) + ntresp + b"This is the MPPE Master Key").digest()[:16]

    def key(magic):
        return hashlib.sha1(master + b"\0" * 40 + magic + b"\xf2" * 40).digest()[:16]
    return key(M2) + key(M3) + b"\0" * 32           # серверный Recv | серверный Send | нули


assert md4(b"abc").hex() == "a448017aaf21d8525fc10ae87aa6729d"
assert md4(b"").hex() == "31d6cfe0d16ae931b73c59d7e0c089c0"
assert md4(b"message digest").hex() == "d9130a8164549fe818874806e1c7014b"
assert md4(b"a").hex() == "bde52cb31de33e46245e05fbdbd6fb24"
_a = bytes.fromhex("5B5D7C7D7B3F2F3E3C2C602132262628")
_p = bytes.fromhex("21402324255E262A28295F2B3A337C7E")
assert nt_response(_a, _p, b"User", "clientPass").hex().upper() == "82309ECD8D708B5EA08FAA3981CD83544233114A3D85D6DF"
assert auth_response("clientPass", nt_response(_a, _p, b"User", "clientPass"), _p, _a, b"User") == \
    "S=407A5589115FD0D6209F510FE9C04566932CDA56"


# ---------------------------------------------------------------------------
# PKI для сценариев с логином и паролем
# ---------------------------------------------------------------------------
def make_cert(cn, key, issuer_cert, issuer_key, san_ip=None, ca=False):
    now = datetime.datetime.now(datetime.timezone.utc)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, cn)])
    b = (x509.CertificateBuilder().subject_name(name)
         .issuer_name(issuer_cert.subject if issuer_cert else name)
         .public_key(key.public_key()).serial_number(x509.random_serial_number())
         .not_valid_before(now - datetime.timedelta(days=1)).not_valid_after(now + datetime.timedelta(days=2)))
    if ca:
        b = b.add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
    if san_ip:
        b = b.add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address(san_ip))]), critical=False)
    return b.sign(issuer_key or key, hashes.SHA256())


def new_key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


def pem(cert):
    return cert.public_bytes(serialization.Encoding.PEM)


def der(cert):
    return cert.public_bytes(serialization.Encoding.DER)


def build_pki(tmp):
    k_root, k_int, k_leaf, k_leaf2, k_other, k_rogue = (new_key() for _ in range(6))
    root = make_cert("Test Root", k_root, None, None, ca=True)
    inter = make_cert("Test Intermediate", k_int, root, k_root, ca=True)
    other_root = make_cert("Other Root", k_other, None, None, ca=True)
    leaf = make_cert("vpn", k_leaf, root, k_root, "127.0.0.1")
    leaf_chain = make_cert("vpn", k_leaf, inter, k_int, "127.0.0.1")
    leaf_wrong = make_cert("vpn", k_leaf2, root, k_root, "10.1.1.1")
    leaf_rogue = make_cert("vpn", k_rogue, other_root, k_other, "127.0.0.1")
    files = {}
    for name, c in (("root", root), ("leaf", leaf), ("other", other_root), ("garbage", None)):
        f = os.path.join(tmp, name + ".pem")
        open(f, "wb").write(pem(c) if c else b"this is not a certificate")
        files[name] = f
    files["-"] = "-"
    pki = {
        "plain": dict(key=k_leaf, send=[der(leaf)]),
        "chain": dict(key=k_leaf, send=[der(leaf_chain), der(inter)]),
        # длинная цепочка: ответ IKE_AUTH больше 2048 байт (как у серверов с Let's Encrypt)
        "bigchain": dict(key=k_leaf, send=[der(leaf_chain), der(inter)] + [der(other_root)] * 6),
        "wrongname": dict(key=k_leaf2, send=[der(leaf_wrong)]),
        "rogue": dict(key=k_rogue, send=[der(leaf_rogue)]),
        "badsig": dict(key=k_other, send=[der(leaf)]),            # сертификат настоящий, подпись чужим ключом
    }
    return files, pki



class Suite:
    """name: cbc256 | cbc128sha1 | gcm128"""

    def __init__(self, name):
        self.gcm = name.startswith("gcm")
        self.bits = 128 if name.endswith("128") or name.endswith("sha1") else 256
        self.sha1 = name.endswith("sha1")
        self.hash = hashlib.sha1 if self.sha1 else hashlib.sha256
        self.prf_id = 2 if self.sha1 else 5
        self.integ_id = 0 if self.gcm else (2 if self.sha1 else 12)
        self.encr_id = 20 if self.gcm else 12
        self.integ_len = 0 if self.gcm else (20 if self.sha1 else 32)
        self.icv = 16 if self.gcm else (12 if self.sha1 else 16)
        self.key_len = self.bits // 8 + (4 if self.gcm else 0)
        self.iv = 8 if self.gcm else 16
        self.block = 4 if self.gcm else 16

    def prf(self, k, d):
        return hmac.new(k, d, self.hash).digest()

    def prfplus(self, k, s, n):
        out, t, i = b"", b"", 1
        while len(out) < n:
            t = self.prf(k, t + s + bytes([i]))
            out += t
            i += 1
        return out[:n]

    def transforms(self, ike):
        tr = [(1, self.encr_id, self.bits)]
        if ike:
            tr.append((2, self.prf_id, 0))
        if not self.gcm:
            tr.append((3, self.integ_id, 0))
        tr.append((4, 14, 0) if ike else (5, 0, 0))
        return tr


def sa_payload(tr, proto, spi):
    tbytes = b""
    for i, (t, ident, bits) in enumerate(tr):
        attr = struct.pack(">HH", 0x800E, bits) if bits else b""
        tbytes += struct.pack(">BBHBBH", 0 if i == len(tr) - 1 else 3, 0, 8 + len(attr), t, 0, ident) + attr
    prop = struct.pack(">BBHBBBB", 0, 0, 8 + len(spi) + len(tbytes), 1, proto, len(spi), len(tr)) + spi + tbytes
    return prop


def chain(pls):
    out = b""
    for i, (t, body) in enumerate(pls):
        nxt = pls[i + 1][0] if i + 1 < len(pls) else 0
        out += struct.pack(">BBH", nxt, 0, 4 + len(body)) + body
    return out


def parse(data, first):
    res, t, o = [], first, 0
    while t:
        nxt, _, ln = struct.unpack(">BBH", data[o:o + 4])
        res.append((t, data[o + 4:o + ln]))
        o += ln
        t = nxt
    return res


def notify(t, data=b""):
    return (41, struct.pack(">BBH", 0, 0, t) + data)


class Server:
    def __init__(self, suite, auth, behavior, ike_port, nat_port, pki):
        self.s = Suite(suite)
        self.auth = auth                                   # {'kind': 'psk'|'eap', ...}
        self.psk = auth.get("psk", "").encode()
        self.pki = pki
        self.behavior = behavior
        self.ike_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.ike_sock.bind((SERVER_IP, ike_port))
        self.nat_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.nat_sock.bind((SERVER_IP, nat_port))
        self.log = []                      # что видел сервер
        self.spi_r = os.urandom(8)
        self.esp_spi = os.urandom(4)       # наш входящий SPI для ESP
        self.esp_seq = 0
        self.client_addr = None
        self.state = "init"
        self.alive = True
        self.cookie_sent = False
        self.our_req_id = 0
        self.esp_cnt = 0
        self.stop = False
        for sock, h in ((self.ike_sock, self.on_ike_port), (self.nat_sock, self.on_nat_port)):
            threading.Thread(target=self.loop, args=(sock, h), daemon=True).start()

    def loop(self, sock, handler):
        while not self.stop:
            sock.settimeout(0.2)
            try:
                d, addr = sock.recvfrom(4096)
            except socket.timeout:
                continue
            except OSError:
                return
            try:
                handler(d, addr, sock)
            except Exception as e:                       # ошибка в разборе — сообщаем и идём дальше
                self.log.append(f"server-exception {e!r}")

    # ---------- IKE_SA_INIT на «порту 500» ----------
    def on_ike_port(self, d, addr, sock):
        if self.behavior == "silent":
            return
        spi_i, _, nxt, ver, exch, flags, mid, ln = struct.unpack(">8s8sBBBBII", d[:28])
        assert exch == 34 and mid == 0
        pls = parse(d[28:], nxt)
        types = [t for t, _ in pls]
        if self.behavior == "noproposal":
            self.reply_init(sock, addr, spi_i, [notify(14)])
            return
        if self.behavior == "cookie" and (not pls or pls[0][0] != 41 or
                                          struct.unpack(">H", pls[0][1][2:4])[0] != 16390):
            self.cookie_sent = True
            self.reply_init(sock, addr, spi_i, [notify(16390, b"cookie-1234567890")])
            return
        if self.behavior == "cookie":
            self.log.append("cookie-echoed " + str(pls[0][1][4:] == b"cookie-1234567890"))
        hashes_ = [b for t, b in pls if t == 41 and struct.unpack(">H", b[2:4])[0] == 16431]
        self.log.append("sighashes " + (hashes_[0][4:].hex() if hashes_ else "none"))
        d_ = {t: b for t, b in pls if t != 41}
        ke = d_[34]
        assert struct.unpack(">H", ke[:2])[0] == 14
        self.ni = d_[40]
        self.msg1 = d
        self.spi_i = spi_i
        # ищем наш набор среди предложений клиента
        sa = d_[33]
        offered = []
        o = 0
        while o < len(sa):
            _, _, plen, num, proto, spisz, ntr = struct.unpack(">BBHBBBB", sa[o:o + 8])
            tr, p = [], o + 8 + spisz
            for _ in range(ntr):
                _, _, tl, tt, _, tid = struct.unpack(">BBHBBH", sa[p:p + 8])
                bits = struct.unpack(">H", sa[p + 10:p + 12])[0] if tl >= 12 else 0
                tr.append((tt, tid, bits))
                p += tl
            offered.append((num, tr))
            o += plen
        want = set(self.s.transforms(True))
        ok = any(want <= set(tr) for _, tr in offered)
        self.log.append(f"offered-match {ok}")
        if not ok:
            self.reply_init(sock, addr, spi_i, [notify(14)])
            return
        y = int.from_bytes(os.urandom(40), "big") | 1 << 319
        gr = be(pow(2, y, P), 256)
        g = be(pow(int.from_bytes(ke[4:], "big"), y, P), 256)
        self.nr = os.urandom(32)
        s = self.s
        skeyseed = s.prf(self.ni + self.nr, g)
        n = s.prf_len = len(s.prf(b"", b""))
        total = 3 * n + 2 * s.integ_len + 2 * s.key_len
        k = s.prfplus(skeyseed, self.ni + self.nr + spi_i + self.spi_r, total)
        o = 0

        def take(m):
            nonlocal o
            r = k[o:o + m]
            o += m
            return r
        self.sk_d = take(n)
        self.sk_ai, self.sk_ar = take(s.integ_len), take(s.integ_len)
        self.sk_ei, self.sk_er = take(s.key_len), take(s.key_len)
        self.sk_pi, self.sk_pr = take(n), take(n)
        sa_resp = sa_payload(s.transforms(True), 1, b"")
        resp = [(33, sa_resp), (34, struct.pack(">HH", 14, 0) + gr), (40, self.nr),
                notify(16388, os.urandom(20)), notify(16389, os.urandom(20))]
        self.msg2 = self.reply_init(sock, addr, spi_i, resp)
        # NAT-D: клиент обязан объявить себя «за NAT» (хеш источника не совпадёт с реальным)
        nat_src = [b for t, b in pls if t == 41 and struct.unpack(">H", b[2:4])[0] == 16388]
        real = hashlib.sha1(spi_i + b"\0" * 8 + socket.inet_aton(addr[0]) + struct.pack(">H", addr[1])).digest()
        self.log.append("nat-forced " + str(bool(nat_src) and nat_src[0][4:] != real))
        self.state = "authwait"

    def reply_init(self, sock, addr, spi_i, pls):
        body = chain(pls)
        spi_r = self.spi_r if self.state != "init" or self.behavior not in ("noproposal", "cookie") else bytes(8)
        spi_r = bytes(8) if (pls and pls[0][0] == 41) else self.spi_r
        msg = struct.pack(">8s8sBBBBII", spi_i, spi_r, pls[0][0], 0x20, 34, 0x20, 0, 28 + len(body)) + body
        sock.sendto(msg, addr)
        return msg

    # ---------- защищённые сообщения ----------
    def seal(self, exch, flags, mid, pls):
        s = self.s
        inner = chain(pls)
        pad = (-(len(inner) + 1)) % s.block
        plain = inner + b"\0" * pad + bytes([pad])
        first = pls[0][0] if pls else 0
        total = 28 + 4 + s.iv + len(plain) + s.icv
        hdr = struct.pack(">8s8sBBBBII", self.spi_i, self.spi_r, 46, 0x20, exch, flags, mid, total)
        hdr += struct.pack(">BBH", first, 0, total - 28)
        iv = os.urandom(s.iv)
        if s.gcm:
            ct = AESGCM(self.sk_er[:-4]).encrypt(self.sk_er[-4:] + iv, plain, hdr)
            return hdr + iv + ct
        enc = Cipher(algorithms.AES(self.sk_er), modes.CBC(iv)).encryptor()
        body = hdr + iv + enc.update(plain) + enc.finalize()
        return body + hmac.new(self.sk_ar, body, s.hash).digest()[:s.icv]

    def open(self, d):
        s = self.s
        if s.gcm:
            nonce = self.sk_ei[-4:] + d[32:40]
            plain = AESGCM(self.sk_ei[:-4]).decrypt(nonce, d[40:], d[:32])
        else:
            want = hmac.new(self.sk_ai, d[:-s.icv], s.hash).digest()[:s.icv]
            assert hmac.compare_digest(want, d[-s.icv:]), "bad ICV from client"
            dec = Cipher(algorithms.AES(self.sk_ei), modes.CBC(d[32:48])).decryptor()
            plain = dec.update(d[48:-s.icv]) + dec.finalize()
        pad = plain[-1]
        return parse(plain[:len(plain) - 1 - pad], d[28]), d

    def on_nat_port(self, d, addr, sock):
        if self.behavior == "dead" and self.state == "up":
            return                                       # рукопожатие прошло, дальше сервер «умер»
        if len(d) == 1 and d[0] == 0xFF:
            self.log.append("keepalive")
            return
        if d[:4] == b"\0\0\0\0":
            self.on_ike_msg(d[4:], addr, sock)
        else:
            self.on_esp(d, addr, sock)

    def on_ike_msg(self, d, addr, sock):
        exch, flags, mid = d[18], d[19], struct.unpack(">I", d[20:24])[0]
        self.client_addr = addr
        if exch == 35 and self.state == "authwait":
            (self.do_auth if self.auth["kind"] == "psk" else self.eap_first)(d, mid, addr, sock)
            return
        if exch == 35 and self.state.startswith("eap"):
            self.eap_step(d, mid, addr, sock)
            return
        pls, _ = self.open(d)
        types = [t for t, _ in pls]
        if flags & 0x20:                                  # ответ клиента на наш запрос
            self.log.append(f"client-response exch={exch} payloads={types}")
            return
        if exch == 37 and not pls:
            self.log.append("dpd-request")
        elif exch == 37 and types == [42]:
            self.log.append("client-delete")
            sock.sendto(b"\0\0\0\0" + self.seal(37, 0x20, mid, []), addr)
            self.state = "closed"
            return
        else:
            self.log.append(f"unexpected exch={exch} types={types}")
        sock.sendto(b"\0\0\0\0" + self.seal(exch, 0x20, mid, []), addr)

    def do_auth(self, d, mid, addr, sock):
        s = self.s
        assert mid == 1
        pls, raw = self.open(d)
        dd = {t: b for t, b in pls}
        self.log.append("auth-payloads " + ",".join(str(t) for t, _ in pls))
        idi, auth = dd[35], dd[39]
        key = s.prf(self.psk, b"Key Pad for IKEv2")
        want = s.prf(key, self.msg1 + self.nr + s.prf(self.sk_pi, idi))
        if auth[0] != 2 or auth[4:] != want:
            self.log.append("client-auth-bad")
            sock.sendto(b"\0\0\0\0" + self.seal(35, 0x20, mid, [notify(24)]), addr)
            self.state = "failed"
            return
        self.log.append("client-auth-ok id=" + idi[4:].decode() + " idtype=" + str(idi[0]))
        self.client_spi = dd[33][8:8 + dd[33][6]]
        idr = bytes([2, 0, 0, 0]) + b"vpn.example.org"
        self.finish_auth(sock, addr, mid, key, idr, with_idr=True)

    def finish_auth(self, sock, addr, mid, key, idr, with_idr):
        """Итоговый ответ IKE_AUTH: AUTH, адрес/DNS, дочерний SA. key — уже «Key Pad»-ключ."""
        s = self.s
        mine = s.prf(key, self.msg2 + self.ni + s.prf(self.sk_pr, idr))
        cp = bytes([2, 0, 0, 0]) + struct.pack(">HH", 1, 4) + CLIENT_INNER + struct.pack(">HH", 3, 4) + DNS
        tsr = bytes([1, 0, 0, 0]) + struct.pack(">BBH", 7, 0, 16) + struct.pack(">HH", 0, 65535) \
            + bytes([10, 9, 0, 0]) + bytes([10, 9, 255, 255])
        tsi = bytes([1, 0, 0, 0]) + struct.pack(">BBH", 7, 0, 16) + struct.pack(">HH", 0, 65535) \
            + CLIENT_INNER + CLIENT_INNER
        resp = ([(36, idr)] if with_idr else []) + [
            (39, bytes([2, 0, 0, 0]) + mine), (47, cp),
            (33, sa_payload(s.transforms(False), 3, self.esp_spi)), (44, tsi), (45, tsr)]
        sock.sendto(b"\0\0\0\0" + self.seal(35, 0x20, mid, resp), addr)
        km = s.prfplus(self.sk_d, self.ni + self.nr, 2 * (s.key_len + s.integ_len))
        o = 0
        self.esp_ei, o = km[o:o + s.key_len], o + s.key_len
        self.esp_ai, o = km[o:o + s.integ_len], o + s.integ_len
        self.esp_er, o = km[o:o + s.key_len], o + s.key_len
        self.esp_ar = km[o:o + s.integ_len]
        self.state = "up"
        if self.behavior == "probes":
            threading.Thread(target=self.probes, daemon=True).start()

    # ---------- EAP-MSCHAPv2 ----------
    def reply(self, sock, addr, mid, pls):
        sock.sendto(b"\0\0\0\0" + self.seal(35, 0x20, mid, pls), addr)

    @staticmethod
    def eap(code, ident, typ=None, data=b""):
        body = (bytes([typ]) if typ is not None else b"") + data
        return (48, struct.pack(">BBH", code, ident, 4 + len(body)) + body)

    def eap_first(self, d, mid, addr, sock):
        s = self.s
        pls, _ = self.open(d)
        dd = {t: b for t, b in pls}
        self.log.append("auth-payloads " + ",".join(str(t) for t, _ in pls))
        self.log.append("first-request-has-AUTH " + str(39 in dd))
        self.client_spi = dd[33][8:8 + dd[33][6]]
        self.idi = dd[35]
        self.log.append("eap-idi " + dd[35][4:].decode())
        pk = self.pki[self.auth["pki"]]
        idr = bytes([2, 0, 0, 0]) + b"vpn.example.org"
        octets = self.msg2 + self.ni + s.prf(self.sk_pr, idr)
        if self.auth.get("sig") == 1:
            sig = pk["key"].sign(octets, padding.PKCS1v15(), hashes.SHA1())
            auth = bytes([1, 0, 0, 0]) + sig
        else:
            sig = pk["key"].sign(octets, padding.PKCS1v15(), hashes.SHA256())
            algid = bytes.fromhex("300d06092a864886f70d01010b0500")
            auth = bytes([14, 0, 0, 0, len(algid)]) + algid + sig
        pl = [(36, idr)] + [(37, b"\x04" + c) for c in pk["send"]] + [(39, auth),
                                                                         self.eap(1, 1, 1)]
        self.reply(sock, addr, mid, pl)
        self.state = "eap_id"

    def eap_step(self, d, mid, addr, sock):
        pls, _ = self.open(d)
        dd = {t: b for t, b in pls}
        user, pw = self.auth["user"], self.auth["password"]
        if self.state == "eap_final":
            auth = dd[39]
            key = self.s.prf(self.msk, b"Key Pad for IKEv2")
            want = self.s.prf(key, self.msg1 + self.nr + self.s.prf(self.sk_pi, self.idi))
            ok = auth[0] == 2 and auth[4:] == want
            self.log.append("client-final-auth " + str(ok))
            if not ok:
                self.reply(sock, addr, mid, [notify(24)])
                self.state = "failed"
                return
            self.finish_auth(sock, addr, mid, key, bytes([2, 0, 0, 0]) + b"vpn.example.org", with_idr=False)
            return
        e = dd[48]
        code, ident, ln, typ = e[0], e[1], struct.unpack(">H", e[2:4])[0], e[4]
        data = e[5:ln]
        if self.state == "eap_id":
            self.log.append("eap-identity " + data.decode())
            self.auth_ch = os.urandom(16)
            self.ms_id = 7
            body = bytes([1, self.ms_id]) + struct.pack(">H", 5 + 16 + 3) + bytes([16]) + self.auth_ch + b"vpn"
            self.reply(sock, addr, mid, [self.eap(1, 2, 26, body)])
            self.state = "eap_chal"
        elif self.state == "eap_chal":
            op, msid = data[0], data[1]
            vs, value, name = data[4], data[5:54], data[54:]
            peer, resv, nt = value[:16], value[16:24], value[24:48]
            good = (op == 2 and msid == self.ms_id and vs == 49 and resv == bytes(8)
                    and name == user.encode() and nt == nt_response(self.auth_ch, peer, name, pw))
            self.log.append("mschap-response-ok " + str(good))
            if not good:
                text = b"E=691 R=0 C=" + os.urandom(16).hex().upper().encode() + b" V=3 M=Authentication failed"
                body = bytes([4, self.ms_id]) + struct.pack(">H", 4 + len(text)) + text
                self.reply(sock, addr, mid, [self.eap(1, 3, 26, body)])
                self.state = "failed"
                return
            self.msk = mschap_msk(pw, nt)
            s_ = auth_response(pw, nt, peer, self.auth_ch, name)
            if self.behavior == "badS":
                s_ = s_[:-1] + ("0" if s_[-1] != "0" else "1")
            text = (s_ + " M=Welcome").encode()
            body = bytes([3, self.ms_id]) + struct.pack(">H", 4 + len(text)) + text
            self.reply(sock, addr, mid, [self.eap(1, 3, 26, body)])
            self.state = "eap_succ"
        elif self.state == "eap_succ":
            self.log.append(f"mschap-success-ack {data == bytes([3])}")
            self.reply(sock, addr, mid, [self.eap(3, 3)])
            self.state = "eap_final"

    def esp_decrypt(self, d):
        s = self.s
        assert d[:4] == self.esp_spi
        if s.gcm:
            plain = AESGCM(self.esp_ei[:-4]).decrypt(self.esp_ei[-4:] + d[8:16], d[16:], d[:8])
        else:
            want = hmac.new(self.esp_ai, d[:-s.icv], s.hash).digest()[:s.icv]
            assert hmac.compare_digest(want, d[-s.icv:]), "bad ESP ICV from client"
            dec = Cipher(algorithms.AES(self.esp_ei), modes.CBC(d[8:24])).decryptor()
            plain = dec.update(d[24:-s.icv]) + dec.finalize()
        pad = plain[-2]
        assert plain[-1] == 4 and plain[-2 - pad:-2] == bytes(range(1, pad + 1)), "bad ESP padding"
        return plain[:len(plain) - 2 - pad]

    def esp_encrypt(self, ip):
        s = self.s
        self.esp_seq += 1
        hdr = self.client_spi + struct.pack(">I", self.esp_seq)
        pad = (-(len(ip) + 2)) % s.block
        plain = ip + bytes(range(1, pad + 1)) + bytes([pad, 4])
        iv = os.urandom(s.iv)
        if s.gcm:
            return hdr + iv + AESGCM(self.esp_er[:-4]).encrypt(self.esp_er[-4:] + iv, plain, hdr)
        enc = Cipher(algorithms.AES(self.esp_er), modes.CBC(iv)).encryptor()
        body = hdr + iv + enc.update(plain) + enc.finalize()
        return body + hmac.new(self.esp_ar, body, s.hash).digest()[:s.icv]

    def on_esp(self, d, addr, sock):
        try:
            ip = self.esp_decrypt(d)
        except Exception as e:
            self.log.append(f"esp-bad {e!r}")
            return
        self.esp_cnt += 1
        self.log.append(f"esp-in {len(ip)}")
        sock.sendto(self.esp_encrypt(ip[:12] + ip[16:20] + ip[12:16] + ip[20:]), addr)
        if self.behavior == "delete" and self.esp_cnt == 1:
            time.sleep(0.2)
            body = bytes([3, 4]) + struct.pack(">H", 1) + self.esp_spi
            sock.sendto(b"\0\0\0\0" + self.seal(37, 0, self.next_id(), [(42, body)]), addr)

    def next_id(self):
        i = self.our_req_id
        self.our_req_id += 1
        return i

    def probes(self):
        """Сервер сам лезет к клиенту: DPD, перегенерация ключей, мусорный ESP, подделка IKE."""
        time.sleep(0.5)
        a = self.client_addr
        sock = self.nat_sock
        sock.sendto(b"\0\0\0\0" + self.seal(37, 0, self.next_id(), []), a)              # DPD
        sock.sendto(b"\0\0\0\0" + self.seal(36, 0, self.next_id(), [notify(1)]), a)    # CREATE_CHILD_SA
        sock.sendto(os.urandom(100), a)                                                  # мусор
        sock.sendto(self.client_spi + os.urandom(60), a)                                 # ESP с неверным ICV
        bad = bytearray(b"\0\0\0\0" + self.seal(37, 0, self.next_id(), []))
        bad[-1] ^= 1
        sock.sendto(bytes(bad), a)                                                       # подделанный IKE


def run_scenario(cp, name, suite, beh, auth, client, mode, expect, ike_port, nat_port, pki, files):
    srv = Server(suite, auth, beh, ike_port, nat_port, pki)
    t0 = time.time()
    kind, secret, ca = client
    out = subprocess.run(["java", "-cp", cp, "ru.minisip.vpn.VpnTest", str(ike_port), str(nat_port),
                          kind, secret, mode, files[ca]], capture_output=True, text=True, timeout=60)
    time.sleep(0.2)
    srv.stop = True
    lines = out.stdout.splitlines()
    if out.returncode != 0:
        print(out.stderr)
    global fails
    print(f"--- {name} ({time.time() - t0:.1f} s)")
    for cond_name, cond in expect(lines, srv.log):
        print(("PASS " if cond else "FAIL ") + f"{name}: {cond_name}")
        if not cond:
            fails += 1
            print("   client:", lines)
            print("   server:", srv.log)
    srv.ike_sock.close()
    srv.nat_sock.close()


fails = 0


def has(lines, s):
    return any(l.startswith(s) for l in lines)


def eap_ok(lines, log):
    return normal(lines, log, eap=True) + [
        ("first IKE_AUTH has no AUTH payload (EAP)", "first-request-has-AUTH False" in log),
        ("signature hash algorithms announced (SHA-256/384/512)", "sighashes 000200030004" in log),
        ("EAP identity is the login", "eap-identity alice@example.org" in log),
        ("MS-CHAPv2 response verified by the server", "mschap-response-ok True" in log),
        ("client confirmed the server's S= proof", "mschap-success-ack True" in log),
        ("final AUTH from the MSK accepted", "client-final-auth True" in log),
    ]


def normal(lines, log, eap=False):
    echoes = [l for l in lines if l.startswith("ECHO")]
    return [
        ("tunnel up", has(lines, "EV up tuntest0")),
        ("address, DNS, route and app list reach the tun",
         "TUN ip=10.9.0.2 dns=10.9.0.1 routes=10.9.0.0/16 mtu=1400 apps=org.example.one,org.example.two" in lines),
        ("login accepted", eap or "client-auth-ok id=alice@example.org idtype=3" in log),
        ("NAT forced by the NAT-D hash", eap or "nat-forced True" in log),
        ("all 7 packets echoed through ESP", len(echoes) == 7 and all(e.startswith("ECHO ok") for e in echoes)),
        ("server saw ESP packets", sum(1 for l in log if l.startswith("esp-in")) == 7),
        ("no ESP errors on the server", not any(l.startswith("esp-bad") or "exception" in l for l in log)),
        ("keepalive reached the server", "keepalive" in log),
        ("client DPD answered", "dpd-request" in log),
        ("Delete sent on disconnect", "client-delete" in log),
        ("onDown(disconnected)", "EV down disconnected" in lines),
        ("one onUp and one onDown", sum(l.startswith("EV up") for l in lines) == 1 and
         sum(l.startswith("EV down") for l in lines) == 1),
    ]


def expect_down(text):
    return lambda lines, log: [(f"EV down contains '{text}'", any(l.startswith("EV down") and text in l for l in lines)),
                               ("no tunnel was brought up", not has(lines, "EV up") and not has(lines, "TUN "))]


def probes(lines, log):
    r = normal(lines, log)
    return r + [
        ("server's DPD answered with an empty INFORMATIONAL", "client-response exch=37 payloads=[]" in log),
        ("CREATE_CHILD_SA refused with NO_ADDITIONAL_SAS", "client-response exch=36 payloads=[41]" in log),
        ("garbage and forged packets did not kill the tunnel", "EV down disconnected" in lines),
    ]


def delete(lines, log):
    return [
        ("tunnel up", has(lines, "EV up")),
        ("server's Delete closes the tunnel", "EV down server closed the tunnel" in lines),
        ("client answered the Delete", "client-response exch=37 payloads=[]" in log),
    ]


def silent(lines, log):
    return [
        ("tunnel up", has(lines, "EV up")),
        ("dead server detected (DPD, then timeout)", "EV down server does not respond" in lines),
    ]


def cookie(lines, log):
    return normal(lines, log) + [("cookie was echoed back", "cookie-echoed True" in log)]


if __name__ == "__main__":
    cp = sys.argv[1]
    base = 21000
    tmp = tempfile.mkdtemp()
    files, pki = build_pki(tmp)
    psk = {"kind": "psk", "psk": "secret"}
    PW = "Passw0rd!"

    def eapcfg(**kw):
        return dict({"kind": "eap", "user": "alice@example.org", "password": PW, "pki": "plain"}, **kw)

    # (имя, набор, поведение сервера, настройки сервера, клиент (вид, секрет, ca), режим, проверки)
    plan = [
        ("cbc256", "cbc256", "normal", psk, ("psk", "secret", "-"), "echo", normal),
        ("cbc128-sha1", "cbc128sha1", "normal", psk, ("psk", "secret", "-"), "echo", normal),
        ("gcm128", "gcm128", "normal", psk, ("psk", "secret", "-"), "echo", normal),
        ("probes", "cbc256", "probes", psk, ("psk", "secret", "-"), "echo", probes),
        ("cookie", "gcm128", "cookie", psk, ("psk", "secret", "-"), "echo", cookie),
        ("wrong-psk", "cbc256", "normal", {"kind": "psk", "psk": "other"}, ("psk", "secret", "-"), "wait",
         expect_down("authentication failed")),
        ("no-proposal", "cbc256", "noproposal", psk, ("psk", "secret", "-"), "wait", expect_down("NO_PROPOSAL_CHOSEN")),
        ("no-server", "cbc256", "silent", psk, ("psk", "secret", "-"), "wait", expect_down("server does not respond")),
        ("server-delete", "gcm128", "delete", psk, ("psk", "secret", "-"), "one", delete),
        ("dead-server", "cbc256", "dead", psk, ("psk", "secret", "-"), "wait", silent),
        # --- логин и пароль (EAP-MSCHAPv2) ---
        ("eap-sha256-sig", "cbc256", "normal", eapcfg(), ("eap", PW, "root"), "echo", eap_ok),
        ("eap-rsa-sha1-sig", "gcm128", "normal", eapcfg(sig=1), ("eap", PW, "root"), "echo", eap_ok),
        ("eap-cert-chain", "cbc128sha1", "normal", eapcfg(pki="chain"), ("eap", PW, "root"), "echo", eap_ok),
        ("eap-big-reply", "cbc256", "normal", eapcfg(pki="bigchain"), ("eap", PW, "root"), "echo", eap_ok),
        ("eap-pinned-server-cert", "cbc256", "normal", eapcfg(), ("eap", PW, "leaf"), "echo", eap_ok),
        ("eap-wrong-password", "cbc256", "normal", eapcfg(), ("eap", "bad-pass", "root"), "wait",
         expect_down("wrong login or password")),
        ("eap-untrusted-ca", "cbc256", "normal", eapcfg(), ("eap", PW, "-"), "wait", expect_down("not trusted")),
        ("eap-other-ca", "cbc256", "normal", eapcfg(), ("eap", PW, "other"), "wait", expect_down("not trusted")),
        ("eap-rogue-server", "cbc256", "normal", eapcfg(pki="rogue"), ("eap", PW, "root"), "wait",
         expect_down("not trusted")),
        ("eap-wrong-hostname", "cbc256", "normal", eapcfg(pki="wrongname"), ("eap", PW, "root"), "wait",
         expect_down("does not match 127.0.0.1")),
        ("eap-forged-signature", "cbc256", "normal", eapcfg(pki="badsig"), ("eap", PW, "root"), "wait",
         expect_down("server signature is invalid")),
        ("eap-server-without-password-proof", "cbc256", "badS", eapcfg(), ("eap", PW, "root"), "wait",
         expect_down("server failed MS-CHAPv2 authentication")),
        ("eap-unreadable-ca", "cbc256", "normal", eapcfg(), ("eap", PW, "garbage"), "wait",
         expect_down("cannot read the CA certificate")),
    ]
    for i, (name, suite, beh, auth, client, mode, exp) in enumerate(plan):
        run_scenario(cp, name, suite, beh, auth, client, mode, exp, base + 2 * i, base + 2 * i + 1, pki, files)
    print("ALL OK" if fails == 0 else f"{fails} FAILED")
    sys.exit(1 if fails else 0)
