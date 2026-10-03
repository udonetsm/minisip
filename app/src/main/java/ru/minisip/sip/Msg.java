package ru.minisip.sip;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Разобранное SIP-сообщение. Имена заголовков — в нижнем регистре, компактные формы раскрыты. */
final class Msg {

    private static final Map<String, String> COMPACT = new HashMap<>();

    static {
        COMPACT.put("i", "call-id");
        COMPACT.put("f", "from");
        COMPACT.put("t", "to");
        COMPACT.put("v", "via");
        COMPACT.put("m", "contact");
        COMPACT.put("c", "content-type");
        COMPACT.put("l", "content-length");
    }

    final String first;
    final String body;
    private final Map<String, List<String>> h = new HashMap<>();

    private Msg(String first, String body) {
        this.first = first;
        this.body = body;
    }

    /** null — если это не SIP (например, keep-alive CRLF). */
    static Msg parse(byte[] data) {
        String s = new String(data, StandardCharsets.UTF_8);
        int i = s.indexOf("\r\n\r\n");
        int skip = 4;
        if (i < 0) {
            i = s.indexOf("\n\n");
            skip = 2;
        }
        String head = i < 0 ? s : s.substring(0, i);
        String body = i < 0 ? "" : s.substring(i + skip);
        String[] lines = head.split("\r?\n");
        if (lines.length == 0 || lines[0].trim().isEmpty()) return null;
        String first = lines[0].trim();
        if (!first.startsWith("SIP/2.0") && !first.endsWith("SIP/2.0")) return null;
        Msg m = new Msg(first, body);
        for (int k = 1; k < lines.length; k++) {
            String l = lines[k];
            int c = l.indexOf(':');
            if (c <= 0 || l.charAt(0) == ' ' || l.charAt(0) == '\t') continue;
            String name = l.substring(0, c).trim().toLowerCase(Locale.ROOT);
            String v = l.substring(c + 1).trim();
            String full = COMPACT.get(name);
            if (full != null) name = full;
            if (name.equals("via") || name.equals("record-route") || name.equals("route")) {
                for (String part : split(v)) m.add(name, part);
            } else {
                m.add(name, v);
            }
        }
        return m;
    }

    private void add(String name, String v) {
        h.computeIfAbsent(name, k -> new ArrayList<>()).add(v);
    }

    /** Делит "a, b" по запятым, не трогая запятые внутри <> и кавычек. */
    private static List<String> split(String v) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean q = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"') q = !q;
            else if (q) continue;
            else if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                out.add(v.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(v.substring(start).trim());
        return out;
    }

    boolean isResp() {
        return first.startsWith("SIP/2.0");
    }

    int status() {
        String[] p = first.split(" ", 3);
        try {
            return Integer.parseInt(p[1]);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    String reason() {
        String[] p = first.split(" ", 3);
        return p.length > 2 ? p[2] : "";
    }

    String method() {
        return first.substring(0, first.indexOf(' '));
    }

    String get(String name) {
        List<String> l = h.get(name);
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    List<String> all(String name) {
        List<String> l = h.get(name);
        return l == null ? Collections.<String>emptyList() : l;
    }

    int cseq() {
        String v = get("cseq");
        if (v == null) return 0;
        try {
            return Integer.parseInt(v.trim().split("\\s+")[0]);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    String cseqMethod() {
        String v = get("cseq");
        if (v == null) return "";
        String[] p = v.trim().split("\\s+");
        return p.length > 1 ? p[1] : "";
    }

    /** Ключ транзакции для сопоставления ответов: Call-ID|CSeq|метод. */
    String key() {
        return get("call-id") + "|" + cseq() + "|" + cseqMethod();
    }

    /** Значение параметра ";name=value" из заголовка (tag, expires, ...). */
    static String param(String hdr, String name) {
        if (hdr == null) return null;
        Matcher m = Pattern.compile(";\\s*" + Pattern.quote(name) + "=([^;,>\\s]+)",
                Pattern.CASE_INSENSITIVE).matcher(hdr);
        return m.find() ? m.group(1) : null;
    }

    /** URI из значения вида  "Имя" <sip:user@host;lr>;tag=x  или  sip:user@host. */
    static String uri(String nameAddr) {
        if (nameAddr == null) return null;
        int a = nameAddr.indexOf('<');
        if (a >= 0) {
            int b = nameAddr.indexOf('>', a);
            return nameAddr.substring(a + 1, b > 0 ? b : nameAddr.length());
        }
        return nameAddr.split(";")[0].trim();
    }

    /** Пользовательская часть URI (для показа номера звонящего). */
    static String user(String nameAddr) {
        String u = uri(nameAddr);
        if (u == null) return "";
        int c = u.indexOf(':');
        if (c >= 0) u = u.substring(c + 1);
        int at = u.indexOf('@');
        return at >= 0 ? u.substring(0, at) : u;
    }
}
