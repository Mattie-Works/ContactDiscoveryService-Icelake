#!/usr/bin/env python3
"""Reference client for explicitly NON-ATTESTED native CDSI (custom clients only).

Requires: pip install 'websockets>=14'
Usage:
  CDSI_AUTH_PASSWORD='external-service-token' python tools/native_cdsi_client.py \
      --url wss://cdsi.example.com/v2/native/discovery \
      --username account-uuid 18005550101 18005550102:0123456789abcdef0123456789abcdef

Never use this with a standard Signal client. WSS server identity MUST be validated.
"""
import argparse
import base64
import json
import os
import sys
from urllib.parse import urlparse

from websockets.sync.client import connect

VERSION = "cdsi-native-v1"
SECURITY_MODEL = "NON_ATTESTED_SERVER_TRUST"


def parse_entry(value: str) -> dict:
    pieces = value.split(":", maxsplit=1)
    if not pieces[0].isdigit():
        raise argparse.ArgumentTypeError("E.164 must contain only digits")
    number = int(pieces[0])
    if number <= 0 or number >= (1 << 63):
        raise argparse.ArgumentTypeError("E.164 is out of range")
    result = {"e164": number}
    if len(pieces) == 2:
        uak = pieces[1]
        if len(uak) != 32 or any(x not in "0123456789abcdefABCDEF" for x in uak):
            raise argparse.ArgumentTypeError("UAK must be exactly 16 hex-encoded bytes")
        result["uak"] = uak
    return result


def run() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--username", required=True)
    parser.add_argument("entries", nargs="+", type=parse_entry)
    args = parser.parse_args()

    if urlparse(args.url).scheme != "wss":
        parser.error("native discovery requires TLS (wss://), even though it is not SGX-attested")
    password = os.environ.get("CDSI_AUTH_PASSWORD")
    if not password:
        parser.error("set CDSI_AUTH_PASSWORD to the external-service credential")
    if len(args.entries) > 1024:
        parser.error("too many entries; default limit is 1024")

    credential = base64.b64encode(
        f"{args.username}:{password}".encode("utf-8")
    ).decode("ascii")
    headers = {"Authorization": f"Basic {credential}"}

    with connect(args.url, additional_headers=headers, max_size=1 << 20) as ws:
        capabilities = json.loads(ws.recv())
        if (
            capabilities.get("type") != "capabilities"
            or capabilities.get("protocol") != VERSION
            or capabilities.get("attested") is not False
            or capabilities.get("security_model") != SECURITY_MODEL
        ):
            raise RuntimeError("server did not explicitly negotiate non-attested native mode")
        ws.send(json.dumps({"type": "accept", "protocol": VERSION, "count": len(args.entries)}))
        token_frame = json.loads(ws.recv())
        token = token_frame.get("token")
        if token_frame.get("type") != "token" or not isinstance(token, str) or len(token) != 64:
            raise RuntimeError("native server did not return a 32-byte token")
        ws.send(json.dumps({"type": "query", "token": token, "entries": args.entries}))
        result = json.loads(ws.recv())
        if result.get("type") != "result" or len(result.get("entries", [])) != len(args.entries):
            raise RuntimeError("invalid or incomplete native discovery result")
        print(json.dumps(result, indent=2))
        return 0


if __name__ == "__main__":
    try:
        sys.exit(run())
    except Exception as exc:
        # Do not print request tokens, UAKs, or complete exception tracebacks.
        print(f"Native CDSI client error: {type(exc).__name__}", file=sys.stderr)
        sys.exit(1)
