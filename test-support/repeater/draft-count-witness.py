"""Count connections to the two synthetic M0 draft destinations without reading payloads."""

import argparse
import json
import selectors
import socket
import time
from contextlib import ExitStack
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="New JSONL evidence file")
    args = parser.parse_args()

    accepts = {18180: 0, 18443: 0}
    with ExitStack() as stack:
        events = stack.enter_context(selectors.DefaultSelector())
        for port in accepts:
            listener = stack.enter_context(socket.socket())
            if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
                listener.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            listener.bind(("127.0.0.1", port))
            listener.listen()
            events.register(listener, selectors.EVENT_READ, port)
        output = stack.enter_context(args.output.open("x", encoding="utf-8", buffering=1))

        def record(event):
            output.write(json.dumps(dict(event=event, timestampNs=time.time_ns(), accepts=accepts)) + "\n")

        record("ready")
        try:
            while True:
                for key, _ in events.select():
                    connection, _ = key.fileobj.accept()
                    with connection:
                        accepts[key.data] += 1
                        record("accept")
        except KeyboardInterrupt:
            record("stopped")


if __name__ == "__main__":
    main()
