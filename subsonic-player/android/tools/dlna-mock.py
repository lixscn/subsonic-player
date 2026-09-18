#!/usr/bin/env python3
"""DSH DLNA mock renderer (UPnP AV MediaRenderer) — for testing the Android app.

Runs on the NAS (or any Linux box) on the same LAN as the phone:
  - answers SSDP M-SEARCH on 239.255.255.250:1900
  - serves a device description + AVTransport / RenderingControl SOAP control
  - keeps a tiny state machine (STOPPED / PLAYING / PAUSED, position advances)
  - logs every SOAP action, so we can verify what the app actually sent

Usage: python3 dlna-mock.py --http-port 8099 --max-dur 0
       (--max-dur N: pretend every track is at most N seconds, to test auto-next fast)
"""
import argparse
import re
import socket
import struct
import threading
import time
import xml.sax.saxutils as sx
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UDN = "uuid:dsh-mock-0001"
DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
AVT = "urn:schemas-upnp-org:service:AVTransport:1"
RCS = "urn:schemas-upnp-org:service:RenderingControl:1"

STATE = {
    "transport": "STOPPED",     # STOPPED / PLAYING / PAUSED / TRANSITIONING
    "uri": "",
    "meta": "",
    "pos": 0.0,                 # seconds
    "dur": 0.0,                 # seconds (0 = unknown)
    "started_at": 0.0,          # monotonic when PLAYING began
    "base_pos": 0.0,
    "volume": 50,
    "stop_count": 0,
}
LOCK = threading.Lock()
ARGS = None
LOG = None


def log(msg):
    line = "%s %s" % (time.strftime("%H:%M:%S"), msg)
    print(line, flush=True)
    if LOG:
        try:
            with open(LOG, "a", encoding="utf-8") as f:
                f.write(line + "\n")
        except Exception:
            pass


def position():
    with LOCK:
        if STATE["transport"] == "PLAYING":
            p = STATE["base_pos"] + (time.monotonic() - STATE["started_at"])
            if STATE["dur"] > 0 and p >= STATE["dur"]:
                STATE["pos"] = STATE["dur"]
                STATE["transport"] = "STOPPED"
                STATE["stop_count"] += 1
                log("== 曲目自然播完（mock）→ state=STOPPED")
                return STATE["dur"], STATE["dur"]
            return p, STATE["dur"]
        return STATE["pos"], STATE["dur"]


def hms(sec):
    sec = max(0.0, float(sec))
    h = int(sec // 3600)
    m = int((sec % 3600) // 60)
    s = sec % 60
    return "%d:%02d:%06.3f" % (h, m, s)


def parse_hms(text):
    m = re.match(r"^(\d+):(\d+):(\d+(?:\.\d+)?)$", (text or "").strip())
    if not m:
        return 0.0
    return int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3))


def envelope(service, action, inner):
    return ('<?xml version="1.0"?>\n'
            '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
            's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">'
            '<s:Body><u:%sResponse xmlns:u="%s">%s</u:%sResponse></s:Body></s:Envelope>'
            % (action, service, inner, action))


DESC = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>%s</deviceType>
    <friendlyName>DSH Mock Renderer (NAS)</friendlyName>
    <manufacturer>DeepSeek Harness</manufacturer>
    <modelName>dsh-mock-renderer</modelName>
    <modelDescription>测试用模拟 DLNA 渲染器</modelDescription>
    <UDN>%s</UDN>
    <serviceList>
      <service>
        <serviceType>%s</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>/AVTransport/scpd.xml</SCPDURL>
        <controlURL>/AVTransport/control</controlURL>
        <eventSubURL>/AVTransport/event</eventSubURL>
      </service>
      <service>
        <serviceType>%s</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>/RenderingControl/scpd.xml</SCPDURL>
        <controlURL>/RenderingControl/control</controlURL>
        <eventSubURL>/RenderingControl/event</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>
""" % (DEVICE_TYPE, UDN, AVT, RCS)

SCPD = """<?xml version="1.0"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>SetAVTransportURI</name></action>
    <action><name>Play</name></action>
    <action><name>Pause</name></action>
    <action><name>Stop</name></action>
    <action><name>Seek</name></action>
    <action><name>GetTransportInfo</name></action>
    <action><name>GetPositionInfo</name></action>
    <action><name>GetMediaInfo</name></action>
  </actionList>
</scpd>
"""


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *a):
        pass

    def _send(self, body, code=200, ctype="text/xml; charset=\"utf-8\""):
        data = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.startswith("/desc.xml") or self.path == "/":
            self._send(DESC)
        elif self.path.startswith("/AVTransport/scpd") or self.path.startswith("/RenderingControl/scpd"):
            self._send(SCPD)
        else:
            self._send("<html>dsh dlna mock</html>", ctype="text/html")

    def do_HEAD(self):
        self._send("", 200, ctype="text/xml")

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n).decode("utf-8", "replace")
        soapaction = (self.headers.get("SOAPACTION") or "").strip('"')
        action = soapaction.split("#")[-1] if "#" in soapaction else ""
        if not action:
            m = re.search(r"<u:([A-Za-z]+)[ >]", body)
            action = m.group(1) if m else "?"

        if "/RenderingControl" in self.path:
            if action == "GetVolume":
                with LOCK:
                    v = STATE["volume"]
                self._send(envelope(RCS, "GetVolume", "<CurrentVolume>%d</CurrentVolume>" % v))
                log("RCS GetVolume -> %d" % v)
            elif action == "SetVolume":
                m = re.search(r"<DesiredVolume>(\d+)</DesiredVolume>", body)
                v = int(m.group(1)) if m else 0
                with LOCK:
                    STATE["volume"] = v
                self._send(envelope(RCS, "SetVolume", ""))
                log("RCS SetVolume -> %d" % v)
            else:
                self._send(envelope(RCS, action, ""))
                log("RCS %s (未实现，回了空响应)" % action)
            return

        # ---- AVTransport ----
        if action == "SetAVTransportURI":
            uri = re.search(r"<CurrentURI>(.*?)</CurrentURI>", body, re.S)
            meta = re.search(r"<CurrentURIMetaData>(.*?)</CurrentURIMetaData>", body, re.S)
            title = re.search(r"<dc:title>(.*?)</dc:title>", body, re.S)
            dur = re.search(r'duration="([^"]+)"', body)
            with LOCK:
                STATE["uri"] = sx.unescape(uri.group(1)) if uri else ""
                STATE["meta"] = sx.unescape(meta.group(1)) if meta else ""
                STATE["pos"] = 0.0
                STATE["base_pos"] = 0.0
                STATE["dur"] = parse_hms(sx.unescape(dur.group(1))) if dur else 0.0
                if ARGS.max_dur > 0 and (STATE["dur"] <= 0 or STATE["dur"] > ARGS.max_dur):
                    STATE["dur"] = float(ARGS.max_dur)
                STATE["transport"] = "STOPPED"
            self._send(envelope(AVT, "SetAVTransportURI", ""))
            log("AVT SetAVTransportURI title=%s uri=%s dur=%s"
                % (sx.unescape(title.group(1)) if title else "?", STATE["uri"], hms(STATE["dur"])))
        elif action == "Play":
            with LOCK:
                STATE["transport"] = "PLAYING"
                STATE["started_at"] = time.monotonic()
                STATE["base_pos"] = STATE["pos"]
            self._send(envelope(AVT, "Play", ""))
            log("AVT Play (从 %s 继续)" % hms(STATE["base_pos"]))
        elif action == "Pause":
            pos, _ = position()
            with LOCK:
                STATE["transport"] = "PAUSED"
                STATE["pos"] = pos
            self._send(envelope(AVT, "Pause", ""))
            log("AVT Pause @ %s" % hms(pos))
        elif action == "Stop":
            position()
            with LOCK:
                STATE["transport"] = "STOPPED"
                STATE["pos"] = 0.0
                STATE["base_pos"] = 0.0
            self._send(envelope(AVT, "Stop", ""))
            log("AVT Stop")
        elif action == "Seek":
            m = re.search(r"<Target>(.*?)</Target>", body)
            target = parse_hms(sx.unescape(m.group(1))) if m else 0.0
            with LOCK:
                STATE["pos"] = target
                STATE["base_pos"] = target
                STATE["started_at"] = time.monotonic()
            self._send(envelope(AVT, "Seek", ""))
            log("AVT Seek -> %s" % hms(target))
        elif action == "GetTransportInfo":
            with LOCK:
                st = STATE["transport"]
            self._send(envelope(AVT, "GetTransportInfo",
                                "<CurrentTransportState>%s</CurrentTransportState>"
                                "<CurrentTransportStatus>OK</CurrentTransportStatus>"
                                "<CurrentSpeed>1</CurrentSpeed>" % st))
        elif action == "GetPositionInfo":
            pos, dur = position()
            with LOCK:
                uri = STATE["uri"]
            self._send(envelope(AVT, "GetPositionInfo",
                                "<Track>1</Track><TrackDuration>%s</TrackDuration>"
                                "<TrackMetaData></TrackMetaData><TrackURI>%s</TrackURI>"
                                "<RelTime>%s</RelTime><AbsTime>%s</AbsTime>"
                                "<RelCount>2147483647</RelCount><AbsCount>2147483647</AbsCount>"
                                % (hms(dur), sx.escape(uri), hms(pos), hms(pos))))
        elif action == "GetMediaInfo":
            pos, dur = position()
            with LOCK:
                uri = STATE["uri"]
            self._send(envelope(AVT, "GetMediaInfo",
                                "<NrTracks>1</NrTracks><MediaDuration>%s</MediaDuration>"
                                "<CurrentURI>%s</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>"
                                "<NextURI></NextURI><NextURIMetaData></NextURIMetaData>"
                                "<PlayMedium>NETWORK</PlayMedium><RecordMedium>NOT_IMPLEMENTED</RecordMedium>"
                                "<WriteStatus>NOT_IMPLEMENTED</WriteStatus>" % (hms(dur), sx.escape(uri))))
        else:
            self._send(envelope(AVT, action, ""))
            log("AVT %s (未实现，回了空响应)" % action)


def ssdp_thread(http_port):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
    except Exception:
        pass
    sock.bind(("", 1900))
    mreq = struct.pack("4sl", socket.inet_aton("239.255.255.250"), socket.INADDR_ANY)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
    sock.settimeout(2.0)
    log("SSDP 监听 0.0.0.0:1900 (multicast 239.255.255.250)")

    ip = ARGS.advertise_ip or "192.168.0.220"
    while True:
        try:
            data, addr = sock.recvfrom(4096)
        except socket.timeout:
            continue
        except Exception as e:
            log("SSDP recv 异常: %r" % e)
            continue
        text = data.decode("utf-8", "replace")
        if not text.upper().startswith("M-SEARCH"):
            continue
        m = re.search(r"(?im)^ST:\s*(\S+)", text)
        st = m.group(1) if m else "ssdp:all"
        usn = UDN
        if st.startswith("urn:schemas-upnp-org:service:AVTransport"):
            usn = "%s::%s" % (UDN, AVT)
        elif st.startswith("urn:schemas-upnp-org:service:RenderingControl"):
            usn = "%s::%s" % (UDN, RCS)
        elif st.startswith("urn:schemas-upnp-org:device"):
            usn = "%s::%s" % (UDN, DEVICE_TYPE)
        elif st == "upnp:rootdevice":
            usn = "%s::upnp:rootdevice" % UDN
        else:
            # ssdp:all → 用设备类型回
            st = DEVICE_TYPE
            usn = "%s::%s" % (UDN, DEVICE_TYPE)
        resp = ("HTTP/1.1 200 OK\r\n"
                "CACHE-CONTROL: max-age=1800\r\n"
                "EXT:\r\n"
                "LOCATION: http://%s:%d/desc.xml\r\n"
                "SERVER: Linux/6.18 UPnP/1.0 DSH-Mock/1.0\r\n"
                "ST: %s\r\n"
                "USN: %s\r\n"
                "BOOTID.UPNP.ORG: 1\r\n"
                "CONFIGID.UPNP.ORG: 1\r\n"
                "\r\n" % (ip, http_port, st, usn))
        sock.sendto(resp.encode("utf-8"), addr)
        log("SSDP 应答 %s  ST=%s" % (addr[0], st))


def main():
    global ARGS, LOG
    ap = argparse.ArgumentParser()
    ap.add_argument("--http-port", type=int, default=8099)
    ap.add_argument("--max-dur", type=int, default=0, help="把每首都当成最多 N 秒（快速验证自动下一首）")
    ap.add_argument("--advertise-ip", default="", help="LOCATION 里用的 IP（默认 192.168.0.220）")
    ap.add_argument("--log", default="/tmp/dlna-mock.log")
    ARGS = ap.parse_args()
    LOG = ARGS.log
    log("=== DSH DLNA mock 启动: http=%d max_dur=%d ===" % (ARGS.http_port, ARGS.max_dur))
    t = threading.Thread(target=ssdp_thread, args=(ARGS.http_port,), daemon=True)
    t.start()
    srv = ThreadingHTTPServer(("0.0.0.0", ARGS.http_port), Handler)
    log("HTTP 控制端点 0.0.0.0:%d" % ARGS.http_port)
    srv.serve_forever()


if __name__ == "__main__":
    main()
