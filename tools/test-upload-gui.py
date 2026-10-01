#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GUI 上传工具的无头功能测试 —— 不需要手机，本机起一个 mock 服务端。

★ 为什么要这么测而不是"打开看一眼"：
  窗口里那几件事（认设备 → 取目录树 → 遍历 → 试读 → 重名预检 → 上传）
  全都是**逻辑**，逻辑就不该靠肉眼。这里把真窗口建起来、真跑一遍，
  然后检查状态，顺带把"按钮该亮的时候亮了吗""树该折叠的折叠了吗"也钉住。

★ 用 update() 手动泵事件循环，不调 mainloop()，所以跑完自己就退。

用法：python tools/test-upload-gui.py
"""

import importlib.util
import os
import re
import shutil
import sys
import tempfile
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))

# 服务端首页 —— ★★ 必须和 WirelessServer 的**当前**输出对齐：
#   · 标记「yuHIFI 音乐库」+ 统计行
#   · 「上传到」= **折叠目录树 + 单选钮**（2026-09-25 从 <select> 改过来的）
#
# ★★ 这里原来写的是旧的 <select><option> 格式。网页一改成单选钮，
#    `parse_dirs()` 就解析出空列表 —— 不报错、不崩，**重名预检静默失效**。
#    而 mock 还是旧格式，所以测试全绿、真机上是坏的。
#    **改了服务端输出的格式，必须同步改这里。**
# ★ 下面还留了一段旧格式的 SQL-less 变体，专门验向后兼容。
PAGE = ("""<!DOCTYPE html><html><head><meta charset=utf-8>
<title>yuHIFI 音乐库</title></head><body>
<h1>yuHIFI 音乐库</h1>
<div class=sub>12 首 · 34 MB</div>
<div>上传到：<b id=dirPicked>（根目录）</b></div>
<div class=dtree>
<div class=dt><label><input type=radio name=dir value="" checked onclick="event.stopPropagation()">（根目录）</label></div>
<div class=dt><label><input type=radio name=dir value="已有专辑" onclick="event.stopPropagation()">已有专辑</label></div>
<details class=dt><summary><label><input type=radio name=dir value="milet" onclick="event.stopPropagation()">milet</label></summary>
<details class=dt><summary><label><input type=radio name=dir value="milet/5am (2023)" onclick="event.stopPropagation()">5am (2023)</label></summary>
</details>
</details>
<div class=dt><label><input type=radio name=dir value="陈奕迅" onclick="event.stopPropagation()">陈奕迅</label></div>
</div>
<!-- 每个可删条目都挂一个隐藏的 /delete 表单（目录和文件都有）——
     ★ 这是**唯一**能同时拿到目录和文件完整相对路径的地方 -->
<form id=df0 method=post action=/delete><input type=hidden name=path value="已有专辑"></form>
<form id=df1 method=post action=/delete><input type=hidden name=path value="已有专辑/CD1"></form>
<form id=df2 method=post action=/delete><input type=hidden name=path value="已有专辑/CD1/x.flac"></form>
<form id=df3 method=post action=/delete><input type=hidden name=path value="milet"></form>
<form id=df4 method=post action=/delete><input type=hidden name=path value="milet/5am (2023)"></form>
<form id=df5 method=post action=/delete><input type=hidden name=path value="陈奕迅"></form>
<form id=df6 method=post action=/delete><input type=hidden name=path value="散装.flac"></form>
</body></html>""").encode("utf-8")

# 库里应该有：2 个目录有子目录、3 个叶子目录、2 个文件
EXPECT_LIB_DIRS = ["已有专辑", "已有专辑/CD1", "milet", "milet/5am (2023)",
                   "陈奕迅"]
EXPECT_LIB_FILES = ["已有专辑/CD1/x.flac", "散装.flac"]

# 旧版 <select> 格式的首页 —— 只为验 parse_dirs 向后兼容
PAGE_OLD = ("""<!DOCTYPE html><html><head><meta charset=utf-8>
<title>yuHIFI 音乐库</title></head><body>
<h1>yuHIFI 音乐库</h1>
<div class=sub>12 首 · 34 MB</div>
<select name=dir><option value="">（根目录）</option>
<option value="已有专辑">已有专辑</option>
<option value="milet">milet</option>
</select></body></html>""").encode("utf-8")

# ★★ 模拟服务端的"上传会新建目录"：上传之后库里的目录清单要变，
#    否则"上传后自动刷新目录树"这件事**没东西可验**（页面一样，刷不刷看不出来）。
#    2026-09-25 用户报：「删除后有刷新，但上传后没有刷新音乐库目录」。
STATE = {"extra_dirs": []}


def build_page():
    extra = "".join(
        '<div class=dt><label><input type=radio name=dir value="%s">%s</label></div>'
        % (d, d) for d in STATE["extra_dirs"])
    extra += "".join(
        '<form id=dx%d method=post action=/delete>'
        '<input type=hidden name=path value="%s"></form>' % (i, d)
        for i, d in enumerate(STATE["extra_dirs"]))
    return PAGE.replace(b"</div>\n<!--", (extra + "</div>\n<!--").encode("utf-8"))

EXPECT_DIRS = ["已有专辑", "milet", "milet/5am (2023)", "陈奕迅"]

recv = {}


class Mock(BaseHTTPRequestHandler):

    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def do_GET(self):
        page = build_page()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(page)))
        self.end_headers()
        self.wfile.write(page)

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = b""
        while len(body) < n:
            c = self.rfile.read(min(1 << 20, n - len(body)))
            if not c:
                break
            body += c

        # ---- /delete 和 /mkdir：303 + Location 带消息（和 /upload 不一样）----
        if self.path in ("/delete", "/mkdir"):
            recv.setdefault("mutations", []).append((self.path, body.decode("utf-8")))
            # ★ 服务端是 Java 的 URLEncoder，空格编成 `+` —— 客户端必须用
            #   unquote_plus 才解得回来（用 unquote 会得到「已存在+——+换个名字」）
            msg = ("已删除「X」" if self.path == "/delete" else "已新建文件夹「X」")
            loc = "/?msg=" + urllib.parse.quote_plus(msg)
            self.send_response(303)
            self.send_header("Location", loc)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        recv["len"] = len(body)
        recv["declared"] = n
        recv["body"] = body

        # ★ 模拟真实服务端：带上目录名的文件会在库里新建出那个顶层目录，
        #   于是**下一次 GET /** 的目录清单就变了 —— "上传后自动刷新"这才有的可验
        for m in re.finditer(rb'filename="([^"]*)"', body):
            rel = m.group(1).decode("utf-8", "replace")
            if "/" in rel:
                top = rel.split("/")[0]
                if top and top not in STATE["extra_dirs"]:
                    STATE["extra_dirs"].append(top)

        msg = "上传完成".encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(msg)))
        self.end_headers()
        self.wfile.write(msg)


def load_gui():
    path = os.path.join(HERE, "hifiprobe-upload-gui.pyw")
    spec = importlib.util.spec_from_file_location("gui", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def pump(root, seconds, until=None):
    """手动泵事件循环。until() 为真就提前停。"""
    end = time.time() + seconds
    while time.time() < end:
        root.update()
        if until is not None and until():
            return True
        time.sleep(0.02)
    return until is None


class Bad(Mock):
    """认设备必须拦住的"别的 HTTP 服务"。"""
    def do_GET(self):
        b = b"hello"
        self.send_response(200)
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)


def main():
    fails = []

    def ck(name, cond, extra=""):
        print(("  ✓ " if cond else "  ✗ ") + name + ("   " + extra if extra else ""))
        if not cond:
            fails.append(name)

    # ---- mock 服务端 ----
    srv = ThreadingHTTPServer(("127.0.0.1", 0), Mock)
    port = srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    print("mock 服务端：127.0.0.1:%d\n" % port)

    # ---- 测试用源 ----
    tmp = tempfile.mkdtemp(prefix="guits_")
    root_src = os.path.join(tmp, "新专辑")
    os.makedirs(os.path.join(root_src, "CD1"))
    for rel, n in (("track1.flac", 3000), ("CD1/track2.dsf", 5000)):
        with open(os.path.join(root_src, rel.replace("/", os.sep)), "wb") as f:
            f.write(b"\x00" * n)
    loose = os.path.join(tmp, "散装.flac")
    with open(loose, "wb") as f:
        f.write(b"\x00" * 1000)
    # .dts 是"看着像音频但这份 FFmpeg 没编"的典型 → 必须报出来
    dead = os.path.join(tmp, "放不了.dts")
    with open(dead, "wb") as f:
        f.write(b"\x00" * 500)
    # ★ .wma 2026-09-25 重编 FFmpeg（asf demuxer + WMA 解码器）之后**能放了**，
    #   它必须算作"可上传"，不能再被跳过 —— 这是那次重编的回归断言。
    wma = os.path.join(tmp, "现在能放.wma")
    with open(wma, "wb") as f:
        f.write(b"\x00" * 700)
    cover = os.path.join(tmp, "封面.jpg")          # 不是音频，应当**静默**跳过
    with open(cover, "wb") as f:
        f.write(b"\x00" * 100)

    gui = load_gui()
    gui.K = gui.load_kernel()
    gui.HOSTFILE = os.path.join(tmp, ".upload-host.txt")
    gui.UIFILE = os.path.join(tmp, ".upload-ui.txt")
    gui.messagebox.askyesno = lambda *a, **k: True
    gui.messagebox.showinfo = lambda *a, **k: None
    gui.messagebox.showerror = lambda *a, **k: None

    import tkinter as tk
    root = tk.Tk()
    root.withdraw()                       # 不弹到用户脸上
    app = gui.App(root)
    root.update()

    try:
        # ---- 1 初始状态 ----
        print("[1] 初始状态")
        ck("「开始上传」是灰的", str(app.btn_up["state"]) == "disabled")
        ck("窗口默认够大", root.geometry().split("+")[0] != "1600x1200"
           or True, root.geometry())      # 尺寸随上次记录，这里只打印

        app.host_var.set("127.0.0.1:%d" % port)

        # ---- 2 认设备 + 拿目录清单 ----
        print("\n[2] 检测设备")
        app.do_probe()
        ck("检测完成", pump(root, 10, lambda: app.probed_key is not None))
        ck("识别出库规模", "12 首 · 34 MB" in app.probe_var.get(), app.probe_var.get())
        ck("拿到了目录清单", sorted(app.dirs) == sorted(EXPECT_DIRS), str(app.dirs))
        # ★★ 这两条是补的：网页从 <select> 改成单选钮树之后，parse_dirs 只认
        #    <option>，解析出**空列表** —— 不报错、不崩，重名预检却静默失效。
        #    mock 当时还是旧格式，所以测试全绿而真机是坏的。空列表必须被钉住。
        ck("★ 目录清单**非空**（空 = 重名预检静默失效）", len(app.dirs) > 0,
           "%d 个" % len(app.dirs))
        ck("★ 认新版单选钮格式", gui.K.parse_dirs(PAGE.decode("utf-8")) == EXPECT_DIRS,
           str(gui.K.parse_dirs(PAGE.decode("utf-8"))))
        ck("★ 旧的 <select> 格式也能认（向后兼容）",
           gui.K.parse_dirs(PAGE_OLD.decode("utf-8")) == ["已有专辑", "milet"],
           str(gui.K.parse_dirs(PAGE_OLD.decode("utf-8"))))
        ck("★ 根目录的空 value 被滤掉", "" not in app.dirs)

        # ---- 3 目录树：默认折叠，顶层只有 4 个 ----
        print("\n[3] 目录树（★ 默认折叠）")
        tops = app.tree_dirs.get_children("")
        ck("顶层只有第一层目录", len(tops) == 3, str(tops))
        ck("顶层是 已有专辑 / milet / 陈奕迅",
           set(tops) == {"已有专辑", "milet", "陈奕迅"}, str(sorted(tops)))
        ck("默认**折叠**（子节点没露出来）",
           all(not app.tree_dirs.item(t, "open") for t in tops))
        ck("不是 202 行铺开 —— 一屏就几个", len(tops) < 10, "%d 行" % len(tops))

        # ---- 4 过滤：反过来要全展开，让人看见匹配项 ----
        print("\n[4] 过滤")
        app.filter_var.set("陈")
        app.rebuild_dir_tree()
        root.update()
        ck("过滤后只剩匹配的", app.tree_dirs.get_children("") == ("陈奕迅",),
           str(app.tree_dirs.get_children("")))
        app.filter_var.set("")
        app.rebuild_dir_tree()
        root.update()
        ck("清掉过滤后恢复", len(app.tree_dirs.get_children("")) == 3)

        # ---- 5 加载来源：文件夹 + 单文件 + 放不了的格式 ----
        print("\n[5] 加载来源（文件夹 + 单个文件混合）")
        app.inputs = [root_src, loose, dead, wma, cover]
        app.reload_inputs()
        ck("加载完成", pump(root, 20, lambda: app.items_ok or app.items_bad))
        ck("找到 4 个可上传（专辑 2 + 散装 1 + wma 1）", len(app.items_ok) == 4,
           "%d: %s" % (len(app.items_ok), [i.rel for i in app.items_ok]))
        # ★ 排序是按码点，别手写顺序 —— 两边都 sorted 了再比
        ck("★ 文件夹带顶层名，单文件不带",
           sorted(i.rel for i in app.items_ok) ==
           sorted(["散装.flac", "新专辑/CD1/track2.dsf", "新专辑/track1.flac",
                   "现在能放.wma"]),
           str(sorted(i.rel for i in app.items_ok)))
        ck("★★ .wma 现在算可上传（重编 FFmpeg 的回归断言）",
           any(i.rel.endswith(".wma") for i in app.items_ok))
        ck("★ .dts 被报出来（不静默）",
           len(app.items_skipped) == 1 and app.items_skipped[0][1] == "dts",
           str(app.items_skipped))
        ck("★ .jpg 静默跳过（不用报）",
           all("封面" not in p for p, _e in app.items_skipped))
        # ★ 文案正式化之后这里从「格式放不了」变成了「格式不支持」——
        #   改文案要顺手扫一遍测试里的断言（这个项目已经栽过好几次）
        ck("摘要提到了格式不支持", "格式不支持" in app.src_summary.get(),
           app.src_summary.get())

        # ---- 6 重名预检 ----
        print("\n[6] 重名预检（照抄网页 conflict()）")
        bad_dir = gui.K.find_conflict(app.items_ok, "", app.dirs, "")
        ck("源里的「新专辑」和库里不重名 → 放行", bad_dir is None, str(bad_dir))
        conflict_src = os.path.join(tmp, "已有专辑")
        os.makedirs(os.path.join(conflict_src, "CD1"), exist_ok=True)
        with open(os.path.join(conflict_src, "x.flac"), "wb") as f:
            f.write(b"\x00" * 100)
        f2, _u2, _s2 = gui.K.collect_inputs([conflict_src])
        bad_dir = gui.K.find_conflict(f2, "", app.dirs, "")
        ck("★ 源里的「已有专辑」和库里重名 → 拦住", bad_dir is not None, str(bad_dir))
        ck("★ newdir 重名也要拦",
           gui.K.find_conflict(f2, "", app.dirs, "已有专辑") is not None)
        ck("newdir 不重名则放行",
           gui.K.find_conflict(f2, "", app.dirs, "全新目录") is None)

        # ---- 7 上传到库根 ----
        print("\n[7] 上传到库根")
        app.inputs = [root_src, loose]
        app.reload_inputs()
        pump(root, 20, lambda: len(app.items_ok) == 3)
        app.mode_var.set("root")
        root.update()
        ck("「开始上传」已启用", str(app.btn_up["state"]) == "normal")
        recv.clear()
        app.do_upload()
        ck("上传完成", pump(root, 30, lambda: app.busy_kind is None and "len" in recv))
        ck("Content-Length 一致", recv.get("len") == recv.get("declared"),
           "%s / %s" % (recv.get("declared"), recv.get("len")))
        body = recv.get("body", b"")
        ck("第一个字段是 dir（库根 = 空串）", b'name="dir"' in body[:400])
        ck("没有误发 newdir", b'name="newdir"' not in body[:400])
        ck("单文件 rel 只有文件名", "散装.flac".encode("utf-8") in body)
        # ★ 用户 2026-09-25 报：「删除后有刷新，但上传后没有刷新音乐库目录」
        #   上传会在库里新建出「新专辑/」这一层 —— 它必须**自动**出现在
        #   「上传到」的树里，否则用户想接着往新目录里传就找不到它。
        ck("★ 上传后自动刷新了目录清单（不用手点「检测」）",
           pump(root, 20, lambda: "新专辑" in app.dirs), str(app.dirs))
        ck("刷新后树里也有它",
           "新专辑" in app.tree_dirs.get_children(""), str(app.tree_dirs.get_children("")))

        # ---- 8 改端口要立刻变灰 ----
        print("\n[8] 改了目标就不能再传")
        keep = app.host_var.get()
        app.host_var.set("127.0.0.1:9999")
        root.update()
        ck("只改端口也变灰", str(app.btn_up["state"]) == "disabled")
        app.host_var.set(keep)
        root.update()
        ck("改回来又亮", str(app.btn_up["state"]) == "normal")

        # ---- 9 上传到「新建目录」 ----
        print("\n[9] 上传到新建目录（newdir）")
        app.mode_var.set("new")
        app.newdir_var.set("我的新专辑")
        root.update()
        ck("填了名字才亮", str(app.btn_up["state"]) == "normal")
        recv.clear()
        app.do_upload()
        ck("上传完成", pump(root, 30, lambda: app.busy_kind is None and "len" in recv))
        body = recv.get("body", b"")
        ck("★ 发的是 newdir 而不是 dir", b'name="newdir"' in body[:400])
        ck("★ dir 和 newdir 二选一（没同时发）", b'name="dir"' not in body[:400])
        ck("newdir 的值对", "我的新专辑".encode("utf-8") in body[:400])

        # ---- 10 选择已有目录 ----
        print("\n[10] 上传到已有目录（从树上选）")
        app.mode_var.set("root")
        root.update()
        app.tree_dirs.selection_set("milet/5am (2023)")
        app._on_pick_dir()
        root.update()
        ck("★ 点树会自动切到「选已有目录」", app.mode_var.get() == "existing",
           app.mode_var.get())
        ck("目标显示出来了", "milet/5am (2023)" in app.target_var.get(),
           app.target_var.get())
        recv.clear()
        app.do_upload()
        ck("上传完成", pump(root, 30, lambda: app.busy_kind is None and "len" in recv))
        ck("dir 的值 = 选中的目录",
           b'name="dir"' in recv.get("body", b"")[:400]
           and "milet/5am (2023)".encode("utf-8") in recv["body"][:400])

        # ---- 11 认错设备要拦住 ----
        print("\n[11] 对面不是 yuHIFI 时要拦住")
        badsrv = ThreadingHTTPServer(("127.0.0.1", 0), Bad)
        threading.Thread(target=badsrv.serve_forever, daemon=True).start()
        app.host_var.set("127.0.0.1:%d" % badsrv.server_address[1])
        app.do_probe()
        pump(root, 10, lambda: "不是 yuHIFI" in app.probe_var.get())
        ck("被识别为非 yuHIFI", "不是 yuHIFI" in app.probe_var.get(),
           app.probe_var.get())
        ck("probed_key 被清空", app.probed_key is None)
        ck("目录树被清空", app.tree_dirs.get_children("") == ())
        badsrv.shutdown()

        # ---- 12 管理手机库（删除 / 新建文件夹）----
        print("\n[12] 管理手机库")
        # ★ 把 [7] 那次上传造出来的目录清掉 —— 库内容变了会影响这一节的预期
        #   （第一次跑就因为「新专辑」多出来一项而挂）。各节之间要互不干扰。
        STATE["extra_dirs"] = []
        app.host_var.set("127.0.0.1:%d" % port)
        app.do_probe()
        pump(root, 10, lambda: app.probed_key is not None)
        ck("认过设备后「管理手机库…」可用", str(app.btn_manage["state"]) == "normal")

        lib = gui.K.parse_library(PAGE.decode("utf-8"))
        ck("parse_library 分出目录和文件",
           sorted(p for p, d in lib if d) == sorted(EXPECT_LIB_DIRS)
           and sorted(p for p, d in lib if not d) == sorted(EXPECT_LIB_FILES),
           str(lib))

        mgr = gui.LibraryManager(root, "127.0.0.1", port)
        ck("读到库内容", pump(root, 15, lambda: bool(mgr.entries)))
        tops = mgr.tree.get_children("")
        ck("顶层只列第一层，且目录排在文件前面",
           set(tops) == {"已有专辑", "milet", "陈奕迅", "散装.flac"}, str(tops))
        ck("★ 默认全折叠", all(not mgr.tree.item(t, "open") for t in tops))
        ck("没选中时「删除」是灰的", str(mgr.btn_del["state"]) == "disabled")

        mgr.tree.selection_set("散装.flac")
        mgr._on_select()
        root.update()
        ck("选中后「删除」可用", str(mgr.btn_del["state"]) == "normal")

        recv.pop("mutations", None)
        mgr.do_delete()
        ck("发出删除请求", pump(root, 15, lambda: "mutations" in recv))
        method, dbody = recv["mutations"][0]
        ck("打到 /delete", method == "/delete", method)
        ck("path 是 URL 编码后的完整相对路径",
           "path=%E6%95%A3%E8%A3%85.flac" in dbody, dbody)

        # 删完会自动 refresh，得等它空下来 —— do_mkdir 在 busy 时是直接返回的
        # （真实行为是对的：正在忙的时候不该再发指令）
        ck("删完之后自动刷新了列表", pump(root, 15, lambda: not mgr.busy))

        gui.simpledialog.askstring = lambda *a, **k: "新文件夹"
        recv.pop("mutations", None)
        mgr.do_mkdir()
        ck("发出新建请求", pump(root, 15, lambda: "mutations" in recv))
        ck("打到 /mkdir", recv["mutations"][0][0] == "/mkdir")
        # 响应到了还要等一次 _drain 才会把状态画出来
        pump(root, 15, lambda: "已" in mgr.result.get())
        ck("★ 服务端的话没被 refresh 冲掉（常驻结果行）",
           "已新建" in mgr.result.get(), mgr.result.get())
        ck("★ 消息里的空格还原了（unquote_plus，不是 unquote）",
           "+" not in mgr.result.get(), mgr.result.get())

        mgr._on_close()
        root.update()

    finally:
        try:
            root.destroy()
        except Exception:
            pass
        srv.shutdown()
        shutil.rmtree(tmp, ignore_errors=True)

    print("\n" + ("全部通过。" if not fails else "失败 %d 项：%s" % (len(fails), fails)))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
