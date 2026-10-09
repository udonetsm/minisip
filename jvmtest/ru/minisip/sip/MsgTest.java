package ru.minisip.sip;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class MsgTest {

    static int fails;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }

    public static void main(String[] args) {
        String raw = "SIP/2.0 200 OK\r\n"
                + "Via: SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK123\r\n"
                + "From: \"Alice\" <sip:alice@example.com>;tag=111\r\n"
                + "To: <sip:bob@example.com>;tag=222\r\n"
                + "Call-ID: abc123xyz\r\n"
                + "CSeq: 1 INVITE\r\n"
                + "i: abc123xyz\r\n"
                + "Content-Type: application/sdp\r\n"
                + "Content-Length: 5\r\n"
                + "\r\n"
                + "hello";

        Msg m = Msg.parse(raw.getBytes(StandardCharsets.UTF_8));
        check(m != null, "Msg.parse parses message successfully");
        check(m.isResp(), "isResp() is true for response");
        check(m.status() == 200, "status() is 200");
        check(m.reason().equals("OK"), "reason() is OK");
        check(m.get("call-id").equals("abc123xyz"), "get('call-id') works");
        check(m.cseq() == 1, "cseq() is 1");
        check(m.cseqMethod().equals("INVITE"), "cseqMethod() is INVITE");
        check(m.key().equals("abc123xyz|1|INVITE"), "key() format is correct");
        check(m.body.equals("hello"), "body is correctly extracted");

        String fromHdr = m.get("from");
        check(Msg.param(fromHdr, "tag").equals("111"), "Msg.param extracts tag");
        check(Msg.uri(fromHdr).equals("sip:alice@example.com"), "Msg.uri extracts URI");
        check(Msg.user(fromHdr).equals("alice"), "Msg.user extracts user part");

        // Test request parsing
        String reqRaw = "INVITE sip:bob@example.com SIP/2.0\r\n"
                + "CSeq: 2 INVITE\r\n"
                + "\r\n";
        Msg req = Msg.parse(reqRaw.getBytes(StandardCharsets.UTF_8));
        check(req != null && !req.isResp(), "Request parsed successfully");
        check(req.method().equals("INVITE"), "Request method is INVITE");

        // Test invalid
        check(Msg.parse("NOTASIP\r\n".getBytes(StandardCharsets.UTF_8)) == null, "Non-SIP message returns null");

        System.out.println(fails == 0 ? "MSG TEST OK" : "MSG TEST FAILED " + fails);
        if (fails > 0) System.exit(1);
    }
}
