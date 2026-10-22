"""A NuGet V3 feed behind Basic authentication, for checking the credential provider of the plugin (helpers/credentialprovider).

    uv run --no-project python tools/nuget-auth-feed/feed.py [--port 5555] [--user probe] [--password probe-password]

Serves one package, DotNetSupport.AuthFeedProbe 1.0.0 (made in memory: a nuspec and an empty lib folder), at
http://127.0.0.1:<port>/v3/index.json (`--host 0.0.0.0`: from a container too). Every request without the right user and password gets 401 with `WWW-Authenticate: Basic`, which
is what makes NuGet ask its credential providers. Each request is printed with whether it was authorized. Standard library only.
"""

import argparse
import base64
import io
import json
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PACKAGE_ID = "DotNetSupport.AuthFeedProbe"
VERSION = "1.0.0"


def make_package() -> bytes:
    nuspec = f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">
  <metadata>
    <id>{PACKAGE_ID}</id>
    <version>{VERSION}</version>
    <authors>C# Project Support</authors>
    <description>A package of the test feed behind Basic authentication.</description>
  </metadata>
</package>
"""
    content_types = """<?xml version="1.0" encoding="utf-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="nuspec" ContentType="application/octet" />
  <Default Extension="_" ContentType="application/octet" />
</Types>
"""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(f"{PACKAGE_ID}.nuspec", nuspec)
        archive.writestr("[Content_Types].xml", content_types)
        archive.writestr("lib/netstandard2.0/_._", "")
    return buffer.getvalue()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1", help="0.0.0.0 to reach it from a container (http://host.docker.internal:<port>)")
    parser.add_argument("--port", type=int, default=5555)
    parser.add_argument("--user", default="probe")
    parser.add_argument("--password", default="probe-password")
    args = parser.parse_args()
    expected = "Basic " + base64.b64encode(f"{args.user}:{args.password}".encode()).decode()
    package = make_package()
    lower = PACKAGE_ID.lower()

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            authorized = self.headers.get("Authorization") == expected
            print(f"{'200' if authorized else '401'} {self.path}", flush=True)
            if not authorized:
                self.send_response(401)
                self.send_header("WWW-Authenticate", 'Basic realm="auth-feed"')
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if self.path == "/v3/index.json":
                # the address the client used: 127.0.0.1 here, host.docker.internal from a container
                base = f"http://{self.headers.get('Host')}"
                body = json.dumps({"version": "3.0.0", "resources": [{"@id": f"{base}/flat/", "@type": "PackageBaseAddress/3.0.0"}]}).encode()
                self.reply(body, "application/json")
            elif self.path == f"/flat/{lower}/index.json":
                self.reply(json.dumps({"versions": [VERSION]}).encode(), "application/json")
            elif self.path == f"/flat/{lower}/{VERSION}/{lower}.{VERSION}.nupkg":
                self.reply(package, "application/octet-stream")
            else:
                self.send_response(404)
                self.send_header("Content-Length", "0")
                self.end_headers()

        def reply(self, body: bytes, content_type: str) -> None:
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_: object) -> None:
            pass

    print(f"http://{args.host}:{args.port}/v3/index.json  user {args.user}  password {args.password}", flush=True)
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
