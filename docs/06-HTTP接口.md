# HTTP 接口

App 里跑着**两个完全独立**的 HTTP 服务。它们的开关在设置里是分开的两项，
不要混在一起考虑。

| 服务 | 默认端口 | 用途 | 谁能访问 | 写操作 |
|---|---|---|---|---|
| `WirelessServer` | 8765 | 局域网内上传 / 管理音乐库 | 同网段任何人 | ✅ 有 |
| `DebugServer` | 8766 | 远程看状态 / 截图 / 崩溃栈 | 同网段任何人 | ❌ **严格只读** |

## ⚠️ 共同的安全前提

**两个服务都没有任何鉴权，也都没有 TLS。** 只要在同一网段（通常是电脑热点
192.168.137.x），任何人都能访问 —— 包括看你的屏幕内容、往你的音乐库里传文件、
删你的文件。

这是刻意的取舍（自用的局域网工具，加鉴权反而麻烦），但意味着：

- **不用的时候要关掉**，尤其是连着公共 WiFi 时
- `DebugServer` 必须**守住只读**这条线。能读最多泄漏屏幕，能写就是远程控制

---

# 一、WirelessServer（8765）

手写的 `ServerSocket`，没有引第三方 HTTP 库。一个请求一个连接，处理完就关。
`SO_TIMEOUT` 60 秒。

```
GET  /                       →  管理页面（HTML）
GET  /download?p=<相对路径>   →  下载一个文件
POST /upload                 →  上传（multipart，唯一的写入口）
POST /delete                 →  删除文件或目录
POST /mkdir                  →  新建目录
其他                          →  404
```

## 1.1 `GET /` —— 管理页面

| 项 | 值 |
|---|---|
| 参数 | `?msg=<文本>` 可选，页面顶部显示这条提示 |
| 返回 | `200 text/html; charset=utf-8` |
| 谁在调 | 浏览器地址栏；各写操作 303 回来 |

页面上的**文件列表是现扫目录**（`root.walkTopDown()`），不是读索引 ——
所以上传/删除之后刷新页面一定是对的。

页面顶部那个数字 `N 首` 读的是**索引**（`Library.size()`），
它靠写操作结束时调的 `Library.refresh()` 保持同步。

### ★★ 页面里有两处**机器可读**的东西，客户端别去另开接口

| 位置 | 内容 | 谁在用 |
|---|---|---|
| `<div class=sub>N 首 · M MB</div>` | 库规模 | `probe()` 认设备时的指纹 |
| 「上传到」那个目录选择器 | 库里**已有目录**的完整清单（相对库根） | 上传前查重名 |

★ 目录清单**曾经是 `<select><option value="…">`，2026-09-25 改成了折叠目录树 +
单选钮**（`<input type=radio name=dir value="…">`），原因是 202 个选项平铺不好选。

★★ **要解析它的客户端两种格式都要认**。只认旧格式的话会解析出**空列表** ——
不报错、不崩，只是**重名预检静默失效**，而"目录已存在"正是 `POST /upload` 最常见的失败。
**空列表比报错还危险**：报错会有人管，空列表一路静默。
（2026-09-25 实测踩到：上传工具改完页面就踩了这个坑。）

✓ 现成的客户端：`tools/hifiprobe-upload.py` 的 `parse_dirs()`（命令行 + GUI 共用）。

## 1.2 `GET /download?p=<相对路径>` —— 下载

| 项 | 值 |
|---|---|
| 参数 | `p` = 相对 `Library.root` 的路径，**URL 编码** |
| 返回 | `200 application/octet-stream` + 文件字节流 |
| 错误 | `404 text/plain` `not found` |
| 谁在调 | 列表每行的「下载」链接，`href="/download?p=…"` |

`Content-Disposition` 同时给两个形式：

```
Content-Disposition: attachment; filename="<ASCII 化后的名字>"; filename*=UTF-8''<百分号编码>
```

老浏览器读 `filename`，新浏览器读 `filename*`。中文文件名两边都能拿到。

## 1.3 `POST /upload` —— 上传 ★ 最复杂的一个

### 请求

`Content-Type: multipart/form-data`

**字段顺序有要求**：`dir` / `newdir` 必须排在所有文件字段**前面** ——
服务端要先用它们定下目标目录，后面的文件才知道自己落在哪儿。
（页面 JS 是手动构造 `FormData` 来保证这个顺序的。）

| 字段 | 类型 | 说明 |
|---|---|---|
| `dir` | 文本 | 目标目录，相对 `Library.root`。空串或省略 = 根目录 |
| `newdir` | 文本 | 新建一个目录当目标。**和 `dir` 互斥**，优先取它 |
| `files` | 文件（可多个） | 文件字段。**filename 里可以带相对路径** |

### ★ filename 里带路径 —— 目录结构就是靠这个传的

```
Content-Disposition: form-data; name="files"; filename="专辑A/CD1/01.flac"
```

浏览器不肯让我们在别的地方带路径，`FormData.append(name, file, path)` 的
**第三个参数**是唯一的通道。

**编码：必须按 UTF-8 解码。** HTTP 规范里 header 默认是 Latin-1，
但浏览器往 `filename="…"` 里塞的是**原样 UTF-8 字节**。
按 Latin-1 读的话「新专辑」会变成「æ°ä¸è¾」。
表单值（`dir` / `newdir`）同理 —— 它们的 body 也要按 UTF-8 读。

### 行为规则

| 情况 | 行为 |
|---|---|
| 文件直接落进 `dir` / `newdir` 指定的目录 | ✅ 允许 —— 这就是"打开对应目录传入" |
| `newdir` 的名字和已有目录重名 | ❌ 拒绝 |
| filename 里**任何一层**目录名和库中已有重名 | ❌ 拒绝 |
| 目标位置的**文件名**已存在 | ✅ **直接覆盖** |
| 文件名里的非法字符 `\ : * ? " < > \|` 和控制字符 | 换成 `_` |
| 文件名里有 `.` / `..` / 空段 | 丢掉那一段 |
| 规范化后仍在目标目录之外 | ❌ 拒绝 |
| 空目录（里面没有音频文件） | 不创建（只有落文件时才 `mkdirs`） |

**为什么不允许同名目录**：避免出现 `专辑A (2)`、`专辑A (3)` 越套越深。
要往已有目录里放东西，必须把它选成「上传到」的目标。

### 覆盖是怎么做到不丢数据的

**先写临时文件、整套 multipart 完整收到才改名。**

```
写入   01.flac.part
成功   Files.move(.part → 01.flac, ATOMIC_MOVE | REPLACE_EXISTING)
失败   删掉 .part，**目标位置的旧文件一个字节都不动**
```

判据是**有没有看到收尾的 `--boundary--`**。断线、被截断、异常 —— 都走不到
那个标记，于是 `.part` 被删掉。

不这么做的话，一首 500MB 的 DSD 传到 90% 断线，库里的原文件就被半个文件顶掉了。

### 响应

**不重定向**，消息直接放响应体里：

```
200 text/plain; charset=utf-8

已上传 3 个文件
```

失败时同样是 `200`，body 是原因：

```
目录「专辑A」已存在 —— 请把它选为「上传到」的目标再传，或换个文件夹名
```

> ★ 页面 JS 必须把 `x.responseText` 原样带回页面：
> `location.href='/?msg='+encodeURIComponent(m)`
> **不要硬编码 `'上传完成'`** —— 那会把服务端的话整个盖掉，
> 用户就永远看不到"目录已存在"这类提示了。这个坑踩过一次。

### 内存占用与文件大小无关

正文是**流式**写盘的：找到 `\r\n--boundary` 分隔符之后，只保留最后
`delim.size - 1` 字节（分隔符的最长真前缀），其余立刻写盘。

> ★ 分隔符必须**含前导 CRLF**。正文和分隔符之间那个 CRLF 属于分隔符、
> 不属于正文 —— 只搜 `--boundary` 的话，流式输出时无从判断这两个字节
> 该不该写进文件，写进去每个文件末尾就凭空多两个字节。

## 1.4 `POST /delete` —— 删除

`Content-Type: application/x-www-form-urlencoded`

| 参数 | 说明 |
|---|---|
| `path` | 相对 `Library.root` 的路径。文件或目录（目录**递归删除**） |

| 返回 | |
|---|---|
| 成功 | `303`，`Location: /?msg=已删除「<名字>」` |
| 失败 | `303`，`Location: /?msg=删除失败` |

删除后**会调 `Library.refresh()`** —— 否则网页上删掉的歌在 App 里还留着
一条幽灵记录，点它播放失败。

**库根本身删不掉**（`path` 为空会被挡下）。

## 1.5 `POST /mkdir` —— 新建目录

`Content-Type: application/x-www-form-urlencoded`

| 参数 | 说明 |
|---|---|
| `name` | 目录名 |

| 情况 | 返回的 msg |
|---|---|
| 名字为空 | `文件夹名不能为空` |
| 路径越界 | `路径不合法` |
| **名字已存在** | `目录「X」已存在 —— 换个名字` |
| 成功 | `已新建文件夹「X」`（并调 `Library.refresh()`）|

「不允许同名目录」和上传是**同一条规则**，提示文案也保持一致。

## 1.6 路径安全 —— `inside()`

所有"用户给的路径 + 库根"的拼接都必须过这个函数：

```kotlin
private fun inside(root: File, f: File): Boolean {
    val r = root.canonicalPath.trimEnd(File.separatorChar)
    val p = f.canonicalPath
    return p == r || p.startsWith(r + File.separator)
}
```

> ★★ **不能用 `f.canonicalPath.startsWith(root.canonicalPath)`。**
> 那是**字符串前缀**，不是**路径前缀**：
>
> ```
> root = /…/files/library
> 目标 = /…/files/libraryX/secret
> ```
>
> `startsWith` 返回 **true**，但它压根不在库里。这个口子能串成一条完整的逃逸链：
>
> ```
> /mkdir    name=../libraryX     → 在库外建目录
> /upload   dir=../libraryX      → 把文件写进库外
> /download p=../libraryX/xxx    → 读库外文件
> /delete   path=../libraryX     → 递归删库外目录
> ```

### 上传里的第二道防线

`openTarget()` 在拼出最终文件路径后，**再复核一次** `inside(dest, target)`。

两道防的不是同一件事，缺一不可：

| | 防什么 |
|---|---|
| `safeParts()`（逐段过滤） | 让危险的东西**进不来** |
| `inside()`（规范化复核） | 进来了也**出不去**（软链接、保留名、平台差异）|

`safeParts()` 单独不够 —— `..` 的编码变体（`%2e%2e`、Unicode 规范化）
在各平台上行为不一致；`inside()` 单独也不够 —— 那会静默丢掉用户合法的文件名。

---

# 二、DebugServer（8766）

**严格只读。** 加功能时守住这条线：能读最多泄漏屏幕，能写就是远程控制。

```
GET /                 →  端点列表（HTML）
GET /debug/state      →  当前 Activity + 播放状态 + 曲库统计（JSON）
GET /debug/shot?w=    →  当前界面截图（PNG）
GET /debug/crash      →  最后一次未捕获异常的堆栈（纯文本）
非 GET 的任何请求      →  405
```

| 端点 | 参数 | 返回 | 备注 |
|---|---|---|---|
| `/` | — | `200 text/html` | |
| `/debug/state` | — | `200 application/json`，**格式化过**（grep 时注意 `"tracks": 3` 有空格） | `Cache-Control: no-store` |
| `/debug/shot` | `w`（宽，默认 720，**夹在 120~1440**） | `200 image/png`；没有前台页面时 `503` | `View.draw()` 必须在主线程，带 4 秒超时 |
| `/debug/crash` | — | `200 text/plain`；没有记录时 `404` | |

`w` 必须夹紧：这是唯一能让人远程指定尺寸的地方，不夹的话 `?w=99999999`
会让主线程去分配一张巨图。

---

# 三、功能 ↔ 接口 对照表

"每一个功能对接哪个接口"的完整对照：

| 页面上的功能 | 接口 | 参数 | 成功 | 失败 |
|---|---|---|---|---|
| 打开管理页 / 刷新 | `GET /` | — | 渲染列表 | — |
| 上传（选文件） | `POST /upload` | `dir`+`files` | 页面提示「已上传 N 个文件」 | 提示里给出原因 |
| 上传（选文件夹） | `POST /upload` | `dir`+`files`（filename 带路径） | 目录结构保留 | 同上 |
| 上传（拖放） | 同上 | 同上 | 同上 | 同上 |
| 上传到新建目录 | `POST /upload` | `newdir`+`files` | 建目录并落盘 | 重名时拒绝 |
| 列表里「下载」 | `GET /download` | `p` | 浏览器保存文件 | 404 |
| 列表里「删」 | `POST /delete` | `path` | 提示「已删除「X」」 | 提示「删除失败」 |
| 「新建文件夹」 | `POST /mkdir` | `name` | 提示「已新建文件夹「X」」 | 重名/空名各自提示 |
| （所有写操作之后） | — | — | `Library.refresh()` 同步索引 | — |

**调试侧**（只读，供人/工具查，App 内部不用）：

| 用途 | 接口 |
|---|---|
| 看当前在哪一页、在放什么 | `GET /debug/state` |
| 看界面长什么样 | `GET /debug/shot?w=720` |
| 看上次为什么崩的 | `GET /debug/crash` |

---

# 四、改这块代码时最容易踩的四个坑

1. **编码** —— multipart 的 header 和表单值都是 **UTF-8**，不是 ISO-8859-1。
   按 Latin-1 读，中文文件名和目录名全变乱码。

2. **分隔符要含前导 CRLF** —— 见 1.3。

3. **路径校验要用 `inside()`** —— 见 1.6。字符串前缀会漏。

4. **服务端的提示不能在半路被吞掉** ——
   服务端算出了准确的失败原因，如果客户端硬编码一句"上传完成"把它盖掉，
   用户看到的永远是成功。**提示的链路要端到端走通。**
