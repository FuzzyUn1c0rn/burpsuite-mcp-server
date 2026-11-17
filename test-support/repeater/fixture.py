"""Synthetic loopback fixture. Run only under the independent verifier's ownership.

Control traffic uses a separate port and is never counted as target traffic.
TLS listener requires a locally generated certificate/key; no private keys are tracked.
"""
import argparse
import base64
import hashlib
import json
import socket
import socketserver
import ssl
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from h2.config import H2Configuration
from h2.connection import H2Connection
from h2.events import DataReceived, RequestReceived, StreamEnded


class State:
    def __init__(self, output):
        self.lock = threading.Lock()
        self.records = []
        self.counter = 0
        self.token = 1
        self.gate = threading.Event()
        self.output = output

    def record(self, listener, protocol, alpn, method, path, headers, body, raw_head=None):
        with self.lock:
            item = dict(sequence=len(self.records) + 1, timestampNs=time.time_ns(),
                        listener=listener, protocol=protocol, negotiatedAlpn=alpn,
                        method=method, path=path, headers=headers,
                        bodySha256=hashlib.sha256(body).hexdigest(), bodyLength=len(body),
                        bodyBase64=base64.b64encode(body).decode(),
                        rawHeadBase64=base64.b64encode(raw_head).decode() if raw_head else None)
            self.records.append(item)
            with self.output.open('a', encoding='utf-8') as stream:
                stream.write(json.dumps(item) + '\n')
            return item

    def reply(self, record, redirect_port):
        path = record['path'].split('?', 1)[0]
        if path == '/delay':
            self.gate.wait(300)
        if path == '/close':
            return None
        status, headers = 200, [('content-type', 'application/json')]
        if path == '/increment':
            with self.lock:
                self.counter += 1
        if path == '/redirect':
            status = 302
            headers.append(('location', f'http://127.0.0.1:{redirect_port}/redirected'))
        if path.startswith('/status/'):
            status = int(path.rsplit('/', 1)[1])
            if not 400 <= status <= 599:
                status = 400
        if path == '/tokens':
            with self.lock:
                self.token += 1
                token = str(self.token)
            headers.extend([('set-cookie', f'session={token}; Path=/'), ('x-csrf-token', token)])
        if path == '/authenticated':
            supplied = [(k.lower(), v) for k, v in record['headers']]
            with self.lock:
                token = str(self.token)
            if ('cookie', f'session={token}') not in supplied or ('x-csrf-token', token) not in supplied:
                status = 403
        body = b'x' * 50000 if path == '/large' else json.dumps(record).encode()
        return status, headers, body


class Target(socketserver.BaseRequestHandler):
    def handle(self):
        conn = self.request
        try:
            if self.server.tls:
                conn = self.server.tls.wrap_socket(conn, server_side=True)
            conn.settimeout(310)
            alpn = conn.selected_alpn_protocol() if isinstance(conn, ssl.SSLSocket) else None
            if alpn == 'h2':
                self.http2(conn, alpn)
            else:
                self.http1(conn, alpn)
        except (ConnectionError, TimeoutError, ssl.SSLError, ValueError) as exc:
            # A failed connection is diagnostic evidence, never a fabricated received request.
            print(f'{self.server.label}: {type(exc).__name__}: {exc}', flush=True)
        finally:
            conn.close()

    def complete(self, method, path, headers, body, protocol, alpn, raw_head=None):
        record = self.server.state.record(self.server.label, protocol, alpn, method, path, headers, body, raw_head)
        return self.server.state.reply(record, self.server.redirect_port)

    def http1(self, conn, alpn):
        # One request per connection; Connection: close avoids unread keepalive state.
        stream = conn.makefile('rb')
        try:
            line = stream.readline(65537)
            if not line:
                return
            method, path, protocol = line.decode('latin1').strip().split(' ', 2)
            raw_head = line
            headers = []
            while True:
                line = stream.readline(65537)
                raw_head += line
                if line in (b'\r\n', b'\n'):
                    break
                if not line or len(raw_head) > 262144:
                    raise ValueError('invalid or oversized headers')
                name, value = line.decode('latin1').split(':', 1)
                headers.append((name, value.strip()))
            if any(k.lower() == 'transfer-encoding' for k, _ in headers):
                raise ValueError('chunked/malformed requests outside this fixture profile')
            lengths = [int(v) for k, v in headers if k.lower() == 'content-length']
            if len(lengths) > 1 or (lengths and not 0 <= lengths[0] <= 262144):
                raise ValueError('invalid content length')
            body = stream.read(lengths[0]) if lengths else b''
            reply = self.complete(method, path, headers, body, protocol, alpn, raw_head)
            if reply is None:
                return
            status, headers, body = reply
            wire = f'HTTP/1.1 {status} Fixture\r\n'.encode()
            for k, v in headers + [('content-length', str(len(body))), ('connection', 'close')]:
                wire += f'{k}: {v}\r\n'.encode()
            conn.sendall(wire + b'\r\n' + body)
        finally:
            stream.close()

    def http2(self, conn, alpn):
        h2 = H2Connection(config=H2Configuration(client_side=False, header_encoding='utf-8'))
        h2.initiate_connection()
        conn.sendall(h2.data_to_send())
        streams, pending = {}, {}
        while True:
            data = conn.recv(65536)
            if not data:
                return
            for event in h2.receive_data(data):
                if isinstance(event, RequestReceived):
                    streams[event.stream_id] = [event.headers, bytearray()]
                elif isinstance(event, DataReceived):
                    streams[event.stream_id][1].extend(event.data)
                    if len(streams[event.stream_id][1]) > 262144:
                        raise ValueError('oversized body')
                    h2.acknowledge_received_data(event.flow_controlled_length, event.stream_id)
                elif isinstance(event, StreamEnded):
                    headers, body = streams.pop(event.stream_id)
                    pseudo = dict((k, v) for k, v in headers if k.startswith(':'))
                    reply = self.complete(pseudo[':method'], pseudo[':path'], headers, bytes(body), 'HTTP/2', alpn)
                    if reply is None:
                        return
                    status, response_headers, response_body = reply
                    h2.send_headers(event.stream_id, [(':status', str(status))] + response_headers +
                                    [('content-length', str(len(response_body)))])
                    pending[event.stream_id] = response_body
            for stream_id in list(pending):
                body = pending[stream_id]
                while body and h2.local_flow_control_window(stream_id) > 0:
                    size = min(len(body), h2.max_outbound_frame_size, h2.local_flow_control_window(stream_id))
                    h2.send_data(stream_id, body[:size], end_stream=size == len(body))
                    body = body[size:]
                if body:
                    pending[stream_id] = body
                else:
                    del pending[stream_id]
            conn.sendall(h2.data_to_send())


class Control(BaseHTTPRequestHandler):
    def do_GET(self):
        with self.server.state.lock:
            result = dict(records=list(self.server.state.records), counter=self.server.state.counter)
        body = json.dumps(result).encode()
        self.send_response(200)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        state = self.server.state
        if self.path == '/release':
            state.gate.set()
        elif self.path == '/hold':
            state.gate.clear()
        elif self.path == '/reset':
            # Verifier must ensure no target requests are active when resetting.
            with state.lock:
                state.records.clear()
                state.counter = 0
        else:
            self.send_error(404)
            return
        self.send_response(204)
        self.end_headers()

    def log_message(self, *args):
        pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = False
    daemon_threads = True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--http-port', type=int, default=18180)
    parser.add_argument('--tls-port', type=int, default=18443)
    parser.add_argument('--redirect-port', type=int, default=18181)
    parser.add_argument('--control-port', type=int, default=18182)
    parser.add_argument('--cert', required=True)
    parser.add_argument('--key', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    state = State(args.output)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(args.cert, args.key)
    tls.set_alpn_protocols(['h2', 'http/1.1'])
    servers = []
    try:
        for label, port, context in [('http', args.http_port, None), ('tls', args.tls_port, tls),
                                     ('redirect', args.redirect_port, None)]:
            server = Server(('127.0.0.1', port), Target)
            server.label, server.tls, server.state = label, context, state
            server.redirect_port = args.redirect_port
            servers.append(server)
        control = ThreadingHTTPServer(('127.0.0.1', args.control_port), Control)
        control.state = state
        servers.append(control)
        for server in servers:
            threading.Thread(target=server.serve_forever, daemon=True).start()
        print(json.dumps(dict(ready=True, ports=[s.server_address[1] for s in servers])), flush=True)
        threading.Event().wait()
    except KeyboardInterrupt:
        pass
    finally:
        for server in servers:
            server.server_close()


if __name__ == '__main__':
    main()
