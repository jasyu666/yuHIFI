#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
hifiprobe 上传工具 —— 图形界面版。

双击就能跑：.pyw 关联 pythonw.exe，**不会弹控制台黑框**。

★ 和命令行版共用同一个内核（hifiprobe-upload.py），逻辑一行都没重写：
    collect_inputs  遍历（文件夹 / 单个文件混合，rel 规则照抄网页）
    preflight       上传前逐个试读
    probe_full      GET / 认设备 + 拿**已有目录清单**
    scan_lan        扫局域网找手机
    find_conflict   重名逐层预检（照抄网页 JS 的 conflict()）
    upload          流式 POST /upload（dir / newdir 二选一）
  这个文件只负责"把内核的事件画到窗口上"。

★ 为什么不是网页：网页上传时**打开本地文件的是 Chrome**，它自己设的上限
  我们够不着，而且读不到的文件会让整批选择一起失败、还不说是哪个。
  这里由我们自己读文件，所以能先体检、能指名道姓地报错。

★ 对齐网页的功能（2026-09-25 用户要求"参考原来 JS 的功能需求"）：
    选文件夹 / 选单个文件 / 拖多个进 argv      ← pickDir / pickFiles / 拖放
    上传到：已有目录（**折叠树**）或新建目录     ← select[name=dir] / input[name=newdir]
    上传前重名预检，冲突则一个字节不发          ← conflict()
    读不到的文件、放不了的格式都要报出来        ← pickErrors
"""

import importlib.util
import io
import os
import queue
import re
import sys
import threading
import time
import tkinter as tk
import tkinter.font as tkfont
from tkinter import filedialog, messagebox, simpledialog, ttk

HERE = os.path.dirname(os.path.abspath(__file__))
HOSTFILE = os.path.join(HERE, ".upload-host.txt")
UIFILE = os.path.join(HERE, ".upload-ui.txt")

# ★ pythonw.exe 下 sys.stdout / stderr 是 None，内核里虽然都做了保护，
#   这里再兜一层 —— 免得将来谁加了一句 print 就整个窗口起不来。
if sys.stdout is None:
    sys.stdout = io.StringIO()
if sys.stderr is None:
    sys.stderr = io.StringIO()

# 内核在 main() 里加载，见下面 —— 放模块级的话，加载失败就是一个
# **没有窗口、没有报错**的静默死亡（.pyw 连控制台都没有），最难查。
K = None

# 首次打开的窗口大小。★ 行高调高之后内容要 1165 px，原来 1200 只剩 35 px 余量，
# 太紧（字体渲染差一点就挤到日志区）—— 抬到 1280 留出余量。
# 用户屏幕 2880×1800（可用高 1706），放得下。改过大小会记在 .upload-ui.txt 里。
DEFAULT_GEOM = "1600x1280"
GEOM_RE = re.compile(r"^\d+x\d+([+-]-?\d+[+-]-?\d+)?$")


def load_kernel():
    """载入 hifiprobe-upload.py。文件名带连字符，不能直接 import。"""
    path = os.path.join(HERE, "hifiprobe-upload.py")
    if not os.path.exists(path):
        raise IOError("找不到内核：%s" % path)
    spec = importlib.util.spec_from_file_location("hifiprobe_upload", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def fatal(title, text):
    """.pyw 没有控制台 —— 出不来窗口的时候，至少弹一个框说清楚。"""
    try:
        r = tk.Tk()
        r.withdraw()
        messagebox.showerror(title, text, parent=r)
        r.destroy()
    except Exception:
        pass


def human(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return "%.1f %s" % (n, unit) if unit != "B" else "%d B" % n
        n /= 1024.0


def hms(sec):
    sec = int(max(sec, 0))
    if sec < 60:
        return "%d 秒" % sec
    if sec < 3600:
        return "%d 分 %d 秒" % (sec // 60, sec % 60)
    return "%d 时 %d 分" % (sec // 3600, (sec % 3600) // 60)


def nest(paths):
    """把扁平的 ["a", "a/b", "a/b/c"] 变成嵌套 dict —— 用来画目录树。"""
    root = {}
    for p in paths:
        node = root
        for seg in p.split("/"):
            if seg:
                node = node.setdefault(seg, {})
    return root


class App:

    def __init__(self, root):
        self.root = root
        self.q = queue.Queue()
        self.busy_kind = None          # None / "scan" / "probe" / "load" / "upload"
        self.cancel_flag = threading.Event()
        self.inputs = []               # 用户选的本地路径（文件夹或文件），用于重新加载
        self.items_ok = []
        self.items_bad = []
        self.items_skipped = []
        self.loaded_key = None         # 上面三个列表对应的 inputs 指纹
        self.dirs = []                 # 手机库里已有的目录（相对库根）
        self.up_started = 0.0
        # ★ 认过设备的**完整** host:port。只记 IP 的话，把端口一改
        #   （8765 → 9999）按钮还是亮的 —— 等于让一个没验过的目标直接开传。
        self.probed_key = None

        root.title("上传音乐到手机 —— yuHIFI")
        root.minsize(900, 680)
        root.protocol("WM_DELETE_WINDOW", self._on_close)

        self.host_var = tk.StringVar(value=self._load_host())
        self.mode_var = tk.StringVar(value="root")     # root / existing / new
        self.newdir_var = tk.StringVar(value="")
        self.filter_var = tk.StringVar(value="")
        self.probe_var = tk.StringVar(value="尚未检测")
        self.src_summary = tk.StringVar(value="尚未选择内容。可以拖文件夹到这个 .pyw 上，"
                                             "或用下面的按钮。")
        self.target_var = tk.StringVar(value="目标：（音乐库根目录）")
        self.status_var = tk.StringVar(value="先确认目标设备，再选要传的东西。")

        self._build()
        self._apply_geom()
        root.after(80, self._drain)

        argv = [a for a in sys.argv[1:] if a.strip()]
        if argv:
            self.inputs = [os.path.abspath(a) for a in argv]
            self.reload_inputs()

    # ------------------------------------------------------------------
    #  窗口
    # ------------------------------------------------------------------

    def _build(self):
        pad = {"padx": 10, "pady": 5}
        root = self.root
        root.columnconfigure(0, weight=1)
        root.rowconfigure(3, weight=3)      # 文件清单
        root.rowconfigure(6, weight=2)      # 日志

        # ---- 1 目标设备 ----
        dev = ttk.LabelFrame(root, text=" 1  目标设备 ")
        dev.grid(row=0, column=0, sticky="ew", **pad)
        dev.columnconfigure(1, weight=1)

        ttk.Label(dev, text="地址").grid(row=0, column=0, sticky="w", padx=(10, 6), pady=7)
        ent = ttk.Entry(dev, textvariable=self.host_var)
        ent.grid(row=0, column=1, sticky="ew", pady=7)
        ent.bind("<Return>", lambda e: self.do_probe())
        ttk.Button(dev, text="检测", width=8, command=self.do_probe).grid(
            row=0, column=2, padx=4, pady=7)
        self.btn_scan = ttk.Button(dev, text="扫描局域网", width=12, command=self.do_scan)
        self.btn_scan.grid(row=0, column=3, padx=(4, 10), pady=7)

        self.lbl_probe = ttk.Label(dev, textvariable=self.probe_var, foreground="#777")
        self.lbl_probe.grid(row=1, column=0, columnspan=4, sticky="w", padx=12, pady=(0, 9))

        # ---- 2 要传什么 ----
        src = ttk.LabelFrame(root, text=" 2  要传什么 ")
        src.grid(row=1, column=0, sticky="ew", **pad)

        ttk.Button(src, text="添加文件夹…", width=13,
                   command=self.add_folders).grid(row=0, column=0, padx=(10, 4), pady=7)
        ttk.Button(src, text="添加文件…", width=11,
                   command=self.add_files).grid(row=0, column=1, padx=4, pady=7)
        ttk.Button(src, text="清空", width=7,
                   command=self.clear_inputs).grid(row=0, column=2, padx=(4, 10), pady=7)
        ttk.Label(src, textvariable=self.src_summary, foreground="#777").grid(
            row=0, column=3, sticky="w", padx=(8, 10), pady=7)

        # ---- 3 上传到 ----
        dst = ttk.LabelFrame(root, text=" 3  上传到 ")
        dst.grid(row=2, column=0, sticky="ew", **pad)
        dst.columnconfigure(1, weight=1)
        dst.rowconfigure(2, weight=1)

        ttk.Radiobutton(dst, text="音乐库根目录", value="root", variable=self.mode_var,
                        command=self._on_mode).grid(row=0, column=0, sticky="w",
                                                    padx=10, pady=(7, 2))
        ttk.Radiobutton(dst, text="选已有目录", value="existing", variable=self.mode_var,
                        command=self._on_mode).grid(row=1, column=0, sticky="w", padx=10)
        ttk.Label(dst, text="过滤").grid(row=1, column=1, sticky="w", padx=(20, 4))
        # 变了就重建树 —— 走 filter_var 的 trace，见文件末尾那组 trace_add
        ttk.Entry(dst, textvariable=self.filter_var, width=18).grid(
            row=1, column=1, sticky="w", padx=(58, 4))

        holder = ttk.Frame(dst)
        holder.grid(row=2, column=0, columnspan=2, sticky="ew", padx=(30, 10), pady=(3, 6))
        holder.columnconfigure(0, weight=1)
        self.tree_dirs = ttk.Treeview(holder, height=7, selectmode="browse",
                                      show="tree")
        self.tree_dirs.grid(row=0, column=0, sticky="ew")
        dsb = ttk.Scrollbar(holder, orient="vertical", command=self.tree_dirs.yview)
        dsb.grid(row=0, column=1, sticky="ns")
        self.tree_dirs.configure(yscrollcommand=dsb.set)
        self.tree_dirs.bind("<<TreeviewSelect>>", self._on_pick_dir)

        ttk.Radiobutton(dst, text="新建目录", value="new", variable=self.mode_var,
                        command=self._on_mode).grid(row=3, column=0, sticky="w",
                                                    padx=10, pady=(0, 9))
        ttk.Entry(dst, textvariable=self.newdir_var, width=30).grid(
            row=3, column=1, sticky="w", padx=(20, 10), pady=(0, 9))

        manage = ttk.Frame(dst)
        manage.grid(row=4, column=0, columnspan=2, sticky="w", padx=12, pady=(0, 9))
        ttk.Label(manage, textvariable=self.target_var).grid(row=0, column=0, sticky="w")
        self.btn_manage = ttk.Button(manage, text="管理手机库…", width=14,
                                     state="disabled", command=self.open_manager)
        self.btn_manage.grid(row=0, column=1, padx=(14, 0))

        # ---- 4 文件清单 ----
        files = ttk.LabelFrame(root, text=" 4  将上传的内容 ")
        files.grid(row=3, column=0, sticky="nsew", **pad)
        files.columnconfigure(0, weight=1)
        files.rowconfigure(0, weight=1)

        self.tree = ttk.Treeview(files, columns=("size",), height=8, selectmode="browse")
        self.tree.heading("#0", text="相对路径（在库里就是这个结构）", anchor="w")
        self.tree.heading("size", text="大小", anchor="e")
        self.tree.column("size", width=100, anchor="e", stretch=False)
        self.tree.grid(row=0, column=0, sticky="nsew", padx=(10, 0), pady=(7, 4))
        tsb = ttk.Scrollbar(files, orient="vertical", command=self.tree.yview)
        tsb.grid(row=0, column=1, sticky="ns", pady=(7, 4), padx=(0, 10))
        self.tree.configure(yscrollcommand=tsb.set)
        self.tree.tag_configure("bad", foreground="#c0392b")
        self.tree.tag_configure("dim", foreground="#999")

        # ---- 进度 ----
        self.prog = ttk.Progressbar(root, mode="determinate", maximum=1000)
        self.prog.grid(row=4, column=0, sticky="ew", padx=10, pady=(2, 2))
        ttk.Label(root, textvariable=self.status_var).grid(
            row=5, column=0, sticky="w", padx=12, pady=(0, 3))

        # ---- 日志 ----
        logf = ttk.LabelFrame(root, text=" 日志 ")
        logf.grid(row=6, column=0, sticky="nsew", **pad)
        logf.columnconfigure(0, weight=1)
        logf.rowconfigure(0, weight=1)
        self.log = tk.Text(logf, height=7, wrap="word", state="disabled",
                           background="#f7f7f7", relief="flat")
        self.log.grid(row=0, column=0, sticky="nsew", padx=(10, 0), pady=(7, 4))
        lsb = ttk.Scrollbar(logf, orient="vertical", command=self.log.yview)
        lsb.grid(row=0, column=1, sticky="ns", pady=(7, 4), padx=(0, 10))
        self.log.configure(yscrollcommand=lsb.set)
        self.log.tag_configure("bad", foreground="#c0392b")
        self.log.tag_configure("ok", foreground="#1e8449")
        self.log.tag_configure("dim", foreground="#888")

        # ---- 按钮 ----
        bar = ttk.Frame(root)
        bar.grid(row=7, column=0, sticky="e", padx=10, pady=(0, 10))
        self.btn_cancel = ttk.Button(bar, text="取消", width=10, state="disabled",
                                     command=self.do_cancel)
        self.btn_cancel.grid(row=0, column=0, padx=(0, 8))
        self.btn_up = ttk.Button(bar, text="开始上传", width=14, state="disabled",
                                 command=self.do_upload)
        self.btn_up.grid(row=0, column=1)

        self.log_line("先在手机 App 里打开「设置 → 无线传输」，然后把页面上那串地址填到上面，")
        self.log_line("或者点「扫描局域网」自动找。之后：选要传的东西 → 选上传目标 → 开始上传。")

        # ★ 这些变量一变，按钮状态必须立刻跟着变，否则会出现
        #   "输入框里是 B，按钮却是照 A 验过的状态亮的"。
        #
        # ★★ 一律用 trace_add，**不要**用 Entry 的 <KeyRelease>：
        #    绑在控件上的话，程序里 set() 变量、鼠标右键粘贴都不触发 ——
        #    测试里当场抓到一次（newdir 填了名字按钮还是灰的）。
        self.host_var.trace_add("write", lambda *a: self._refresh_upload_button())
        self.mode_var.trace_add("write", lambda *a: self._refresh_upload_button())
        self.newdir_var.trace_add("write", lambda *a: self._refresh_upload_button())
        self.filter_var.trace_add("write", lambda *a: self.rebuild_dir_tree())

    # ------------------------------------------------------------------
    #  小工具
    # ------------------------------------------------------------------

    def log_line(self, text, tag=None):
        self.log.configure(state="normal")
        self.log.insert("end", text + "\n", tag or ())
        self.log.see("end")
        self.log.configure(state="disabled")

    def _load_host(self):
        try:
            with io.open(HOSTFILE, encoding="utf-8") as f:
                return f.readline().strip()
        except OSError:
            return ""

    def _save_host(self, host):
        try:
            with io.open(HOSTFILE, "w", encoding="utf-8") as f:
                f.write(host + "\n")
        except OSError:
            pass

    def _load_geom(self):
        try:
            with io.open(UIFILE, encoding="utf-8") as f:
                g = f.readline().strip()
            if GEOM_RE.match(g):
                return g
        except OSError:
            pass
        return DEFAULT_GEOM

    def _apply_geom(self):
        """
        套用上次记下的窗口尺寸/位置 —— 但**不许比内容需要的高度还矮**。

        ★★ 这条是必须的，踩过：行高从 20 px 调到 31 px 之后内容要 1165+ px，
           而用户机器上 `.upload-ui.txt` 里存的是 1600x1200 —— **它会覆盖新的
           默认值**，于是"改完行高"他看到的还是挤的那一版。
           把下限钉在 winfo_reqheight() 上，以后再加控件也不会挤穿。
        """
        g = self._load_geom()
        m = re.match(r"^(\d+)x(\d+)([+-]-?\d+[+-]-?\d+)?$", g)
        if not m:
            self.root.geometry(g)
            return
        w, h, pos = int(m.group(1)), int(m.group(2)), m.group(3) or ""
        self.root.geometry("%dx%d%s" % (w, h, pos))
        self.root.update_idletasks()
        need = self.root.winfo_reqheight()
        if h < need:
            self.root.geometry("%dx%d%s" % (w, need, pos))
        self.root.minsize(min(900, w), min(680, need))

    def _on_close(self):
        try:
            with io.open(UIFILE, "w", encoding="utf-8") as f:
                f.write(self.root.geometry() + "\n")
        except OSError:
            pass
        self.root.destroy()

    def split_host(self):
        """地址里可以带端口（192.168.42.129:8765），和命令行版一个规矩。"""
        host, _, p = self.host_var.get().strip().partition(":")
        return host.strip(), int(p) if p.strip().isdigit() else 8765

    def probe_key(self):
        """认设备认的是**这个完整目标**，换端口也算换目标。"""
        host, port = self.split_host()
        return "%s:%d" % (host, port) if host else None

    def _inputs_key(self):
        return tuple(self.inputs)

    def _set_busy(self, kind):
        self.busy_kind = kind
        busy = kind is not None
        self.btn_scan.configure(state="disabled" if busy else "normal")
        self.btn_cancel.configure(state="normal" if kind == "upload" else "disabled")
        self._refresh_upload_button()

    def target(self):
        """返回 (dir, newdir) —— 二选一，newdir 优先（照抄网页 JS）。"""
        mode = self.mode_var.get()
        if mode == "new":
            return "", self.newdir_var.get().strip()
        if mode == "existing":
            sel = self.tree_dirs.selection()
            return (sel[0] if sel else ""), ""
        return "", ""

    def _refresh_upload_button(self):
        if not hasattr(self, "btn_up"):
            return
        if self.busy_kind is not None:
            self.btn_up.configure(state="disabled")
            return
        d, nd = self.target()
        ready = (bool(self.items_ok)
                 and self.loaded_key == self._inputs_key()
                 and self.probe_key() is not None
                 and self.probed_key == self.probe_key())
        if self.mode_var.get() == "existing" and not d:
            ready = False
        if self.mode_var.get() == "new" and not nd:
            ready = False
        self.btn_up.configure(state="normal" if ready else "disabled")
        if hasattr(self, "btn_manage"):
            # 认过设备才能管理 —— 否则不知道连的是谁
            self.btn_manage.configure(
                state="normal" if (self.probed_key == self.probe_key()
                                   and self.probe_key() is not None) else "disabled")

        if self.mode_var.get() == "new":
            self.target_var.set("目标：新建目录「%s」" % (nd or "（尚未填写名称）"))
        elif self.mode_var.get() == "existing":
            self.target_var.set("目标：%s" % (d if d else "（尚未选择）"))
        else:
            self.target_var.set("目标：（音乐库根目录）")

    def fail(self, text):
        messagebox.showerror("出错了", text, parent=self.root)

    # ------------------------------------------------------------------
    #  选东西
    # ------------------------------------------------------------------

    def add_folders(self):
        p = filedialog.askdirectory(parent=self.root, title="选要上传的音乐文件夹")
        if p:
            self.inputs.append(os.path.abspath(p))
            self.reload_inputs()

    def add_files(self):
        ps = filedialog.askopenfilenames(parent=self.root, title="选要上传的音乐文件")
        if ps:
            self.inputs.extend(os.path.abspath(x) for x in ps)
            self.reload_inputs()

    def clear_inputs(self):
        if self.busy_kind:
            return
        self.inputs = []
        self.items_ok, self.items_bad, self.items_skipped = [], [], []
        self.loaded_key = None
        self.tree.delete(*self.tree.get_children())
        self.src_summary.set("尚未选择内容。")
        self.status_var.set("已清空。")
        self._refresh_upload_button()

    def reload_inputs(self):
        if self.busy_kind or not self.inputs:
            return
        self._set_busy("load")
        self.items_ok, self.items_bad, self.items_skipped = [], [], []
        self.loaded_key = None
        self.tree.delete(*self.tree.get_children())
        self.src_summary.set("正在遍历…")
        self.status_var.set("正在遍历并逐个试读…")
        for p in self.inputs:
            self.log_line("  · %s" % p)
        self.log_line("— 遍历 %d 个来源 —" % len(self.inputs))

        paths = list(self.inputs)

        def work():
            try:
                files, unreadable, skipped = K.collect_inputs(paths)
                if not files and not unreadable:
                    self.q.put(("loaded", [], [], skipped,
                                "这里没有可识别的音频文件。"))
                    return
                self.q.put(("status", "正在逐个试读（这是网页做不到的一步）…"))
                ok, bad = K.preflight(files, unreadable,
                                      progress=lambda d, t: self.q.put(("pf", d, t)))
                self.q.put(("loaded", ok, bad, skipped, None))
            except Exception as e:
                self.q.put(("loaded", [], [], [], "遍历出错：%s" % e))

        threading.Thread(target=work, daemon=True).start()

    # ------------------------------------------------------------------
    #  目录树（手机上的）
    # ------------------------------------------------------------------

    def _on_mode(self):
        self._refresh_upload_button()

    def _on_pick_dir(self, _ev=None):
        if self.tree_dirs.selection():
            self.mode_var.set("existing")
        self._refresh_upload_button()

    def rebuild_dir_tree(self):
        """
        用手机上的目录清单重建树。

        ★★ **默认全部折叠**（用户 2026-09-25 要求：2026-09-25 "选择的时候默认子目录
          都折叠，不然多了以后不好选择"）。真实数据：202 个目录，但**顶层只有 7 个**
          —— 折叠后一屏 7 行，而不是 202 行。

        ★ 过滤时反过来**全展开** —— 那时候要的是"看见匹配项"，不是"少看见几行"。
        """
        t = self.tree_dirs
        keep_sel = t.selection()
        t.delete(*t.get_children())

        needle = self.filter_var.get().strip().lower()
        force_open = False
        if needle:
            shown = set()
            for d in self.dirs:
                if needle in d.lower():
                    parts = d.split("/")
                    for i in range(1, len(parts) + 1):
                        shown.add("/".join(parts[:i]))
            shown = sorted(shown)
            force_open = True
        else:
            shown = list(self.dirs)

        def fill(node, parent):
            for name in sorted(node):
                full = (parent + "/" + name) if parent else name
                t.insert(parent, "end", iid=full, text="  " + name, open=force_open)
                fill(node[name], full)

        fill(nest(shown), "")
        if keep_sel and t.exists(keep_sel[0]):
            t.selection_set(keep_sel[0])
        self._refresh_upload_button()

    # ------------------------------------------------------------------
    #  动作
    # ------------------------------------------------------------------

    def do_scan(self):
        if self.busy_kind:
            return
        self._set_busy("scan")
        self.status_var.set("正在扫描局域网…（只 GET / 认设备，不会发任何上传）")
        self.log_line("— 扫描局域网 —")
        port = self.split_host()[1]

        def work():
            try:
                found = K.scan_lan(port, on_note=lambda s: self.q.put(("log", "  " + s)))
                self.q.put(("scan", found, port))
            except Exception as e:
                self.q.put(("log", "  ✗ 扫描出错：%s" % e))
                self.q.put(("scan", [], port))

        threading.Thread(target=work, daemon=True).start()

    def do_probe(self):
        if self.busy_kind:
            return
        host, port = self.split_host()
        if not host:
            self.fail("先填手机地址，或者点「扫描局域网」。")
            return
        key = self.probe_key()
        self.probed_key = None
        self._set_busy("probe")
        self.probe_var.set("正在检测 %s:%d …" % (host, port))
        self.lbl_probe.configure(foreground="#777")

        def work():
            try:
                # ★ probe_full 一次 GET 同时拿到"库规模"和"已有目录清单"
                desc, dirs = K.probe_full(host, port)
                self.q.put(("probe", key, True, desc, dirs))
            except K.NotYuHifi as e:
                self.q.put(("probe", key, False,
                            "%s 上有 HTTP 服务，但不是 yuHIFI（%s）" % (host, e), []))
            except Exception as e:
                self.q.put(("probe", key, False,
                            "无法连接 %s:%d —— %s" % (host, port, e), []))

        threading.Thread(target=work, daemon=True).start()

    def open_manager(self):
        """开「管理手机库」窗口（删除 / 新建文件夹）。"""
        if self.busy_kind:
            return
        host, port = self.split_host()
        if not host or self.probed_key != self.probe_key():
            self.fail("先确认目标设备，再管理它的库。")
            return
        # 管理窗口里删过/建过目录 —— 关掉之后刷新「上传到」那棵树。
        # ★ 用 refresh_library 而不是 do_probe：设备早就认过了，没必要把
        #   "确认设备"整个流程再走一遍（那会让按钮闪一下、状态行被覆盖）。
        LibraryManager(self.root, host, port, on_close=self.refresh_library)

    def refresh_library(self):
        """
        重取「库规模 + 目录清单」，刷新「上传到」那棵树。

        ★ 和 `do_probe()` 的区别：**不碰 probed_key**。设备早就认过了，
          变的只是库里的内容 —— 没必要把整个"确认设备"的流程再走一遍
          （那会短暂把按钮变灰、状态行刷成"正在检测"，还会把确认状态清掉）。

        ★★ 上传完**必须**调这个。实测漏掉的后果（用户 2026-09-25 报）：
          新建出来的那层目录不在树里，用户想接着往新目录里传就找不到它，
          得手动点一次「检测」才行 —— 而"手动点击"这种事，用户永远不知道要做。
        """
        key = self.probe_key()
        if not key or self.probed_key != key:
            return
        host, port = self.split_host()

        def work():
            try:
                _desc, dirs = K.probe_full(host, port)
                # ★ 带上**这次是给哪个目标取的** —— 回来的时候目标可能已经变了
                self.q.put(("dirs", key, dirs))
            except Exception as e:
                self.q.put(("log", "  （刷新目录清单失败：%s —— 点「检测」重试）" % e))

        threading.Thread(target=work, daemon=True).start()

    def do_upload(self):
        if self.busy_kind:
            return
        host, port = self.split_host()
        if not self.items_ok or self.loaded_key != self._inputs_key():
            self.fail("没有可上传的文件 —— 重新选一下。")
            return
        if self.probed_key != self.probe_key():
            self.fail("这个目标尚未确认过（地址或端口和上次检测的不一样）。\n"
                      "先点「检测」。")
            return

        d, nd = self.target()
        if self.mode_var.get() == "existing" and not d:
            self.fail("尚未选择上传目标目录。")
            return
        if self.mode_var.get() == "new" and not nd:
            self.fail("「新建目录」的名字是空的。")
            return

        # ★★ 重名预检 —— 和网页一样，冲突就**一个字节都不发**。
        #    整棵树在一次请求里发完，被拒就是白传；所以这一查很值。
        bad_dir = K.find_conflict(self.items_ok, d, self.dirs, nd)
        if bad_dir:
            self.log_line("  ✗ %s" % bad_dir, "bad")
            self.fail("上传会和手机上的目录重名：\n\n%s\n\n"
                      "服务端的规则是一次请求里不允许新建已存在的同名目录。\n"
                      "改「上传到」，或者给源文件夹更换名称。\n"
                      "一个字节都没发。" % bad_dir)
            return

        where = ("新建「%s」" % nd) if nd else (d if d else "音乐库根目录")
        if not messagebox.askyesno(
                "确认上传",
                "把 %d 个文件（%s）上传至\n\n%s\n\n上传至：%s\n\n"
                "整棵目录树会在**一次请求**里发完 —— 这是服务端的硬要求。\n"
                "上传过程中请勿关闭手机上的无线传输。"
                % (len(self.items_ok), human(sum(i.size for i in self.items_ok)),
                   "%s:%d" % (host, port), where),
                parent=self.root):
            return

        self.cancel_flag.clear()
        self._set_busy("upload")
        self.up_started = time.time()
        self.prog.configure(value=0)
        items = list(self.items_ok)
        self.log_line("— 上传 %d 个文件（%s）到 %s —"
                      % (len(items), human(sum(i.size for i in items)), where))

        last = [0.0]

        def on_progress(sent, total, speed):
            now = time.time()
            if now - last[0] < 0.12:
                return
            last[0] = now
            self.q.put(("up", sent, total, speed))

        def work():
            try:
                success, msg = K.upload(host, port, items, d, 1800,
                                        progress=on_progress,
                                        cancelled=self.cancel_flag.is_set,
                                        newdir=nd)
                self.q.put(("done", success, msg))
            except K.UploadCancelled:
                self.q.put(("cancelled",))
            except Exception as e:
                self.q.put(("failed", "%s" % e))

        threading.Thread(target=work, daemon=True).start()

    def do_cancel(self):
        self.cancel_flag.set()
        self.status_var.set("正在取消…（已经发出去的部分，手机上会留下 .part 文件）")

    # ------------------------------------------------------------------
    #  收消息
    # ------------------------------------------------------------------

    def _drain(self):
        try:
            while True:
                m = self.q.get_nowait()
                self._handle(m)
        except queue.Empty:
            pass
        self.root.after(80, self._drain)

    def _handle(self, m):
        kind = m[0]

        if kind == "log":
            self.log_line(m[1])

        elif kind == "status":
            self.status_var.set(m[1])

        elif kind == "scan":
            self._set_busy(None)
            found, port = m[1], m[2]
            if not found:
                self.log_line("  未找到。请检查：手机 App 是否已开启？「无线传输」开关开了吗？"
                              "是否处于同一网络？", "bad")
                self.status_var.set("未找到设备 —— 也可以手动填地址再点「检测」。")
                return
            host, desc = found[0]
            # ★ 端口写回输入框：非默认端口只写裸 IP 的话，probe_key() 会
            #   按 8765 算，和扫描实际用的端口对不上 —— 按钮就永远是灰的。
            self.host_var.set(host if port == 8765 else "%s:%d" % (host, port))
            if len(found) > 1:
                self.log_line("  ★ 找到 %d 台，用的是第一台；若不正确，手动改成别的。"
                              % len(found))
                for ip, dsc in found:
                    self.log_line("     %s  %s" % (ip, dsc), "dim" if ip != host else None)
            self.probe_var.set("✓ " + desc)
            self.lbl_probe.configure(foreground="#1e8449")
            # ★ 扫描本来就是 probe() 找出来的，直接算验过
            self.probed_key = self.probe_key()
            self._save_host(host)
            self.log_line("  ✓ %s → %s" % (host, desc), "ok")
            self.status_var.set("目标已确认，正在取目录清单…")
            self.do_probe()          # 再走一趟拿目录树（扫的那趟只读了前 256 KB）

        elif kind == "probe":
            _, key, ok, text, dirs = m
            self._set_busy(None)
            self.probe_var.set(("✓ " if ok else "✗ ") + text)
            self.lbl_probe.configure(foreground="#1e8449" if ok else "#c0392b")
            self.log_line(("  ✓ " if ok else "  ✗ ") + text, "ok" if ok else "bad")
            if ok:
                self.probed_key = key
                self.dirs = dirs
                self._save_host(key.rsplit(":", 1)[0])
                self.rebuild_dir_tree()
                self.log_line("  音乐库中已有 %d 个目录，最高一层 %d 个（树默认折叠）"
                              % (len(dirs),
                                 len({d.split("/")[0] for d in dirs})))
                self.status_var.set("目标已确认。")
            else:
                self.probed_key = None
                self.dirs = []
                self.rebuild_dir_tree()
                self.status_var.set("目标没确认 —— 上传会被拦住。")
            self._refresh_upload_button()

        elif kind == "dirs":
            _, key, dirs = m
            # ★★ 这是**异步**回来的。取的时候是 A 设备，回来的时候用户可能已经
            #    改成 B 了 —— 那样这份清单是**上一个设备的**，套上去就会：
            #      · 把已经清空的树（认设备失败时清过）又填回来
            #      · 让「上传到」指向 B 上根本不存在的目录
            #    和 probed_key 是同一类错误：异步结果必须确认目标没变再用。
            if key != self.probe_key():
                return
            self.dirs = dirs
            self.rebuild_dir_tree()
            self.log_line("  目录清单已刷新：%d 个目录" % len(dirs))

        elif kind == "pf":
            self.prog.configure(value=m[1] * 1000 // max(m[2], 1))

        elif kind == "loaded":
            _, ok, bad, skipped, err = m
            self._set_busy(None)
            self.items_ok, self.items_bad, self.items_skipped = ok, bad, skipped
            self.loaded_key = self._inputs_key()
            self._fill_tree(ok, bad)
            total = sum(i.size for i in ok)
            odd = []
            if bad:
                odd.append("%d 个无法读取" % len(bad))
            if skipped:
                odd.append("%d 个格式不支持" % len(skipped))
            if err:
                self.src_summary.set(err)
                self.log_line("  " + err, "bad")
            else:
                self.src_summary.set("可上传 %d 个 · %s%s"
                                     % (len(ok), human(total),
                                        ("　⚠ " + "、".join(odd)) if odd else ""))
                self.log_line("  ✓ %d 个可上传，合计 %s" % (len(ok), human(total)), "ok")
            if bad:
                self.log_line("  ⚠ %d 个文件无法读取，**不会**参与上传：" % len(bad), "bad")
                for path, why in bad[:8]:
                    self.log_line("     · %s\n       %s" % (path, why), "bad")
                if len(bad) > 8:
                    self.log_line("     … 另有 %d 个（清单里标红）" % (len(bad) - 8), "bad")
            if skipped:
                # ★ "传完了" ≠ "传全了" —— 放不了的格式必须报出来，不能静默跳过
                self.log_line("  ⚠ %d 个文件疑似音频文件，但音乐库**不支持**，已跳过："
                              % len(skipped), "bad")
                for path, _ext in skipped[:8]:
                    self.log_line("     · %s" % path, "bad")
                if len(skipped) > 8:
                    self.log_line("     … 另有 %d 个" % (len(skipped) - 8), "bad")
            self.prog.configure(value=0)
            self.status_var.set("可以上传了。" if ok else "没有可上传的文件。")
            self._refresh_upload_button()

        elif kind == "up":
            _, sent, total, speed = m
            self.prog.configure(value=sent * 1000 // max(total, 1))
            left = (total - sent) / max(speed * 1048576, 1)
            self.status_var.set(
                "%d%%   %s / %s   %.1f MB/s   约剩 %s"
                % (sent * 100 // max(total, 1), human(sent), human(total), speed, hms(left)))

        elif kind == "done":
            _, ok, msg = m
            self._set_busy(None)
            self.prog.configure(value=1000 if ok else 0)
            if ok:
                self.log_line("  ✓ %s" % msg, "ok")
                self.status_var.set("上传完成。")
                messagebox.showinfo("完成", msg, parent=self.root)
                # ★ 库内容变了，「上传到」那棵树必须跟着变 —— 刚建出来的目录
                #   要立刻能选中，否则用户没法接着往它里面传
                self.refresh_library()
            else:
                self.log_line("  ✗ 服务端拒绝了：%s" % msg, "bad")
                self.status_var.set("服务端拒绝了这次上传。")
                self.fail("服务端拒绝了这次上传：\n\n%s\n\n"
                          "最常见的是「目录已存在」—— 那就把「上传到」指到\n"
                          "那个已存在的目录，或者给源文件夹更换名称。" % msg)

        elif kind == "cancelled":
            self._set_busy(None)
            self.prog.configure(value=0)
            self.log_line("  已取消。手机上可能留下未写完的 .part 文件"
                          "（正式文件不会损坏，它们是在完整接收后才改名的）。", "bad")
            self.status_var.set("已取消。")

        elif kind == "failed":
            self._set_busy(None)
            self.prog.configure(value=0)
            self.log_line("  ✗ %s" % m[1], "bad")
            self.status_var.set("上传失败。")
            self.fail("上传失败：\n\n%s\n\n检查：① 手机和电脑在同一个网络 "
                      "② App 里「无线传输」还开着 ③ 地址对不对" % m[1])

    def _fill_tree(self, ok, bad):
        self.tree.delete(*self.tree.get_children())
        shown = 0
        for path, why in bad[:400]:
            self.tree.insert("", "end", text=path, values=("无法读取",), tags=("bad",))
            shown += 1
        for it in ok[:1000]:
            self.tree.insert("", "end", text=it.rel, values=(human(it.size),))
            shown += 1
        rest = (len(bad) + len(ok)) - shown
        if rest > 0:
            self.tree.insert("", "end", text="… 另有 %d 个未列出（不影响上传）" % rest,
                             values=("",), tags=("dim",))


class LibraryManager:
    """
    手机音乐库管理器：看全库、删条目、新建文件夹。

    对齐网页那套 JS 的功能（树里每个目录/文件都能删、另有一个新建文件夹）。

    ★★ 删除是**破坏性的、不可撤销**。所以：
      · 每次都要在确认框里把**完整路径**摆出来
      · 删目录还要额外说清**里面的东西会一起没**
      · 库根不给删（服务端也挡着，但界面上就不该出现这个选项）
    """

    def __init__(self, parent, host, port, on_close=None):
        self.host, self.port = host, port
        self.on_close = on_close
        self.entries = []          # [(path, is_dir)]
        self.busy = False
        self.q = queue.Queue()

        win = tk.Toplevel(parent)
        self.win = win
        win.title("管理手机库 —— %s:%d" % (host, port))
        win.geometry("760x760")
        win.minsize(560, 480)
        win.transient(parent)
        # ★ 这个窗口里删过东西之后，「上传到」那棵树就过时了 —— 关窗时让主窗口重拉
        win.protocol("WM_DELETE_WINDOW", self._on_close)
        win.columnconfigure(0, weight=1)
        win.rowconfigure(2, weight=1)

        head = ttk.Frame(win)
        head.grid(row=0, column=0, sticky="ew", padx=10, pady=(10, 4))
        head.columnconfigure(1, weight=1)
        ttk.Label(head, text="过滤").grid(row=0, column=0, padx=(0, 6))
        self.filter_var = tk.StringVar(value="")
        ttk.Entry(head, textvariable=self.filter_var).grid(row=0, column=1, sticky="ew")
        self.filter_var.trace_add("write", lambda *a: self._rebuild())

        ttk.Label(win, text="点开目录才看得到里面的东西。选中一项再按「删除」。",
                  foreground="#777").grid(row=1, column=0, sticky="w", padx=12)

        holder = ttk.Frame(win)
        holder.grid(row=2, column=0, sticky="nsew", padx=10, pady=6)
        holder.columnconfigure(0, weight=1)
        holder.rowconfigure(0, weight=1)
        self.tree = ttk.Treeview(holder, show="tree", selectmode="browse")
        self.tree.grid(row=0, column=0, sticky="nsew")
        sb = ttk.Scrollbar(holder, orient="vertical", command=self.tree.yview)
        sb.grid(row=0, column=1, sticky="ns")
        self.tree.configure(yscrollcommand=sb.set)
        self.tree.tag_configure("file", foreground="#4a6fa5")

        bar = ttk.Frame(win)
        bar.grid(row=3, column=0, sticky="ew", padx=10, pady=(0, 4))
        self.btn_del = ttk.Button(bar, text="删除选中项", width=14,
                                  state="disabled", command=self.do_delete)
        self.btn_del.grid(row=0, column=0)
        ttk.Button(bar, text="新建文件夹…", width=14,
                   command=self.do_mkdir).grid(row=0, column=1, padx=8)
        ttk.Button(bar, text="刷新", width=8,
                   command=self.refresh).grid(row=0, column=2)
        # ★ 状态是**临时**的（正在读取… / 共 N 项），会被下一次操作覆盖；
        #   结果是**常驻**的（✓ 已删除「X」）。
        #   ★★ 合成一个字段的话，`_after_mutation` 刚写进去、紧接着 `refresh()`
        #      就把它冲掉了 —— 用户永远看不到服务端那句「已删除」。测试当场抓到。
        self.status = tk.StringVar(value="正在读取…")
        ttk.Label(win, textvariable=self.status, foreground="#777").grid(
            row=4, column=0, sticky="w", padx=12)
        self.result = tk.StringVar(value="")
        self.lbl_result = ttk.Label(win, textvariable=self.result)
        self.lbl_result.grid(row=5, column=0, sticky="w", padx=12, pady=(0, 10))

        self.tree.bind("<<TreeviewSelect>>", self._on_select)

        # 双击展开/收起，和网页的 <details> 一个手感
        self.tree.bind("<Double-1>", self._on_double)

        win.after(100, self._drain)
        self.refresh()

    # ------------------------------------------------------------------

    def _on_close(self):
        try:
            self.win.destroy()
        except Exception:
            pass
        if self.on_close:
            self.on_close()

    def _drain(self):
        try:
            while True:
                self.q.get_nowait()()
        except queue.Empty:
            pass
        try:
            if self.win.winfo_exists():
                self.win.after(100, self._drain)
        except Exception:
            pass

    def _on_select(self, _e=None):
        sel = self.tree.selection()
        self.btn_del.configure(
            state=("normal" if (sel and not self.busy) else "disabled"))

    def _on_double(self, _e=None):
        sel = self.tree.selection()
        if sel:
            iid = sel[0]
            self.tree.item(iid, open=not self.tree.item(iid, "open"))

    def _set_busy(self, b):
        self.busy = b
        self.btn_del.configure(state="disabled" if b else "normal")
        self._on_select()

    # ------------------------------------------------------------------

    def refresh(self):
        if self.busy:
            return
        self._set_busy(True)
        self.status.set("正在读取手机上的库…")

        def work():
            try:
                _, body = K.fetch_page(self.host, self.port)
                self.q.put(lambda: self._loaded(K.parse_library(body)))
            except Exception as e:
                self.q.put(lambda: self._loaded(None, str(e)))

        threading.Thread(target=work, daemon=True).start()

    def _loaded(self, entries, err=None):
        self._set_busy(False)
        if entries is None:
            self.status.set("读取失败：%s" % err)
            return
        self.entries = entries
        self._rebuild()
        d = sum(1 for _p, x in entries if x)
        self.status.set("共 %d 项：%d 个目录、%d 个文件" % (len(entries), d, len(entries) - d))

    def _rebuild(self):
        t = self.tree
        keep = t.selection()
        t.delete(*t.get_children())
        needle = self.filter_var.get().strip().lower()
        dirset = set(p for p, d in self.entries if d)

        if needle:
            shown = set()
            for p, _d in self.entries:
                if needle in p.lower():
                    parts = p.split("/")
                    for i in range(1, len(parts) + 1):
                        shown.add("/".join(parts[:i]))
            force_open = True          # 过滤时反过来全展开 —— 要的是"看见匹配项"
        else:
            shown = set(p for p, _d in self.entries)
            force_open = False         # ★ 默认全折叠

        kids = {}
        for p in shown:
            kids.setdefault(p.rsplit("/", 1)[0] if "/" in p else "", []).append(p)

        def fill(parent):
            # 目录排在文件前面
            for p in sorted(kids.get(parent, []),
                            key=lambda x: (x not in dirset, x.rsplit("/", 1)[-1].lower())):
                name = p.rsplit("/", 1)[-1]
                isd = p in dirset
                t.insert(parent, "end", iid=p,
                         text=("  📁 " if isd else "  ♪ ") + name,
                         open=force_open,
                         tags=() if isd else ("file",))
                fill(p)

        fill("")
        if keep and t.exists(keep[0]):
            t.selection_set(keep[0])

    # ------------------------------------------------------------------

    def do_delete(self):
        sel = self.tree.selection()
        if not sel or self.busy:
            return
        path = sel[0]
        is_dir = path in set(p for p, d in self.entries if d)
        if is_dir:
            warn = ("目录里面**所有东西**都会一起删掉（递归），无法撤销。\n\n"
                    "     %s\n\n确定要删除吗？" % path)
        else:
            warn = "这个文件会从手机上删掉，无法撤销。\n\n     %s\n\n确定要删除吗？" % path
        if not messagebox.askyesno("确认删除", warn, parent=self.win,
                                   default=messagebox.NO):
            return

        self._set_busy(True)
        self.result.set("")            # 清掉上一次的结果，别让人以为这次也成了
        self.status.set("正在删除…")

        def work():
            try:
                ok, msg = K.delete_entry(self.host, self.port, path)
            except Exception as e:
                ok, msg = False, "%s" % e
            self.q.put(lambda: self._after_mutation(ok, msg))

        threading.Thread(target=work, daemon=True).start()

    def do_mkdir(self):
        if self.busy:
            return
        name = simpledialog.askstring("新建文件夹", "文件夹名字：", parent=self.win)
        if not name or not name.strip():
            return
        self._set_busy(True)
        self.result.set("")
        self.status.set("正在新建…")

        def work():
            try:
                ok, msg = K.mkdir(self.host, self.port, name.strip())
            except Exception as e:
                ok, msg = False, "%s" % e
            self.q.put(lambda: self._after_mutation(ok, msg))

        threading.Thread(target=work, daemon=True).start()

    def _after_mutation(self, ok, msg):
        self._set_busy(False)
        # ★ 写进**常驻**的结果行，不是状态行 —— 下面 refresh() 会把状态行冲掉
        self.result.set(("✓ " if ok else "✗ ") + msg)
        self.lbl_result.configure(foreground="#1e8449" if ok else "#c0392b")
        if not ok:
            messagebox.showwarning("未成功", msg, parent=self.win)
        # ★ 删/建之后必须重拉 —— 服务端那边已经 Library.refresh() 了，
        #   本地这份列表也得跟上，否则界面上还挂着已经没了的条目
        self.refresh()


def main():
    global K
    try:
        K = load_kernel()
    except Exception as e:
        fatal("起不来", "载入内核失败：\n\n%s\n\n"
                        "确认 hifiprobe-upload.py 和这个文件在同一个目录里。" % e)
        return 1

    # 高分屏下不糊。必须在建 Tk() 之前调用。
    try:
        import ctypes
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        pass

    root = tk.Tk()
    try:
        for name in ("TkDefaultFont", "TkTextFont", "TkMenuFont"):
            tkfont.nametofont(name).configure(family="Microsoft YaHei UI", size=10)
    except Exception:
        pass

    # 行高。Tkinter 的 Treeview 默认 rowheight 只有 20px —— 在 2880×1800 上
    # 挤成一团（用户 2026-09-25 提："行高不够高，要再高一点"）。
    #
    # ★ 按**字体的实际行高**加，不写死像素：
    #   fonts.metrics("linespace") 会跟着 DPI 和字号走，
    #   换台机器、系统缩放变了都不会错位。写死 30 的话换个环境就又不合适了。
    ROW_PAD = 12                      # 比字体行高多出来的余量
    try:
        base = tkfont.nametofont("TkDefaultFont")
        line = base.metrics("linespace")
        row_h = line + ROW_PAD
        st = ttk.Style()
        st.configure("Treeview", rowheight=row_h)
        st.configure("Treeview.Heading", padding=(6, 6))
        st.configure("TButton", padding=(8, 5))
        st.configure("TRadiobutton", padding=(2, 3))
        st.configure("TLabel", padding=(1, 2))
        # 挂到 root 上，App 里要报出来（"看不见的状态变成一行字"）
        root.hifi_row_height = row_h
        root.hifi_line_space = line
    except Exception as e:
        root.hifi_row_height = None
        sys.stderr.write("行高设置失败：%s\n" % e)

    app = App(root)
    root.mainloop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
