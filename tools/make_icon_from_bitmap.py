#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把用户提供的一张 1254x1254 图标位图，转成 Android 自适应图标（adaptive icon）需要的
各密度 mipmap PNG 资源。

生成的资源（相对 app/src/main/res/）：
  mipmap-mdpi/ic_launcher.png               48x48     legacy 方形
  mipmap-hdpi/ic_launcher.png               72x72
  mipmap-xhdpi/ic_launcher.png              96x96
  mipmap-xxhdpi/ic_launcher.png             144x144
  mipmap-xxxhdpi/ic_launcher.png            192x192
  mipmap-{d}/ic_launcher_round.png          同上，圆形遮罩（legacy round）
  mipmap-{d}/ic_launcher_foreground.png     108dp * 密度，主体缩进安全区
  mipmap-{d}/ic_launcher_background.png     108dp * 密度，平铺实色底

关键设计（为什么这么做）：
  源图是「一个圆角方形图标」，它已经把**形状**烘焙进像素了。Android 的 adaptive icon
  会给前景层再套一次系统遮罩，如果直接整张塞进去，就会被"方形里的方形"双重裁切。
  所以这里把源图拆成两层：
    前景层 = 圆角方形**以内**的内容（去掉边缘的直角区域），按规格缩放到 66/108 安全区；
    背景层 = 从源图四角采样出的底色，平铺整层。
  这样系统遮罩无论裁成圆形/水滴/方形，看到的都是"底 + 居中主体"，不会被切角。

用法：
  python tools/make_icon_from_bitmap.py <源图> [--res <res目录>] [--dry-run]
"""
from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw
except ImportError:  # pragma: no cover
    sys.exit("需要 Pillow：pip install Pillow")

try:
    import numpy as np
except ImportError:  # pragma: no cover
    np = None


# legacy launcher 图标尺寸（px），键是 res 目录名
LEGACY = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# adaptive icon 的密度倍率（108dp 基准）
DENSITY_SCALE = {
    "mipmap-mdpi": 1.0,
    "mipmap-hdpi": 1.5,
    "mipmap-xhdpi": 2.0,
    "mipmap-xxhdpi": 3.0,
    "mipmap-xxxhdpi": 4.0,
}

ADAPTIVE_DP = 108          # adaptive icon 画布
SAFE_DP = 66               # 系统保证可见的安全区
SAFE_RATIO = SAFE_DP / ADAPTIVE_DP


def jpeg_size(path: Path) -> tuple[int, int] | None:
    """不依赖 Pillow，直接从 JPEG 头读尺寸（用于日志核对）。"""
    data = path.read_bytes()
    i = 2
    while i < len(data) - 1:
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if marker in (0xC0, 0xC1, 0xC2):
            h, w = struct.unpack(">HH", data[i + 5 : i + 9])
            return w, h
        if marker in (0xD8, 0xD9) or 0xD0 <= marker <= 0xD7:
            i += 2
            continue
        i += 2 + struct.unpack(">H", data[i + 2 : i + 4])[0]
    return None


def detect_bg_color(img: Image.Image, log=print) -> tuple[int, int, int]:
    """
    从圆角**内侧**采样底色。

    不要取四个角：实际拿到的源图常常带一圈黑边（截图工具的描边），
    或者四角本身就是透明的，采样结果会变成 #000000，把整个图标染黑
    —— 这个坑实测踩到过一次（采样出 #000002）。

    做法：从四个边往内推进若干比例，取一条"边带"上出现次数最多的颜色。
    """
    from collections import Counter

    w, h = img.size
    rgb = img.convert("RGB")
    px = rgb.load()

    # 采样带的位置：距边缘 6% ~ 14%（既躲开外圈描边，又落在圆角方形之内）
    lo, hi = int(w * 0.06), int(w * 0.14)
    counter: Counter[tuple[int, int, int]] = Counter()
    step = max(1, w // 160)

    for t in range(lo, hi, step):
        for x in range(lo, w - lo, step):
            counter[px[x, t]] += 1              # 上边带
            counter[px[x, h - 1 - t]] += 1      # 下边带
        for y in range(lo, h - lo, step):
            counter[px[t, y]] += 1              # 左边带
            counter[px[w - 1 - t, y]] += 1      # 右边带

    if not counter:
        return (16, 26, 46)

    color, count = counter.most_common(1)[0]
    total = sum(counter.values())
    log(
        "底色采样：命中 %d/%d 个样本  #%02X%02X%02X"
        % (count, total, color[0], color[1], color[2])
    )
    return color


def detect_inset(img: Image.Image, log=print) -> int:
    """
    自动量出源图自带的**外圈描边**宽度（截图工具常在图像外围留一圈黑边）。

    做法：从四条边逐行向内的平均亮度，找到第一次明显"变亮"（进入图标实体）的位置。
    返回需要裁掉的像素数。找不到就返回 0（视为无描边）。
    """
    if np is None:
        return 0

    w, h = img.size
    arr = np.asarray(img.convert("L"), dtype=np.float32) / 255.0

    def first_bright(profile: np.ndarray) -> int:
        # 背景（描边）暗，图标本体亮。以整体均值的 40% 作为跨过阈值
        thr = max(0.06, float(profile.mean()) * 0.4)
        for i, v in enumerate(profile):
            if v > thr and i + 1 < len(profile) and profile[i + 1] > thr:
                return i
        return 0

    tops = [first_bright(arr[i, :]) for i in range(0, min(6, h))]
    bots = [first_bright(arr[h - 1 - i, :]) for i in range(0, min(6, h))]
    lefts = [first_bright(arr[:, i]) for i in range(0, min(6, w))]
    rights = [first_bright(arr[:, w - 1 - i]) for i in range(0, min(6, w))]

    inset = int(
        round(
            np.median([np.median(tops), np.median(bots), np.median(lefts), np.median(rights)])
        )
    )
    log(f"检测到外圈描边宽度：约 {inset}px")
    return max(0, min(inset, w // 8))


def strip_background(
    img: Image.Image, bg: tuple[int, int, int], tol: int = 46, log=print
) -> Image.Image:
    """
    把源图自带的底色抠成透明，只留下主体（白色形体 + 红色丝带）。

    为什么必须抠：把源图整张缩进安全区时，它**自带的圆角方形底色**会跟着一起缩，
    于是深色底上浮出一个"深色小方块"的轮廓 —— 底色与背景层的采样色并不完全相等
    （实测源图圆角内是 #0F1A2E，采样出来是 #111C32），缩小后这条接缝非常明显。
    抠掉之后，底色完全交给 background 层，任何遮罩形状下都不会露馅。

    做法：按颜色距离做软阈值。完全接近底色的 → 全透明；主体边缘按距离做羽化，
    避免锯齿。这一点比硬阈值重要 —— 硬阈值会让红色丝带和白色文字的边缘起毛刺。
    """
    if np is None:
        return img

    arr = np.asarray(img.convert("RGBA"), dtype=np.float32)
    rgb = arr[:, :, :3]
    dist = np.sqrt(((rgb - np.array(bg, dtype=np.float32)) ** 2).sum(axis=2))

    # 距离 < tol 视为纯底色 → alpha=0；> tol*2 视为纯主体 → alpha 保持
    lo, hi = float(tol), float(tol * 2)
    alpha = np.clip((dist - lo) / max(1.0, hi - lo), 0.0, 1.0)
    alpha = alpha * (arr[:, :, 3] / 255.0)

    out = arr.copy()
    out[:, :, 3] = alpha * 255.0

    kept = float((alpha > 0.5).mean())
    log(f"抠底：保留主体 {kept:.1%} 的像素（透明度阈值 tol={tol}）")
    if kept < 0.02:
        log("⚠️ 保留像素过少，抠底可能误伤主体 —— 请检查底色采样是否正确")
    return Image.fromarray(out.astype("uint8"), "RGBA")


def make_round_mask(size: int, supersample: int = 4) -> Image.Image:
    """生成抗锯齿的圆形遮罩（legacy 的 round 图标）。"""
    big = size * supersample
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, big - 1, big - 1), fill=255)
    return mask.resize((size, size), Image.LANCZOS)


def make_squircle_mask(size: int, supersample: int = 4) -> Image.Image:
    """圆角方形遮罩。半径取 22.3%，贴近 Android / HyperOS 的图标形状。"""
    big = size * supersample
    radius = int(big * 0.223)
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, big - 1, big - 1), radius=radius, fill=255
    )
    return mask.resize((size, size), Image.LANCZOS)


def write_png(img: Image.Image, path: Path) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "PNG", optimize=True)
    return path.stat().st_size


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("source", type=Path, help="1254x1254 或更大的源图")
    ap.add_argument(
        "--res",
        type=Path,
        default=Path("app/src/main/res"),
        help="资源根目录（默认 app/src/main/res）",
    )
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument(
        "--subject-ratio",
        type=float,
        default=0.62,
        help="前景主体在 108dp 画布上的占比（默认 0.62，越小主体越精巧）",
    )
    ap.add_argument(
        "--chroma-tol",
        type=int,
        default=46,
        help="抠底的颜色容差，越大抠掉越多（默认 46）",
    )
    ap.add_argument(
        "--solid-bg",
        action="store_true",
        help="背景层用采样出的纯色，而不是保留源图的圆角方形底",
    )
    args = ap.parse_args()

    if not args.source.exists():
        return f"源图不存在：{args.source}"

    src_size = jpeg_size(args.source)
    print(f"源图：{args.source.name}  {src_size[0]}x{src_size[1]}" if src_size else "源图：读取尺寸失败")

    img = Image.open(args.source).convert("RGBA")
    w, h = img.size
    if w != h:
        return f"源图必须是正方形，实际 {w}x{h}"

    # ① 先裁掉源图自带的黑边（不裁的话 legacy 图标外围会留一圈脏边）
    inset = detect_inset(img)
    if inset > 0:
        img = img.crop((inset, inset, w - inset, h - inset))
        w = h = img.size[0]
        print(f"裁掉描边后：{w}x{h}")

    bg = detect_bg_color(img)
    print(f"采用底色：#{bg[0]:02X}{bg[1]:02X}{bg[2]:02X}")

    squircle = make_squircle_mask(w)

    # ── legacy：整张方形图标。额外多裁 3% 作为安全边距 —— 启动器/task 切图对
    #    legacy 图标也会做轻微裁切，贴边太紧的图形会被削到。
    margin = int(w * 0.03)
    legacy_src = img.crop((margin, margin, w - margin, h - margin))

    sm = make_squircle_mask(legacy_src.size[0])
    legacy_base = Image.new("RGBA", legacy_src.size, (0, 0, 0, 0))
    legacy_base.paste(legacy_src, (0, 0), sm)

    # ── adaptive 前景：只保留**主体本身**（抠掉源图自带的方底色）。
    #
    #    为什么不能整张塞进去：源图已经把"圆角方形 + 底色"烘焙进像素了，
    #    整张作为前景层 = 系统遮罩外再套一层方形裁切，"方形里的方形"；
    #    缩小后那条方形接缝尤其明显（实测踩到过）。
    #
    #    做法：抠底 → 裁到主体包围盒 → 按比例缩进安全区居中。
    #    底色由 background 层给，系统遮罩爱怎么裁都不露馅。
    subject = strip_background(img, bg, tol=args.chroma_tol)

    # 裁到主体的实际包围盒。
    # ⚠️ 不能直接用 Alpha 通道求 getbbox()：抠底走的是软阈值（边缘有羽化），
    #    羽化带里的半透明噪点会让 getbbox() 认为"整张图都有内容"，返回整幅尺寸。
    #    必须用**硬阈值**先算出实心区域，再拿它的包围盒 —— 实测踩过这个坑。
    if np is not None:
        a = np.asarray(subject, dtype=np.float32)
        solid = a[:, :, 3] > 128
        # 顺带清掉孤立噪点：只保留面积足够大的连通块的包围盒
        ys, xs = np.where(solid)
        if len(ys) > 0:
            y0, y1, x0, x1 = ys.min(), ys.max(), xs.min(), xs.max()
            pad = max(2, int(max(y1 - y0, x1 - x0) * 0.02))
            y0, x0 = max(0, y0 - pad), max(0, x0 - pad)
            y1, x1 = min(a.shape[0], y1 + pad), min(a.shape[1], x1 + pad)
            subject = subject.crop((x0, y0, x1, y1))
            print(f"主体包围盒：(x{x0},y{y0})-({x1},{y1}) → {subject.size[0]}x{subject.size[1]}")

    inner_ratio = args.subject_ratio
    print(f"前景主体占比：{inner_ratio:.0%}")

    total = 0
    plan: list[tuple[Path, Image.Image]] = []

    for res_dir, px in LEGACY.items():
        # legacy 方形（保留圆角，贴 Android 的 legacy 观感）
        flat = legacy_base.resize((px, px), Image.LANCZOS)
        plan.append((args.res / res_dir / "ic_launcher.png", flat))

        # legacy 圆形
        rounded = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        rounded.paste(flat, (0, 0), make_round_mask(px))
        plan.append((args.res / res_dir / "ic_launcher_round.png", rounded))

        # adaptive 画布尺寸
        canvas = int(round(ADAPTIVE_DP * DENSITY_SCALE[res_dir]))
        safe = int(round(canvas * inner_ratio))

        # 前景层：主体等比放进 safe×safe 的盒子里并居中（保持长宽比，不拉伸）
        fg = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        sw, sh = subject.size
        k = min(safe / sw, safe / sh)
        core = subject.resize((max(1, int(sw * k)), max(1, int(sh * k))), Image.LANCZOS)
        fg.paste(core, ((canvas - core.size[0]) // 2, (canvas - core.size[1]) // 2), core)
        plan.append((args.res / res_dir / "ic_launcher_foreground.png", fg))

        # 背景层：默认铺采样出的纯色。
        # 这时候 foreground 里已经含源图的圆角方形底了，如果背景再叠一层纯色，
        # 遮罩边缘会露出"方形底 vs 纯色底"的接缝 —— 所以把纯色**垫在下面**即可，
        # 前景自带的圆角方形自然盖住中间，视觉上是一体的。
        plan.append(
            (
                args.res / res_dir / "ic_launcher_background.png",
                Image.new("RGBA", (canvas, canvas), bg + (255,)),
            )
        )

    print(f"\n将写入 {len(plan)} 个文件到 {args.res}：")
    for path, im in plan:
        size = im.size
        if args.dry_run:
            print(f"  [dry-run] {path}  {size[0]}x{size[1]}")
        else:
            n = write_png(im, path)
            total += n
            print(f"  {path}  {size[0]}x{size[1]}  {n / 1024:.1f} KB")

    if not args.dry_run:
        print(f"\n完成，共 {total / 1024:.1f} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
