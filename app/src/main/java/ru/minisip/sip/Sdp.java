package ru.minisip.sip;

/** Минимальный SDP: один аудио-поток, только G.711. */
final class Sdp {

    private Sdp() {}

    /** pts — предлагаемые типы по порядку предпочтения: 8 = PCMA, 0 = PCMU. */
    static String make(String ip, int port, int... pts) {
        StringBuilder fmts = new StringBuilder();
        StringBuilder map = new StringBuilder();
        for (int pt : pts) {
            fmts.append(' ').append(pt);
            map.append("a=rtpmap:").append(pt).append(pt == 8 ? " PCMA/8000" : " PCMU/8000").append("\r\n");
        }
        long id = System.currentTimeMillis() / 1000;
        return "v=0\r\n"
                + "o=- " + id + " " + id + " IN IP4 " + ip + "\r\n"
                + "s=-\r\n"
                + "c=IN IP4 " + ip + "\r\n"
                + "t=0 0\r\n"
                + "m=audio " + port + " RTP/AVP" + fmts + "\r\n"
                + map
                + "a=ptime:20\r\n"
                + "a=sendrecv\r\n";
    }

    /**
     * Разбор: {ip, порт, тип}. Тип — первый из предложенных, который мы умеем (8 или 0).
     * null — нет аудио, нет адреса или нет общего кодека.
     */
    static String[] parse(String sdp) {
        String sessionIp = null;
        String mediaIp = null;
        String port = null;
        String pt = null;
        boolean inAudio = false;
        for (String line : sdp.split("\r?\n")) {
            if (line.startsWith("c=IN IP4 ")) {
                String ip = line.substring(9).trim().split("/")[0];
                if (inAudio) mediaIp = ip;
                else if (port == null) sessionIp = ip;
            } else if (line.startsWith("m=")) {
                inAudio = false;
                if (port != null) continue;
                String[] p = line.substring(2).trim().split("\\s+");
                if (p.length < 4 || !p[0].equals("audio")) continue;
                for (int i = 3; i < p.length; i++) {
                    if (p[i].equals("8") || p[i].equals("0")) {
                        pt = p[i];
                        break;
                    }
                }
                if (pt == null) continue;
                port = p[1];
                inAudio = true;
            }
        }
        String ip = mediaIp != null ? mediaIp : sessionIp;
        if (ip == null || port == null || pt == null || ip.equals("0.0.0.0") || port.equals("0")) {
            return null;
        }
        return new String[]{ip, port, pt};
    }
}
