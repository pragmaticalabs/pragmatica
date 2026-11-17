"""Mints the token variants local/hurl/auth_tokens.hurl needs. Usage: mint_tokens.py SECRET REAL_TOKEN. Prints name=value lines.

The real token (as issued by the app) supplies the subject; every variant keeps its header and subject. Run with python3 -I."""
import base64
import hashlib
import hmac
import json
import sys
import time


def b64(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def unb64(text):
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def signed(secret, header, claims):
    signing_input = b64(json.dumps(header, separators=(",", ":")).encode()) + "." + b64(json.dumps(claims, separators=(",", ":")).encode())
    mac = hmac.new(secret.encode(), signing_input.encode(), hashlib.sha256).digest()
    return signing_input + "." + b64(mac)


def main(secret, real):
    head, payload, signature = real.split(".")
    sub = json.loads(unb64(payload))["sub"]
    now = int(time.time())
    hs256 = {"alg": "HS256", "typ": "JWT"}
    # The first signature character carries 6 full bits; the last one carries padding bits, so changing it can leave the signature intact.
    forged_signature = ("A" if signature[0] != "A" else "B") + signature[1:]
    variants = {
        "real_token": real,
        "forged_token": head + "." + payload + "." + forged_signature,
        "none_token": b64(json.dumps({"alg": "none", "typ": "JWT"}, separators=(",", ":")).encode()) + "." + payload + ".",
        "expired_token": signed(secret, hs256, {"sub": sub, "iat": now - 7200, "exp": now - 3600}),
        "fresh_token": signed(secret, hs256, {"sub": sub, "iat": now, "exp": now + 3600}),
    }
    for name, value in variants.items():
        print(name + "=" + value)


main(sys.argv[1], sys.argv[2])
