#!/usr/bin/env python3
"""Exercise real preview/production Nginx upload limits with an isolated upstream.

Requires Docker and OpenSSL. No application, shared database or preview is touched.
Backend tests separately enforce file signatures, single-file size and attachment count.
"""
from __future__ import annotations

import http.client
import http.server
import pathlib
import shutil
import ssl
import subprocess
import tempfile
import threading
import time
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]
IMAGE = "nginx:1.30.5-alpine"
MIB = 1024 * 1024
PATH = "/admin-api/commission/prepays/47/application-details"


def docker(*args: str) -> str:
    return subprocess.check_output(["docker", *args], text=True).strip()


class Upstream(http.server.BaseHTTPRequestHandler):
    def do_PUT(self) -> None:
        remaining = int(self.headers.get("Content-Length", "0"))
        while remaining:
            chunk = self.rfile.read(min(remaining, MIB))
            if not chunk:
                raise ConnectionError("Request body ended early")
            remaining -= len(chunk)
        self.send_response(204)
        self.send_header("X-T47-Upstream", "reached")
        self.end_headers()

    def log_message(self, *_args: object) -> None:
        pass


class LocalHTTPSConnection(http.client.HTTPSConnection):
    def connect(self) -> None:
        # Connect to the local mapped port while sending the production admin site's SNI.
        http.client.HTTPConnection.connect(self)
        self.sock = self._context.wrap_socket(self.sock, server_hostname="admin.example.test")


def multipart(count: int, size: int) -> bytes:
    boundary = b"t47-upload-boundary"
    parts = [b'--' + boundary + b'\r\nContent-Disposition: form-data; name="payload"\r\n'
             b'Content-Type: application/json\r\n\r\n{}\r\n']
    for index in range(count):
        parts.append(b'--' + boundary + b'\r\nContent-Disposition: form-data; '
                     b'name="attachments"; filename="' + str(index).encode() +
                     b'.png"\r\nContent-Type: image/png\r\n\r\n' +
                     b'\x89PNG\r\n\x1a\n' + bytes(size - 8) + b'\r\n')
    return b"".join(parts) + b"--" + boundary + b"--\r\n"


def check(config: pathlib.Path, tls: bool, upstream_port: int, fixture: pathlib.Path) -> None:
    name = "t47-upload-check-" + uuid.uuid4().hex[:10]
    port = 443 if tls else 8890
    rendered = config.read_text(encoding="utf-8").replace("${TCRM_DOMAIN}", "example.test")
    rendered = rendered.replace("crm-admin:8080", f"host.docker.internal:{upstream_port}")
    rendered = rendered.replace("crm-redirect:8081", f"host.docker.internal:{upstream_port}")
    (fixture / "nginx.conf").write_text("events {}\nhttp {\n" + rendered + "\n}\n", encoding="utf-8")
    mounts = ["--mount", f"type=bind,source={fixture / 'nginx.conf'},target=/etc/nginx/nginx.conf,readonly",
              "--mount", f"type=bind,source={ROOT / 'deploy/nginx/snippets'},target=/etc/nginx/snippets,readonly",
              "--mount", f"type=bind,source={fixture},target=/etc/nginx/certs,readonly",
              "--add-host", "host.docker.internal:host-gateway"]
    docker("run", "--rm", *mounts, "--entrypoint", "nginx", IMAGE, "-t")
    try:
        docker("run", "-d", "--name", name, "-p", f"127.0.0.1::{port}",
               *mounts, "--entrypoint", "nginx", IMAGE, "-g", "daemon off;")
        host_port = int(docker("port", name, f"{port}/tcp").split(":")[-1])
        time.sleep(0.5)
        for count, size in [(3, 8 * MIB), (5, 10 * MIB), (1, 10 * MIB + 1), (6, MIB)]:
            connection = (LocalHTTPSConnection("127.0.0.1", host_port,
                          context=ssl._create_unverified_context(), timeout=60) if tls else
                          http.client.HTTPConnection("127.0.0.1", host_port, timeout=60))
            try:
                connection.request("PUT", PATH, multipart(count, size),
                                   {"Host": "admin.example.test", "Content-Type":
                                    "multipart/form-data; boundary=t47-upload-boundary"})
                response = connection.getresponse()
                assert response.status == 204 and response.getheader("X-T47-Upstream") == "reached", response.status
                response.read()
            finally:
                connection.close()
            print(f"{config.name}: {count} x {size} bytes reaches upstream", flush=True)
        # Oversized prepay bodies and unrelated imports retain their proxy rejection.
        for path, size in [(PATH, 52 * MIB), ("/admin-api/kol/imports", 24 * MIB)]:
            connection = (LocalHTTPSConnection("127.0.0.1", host_port,
                          context=ssl._create_unverified_context(), timeout=10) if tls else
                          http.client.HTTPConnection("127.0.0.1", host_port, timeout=10))
            try:
                connection.putrequest("PUT", path, skip_host=True)
                connection.putheader("Host", "admin.example.test")
                connection.putheader("Content-Length", str(size))
                connection.putheader("Expect", "100-continue")
                connection.endheaders()
                response = connection.getresponse()
                assert response.status == 413, response.status
                response.read()
            finally:
                connection.close()
            print(f"{config.name}: {path} rejects {size} bytes with 413", flush=True)
    finally:
        docker("rm", "-f", name)


def main() -> None:
    openssl = shutil.which("openssl") or "C:/Program Files/Git/usr/bin/openssl.exe"
    # Keep temporary files inside this worktree (also works with Windows Docker bind mounts).
    target = ROOT / "backend/target"
    target.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="t47-nginx-", dir=target) as directory:
        fixture = pathlib.Path(directory)
        subprocess.run([openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                        "-subj", "/CN=admin.example.test", "-keyout", str(fixture / "origin.key"),
                        "-out", str(fixture / "origin.pem")], check=True, capture_output=True)
        server = http.server.ThreadingHTTPServer(("0.0.0.0", 0), Upstream)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            check(ROOT / "deploy/nginx/preview/preview.conf", False, server.server_port, fixture)
            check(ROOT / "deploy/nginx/templates/default.conf.template", True, server.server_port, fixture)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()
    print("Preview and production Nginx upload checks passed.")


if __name__ == "__main__":
    main()
