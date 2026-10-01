#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
hifiprobe 命令行上传工具 —— 把电脑上的音乐文件夹传到手机的音乐库。

GUI 版在 hifiprobe-upload-gui.pyw，**共用这个文件里的全部内核**
（walk_audio / preflight / upload / probe），两份界面一套逻辑。

★ 为什么需要它（而不是直接用网页上传）

  ★★ 先纠正一条曾经的错误结论：**260 的瓶颈在 Chrome，不在 Windows。**
     2026-09-24 实测（同一台机器）：

       · 注册表 LongPathsEnabled 早就是 0x1 了 —— **改它没用，别再推荐**
       · Python 用普通 open() 打开 310 字符的路径：成功
       · Chrome 打开 275 字符的路径：ERR_FILE_NOT_FOUND

     所以"改注册表"这条路解决不了网页上传。真正的分界线是
     **谁来打开这个文件**：Chrome 打开 → 它自己设的上限，我们够不着；
     我们自己打开 → 想读多长读多长。

  这个工具自己读文件（顺手用 \\\\?\\ 前缀，于是连注册表都不用管），
  能做网页做不到的三件事：

    1. 上传**之前**逐个试读，把读不到的文件连完整路径和真实原因一起列出来
       （网页那边这些文件会让**整批选择一起失败**，而且不说是哪个）
    2. 不受任何浏览器路径上限影响
    3. 几万个文件流式发，不吃内存

  但它的**主要定位还是"命令行 / 批量 / 不经过浏览器"这条路** ——
  \\\\?\\ 只是顺带的好处，不是它存在的唯一理由。

★ 它和网页走的是**同一个接口**（POST /upload），手机 App 零改动。
  契约见 docs/06-HTTP接口.md。

用法：

    # 先只体检，一个字节都不发（推荐第一次就这么跑）
    python hifiprobe-upload.py --dry-run "D:\\Music\\柏林爱乐"

    # 确认没问题再真传
    python hifiprobe-upload.py --host 192.168.42.129 "D:\\Music\\柏林爱乐"

    # 传到某个子目录下
    python hifiprobe-upload.py --host 192.168.42.129 --dest "古典" "D:\\Music\\柏林爱乐"

    # 不知道手机地址？扫一遍局域网
    python hifiprobe-upload.py --scan
"""

import argparse
import concurrent.futures
import http.client
import os
import re
import socket
import sys
import time
import urllib.parse
import uuid

# Windows 控制台默认 GBK，中文路径会直接抛 UnicodeEncodeError。
# ★ .pyw（pythonw.exe）下 sys.stdout 是 None，hasattr 会挡住，不会崩。
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

# ★★ 这份名单必须和 MusicLibrary 的 `Library.AUDIO_EXT` 一致 ——
#    音乐库扫不到的后缀，传上去 = **文件在手机上但 App 里看不见**。
#    权威说明见 docs/08-音频格式支持.md，改这里之前先读它。
AUDIO_EXT = {
    "flac", "alac", "m4a", "mp3", "aac", "wav", "aif", "aiff",
    "ape", "wv", "ogg", "opus", "dsf", "dff",
    # 2026-09-25：FFmpeg 里 OGG / MATROSKA 解复用本来就是开的，
    # 这两个纯粹是名单漏写 —— 加上就能播，不需要重编。
    "oga", "mka",
    # 2026-09-25：这两个要先重编 FFmpeg（asf demuxer + WMA 解码器）。
    # 已重编完成（third_party/_ff/rebuild_with_wma.sh），.so 已进 jniLibs。
    "wma",
}

# 一看就是音频、但这套构建**放不了**的后缀。
#
# ★ 为什么要单独列出来：`walk_audio` 对不认识的扩展名是**直接跳过**的
#   （封面 .jpg、说明 .txt 本来就该跳）。但跳过 .wma 属于另一种性质 ——
#   用户以为传全了，其实一首没传。这两类必须分开：
#     在 AUDIO_EXT  → 能放，传
#     在 OTHER_AUDIO_EXT → **报出来**（"这个格式音乐库放不了"）
#     两边都不在    → 不是音频，静默跳过
OTHER_AUDIO_EXT = {
    # ★ wma 2026-09-25 已挪进 AUDIO_EXT（重编 FFmpeg 之后），别再放这儿
    "ac3", "dts", "dtshd", "thd", "mlp", "mpc", "tak", "tta",
    "spx", "amr", "ra", "rm", "aifc", "caf", "m4b", "m4p", "shn",
    "ofr", "w64", "mid", "midi", "mod", "xm", "s3m", "it",
}

# 读文件的分块大小（也是进度上报的粒度）
CHUNK = 1 << 20


# ----------------------------------------------------------------------
#  长路径
# ----------------------------------------------------------------------

def long_path(p):
    r"""
    给路径加 \\?\ 前缀，绕开 Windows 的 MAX_PATH 260。

    ★ 这是整个工具的**立身之本**，网页那条路做不到就是因为这一步在 Chrome 手里。

    规则（微软的约定，别自己发挥）：
      · 必须是**绝对的**、用反斜杠的路径 —— 所以先 abspath + 统一分隔符
      · UNC 路径（\\server\share）要写成 \\?\UNC\server\share
      · 前缀之后不能再有 . 或 .. —— abspath 已经规范化掉了

    非 Windows 上原样返回，方便在别的机器上跑测试。
    """
    if os.name != "nt":
        return p
    p = os.path.abspath(p)
    if p.startswith("\\\\?\\"):
        return p
    if p.startswith("\\\\"):
        return "\\\\?\\UNC" + p[1:]
    return "\\\\?\\" + p


def display_path(p):
    # ★ 这里必须是 raw docstring：普通 docstring 里的 `\ ` 是无效转义，
    #   Python 会刷一行 SyntaxWarning（还能跑，但一启动就报错很难看）
    r"""把 \\?\ 前缀去掉再显示 —— 不然报错信息里全是反斜杠，没法看"""
    if p.startswith("\\\\?\\UNC"):
        return "\\\\" + p[8:]
    if p.startswith("\\\\?\\"):
        return p[4:]
    return p


# ----------------------------------------------------------------------
#  本地遍历
# ----------------------------------------------------------------------

class Item:
    __slots__ = ("local", "rel", "size")

    def __init__(self, local, rel, size):
        self.local = local      # 完整本地路径（不带 \\?\）
        self.rel = rel          # 相对源根目录的路径，用 / 分隔 —— 这就是上传时的目录结构
        self.size = size


def walk_audio(root):
    """
    遍历 [root]，返回 (音频文件列表, 读不了的, 看着像音频但放不了的)。

    ★ 用 os.scandir + long_path 而不是 os.walk：
      os.walk 每一层都用普通路径，**一旦某层目录本身超 260 就枚举不进去，
      而且它不吭声** —— 那正是最需要被发现的文件。这里每层都带前缀，
      而且枚举失败会**记进 unreadable**，不静默跳过。

    ★★ rel 从**顶层文件夹名**开始，不是空串。
      被选中的那个文件夹本身也要在库里建出来 —— 网页拖拽是这么做的
      （webkitGetAsEntry 给的 entry 名会进 prefix），U 盘导入也是这么做的
      （见 Library.importTree 的 topName）。三条路必须一个规则，
      否则用户按专辑分好的目录会被拍平在库根。

    两个列表都按路径排序，输出稳定、可复现。
    """
    root_abs = os.path.abspath(root)
    top = os.path.basename(root_abs.rstrip("\\/"))
    files, unreadable, skipped = [], [], []
    stack = [(root_abs, top)]

    while stack:
        dir_path, rel_dir = stack.pop()
        try:
            with os.scandir(long_path(dir_path)) as it:
                entries = sorted(it, key=lambda e: e.name)
        except OSError as e:
            # 目录本身都进不去 —— 记下来，别静默跳过（网页就是栽在静默跳过上）
            unreadable.append((display_path(dir_path), "无法枚举目录：%s" % e))
            continue

        for e in entries:
            local = os.path.join(dir_path, e.name)
            rel = "%s/%s" % (rel_dir, e.name) if rel_dir else e.name
            try:
                is_dir = e.is_dir()
            except OSError as ex:
                unreadable.append((display_path(local), "无法判断类型：%s" % ex))
                continue
            if is_dir:
                stack.append((local, rel))
            else:
                ext = e.name.rsplit(".", 1)[-1].lower() if "." in e.name else ""
                if ext not in AUDIO_EXT:
                    # ★ 分清两类"跳过"：像音频但放不了的**要报**，封面/文本不报
                    if ext in OTHER_AUDIO_EXT:
                        skipped.append((display_path(local), ext))
                    continue
                try:
                    size = e.stat().st_size
                except OSError as ex:
                    unreadable.append((display_path(local), "无法读取文件大小：%s" % ex))
                    continue
                files.append(Item(local, rel, size))

    files.sort(key=lambda i: i.rel)
    unreadable.sort(key=lambda x: x[0])
    skipped.sort(key=lambda x: x[0])
    return files, unreadable, skipped


def collect_inputs(paths):
    """
    [paths] 里每一项可以是**文件夹**或**单个文件** —— 对应网页的 `pickDir` / `pickFiles`。

    ★★ 两条路的相对路径规则**不一样**，必须照抄网页，否则落进库里的结构就变了：

        文件夹   →  `文件夹名/子目录/曲目.flac`    ← 带顶层文件夹名
        单个文件 →  `曲目.flac`                     ← 只有文件名，不额外套一层

    `walk_audio` 的 rel 一直是从顶层文件夹名开始的（那是 pickDir 的规则）；
    单文件这条路是新的，别搞混。

    返回 (files, unreadable, skipped) —— 和 `walk_audio` 同一个形状。

    ★ 去重按**绝对路径**，不是按 rel：两张不同专辑里的 `01.flac` 是 rel 相同、
      本地不同，按 rel 去重会**凭空丢掉一首**。
      但它们在服务端确实会互相覆盖（上传规则是文件名重名直接覆盖），
      所以这种情况会记进 unreadable 报出来。
    """
    files, unreadable, skipped = [], [], []
    seen_local = set()
    by_rel = {}

    for p in paths:
        ap = os.path.abspath(p)
        if os.path.isdir(long_path(p)):
            f, u, s = walk_audio(p)
        elif os.path.isfile(long_path(p)):
            name = os.path.basename(ap)
            ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
            if ext not in AUDIO_EXT:
                if ext in OTHER_AUDIO_EXT:
                    skipped.append((display_path(ap), ext))
                continue
            try:
                size = os.path.getsize(long_path(p))
            except OSError as e:
                unreadable.append((display_path(ap), "无法读取文件大小：%s" % e))
                continue
            f, u, s = [Item(ap, name, size)], [], []
        else:
            unreadable.append((display_path(ap), "找不到这个文件或文件夹"))
            continue

        for it in f:
            if it.local in seen_local:      # 同一个文件夹被选了两遍
                continue
            seen_local.add(it.local)
            files.append(it)
        unreadable += u
        skipped += s

    # 重名检查：rel 撞车 = 服务端后一个覆盖前一个
    for it in files:
        by_rel.setdefault(it.rel, []).append(it)
    for rel, group in sorted(by_rel.items()):
        if len(group) > 1:
            unreadable.append((display_path(group[0].local),
                               "和另外 %d 个文件重名（都叫 %s）—— 上传至手机后只会剩下一个。"
                               "分开传，或者先把它们放进各自的文件夹"
                               % (len(group) - 1, rel.rsplit("/", 1)[-1])))

    files.sort(key=lambda i: i.rel)
    unreadable.sort(key=lambda x: x[0])
    skipped.sort(key=lambda x: x[0])
    return files, unreadable, skipped


def preflight(files, unreadable, progress=None):
    """
    上传**之前**的体检：把每个文件真读一个字节。

    ★ 这一步是网页做不到、而它最有价值的地方。

      读不出来的原因不止路径过长，还有：
        · 文件已被移动 / 重命名 / 删除（选完之后才发生的）
        · 网盘占位文件（OneDrive 那种"仅在线"的，本地没有实体）
        · 权限被拒
        · 文件被独占锁定

      网页那边这四类会以**同一个** ERR_FILE_NOT_FOUND 出现，而且不说是哪个文件。

    progress(done, total) 可选 —— GUI 走这个；不传就往 stdout 画进度（CLI 老行为）。
    返回读得了的列表 + 读不了的列表。
    """
    ok, bad = [], list(unreadable)
    for i, item in enumerate(files, 1):
        if progress is not None:
            if i % 20 == 0 or i == len(files):
                progress(i, len(files))
        elif i % 200 == 0 or i == len(files):
            sys.stdout.write("\r  试读 %d/%d …" % (i, len(files)))
            sys.stdout.flush()
        try:
            with open(long_path(item.local), "rb") as f:
                f.read(1)
            ok.append(item)
        except OSError as e:
            bad.append((item.local, "%s" % e))
    if files and progress is None:
        sys.stdout.write("\r" + " " * 40 + "\r")
    bad.sort(key=lambda x: x[0])
    return ok, bad


# ----------------------------------------------------------------------
#  认设备 / 找设备
# ----------------------------------------------------------------------

# 无线传输页面的标题，用来确认"对面跑的是 yuHIFI"，而不是碰巧占了 8765 的别的东西。
PAGE_MARKER = "yuHIFI 音乐库"

# 首页那行 `${size} 首 · ${MB} MB`。既是身份指纹，也是"别传错库"的兜底。
PAGE_STATS = re.compile(r"(\d+)\s*首\s*·\s*(\d+)\s*MB")

# 只读这么多就够了 —— 标记和统计都在文件树**之前**，不必把整页拉回来
PROBE_MAX = 256 * 1024


class NotYuHifi(Exception):
    """对面有 HTTP 服务，但不是 yuHIFI。"""


def probe(host, port=8765, timeout=4.0):
    """
    GET / 确认对面是 yuHIFI，顺便把库规模读出来。

    ★ 为什么值得单独跑一趟：

      以前是**直接往裸 IP 发 POST**。填错一位数字的话，轻则传到一半才报
      连接失败，重则那台机器上正好有别的 HTTP 服务，**整棵树已经发出去了**。
      先花一次 GET 换"这是谁的库、现在多大"，很便宜。

    返回 "yuHIFI 音乐库 · 1842 首 · 21 MB" 这样的字符串。
    对面不是 yuHIFI → 抛 NotYuHifi；连不上 → 让 OSError 冒上去。
    """
    conn = http.client.HTTPConnection(host, port, timeout=timeout)
    try:
        conn.request("GET", "/")
        r = conn.getresponse()
        body = r.read(PROBE_MAX).decode("utf-8", "replace")
        if r.status != 200:
            raise NotYuHifi("对方回了 HTTP %d" % r.status)
        if PAGE_MARKER not in body:
            raise NotYuHifi("页面里没有「%s」标记" % PAGE_MARKER)
        m = PAGE_STATS.search(body)
        if m:
            return "%s · %s 首 · %s MB" % (PAGE_MARKER, m.group(1), m.group(2))
        return PAGE_MARKER
    finally:
        conn.close()


# 首页「上传到」里那个目录选择器，**两种格式都要认**：
#
#   新版（2026-09-25 起）：折叠目录树 + 单选钮
#     <input type=radio name=dir value="MIREI">
#   旧版：平铺下拉
#     <select name=dir><option value="">（根目录）</option><option value="MIREI">…
#
# ★★ 两种都留着不是"保守"，是**踩过**：改成单选钮之后只认 <option> 的话，
#    解析结果变成空列表 —— 不报错、不崩，只是**重名预检静默失效**，
#    而"目录已存在"正是这个接口最常见的失败。空列表比报错还危险。
# ★ 根目录的 value 是空串，两边都要过滤掉。
DIR_RADIO = re.compile(r'name=dir value="([^"]*)"')
DIR_OPTION = re.compile(r'<option value="([^"]*)"')


def fetch_page(host, port=8765, timeout=8.0, limit=16 << 20):
    """GET / 取**整页**。返回 (状态码, HTML 文本)。"""
    conn = http.client.HTTPConnection(host, port, timeout=timeout)
    try:
        conn.request("GET", "/")
        r = conn.getresponse()
        return r.status, r.read(limit).decode("utf-8", "replace")
    finally:
        conn.close()


def parse_dirs(body):
    """
    从页面里抠出「库里已有的目录」清单（相对库根，用 / 分隔）。

    ★ 数据源就是网页「上传到」那个下拉（`<select name=dir>`，WirelessServer ~L906）。
      整页只有这一个 `<select>`，不会混进别的东西 —— 2026-09-25 在真机上确认过。

    ★ 为什么不自己另开一个 JSON 接口：网页那边已经有一份现成的（网页 JS 的 `HAVE`），
      再开一个就多一处要同步的东西 —— 这个工程已经栽过"三份名单各写各的"了。

    ★ 传上去的相对路径**带顶层文件夹名**，所以这里的值就是能直接填给 `dir` / `newdir`
      前缀用的那种形式。

    ★★ 这一步**必须**有结果。返回空列表意味着重名预检形同虚设，而且不会有任何报错
      —— 调用方至少要把它显示出来（"音乐库中已有 N 个目录"），空的那一眼就能看见。
    """
    dirs = [d for d in DIR_RADIO.findall(body) if d]
    if not dirs:
        dirs = [d for d in DIR_OPTION.findall(body) if d]
    return dirs


# 页面上每一个可删的条目（目录和文件都有）都挂着一个隐藏表单：
#   <form id=dfN method=post action=/delete><input type=hidden name=path value="X"></form>
# ★ 这是**唯一**能同时拿到目录和文件完整相对路径的地方 —— 文件树那些
#   <div class=row> 只有文件名，没有路径。
DELETE_FORM = re.compile(
    r'<form id=\w+ method=post action=/delete>\s*'
    r'<input type=hidden name=path value="([^"]*)">')


def parse_library(body):
    """
    从页面抠出库里**所有条目**。返回 [(path, is_dir), …]，按路径排序。

    ★ 怎么区分目录和文件 —— **两个判据取或**：

      ① 它在 `parse_dirs()` 那份里（「上传到」的单选钮只列目录）
      ② 有别的路径以 `它 + /` 开头（说明它下面还有东西，那它必然是目录）

    ★★ 为什么两个都要：只靠 ① 的话，一旦某个目录没被渲染成单选钮（页面结构变了、
       或者客户端拿到的是一份不完整的页面），它就会被**误判成文件** ——
       而误判的后果是"删它的时候以为在删一个文件"。判据 ② 只依赖路径本身，更硬。
       （2026-09-25：测试 mock 少写了一个单选钮，当场抓到这个误判。）

    2026-09-25 真机核对：1881 个表单 = 203 目录 + 1678 文件，且 dirs <= paths。
    """
    paths = [p for p in DELETE_FORM.findall(body) if p]
    dirs = set(parse_dirs(body))

    def is_dir(p):
        if p in dirs:
            return True
        prefix = p + "/"
        return any(q.startswith(prefix) for q in paths)

    return sorted(((p, is_dir(p)) for p in paths), key=lambda x: x[0])


def _post_form(host, port, action, fields, timeout=30):
    """
    POST 一个 urlencoded 表单，把服务端放在 `Location: /?msg=…` 里的话抠出来。

    ★ `/delete` 和 `/mkdir` 都是 **303 + Location 带消息**（和 `/upload` 不一样，
      后者把消息放响应体）。见 docs/06-HTTP接口.md 1.4 / 1.5。

    返回 (HTTP 状态码, msg 文本)。
    """
    body = urllib.parse.urlencode(fields).encode("utf-8")
    conn = http.client.HTTPConnection(host, port, timeout=timeout)
    try:
        conn.request("POST", action, body=body, headers={
            "Content-Type": "application/x-www-form-urlencoded",
            "Content-Length": str(len(body)),
        })
        r = conn.getresponse()
        r.read()
        loc = r.getheader("Location") or ""
        m = re.search(r"[?&]msg=([^&]*)", loc)
        # ★ 必须用 unquote_plus，不是 unquote：服务端是 Java 的 URLEncoder，
        #   它把**空格编成 `+`**，而 unquote 不还原 `+` —— 消息会变成
        #   「目录「X」已存在+——+换个名字」（实测踩到）。
        return r.status, (urllib.parse.unquote_plus(m.group(1)) if m else "")
    finally:
        conn.close()


def delete_entry(host, port, path, timeout=60):
    """
    删除库里的一个条目（文件，或**递归**删目录）。

    ★★ 破坏性、**不可撤销**。调用方必须先让用户确认，并把完整路径显示出来。

    ★ 服务端还有两道防线（`Paths.inside()` + 库根删不掉），但别指望它兜底。

    返回 (是否成功, 服务端的话)。
    """
    status, msg = _post_form(host, port, "/delete", {"path": path}, timeout)
    if status != 303:
        return False, "服务端回了 HTTP %d（预期 303）" % status
    # ★ 靠文案判断成败，因为契约里服务端只用文案区分（docs/06 1.4）。
    #   成功是「已删除「X」」，失败是「删除失败」。
    return msg.startswith("已删除"), (msg or "（服务端没给消息）")


def mkdir(host, port, name, timeout=30):
    """
    在库根新建一个目录。返回 (是否成功, 服务端的话)。

    ★ 名字重名会被服务端拒（和上传是同一条规则），文案照搬。
    """
    status, msg = _post_form(host, port, "/mkdir", {"name": name}, timeout)
    if status != 303:
        return False, "服务端回了 HTTP %d（预期 303）" % status
    return msg.startswith("已新建"), (msg or "（服务端没给消息）")


def probe_full(host, port=8765, timeout=8.0):
    """
    一次 GET 拿全：**库规模描述 + 已有目录清单**。GUI 用这个，省一趟往返。

    返回 (描述, 目录列表)。对面不是 yuHIFI → 抛 NotYuHifi。

    ★ `probe()` 是给扫局域网用的（只读前 256 KB，快）；这个要整页（目录清单在页面
      靠后，而且文件树占了绝大部分）。两者分工不同，别互相替代。
    """
    status, body = fetch_page(host, port, timeout)
    if status != 200:
        raise NotYuHifi("对方回了 HTTP %d" % status)
    if PAGE_MARKER not in body:
        raise NotYuHifi("页面里没有「%s」标记" % PAGE_MARKER)
    m = PAGE_STATS.search(body)
    desc = ("%s · %s 首 · %s MB" % (PAGE_MARKER, m.group(1), m.group(2))
            if m else PAGE_MARKER)
    return desc, parse_dirs(body)


def local_ipv4s():
    """
    本机所有非回环 IPv4，用来决定扫哪些网段。

    ★ 两个来源都要，缺一不可：

      · UDP connect 探针 —— 不发任何包，只是让内核挑一张网卡，
        拿到的是**默认路由**那张网卡。U 盘网络共享（192.168.42.x）就是靠它。
      · 主机名解析 —— 能拿到其余网卡，但**可能漏掉**刚插上的那张，
        所以不能只用它。
    """
    ips = set()
    for peer in (("223.5.5.5", 53), ("8.8.8.8", 53)):
        s = None
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(peer)
            ips.add(s.getsockname()[0])
        except OSError:
            pass
        finally:
            if s is not None:
                s.close()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    return sorted(ip for ip in ips if not ip.startswith("127."))


def _probe_port(ip, port, timeout):
    """端口通不通。通就返回 ip，不通返回 None。"""
    try:
        with socket.create_connection((ip, port), timeout=timeout):
            return ip
    except OSError:
        return None


def scan_lan(port=8765, timeout=0.35, workers=64, on_note=None):
    """
    扫本机所在各 /24 网段的 8765 端口，再用 probe() 确认是不是 yuHIFI。

    返回 [(host, 描述), …] —— 描述就是 probe() 那串。

    ★ 只扫 8765 端口、只认页面标记。**不会**往任何一台机器发上传请求。

    ★ 254 个地址 × 0.35 秒超时、64 并发 ≈ 两秒。被防火墙丢弃的地址才会
      吃满超时，同一个局域网里绝大多数是立刻 RST，实际更快。
    """
    nets = []
    for ip in local_ipv4s():
        head = ip.rsplit(".", 1)[0]
        if head not in nets:
            nets.append(head)
    if on_note:
        on_note("本机网段：" + ("、".join(n + ".0/24" for n in nets) or "（一个都没有）"))
    if not nets:
        return []

    targets = []
    for head in nets:
        for i in range(1, 255):
            targets.append("%s.%d" % (head, i))

    alive = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as ex:
        for r in ex.map(lambda t: _probe_port(t, port, timeout), targets):
            if r:
                alive.append(r)
        if on_note:
            on_note("扫了 %d 个地址，%d 台开着 %d 端口" % (len(targets), len(alive), port))

        def identify(ip):
            try:
                return ip, probe(ip, port, timeout=2.0)
            except (OSError, NotYuHifi, http.client.HTTPException):
                return ip, None

        found = []
        for ip, desc in ex.map(identify, alive):
            if desc:
                found.append((ip, desc))
                if on_note:
                    on_note("  ✓ %s → %s" % (ip, desc))
    found.sort()
    return found


# ----------------------------------------------------------------------
#  multipart
# ----------------------------------------------------------------------

def multipart_stream(items, boundary, dest_dir, newdir=None):
    """
    流式产出 multipart 正文。返回一个 (生成器, 总字节数)。

    ★ 字段顺序**必须**是 dir / newdir 在前、文件在后。
      服务端要先用它们定下目标目录，后面的文件才知道自己落在哪儿
      （见 docs/06-HTTP接口.md 1.3）。

    ★ `dir` 和 `newdir` **二选一**（照抄网页 JS 的 `submit` 处理）：
        dir    —— 传到**已有**目录（值是相对库根的路径，"" = 库根）
        newdir —— 先**新建**这个目录再传进去（值是一个名字）
      两个都发的话服务端会用 newdir，但别赌，只发一个。

    ★ filename 里带相对路径就是**目录结构的唯一载体** ——
      浏览器那边靠 FormData.append 的第三个参数，这里靠我们自己拼 header。

    ★ header 必须按 **UTF-8** 编码。HTTP 规范里 header 默认是 Latin-1，
      但服务端明确按 UTF-8 解 —— 按 Latin-1 编码的话「新专辑」会变乱码。
    """
    b = boundary.encode("ascii")

    def field(name, value):
        head = ('--%s\r\nContent-Disposition: form-data; name="%s"\r\n\r\n'
                % (boundary, name)).encode("utf-8")
        return head + value.encode("utf-8") + b"\r\n"

    def file_head(rel):
        # 引号和 CR/LF 会把 multipart 的 header 撑破，先换掉。
        # 服务端自己也会把非法字符换成 _，这里只是别让**报文本身**坏掉。
        safe = rel.replace('"', "_").replace("\r", "_").replace("\n", "_")
        return ('--%s\r\nContent-Disposition: form-data; name="files"; filename="%s"\r\n'
                'Content-Type: application/octet-stream\r\n\r\n'
                % (boundary, safe)).encode("utf-8")

    tail = ("--%s--\r\n" % boundary).encode("ascii")

    head = field("newdir", newdir) if newdir else field("dir", dest_dir)

    # 先算总长 —— Content-Length 必须是准的，服务端拿它做进度和边界判断
    total = len(head)
    for it in items:
        total += len(file_head(it.rel)) + it.size + 2      # +2 是每段结尾的 CRLF
    total += len(tail)

    def gen():
        yield head
        for it in items:
            yield file_head(it.rel)
            with open(long_path(it.local), "rb") as f:
                while True:
                    chunk = f.read(CHUNK)
                    if not chunk:
                        break
                    yield chunk
            yield b"\r\n"
        yield tail

    return gen(), total


def find_conflict(items, dest_dir, have, newdir=""):
    """
    上传前的重名预检 —— **照抄网页 JS 的 `conflict()`**（`WirelessServer.kt:1053`）。

    ★ 服务端的规则是"一次请求里不允许新建一个**已存在**的同名目录"
      （`createdDirs`，`WirelessServer.kt:390`）。放到本地先查一遍，
      用户就不用等几百 MB 传完才被告知。
      **服务端仍会再查一遍，那才是权威判据** —— 这里只是提前告知。

    ★ 为什么值得做：这是这个上传接口**最常见的失败**，而且失败代价最大
      —— 整棵树在**一次请求**里发完，被拒就是白传。

    参数：
      items     要传的文件（用它们的 `rel` 判断会新建出哪些目录）
      dest_dir  目标已有目录（相对库根，"" = 库根）
      have      库里已有的目录清单（`probe_full()` 拿到的）
      newdir    非空 = "新建目录"，此时 dest_dir 不参与（和 `dir` 二选一）

    返回冲突说明；没有冲突返回 None。
    """
    haveset = set(have)
    if newdir:
        if newdir in haveset:
            # ★ 措辞**和服务端保持一致** —— 用户看到的应该是同一句话，
            #   不管是客户端预检拦下的还是服务端拒的
            return "目录「%s」已存在。请将其选为「上传到」的目标，或更换名称。" % newdir
        base = newdir
    else:
        base = dest_dir

    # 目标目录当**前缀**，逐个文件沿路径每一层查一遍
    for it in items:
        segs = it.rel.split("/")
        pre = base
        for j in range(len(segs) - 1):          # 最后一段是文件名，不查
            cur = (pre + "/" + segs[j]) if pre else segs[j]
            if cur in haveset:
                return ("目录「%s」已存在。请将其选为「上传到」的目标后重新上传，"
                        "或更换文件夹名称。" % segs[j])
            pre = cur
    return None


class UploadCancelled(Exception):
    """用户在传输过程中按了取消。"""


def upload(host, port, items, dest_dir, timeout, progress=None, cancelled=None,
           newdir=None):
    """
    POST 到 /upload。返回 (是否成功, 服务端的话)。

    progress(sent, total, speed_mbps) 可选 —— GUI 走这个；不传就画 stdout 进度条。
    cancelled() 可选 —— 每块问一次，返回 True 就抛 UploadCancelled。
    newdir 可选 —— 非空就发 `newdir` 字段（新建目录），此时忽略 dest_dir。

    ★ 取消是**硬断**：已经发出去的部分，服务端那边会残留 .part 文件
      （正式文件是收完才改名的，所以库不会坏）。GUI 里要把这点说清楚。
    """
    boundary = "----hifiprobe" + uuid.uuid4().hex
    body, total = multipart_stream(items, boundary, dest_dir, newdir)

    conn = http.client.HTTPConnection(host, port, timeout=timeout)
    try:
        conn.putrequest("POST", "/upload")
        conn.putheader("Content-Type", "multipart/form-data; boundary=%s" % boundary)
        conn.putheader("Content-Length", str(total))
        conn.endheaders()

        # ★ 手动分块 send 而不是 conn.request(body=...)，为的是能报进度。
        #   一次请求发完**整棵树**是硬要求 —— 见文件末尾的说明。
        sent = 0
        started = time.time()
        for chunk in body:
            if cancelled is not None and cancelled():
                raise UploadCancelled()
            conn.send(chunk)
            sent += len(chunk)
            if total:
                speed = sent / max(time.time() - started, 1e-6) / 1048576
                if progress is not None:
                    progress(sent, total, speed)
                else:
                    sys.stdout.write("\r  上传 %3d%%  %6.1f / %.1f MB  (%.1f MB/s)   "
                                     % (sent * 100 // total, sent / 1048576,
                                        total / 1048576, speed))
                    sys.stdout.flush()
        if progress is None:
            sys.stdout.write("\r" + " " * 60 + "\r")

        r = conn.getresponse()
        text = r.read().decode("utf-8", "replace").strip()
        return r.status == 200, text
    finally:
        conn.close()


# ----------------------------------------------------------------------

def human(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return "%.1f %s" % (n, unit) if unit != "B" else "%d B" % n
        n /= 1024.0


def do_scan(port):
    print("扫描局域网里的 yuHIFI（只 GET /，不会发任何上传）…\n")
    found = scan_lan(port, on_note=lambda s: print("  " + s))
    print()
    if not found:
        print("未找到。请检查：")
        print("  ① 手机 App 是否开着，且「设置 → 无线传输」开关是开的")
        print("  ② 手机和电脑是否在同一个网络")
        print("  ③ 手机是不是用 USB 网络共享连的（那样地址通常是 192.168.42.129）")
        return 1
    print("找到 %d 台：" % len(found))
    for ip, desc in found:
        print("  http://%s:%d/    %s" % (ip, port, desc))
    print("\n把这个地址填入 --host 即可。")
    return 0


def main():
    ap = argparse.ArgumentParser(
        description="把电脑上的音乐文件夹上传至手机的音乐库（自己读文件，不经过浏览器）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="接口契约见 docs/06-HTTP接口.md。手机 App 无需任何改动。",
    )
    ap.add_argument("source", nargs="*",
                    help="要上传的本地文件夹或单个文件，可以给多个（--scan 时可以省略）")
    ap.add_argument("--host", help="手机地址，可以是 192.168.42.129 或 192.168.42.129:8765")
    ap.add_argument("--port", type=int, default=8765, help="端口，默认 8765")
    ap.add_argument("--dest", default="", help="上传至音乐库里的哪个子目录（默认：库根）")
    ap.add_argument("--newdir", default="",
                    help="先新建这个目录再传进去（和 --dest 二选一，这个优先）")
    ap.add_argument("--dry-run", action="store_true",
                    help="只体检不上传 —— 验证文件能不能读、路径多长，一个字节都不发")
    ap.add_argument("--timeout", type=int, default=1800, help="单次请求超时秒数，默认 1800")
    ap.add_argument("--scan", action="store_true",
                    help="扫局域网找手机（只 GET / 认设备，不发任何上传）")
    args = ap.parse_args()

    if args.scan:
        return do_scan(args.port)

    if not args.source:
        ap.error("缺少 source（要上传的文件夹或文件）")

    srcs = args.source
    print("来源：")
    for s in srcs:
        if os.path.isdir(long_path(s)):
            kind = "文件夹"
        elif os.path.isfile(long_path(s)):
            kind = "单个文件"
        else:
            kind = "**找不到**"
        print("  [%-8s] %s" % (kind, display_path(os.path.abspath(s))))
    tops = [os.path.basename(os.path.abspath(s).rstrip("\\/"))
            for s in srcs if os.path.isdir(long_path(s))]
    if tops:
        print("顶层文件夹会在库里各建一层（和网页拖拽一个规则）：%s"
              % "、".join("「%s/」" % t for t in tops))
    print()

    print("[1/4] 遍历 …")
    files, unreadable, skipped = collect_inputs(srcs)
    if not files and not unreadable:
        print("  这里没有可识别的音频文件。")
        print("  支持的扩展名：%s" % "、".join(sorted(AUDIO_EXT)))
        return 1

    total_bytes = sum(i.size for i in files)
    longest = max((len(i.local) for i in files), default=0)
    over260 = [i for i in files if len(i.local) >= 260]

    print("  音频文件 %d 个，合计 %s" % (len(files), human(total_bytes)))
    print("  最长本地路径 %d 字符%s"
          % (longest, "   ← 超过 260，网页上传必然失败" if longest >= 260 else ""))

    # ★ "传完了" ≠ "传全了" —— 被跳过的必须报出来，不能静默
    if skipped:
        print("\n  ⚠ 有 %d 个文件疑似音频文件，但音乐库**不支持**，已跳过：" % len(skipped))
        for path, _ext in skipped[:10]:
            print("     · %s" % path)
        if len(skipped) > 10:
            print("     … 另有 %d 个" % (len(skipped) - 10))
        print("     （能放的是：%s）" % "、".join(sorted(AUDIO_EXT)))

    print("\n[2/4] 逐个试读（这是网页做不到的一步）…")
    ok, bad = preflight(files, unreadable)

    if bad:
        print("\n  ⚠ 有 %d 个文件无法读取，**上传前**就能看到：" % len(bad))
        for path, why in bad[:15]:
            mark = "  [%d 字符]" % len(path) if len(path) >= 260 else ""
            print("     · %s%s\n       %s" % (path, mark, why))
        if len(bad) > 15:
            print("     … 另有 %d 个，省略" % (len(bad) - 15))
    else:
        print("  全部可读。")

    if over260:
        print("\n  ★ 其中 %d 个文件路径超过 260 字符 —— 网页上传的话这些**必然**是"
              "  ERR_FILE_NOT_FOUND。\n    这个工具用 \\\\?\\ 前缀读，不受这个限制。"
              % len(over260))

    if args.dry_run:
        print("\n体检完毕（--dry-run，一个字节都没发）。")
        print("去掉 --dry-run 并加上 --host 即可真正上传（真传前会先认设备）。")
        return 0

    if not ok:
        print("\n没有可上传的文件。")
        return 1

    if not args.host:
        print("\n缺少 --host（手机地址）。在 App 的「设置 → 无线传输」里能看到。")
        return 2

    host, _, port_s = args.host.partition(":")
    port = int(port_s) if port_s else args.port

    # 先认设备。填错地址的话，这里就拦住 —— 而不是发到一半才发现。
    # ★ 顺便把**已有目录清单**一起拿回来，做重名预检（和网页 JS 的 conflict() 一个规则）。
    print("\n[3/4] 确认对面是 yuHIFI …")
    try:
        desc, have = probe_full(host, port)
        print("  ✓ %s" % desc)
        print("  音乐库中已有 %d 个目录" % len(have))
    except NotYuHifi as e:
        print("\n  ✗ %s 上确实有 HTTP 服务，但**不是** yuHIFI（%s）。" % (host, e))
        print("    为了不把音乐发到别人的机器上，已中止。检查一下地址。")
        return 2
    except OSError as e:
        print("\n  ✗ 无法连接 %s:%d —— %s" % (host, port, e))
        print("    检查：① 手机和电脑在同一个网络 ② App 里「无线传输」开着 ③ 地址对不对")
        print("    不知道地址的话：python %s --scan" % os.path.basename(__file__))
        return 2

    # 重名预检：和网页一样，冲突就**一个字节都不发**
    if args.newdir:
        print("\n  上传到**新建**目录「%s」" % args.newdir)
    elif args.dest:
        print("\n  上传到已有目录「%s」" % args.dest)
    else:
        print("\n  上传到库根")

    bad_dir = find_conflict(ok, args.dest, have, args.newdir)
    if bad_dir:
        print("\n  ✗ %s" % bad_dir)
        print("    （服务端规则：一次请求里不允许新建已存在的同名目录。）")
        print("    一个字节都没发。把 --dest 指到那个已存在的目录，或者给源文件夹更换名称。")
        return 1

    print("\n[4/4] 上传到 %s:%d …" % (host, port))
    print("  ★ 整棵目录树在**一次请求**里发完。")
    print("    服务端的目录判重是按「请求内新建的目录」算的（WirelessServer.kt:390），")
    print("    拆成多个请求的话，第二个文件就会撞上「目录已存在」。\n")

    try:
        success, msg = upload(host, port, ok, args.dest, args.timeout,
                              newdir=args.newdir)
    except Exception as e:
        print("\n请求失败：%s" % e)
        print("检查：① 手机和电脑在同一个网络 ② App 里「无线传输」开着 ③ 地址和端口对不对")
        return 1

    print("\n服务端回复：%s" % msg)
    if not success:
        print("\n✗ 服务端拒绝了这次上传。上面的原话就是原因 —— 最常见的是")
        print("  「目录已存在」，那就把 --dest 指到那个已存在的目录，或者给源文件夹更换名称。")
        return 1

    print("\n✓ 完成：%d 个文件，%s" % (len(ok), human(total_bytes)))
    if bad:
        print("  有 %d 个文件未参与上传（见上面清单）。" % len(bad))
    return 0


if __name__ == "__main__":
    sys.exit(main())
