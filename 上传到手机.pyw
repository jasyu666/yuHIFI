# -*- coding: utf-8 -*-
r"""
上传音乐到手机 —— 图形界面启动器。

    · 双击这个文件          → 打开窗口（**不会弹控制台黑框**，.pyw 关联 pythonw.exe）
    · 把音乐文件夹拖到它上面 → 打开窗口，并且源文件夹已经填好

★ 真正的程序在 tools\hifiprobe-upload-gui.pyw，这里只是把它从工程根目录
  起起来，省得你每次翻进 tools\ 里找。

★ 万一点了没反应（.pyw 没有控制台，报错也看不见），就用同目录下的
  「上传到手机.bat」—— 那个会带一个控制台，报错直接显示出来。
"""

import os
import runpy
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TARGET = os.path.join(HERE, "tools", "hifiprobe-upload-gui.pyw")


def die(msg):
    try:
        import tkinter as tk
        from tkinter import messagebox
        r = tk.Tk()
        r.withdraw()
        messagebox.showerror("起不来", msg, parent=r)
        r.destroy()
    except Exception:
        sys.stderr.write(msg + "\n")
    return 1


def main():
    if not os.path.exists(TARGET):
        return die("找不到界面程序：\n%s\n\n"
                   "确认 tools\\hifiprobe-upload-gui.pyw 还在，\n"
                   "或者把本文件开头的路径改对。" % TARGET)
    # sys.argv 原样带过去 —— 拖进来的文件夹就是 argv[1]
    runpy.run_path(TARGET, run_name="__main__")
    return 0


if __name__ == "__main__":
    sys.exit(main())
