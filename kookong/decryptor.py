"""复现 Kookong SDK 的 StreamHelper.dec2 / dec（源自 libkksdk.so 0x4e98c + 0x4ecbc）。

格式：ct = 4 字节头（头即密钥）+ 自研分块变换后的数据
      密钥 = b0 | (b2 << 8) | (b3 << 16) | (b1 << 24)，按有符号 32 位解释
"""

import gzip


def _s32(v: int) -> int:
    v &= 0xFFFFFFFF
    return v - 0x100000000 if v & 0x80000000 else v


def _trunc_div(a: int, b: int) -> int:
    q = abs(a) // abs(b)
    return -q if (a < 0) != (b < 0) else q


def decrypt(ct: bytes) -> bytes:
    """解掉头部的分块变换，返回原始字节（未做 gzip）。"""
    if len(ct) < 5:
        return b""
    key = _s32(ct[0] | (ct[2] << 8) | (ct[3] << 16) | (ct[1] << 24))
    data = ct[4:]
    n = len(data)
    out = bytearray(n)
    pos = 0
    j = 1  # 块长 1..8 循环
    while pos < n:
        blk = min(j, n - pos)
        mask = (1 << (8 * blk)) - 1
        # 块内逆序折叠成整数：最后一个字节在最低位
        x = 0
        for i in range(blk):
            x |= data[pos + blk - 1 - i] << (8 * i)
        k = _trunc_div(key, pos + 1)  # 32 位有符号除法
        s = (key >> (9 - j)) + _s32(key << j)
        y = ((x - pos - k) ^ s) & mask
        for i in range(blk):
            out[pos + i] = (y >> (8 * i)) & 0xFF
        pos += blk
        j = 1 if j > 7 else j + 1
    return bytes(out)


def decode(body: bytes, header: str) -> bytes:
    """header 为响应头标记：'e2' 表示需 gzip，'e' 表示直出，其它视为明文。"""
    plain = decrypt(body)
    if header == "e2":
        return gzip.decompress(plain)
    return plain


if __name__ == "__main__":
    import sys

    raw = open(sys.argv[1], "rb").read()
    mode = sys.argv[3] if len(sys.argv) > 3 else "e2"
    open(sys.argv[2], "wb").write(decode(raw, mode))
