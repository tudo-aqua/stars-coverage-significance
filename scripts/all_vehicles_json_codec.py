"""
Encode/decode metric_failed_monitors.all_vehicles_json_deflate values.

Python counterpart of the Kotlin `AllVehiclesJsonCompression.kt`. A value is one format-version
byte (1) followed by raw DEFLATE using the preset dictionary below. DICTIONARY must stay
byte-identical to `ALL_VEHICLES_JSON_DICTIONARY` in the Kotlin file (a Kotlin test checks this).

Usage as a library:
    from all_vehicles_json_codec import decompress
    vehicles_json = decompress(row["all_vehicles_json_deflate"])

Usage on the command line, with a value as printed by psql:
    python all_vehicles_json_codec.py '\\x01ab12...'

No dependencies beyond the standard library.
"""

import sys
import zlib

FORMAT_VERSION = 1

DICTIONARY = '"lane":2,"front":"lane":1,"front":"lane":0,"front":{"id":"slow_3","type":"car_calm",{"id":"normal_3","type":"car_normal",{"id":"fast_3","type":"car_speedy",[{"id":"ego","ego":true,"type":"mutant","lane":[{"id":"ego","ego":true,"type":"ego","lane":,"speed":16.666666,"accel":0.0},{"id":"slow_2","type":"car_calm","lane":,"speed":33.333332,"accel":0.0},{"id":"fast_2","type":"car_speedy","lane":,"speed":25.0,"accel":0.0},{"id":"normal_2","type":"car_normal","lane":,"speed":16.666666,"accel":0.0},{"id":"slow_1","type":"car_calm","lane":,"speed":33.333332,"accel":0.0},{"id":"fast_1","type":"car_speedy","lane":,"speed":25.0,"accel":0.0},{"id":"normal_1","type":"car_normal","lane":,"back":,"speed":,"accel":.0,"back":'

_DICTIONARY_BYTES = DICTIONARY.encode("utf-8")


def compress(vehicles_json: str) -> bytes:
    """Compresses a vehicle JSON string into an all_vehicles_json_deflate value."""
    deflater = zlib.compressobj(9, zlib.DEFLATED, -15, zdict=_DICTIONARY_BYTES)
    return bytes([FORMAT_VERSION]) + deflater.compress(vehicles_json.encode("utf-8")) + deflater.flush()


def decompress(value: bytes) -> str:
    """Decompresses an all_vehicles_json_deflate value back into its JSON string."""
    value = bytes(value)  # psycopg2 returns memoryview for bytea
    if not value or value[0] != FORMAT_VERSION:
        raise ValueError("Unknown all_vehicles_json_deflate format version: %r" % value[:1])
    inflater = zlib.decompressobj(-15, zdict=_DICTIONARY_BYTES)
    return (inflater.decompress(value[1:]) + inflater.flush()).decode("utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    text = sys.argv[1].strip()
    if text.startswith("\\x"):
        text = text[2:]
    print(decompress(bytes.fromhex(text)))


if __name__ == "__main__":
    main()
