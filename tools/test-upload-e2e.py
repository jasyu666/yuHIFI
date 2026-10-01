# -*- coding: utf-8 -*-
r"""
hifiprobe-upload.py 的端到端验证。

手机没连上，所以用一个**本机 mock 服务端**接住请求，逐项核对：

  1. 构造出来的树里，含一个**完整路径超过 260 字符**的文件
  2. 用 \\?\ 能读到它的字节，并且和源文件**逐字节相同**
  3. Content-Length 头和正文实际长度**严格相等**（不等服务端会错位/挂住）
  4. dir 字段排在所有文件字段**前面**（服务端的硬要求）
  5. filename 是**原样 UTF-8 字节**（服务端按 UTF-8 解 header 拿目录结构）
  6. 每个文件收到的字节 == 磁盘上的字节

最后按项目规矩报"逐字节比对通过"。
"""
import os
import shutil
import socket
import subprocess
import sys
import threading

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

TOOL = r"C:\Users\123\Desktop\hifiprobe\tools\hifiprobe-upload.py"
BASE = os.path.join(os.environ["TEMP"], "hp_e2e")
SRC = os.path.join(BASE, "src")
TOP = "src"
REL_LONG = None          # 超 260 的那个文件的相对路径


def long_path(p):
    p = os.path.abspath(p)
    if p.startswith("\\\\?\\"):
        return p
    return "\\\\?\\" + p


def build_tree():
    shutil.rmtree(long_path(BASE), ignore_errors=True)
    os.makedirs(long_path(SRC), exist_ok=True)

    # 两个普通文件
    for rel, data in [("a.flac", b"A" * 1000), ("CD1/b.flac", b"B" * 2000)]:
        p = os.path.join(SRC, rel.replace("/", os.sep))
        os.makedirs(long_path(os.path.dirname(p)), exist_ok=True)
        with open(long_path(p), "wb") as f:
            f.write(data)

    # 一条超过 260 字符的路径 —— 就是网页必然失败的那种
    seg = "L" * 55
    cur = SRC
    while len(os.path.join(cur, seg + "x.flac")) < 300:
        cur = os.path.join(cur, seg)
        os.makedirs(long_path(cur), exist_ok=True)
    p = os.path.join(cur, "long.flac")
    with open(long_path(p), "wb") as f:
        f.write(b"L" * 3000)

    global REL_LONG
    # ★ 上传时的相对路径**带顶层文件夹名** —— 和网页拖拽、U 盘导入一个规则
    REL_LONG = TOP + "/" + os.path.relpath(p, SRC).replace(os.sep, "/")
    return len(p)


# 无线传输首页 —— 客户端的"认设备"探针要在这里找到标记和库规模。
#
# ★★ 必须和 WirelessServer.servePage 的**当前**输出对齐，否则闸门会把 mock 也拦掉。
# ★★ 而且**改了服务端输出格式，这里必须同步改**：网页从 <select> 改成单选钮树时，
#    只认 <option> 的 parse_dirs 会返回空列表 —— 不报错，但重名预检静默失效。
#    mock 当时是旧格式，测试全绿而真机是坏的。
PAGE = ("""<!DOCTYPE html><html><head><meta charset=utf-8>
<title>yuHIFI 音乐库</title></head><body>
<h1>yuHIFI 音乐库</h1>
<div class=sub>12 首 · 34 MB</div>
<div class=dtree>
<div class=dt><label><input type=radio name=dir value="" checked>（根目录）</label></div>
<!-- ★ 这里**故意不写 src**：本测试要传的顶层文件夹就叫 src，
     写上它等于"库里已有 src"，重名预检会把这次上传拦住（踩过）。
     重名预检本身由 test-upload-gui.py 覆盖。 -->
<div class=dt><label><input type=radio name=dir value="别的专辑">别的专辑</label></div>
</div>
</body></html>""").encode("utf-8")


class MockServer(threading.Thread):
    """接住原始请求字节，按 docs/06-HTTP接口.md 的契约核对。"""

    def __init__(self):
        super().__init__(daemon=True)
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(4)
        self.port = self.sock.getsockname()[1]
        self.raw = b""
        self.result = {}
        self.probes = []          # 客户端发过的 GET 路径
        self.done = threading.Event()

    def run(self):
        """
        ★ 要接**两个**连接，不是一个：
            ① GET  /        客户端的"认设备"探针（新加的闸门）
            ② POST /upload  真正上传
          只 accept 一次的话，探针会把那一发用掉，后面的上传根本没人接。
        """
        while not self.done.is_set():
            try:
                conn, _ = self.sock.accept()
            except OSError:
                break
            conn.settimeout(30)
            try:
                buf = b""
                while b"\r\n\r\n" not in buf:
                    c = conn.recv(65536)
                    if not c:
                        break
                    buf += c
                head, _, rest = buf.partition(b"\r\n\r\n")
                lines = head.split(b"\r\n")
                method, path = (lines[0].split(b" ") + [b"", b""])[:2]
                headers = {}
                for line in lines[1:]:
                    if b":" in line:
                        k, v = line.split(b":", 1)
                        headers[k.strip().lower().decode()] = v.strip().decode()

                if method == b"GET":
                    self.probes.append(path.decode("latin-1"))
                    conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: "
                                 + str(len(PAGE)).encode("ascii")
                                 + b"\r\nContent-Type: text/html; charset=utf-8\r\n\r\n"
                                 + PAGE)
                    continue

                clen = int(headers.get("content-length", "0"))
                body = rest
                while len(body) < clen:
                    c = conn.recv(1 << 20)
                    if not c:
                        break
                    body += c
                self.raw = body
                self.result["headers"] = headers
                self.result["declared"] = clen
                self.result["actual"] = len(body)
                # ★ Content-Length 必须是**字节数**，不是字符数。
                #   这里原来写死成 7，而「已上传 3 个文件」是 21 字节 ——
                #   客户端严格按声明长度截断，回显就成了半个汉字「已上�」。
                #   （真实服务端 WirelessServer 把整个 body 长度算对了，所以没这个问题；
                #     是测试脚手架自己写错了。）
                reply = "已上传 3 个文件".encode("utf-8")
                conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: "
                             + str(len(reply)).encode("ascii")
                             + b"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n"
                             + reply)
                self.done.set()
            finally:
                conn.close()


def parse_parts(body, boundary):
    """按 boundary 切分，返回 [(headers_bytes, payload_bytes), ...]"""
    b = b"--" + boundary
    chunks = body.split(b)
    out = []
    for ch in chunks[1:]:
        if ch.startswith(b"--"):
            break
        ch = ch[2:] if ch.startswith(b"\r\n") else ch
        if ch.endswith(b"\r\n"):
            ch = ch[:-2]
        if b"\r\n\r\n" not in ch:
            continue
        h, _, payload = ch.partition(b"\r\n\r\n")
        out.append((h, payload))
    return out


def main():
    fails = []
    total_len = build_tree()
    print("测试树建好了。超长文件完整路径 %d 字符" % total_len)
    print("  相对路径: %s" % REL_LONG)
    print("  （网页上传这种文件必然 ERR_FILE_NOT_FOUND）\n")

    srv = MockServer()
    srv.start()

    print("调工具上传到 mock 服务端 127.0.0.1:%d …" % srv.port)
    r = subprocess.run(
        [sys.executable, TOOL, "--host", "127.0.0.1:%d" % srv.port, SRC],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120,
    )
    print(r.stdout)
    if r.returncode != 0:
        print(r.stderr)
        print("!! 工具退出码 %d" % r.returncode)
        return 1

    srv.done.wait(10)
    res = srv.result
    print("=" * 60)

    # --- 核对 1: Content-Length 与实际长度一致 ---
    if res["declared"] == res["actual"]:
        print("[1] Content-Length  =  实际正文长度      : 一致 (%d)" % res["actual"])
    else:
        fails.append("Content-Length 头是 %d，实际收到 %d —— 服务端会错位"
                     % (res["declared"], res["actual"]))
        print("[1] Content-Length  != 实际正文长度      : **不一致**")

    ctype = res["headers"].get("content-type", "")
    boundary = ""
    if "boundary=" in ctype:
        boundary = ctype.split("boundary=", 1)[1].strip().strip('"')
    boundary = boundary.encode()

    parts = parse_parts(srv.raw, boundary)
    print("[2] 解析出 %d 个 part" % len(parts))

    # --- 核对 2: dir 必须排在最前 ---
    first = parts[0][0].decode("utf-8", "replace")
    if 'name="dir"' in first:
        print("[3] 第一个字段是 dir                   : 是（服务端的硬要求）")
    else:
        fails.append("第一个字段不是 dir，服务端拿不到目标目录")
        print("[3] 第一个字段是 dir                   : **不是** —— %s" % first[:80])

    # --- 核对 3: 文件字节逐字节比对 ---
    got = {}
    for h, payload in parts[1:]:
        txt = h.decode("utf-8", "replace")
        if 'name="files"' not in txt:
            continue
        name = txt.split('filename="', 1)[1].rsplit('"', 1)[0]
        got[name] = payload

    print("[4] 收到 %d 个文件" % len(got))
    expect = {}
    for dp, _dn, fn in os.walk(SRC):
        for f in fn:
            p = os.path.join(dp, f)
            rel = TOP + "/" + os.path.relpath(p, SRC).replace(os.sep, "/")
            with open(long_path(p), "rb") as fh:
                expect[rel] = fh.read()

    for rel in sorted(expect):
        if rel not in got:
            fails.append("文件没收到：%s" % rel)
            print("    ✗ %s  没收到" % rel)
        elif got[rel] != expect[rel]:
            fails.append("字节不一致：%s（源 %d，收到 %d）"
                         % (rel, len(expect[rel]), len(got[rel])))
            print("    ✗ %s  字节不一致" % rel)
        else:
            tag = "  ← 路径 %d 字符" % len(os.path.join(SRC, rel.replace("/", os.sep))) \
                if rel == REL_LONG else ""
            print("    ✓ %s  %d 字节  逐字节相同%s" % (rel, len(expect[rel]), tag))

    # --- 核对 4: filename 是原样 UTF-8（中文目录结构靠它） ---
    if REL_LONG.encode("utf-8") in srv.raw:
        print("[5] filename 是原样 UTF-8 字节          : 是")
    else:
        fails.append("filename 不是原样 UTF-8，中文目录结构会变乱码")
        print("[5] filename 是原样 UTF-8 字节          : **不是**")

    # --- 核对 5: 上传之前先认过设备（新加的闸门，别被悄悄绕过去） ---
    if srv.probes[:1] == ["/"]:
        print("[6] 上传前先 GET / 认设备               : 是（%d 次探针）" % len(srv.probes))
    else:
        fails.append("没看到认设备的 GET / —— 闸门被绕过了：%r" % (srv.probes,))
        print("[6] 上传前先 GET / 认设备               : **没有** %r" % (srv.probes,))

    print("=" * 60)
    shutil.rmtree(long_path(BASE), ignore_errors=True)
    if fails:
        print("发现 %d 个问题：" % len(fails))
        for f in fails:
            print("  · %s" % f)
        return 1
    print("全部通过：含 %d 字符超长路径在内的 %d 个文件，逐字节一致。"
          % (total_len, len(expect)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
