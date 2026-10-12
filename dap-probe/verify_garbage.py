import json, time
from dap import Session

def frame(obj):
    body = json.dumps(obj).encode()
    return b"Content-Length: %d\r\n\r\n" % len(body) + body

CASES = {
    "body is not JSON": b"Content-Length: 5\r\n\r\nhello",
    "invalid JSON": b"Content-Length: 18\r\n\r\n{\"seq\": \"x\", 1234}",
    "header without Content-Length": b"Garbage-Header: 1\r\n\r\n",
    "request without command": frame({"seq": 900, "type": "request"}),
    "stray response": frame({"seq": 901, "type": "response", "request_seq": 1, "success": True, "command": "zzz"}),
    "unknown message type": frame({"seq": 902, "type": "banana"}),
    "seq is a string": frame({"seq": "7", "type": "request", "command": "threads"}),
    "arguments is an array": frame({"seq": 903, "type": "request", "command": "evaluate", "arguments": [1, 2]}),
    "JSON array instead of object": frame([1, 2, 3]),
    "negative Content-Length": b"Content-Length: -1\r\n\r\n",
    "huge Content-Length then EOF-less silence": b"Content-Length: 999999999\r\n\r\n{}",
    "lowercase header": b"content-length: 2\r\n\r\n{}",
    "LF only line endings": b"Content-Length: 2\n\n{}",
}
for name, data in CASES.items():
    s = Session("garbage")
    s.initialize()
    s.send_raw(data)
    s.drain(1.0)
    alive = s.proc.poll() is None
    r = s.request("threads", timeout=5, show=False) if alive else {}
    print("   RESULT %-44s adapter %s; next request: %s; stderr: %s" % (name, "alive" if alive else "EXITED code %s" % s.proc.poll(),
          "ok" if r.get("success") else r.get("message"), "".join(s.stderr_text).strip().splitlines()[:1]))
    if s.proc.poll() is None:
        s.proc.kill()
