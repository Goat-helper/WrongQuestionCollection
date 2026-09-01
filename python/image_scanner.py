# -*- coding: utf-8 -*-
"""
image_scanner.py — OpenCV 图像处理 + OCR 模块
=====================================================
对应原 Java 版扫描导入功能，图像处理全部基于 OpenCV：

  1. 读取图像（含文件大小 / 像素总量上限检查，防止超大图 OOM）
  2. 预处理：灰度化 -> 自适应二值化 -> 形态学开运算去噪
  3. 题目自动分割：水平投影法 + 相邻块合并
  4. OCR 识别：优先 PaddleOCR（中文准确率高），降级 pytesseract；
     两者都不可用时返回空结果，由 GUI 引导用户手动输入。

依赖（脚本运行时自动安装）：
  - 必需：opencv-python, numpy
  - 可选：paddleocr + paddlepaddle  或  pytesseract（OCR 引擎）
"""

import sys
import os
import json
import importlib
import subprocess


# ============================================================
#  依赖自动安装（避免用户手动 pip install）
# ============================================================
def ensure_package(package_name, import_name=None):
    """确保包已安装，未安装则自动 pip 安装。
    默认安装失败（如系统目录无写权限）时自动改用 --user 重试。"""
    if import_name is None:
        import_name = package_name
    try:
        importlib.import_module(import_name)
        return True
    except ImportError:
        pass
    for extra_args in (["-q", "--disable-pip-version-check"],
                       ["-q", "--disable-pip-version-check", "--user"]):
        try:
            print("[INFO] Installing %s ..." % package_name, file=sys.stderr)
            subprocess.check_call(
                [sys.executable, "-m", "pip", "install", package_name] + extra_args,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            importlib.invalidate_caches()
            importlib.import_module(import_name)
            return True
        except Exception:
            continue
    return False


# 必需依赖
HAS_CV2 = ensure_package("opencv-python", "cv2")
HAS_NUMPY = ensure_package("numpy", "numpy")

if not HAS_CV2 or not HAS_NUMPY:
    raise RuntimeError(
        "opencv-python and numpy are required. Auto-install failed. "
        "Please run: pip install opencv-python numpy")

import cv2
import numpy as np

# 图像加载安全上限（防解压炸弹 / 超大图 OOM）
MAX_IMAGE_FILE_BYTES = 50 * 1024 * 1024      # 50MB
MAX_IMAGE_PIXELS = 100_000_000               # 约 1 亿像素


# ============================================================
#  OCR 引擎（懒加载，优先 PaddleOCR，降级 pytesseract）
# ============================================================
_ocr_engine = None
_ocr_type = None


def init_ocr():
    """初始化 OCR 引擎，返回 (engine, type_str)，不可用时返回 (None, None)"""
    global _ocr_engine, _ocr_type
    if _ocr_engine is not None:
        return _ocr_engine, _ocr_type

    # 尝试 PaddleOCR（中文识别率高）
    try:
        from paddleocr import PaddleOCR
        _ocr_engine = PaddleOCR(lang='ch')
        _ocr_type = "paddleocr"
        print("[INFO] Using PaddleOCR engine", file=sys.stderr)
        return _ocr_engine, _ocr_type
    except ImportError:
        print("[INFO] PaddleOCR not installed, trying pytesseract...", file=sys.stderr)
    except Exception as e:
        print("[WARN] PaddleOCR init failed: %s, trying pytesseract..." % e, file=sys.stderr)

    # 尝试 pytesseract
    try:
        import pytesseract
        pytesseract.get_tesseract_version()
        _ocr_engine = pytesseract
        _ocr_type = "tesseract"
        print("[INFO] Using pytesseract engine", file=sys.stderr)
        return _ocr_engine, _ocr_type
    except ImportError:
        print("[WARN] pytesseract not installed", file=sys.stderr)
    except Exception as e:
        print("[WARN] tesseract not available: %s" % e, file=sys.stderr)

    _ocr_engine = None
    _ocr_type = "none"
    return None, None


def ocr_available():
    """当前是否有可用的 OCR 引擎"""
    engine, _ = init_ocr()
    return engine is not None


def ocr_recognize(roi_img):
    """对 BGR 裁剪区域做 OCR，返回识别文本"""
    engine, ocr_type = init_ocr()
    if engine is None:
        return ""
    try:
        if ocr_type == "paddleocr":
            result = engine.ocr(roi_img, cls=True)
            if result and result[0]:
                texts = [line[1][0] for line in result[0]]
                return "\n".join(texts)
            return ""
        elif ocr_type == "tesseract":
            import pytesseract
            # pytesseract 期望 RGB / 可直接 numpy BGR
            return pytesseract.image_to_string(roi_img, lang='chi_sim+eng')
    except Exception as e:
        print("[WARN] OCR failed: %s" % e, file=sys.stderr)
        return ""
    return ""


# ============================================================
#  图像读取与预处理
# ============================================================
def load_image(image_path):
    """读取图像（含安全上限检查），返回 BGR ndarray。
    使用 np.fromfile + imdecode，以兼容中文/Unicode 路径（cv2.imread 对非 ASCII 路径会失败）。"""
    if not os.path.exists(image_path):
        raise ValueError("Image not found: %s" % image_path)
    if os.path.getsize(image_path) > MAX_IMAGE_FILE_BYTES:
        raise ValueError(
            "Image file too large (limit %d MB)." % (MAX_IMAGE_FILE_BYTES // (1024 * 1024)))

    data = np.fromfile(image_path, dtype=np.uint8)
    img = cv2.imdecode(data, cv2.IMREAD_COLOR)
    if img is None:
        raise ValueError("Cannot read image (unsupported or corrupted): %s" % image_path)

    h, w = img.shape[:2]
    if h * w > MAX_IMAGE_PIXELS:
        raise ValueError(
            "Image dimensions too large (%dx%d)." % (w, h))
    return img


def preprocess(img):
    """
    图像预处理：缩放超大图 -> 灰度 -> 自适应二值化 -> 形态学开运算去噪。
    返回 (原图BGR, 二值化图)
    """
    # 缩放超大图（加速处理，最大宽度 2000px）
    max_width = 2000
    if img.shape[1] > max_width:
        scale = max_width / img.shape[1]
        img = cv2.resize(img, None, fx=scale, fy=scale,
                         interpolation=cv2.INTER_AREA)

    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

    # 自适应二值化（适应扫描件光照不均）
    binary = cv2.adaptiveThreshold(
        gray, 255,
        cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
        cv2.THRESH_BINARY_INV,
        blockSize=15, C=8)

    # 形态学开运算去除小噪点
    kernel = np.ones((2, 2), np.uint8)
    cleaned = cv2.morphologyEx(binary, cv2.MORPH_OPEN, kernel)

    return img, cleaned


def split_questions(binary_img):
    """
    水平投影法分割题目：
      1. 计算每行黑色像素数（投影）
      2. 检测文本行块（超过阈值的连续行）
      3. 合并间距小于 40px 的相邻块（同一道题可能有多行）
    返回 [(y1, y2), ...] 题目区域列表
    """
    h, w = binary_img.shape[:2]

    projection = np.sum(binary_img, axis=1)
    threshold = max(3, w * 0.005)  # 动态阈值，至少 3 个像素

    # 检测文本行块
    raw_blocks = []
    in_block = False
    start = 0
    for y in range(h):
        if projection[y] > threshold:
            if not in_block:
                start = y
                in_block = True
        else:
            if in_block:
                if y - start > 8:  # 过滤高度小于 8px 的噪声块
                    raw_blocks.append((max(0, start - 3), min(h, y + 3)))
                in_block = False
    if in_block and h - start > 8:
        raw_blocks.append((max(0, start - 3), h))

    if not raw_blocks:
        return []

    # 合并相邻块（间距小于 40px 视为同一道题）
    merged = []
    cur_start, cur_end = raw_blocks[0]
    for s, e in raw_blocks[1:]:
        if s - cur_end < 40:
            cur_end = e
        else:
            merged.append((cur_start, cur_end))
            cur_start, cur_end = s, e
    merged.append((cur_start, cur_end))

    # 过滤高度小于 15px 的块（可能是页码、批注等）
    merged = [(s, e) for s, e in merged if e - s >= 15]

    return merged


def process_image(image_path):
    """
    处理单张图片：预处理 -> 分割 -> 逐块 OCR。
    返回 dict: {"success": True, "questions": [{"content":..., "region": {...}}], "blocks": n}
    """
    img = load_image(image_path)
    img, binary = preprocess(img)

    blocks = split_questions(binary)
    if not blocks:
        return {"success": True, "count": 0, "questions": [],
                "message": "No text blocks detected"}

    questions = []
    for i, (y1, y2) in enumerate(blocks):
        roi = img[y1:y2, :]
        text = ocr_recognize(roi).strip()
        # 过滤过短或无意义结果
        if text and len(text) > 3:
            questions.append({
                "index": i + 1,
                "content": text,
                "region": {"y": int(y1), "height": int(y2 - y1)}
            })

    return {
        "success": True,
        "count": len(questions),
        "blocks": len(blocks),
        "questions": questions
    }


# ============================================================
#  命令行入口（便于独立调试）
# ============================================================
def main():
    if len(sys.argv) < 2:
        print(json.dumps({"success": False,
                          "error": "Usage: python image_scanner.py <image_path>"}))
        sys.exit(1)

    image_path = sys.argv[1]
    try:
        result = process_image(image_path)
        print(json.dumps(result, ensure_ascii=False))
    except Exception as e:
        print(json.dumps({"success": False, "error": str(e)}))
        sys.exit(1)


if __name__ == "__main__":
    main()
