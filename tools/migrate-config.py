#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 0.1.x（旧包名 com.flymeport.hyperos4 / 分组 flymeport）的配置导出成 JSON。

为什么需要它：包名迁到 io.github.kotoriinsky.tsuzuri 后，LSPosed 的远程配置
存储键 = **模块包名 + 分组名**，新包名模块读到的是一份空存储。配置的键名和
值格式一个都没变，所以只要把旧键值搬进新分组即可 —— 搬运路径是：

  1. 本脚本（root 读 /data/adb/lspd/config/modules_config.db）→ 导出 JSON；
  2. 新版 App 的设置页「恢复配置」读这份 JSON，逐键写进新分组。

JSON 结构（设置页恢复功能的输入契约）：

  {
    "version": 1,                      # 文件结构版本
    "source": "com.flymeport.hyperos4 / flymeport",
    "exported": "2026-09-18T18:00:00",
    "items": [
      {"key": "...", "type": "Boolean|Integer|Long|Float|String|Set", "value": ...}
    ]
  }

用法:
  python tools/migrate-config.py                    # 导出到 tools/ 下默认文件名
  python tools/migrate-config.py --out /tmp/x.json
  python tools/migrate-config.py --serial <设备序列号>
"""

import argparse
import json
import os
import sys
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import prefs  # noqa: E402  复用 fetch_db / load_rows / decode

OLD_PKG = "com.flymeport.hyperos4"
OLD_GROUP = "flymeport"


def main():
    ap = argparse.ArgumentParser(description="导出旧包名/旧分组的模块配置为 JSON")
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--out", help="输出 JSON 路径（默认 tsuzuri-config-backup.json）")
    args = ap.parse_args()

    tmp = prefs.fetch_db(args.serial)
    rows = prefs.load_rows(os.path.join(tmp, "modules_config.db"))
    mine = [(k, d) for pkg, g, k, d in rows if pkg == OLD_PKG and g == OLD_GROUP]
    if not mine:
        groups = sorted({(pkg, g) for pkg, g, _k, _d in rows if pkg == OLD_PKG})
        print("旧分组 %s 下没有配置。该模块的分组：%s" % (OLD_GROUP, groups or "无"))
        return 1

    items = []
    skipped = []
    for key, data in sorted(mine):
        type_name, value = prefs.decode(data)
        if type_name == "?":
            skipped.append(key)
            continue
        items.append({"key": key, "type": type_name, "value": value})

    doc = {
        "version": 1,
        "source": "%s / %s" % (OLD_PKG, OLD_GROUP),
        "exported": datetime.now().isoformat(timespec="seconds"),
        "items": items,
    }
    out = args.out or "tsuzuri-config-backup.json"
    with open(out, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)

    print("导出 %d 个键 → %s" % (len(items), out))
    for it in items[:8]:
        print("  %-46s = %s" % (it["key"], prefs.fmt(it["value"], it["type"])))
    if len(items) > 8:
        print("  …（其余 %d 个见文件）" % (len(items) - 8))
    if skipped:
        print("跳过无法解码的键（请人工核对）：%s" % ", ".join(skipped))
    print()
    print("下一步：在新版 App 的设置页用「恢复配置」导入这份 JSON")
    print("（恢复功能落地前，可先保留旧版 APK 的截图对照手工设置）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
