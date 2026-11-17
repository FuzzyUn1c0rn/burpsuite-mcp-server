"""In-memory component checks only: no sockets, listeners, Burp or MCP pipeline."""
import io
import socket
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from h2.config import H2Configuration
from h2.connection import H2Connection
from h2.events import StreamEnded

from fixture import State, Target


class FixtureComponents(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.state = State(Path(self.directory.name) / 'received.jsonl')
        self.target = object.__new__(Target)
        self.target.server = SimpleNamespace(state=self.state, label='component', redirect_port=18181)

    def http1_connection(self, request):
        return SimpleNamespace(makefile=lambda mode: io.BytesIO(request), sendall=lambda wire: None)

    def test_incomplete_http1_body_is_not_recorded_as_complete(self):
        conn = self.http1_connection(b'POST /echo HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc')
        with self.assertRaisesRegex(ValueError, 'expected 10 bytes, received 3'):
            self.target.http1(conn, None)
        self.assertEqual([], self.state.records)

    def test_complete_utf8_body_and_ordered_repeats_are_recorded(self):
        conn = self.http1_connection('POST /echo HTTP/1.1\r\nX-Test: one\r\nX-Test: two\r\nContent-Length: 2\r\n\r\né'.encode())
        self.target.http1(conn, None)
        record, = self.state.records
        self.assertEqual(2, record['bodyLength'])
        self.assertEqual([('X-Test', 'one'), ('X-Test', 'two')], record['headers'][:2])

    def test_gated_h2_stream_does_not_block_other_stream(self):
        client = H2Connection(config=H2Configuration(client_side=True, header_encoding='utf-8'))
        client.initiate_connection()
        for stream_id, path in [(1, '/delay'), (3, '/echo')]:
            client.send_headers(stream_id, [(':method', 'GET'), (':scheme', 'https'),
                                           (':authority', 'localhost'), (':path', path)], end_stream=True)
        initial = client.data_to_send()
        ended = []
        state = self.state

        class Connection:
            reads = 0

            def settimeout(self, timeout):
                pass

            def sendall(self, data):
                ended.extend(event.stream_id for event in client.receive_data(data) if isinstance(event, StreamEnded))

            def recv(self, limit):
                self.reads += 1
                if self.reads == 1:
                    return initial
                if self.reads == 2:
                    # /echo completed while the earlier /delay is still held.
                    if ended != [3]:
                        raise AssertionError(f'expected only stream 3 before release, got {ended}')
                    state.gate.set()
                    raise socket.timeout()
                return b''

        self.target.http2(Connection(), 'h2')
        self.assertEqual([3, 1], ended)
        self.assertEqual(['/delay', '/echo'], [record['path'] for record in state.records])

    def test_reset_gated_h2_stream_does_not_abort_connection(self):
        client = H2Connection(config=H2Configuration(client_side=True, header_encoding='utf-8'))
        client.initiate_connection()

        def request(stream_id, path, end_stream=True):
            client.send_headers(stream_id, [(':method', 'GET'), (':scheme', 'https'),
                                           (':authority', 'localhost'), (':path', path)], end_stream=end_stream)

        request(1, '/delay')
        request(3, '/echo')
        initial = client.data_to_send()
        ended = []
        state = self.state

        class Connection:
            reads = 0

            def settimeout(self, timeout):
                pass

            def sendall(self, data):
                ended.extend(event.stream_id for event in client.receive_data(data) if isinstance(event, StreamEnded))

            def recv(self, limit):
                self.reads += 1
                if self.reads == 1:
                    return initial
                if self.reads == 2:
                    if ended != [3]:
                        raise AssertionError(f'expected stream 3 before reset, got {ended}')
                    client.reset_stream(1)
                    # Reset an unfinished upload too, challenging active-stream cleanup.
                    request(5, '/echo', end_stream=False)
                    client.reset_stream(5)
                    request(7, '/echo')
                    state.gate.set()
                    return client.data_to_send()
                return b''

        self.target.http2(Connection(), 'h2')
        self.assertEqual([3, 7], ended)
        self.assertEqual(['/delay', '/echo', '/echo'], [record['path'] for record in state.records])


if __name__ == '__main__':
    unittest.main()
