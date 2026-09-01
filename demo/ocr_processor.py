#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ocr_processor.py - Wrong Question Book OCR Processor
======================================================
Python端：OpenCV图像预处理 + 题目自动分割 + OCR识别
Java端通过 ProcessBuilder 调用本脚本，传入图片路径，接收JSON结果。

用法:
    python ocr_processor.py <image_path>

输出 (stdout, JSON):
    {"success": true, "count": 3, "questions": [{"index":1, "content":"...", "region":{"y":0,"height":100}}, ...]}
    {"success": false, "error": "..."}

依赖:
    - opencv-python, numpy (必需，脚本会自动尝试安装)
    - paddleocr (推荐，中文识别率高)
    - pytesseract + tesseract (降级方案)
"""

import sys
import os
import json
import subprocess
import importlib


# ============================================================
#  依赖自动安装
# ============================================================
def ensure_package(package_name, import_name=None):
    """确保包已安装，未安装则自动尝试pip安装"""
    if import_name is None:
        import_name = package_name
    try:
        importlib.import_module(import_name)
        return True
    except ImportError:
        pass
    try:
        print(f"[INFO] Installing {package_name}...", file=sys.stderr)
        subprocess.check_call(
            [sys.executable, "-m", "pip", "install", package_name, "-q"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL
        )
        importlib.invalidate_caches()
        importlib.import_module(import_name)
        return True
    except Exception as e:
        print(f"[WARN] Failed to install {package_name}: {e}", file=sys.stderr)
        return False


# 确保基础依赖
HAS_CV2 = ensure_package("opencv-python", "cv2")
HAS_NUMPY = ensure_package("numpy", "numpy")

if not HAS_CV2 or not HAS_NUMPY:
    print(json.dumps({
        "success": False,
        "error": "opencv-python and numpy are required. Install with: pip install opencv-python numpy"
    }))
    sys.exit(1)

import cv2
import numpy as np


# ============================================================
#  OCR 引擎初始化（优先 PaddleOCR，降级 pytesseract）
# ============================================================
_ocr_engine = None
_ocr_type = None


def init_ocr():
    """初始化OCR引擎，返回 (engine, type_str) 或 (None, None)"""
    global _ocr_engine, _ocr_type
    if _ocr_engine is not None:
        return _ocr_engine, _ocr_type

    # 尝试 RapidOCR（无需PaddlePaddle，轻量快速）
    try:
        from rapidocr_onnxruntime import RapidOCR
        _ocr_engine = RapidOCR()
        _ocr_type = "rapidocr"
        print("[INFO] Using RapidOCR engine", file=sys.stderr)
        return _ocr_engine, _ocr_type
    except ImportError:
        print("[INFO] RapidOCR not installed", file=sys.stderr)
    except Exception as e:
        print(f"[WARN] RapidOCR init failed: {e}", file=sys.stderr)
    
    # ... 后续继续尝试 PaddleOCR / pytesseract
    # 尝试 PaddleOCR
    try:
        from paddleocr import PaddleOCR
        _ocr_engine = PaddleOCR( lang='ch')
        _ocr_type = "paddleocr"
        print("[INFO] Using PaddleOCR engine", file=sys.stderr)
        return _ocr_engine, _ocr_type
    except ImportError:
        print("[INFO] PaddleOCR not available, trying pytesseract...", file=sys.stderr)
    except Exception as e:
        print(f"[WARN] PaddleOCR init failed: {e}, trying pytesseract...", file=sys.stderr)

    # 尝试 pytesseract
    try:
        import pytesseract
        # 测试tesseract是否可用
        pytesseract.get_tesseract_version()
        _ocr_engine = pytesseract
        _ocr_type = "tesseract"
        print("[INFO] Using pytesseract engine", file=sys.stderr)
        return _ocr_engine, _ocr_type
    except ImportError:
        print("[WARN] pytesseract not installed", file=sys.stderr)
    except Exception as e:
        print(f"[WARN] tesseract not available: {e}", file=sys.stderr)

    _ocr_engine = None
    _ocr_type = "none"
    return None, None


def ocr_recognize(roi_img):
    """对裁剪区域做OCR，返回识别文本"""
    engine, ocr_type = init_ocr()
    if engine is None:
        return "[OCR_NOT_AVAILABLE]"

    try:
        if ocr_type == "paddleocr":
            result = engine.ocr(roi_img, cls=True)
            if result and result[0]:
                texts = [line[1][0] for line in result[0]]
                return '\n'.join(texts)
            return ""
        elif ocr_type == "tesseract":
            import pytesseract
            return pytesseract.image_to_string(roi_img, lang='chi_sim+eng')
        elif ocr_type == "rapidocr":
            result = engine(roi_img)
            if result and result[0]:
                texts = [line[1][0] for line in result[0]]
                return '\n'.join(texts)
            return ""
    except Exception as e:
        print(f"[WARN] OCR failed: {e}", file=sys.stderr)
        return f"[OCR_ERROR: {e}]"
    return ""


# ============================================================
#  OpenCV 图像预处理
# ============================================================
def preprocess(image_path):
    """
    图像预处理：读取 -> 灰度 -> 自适应二值化 -> 形态学去噪
    返回 (原图BGR, 二值化图)
    """
    img = cv2.imread(image_path)
    if img is None:
        raise ValueError(f"Cannot read image: {image_path}")

    # 缩放超大图（加速处理，最大宽度2000px）
    max_width = 2000
    if img.shape[1] > max_width:
        scale = max_width / img.shape[1]
        img = cv2.resize(img, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)

    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

    # 自适应二值化（适应扫描件光照不均）
    binary = cv2.adaptiveThreshold(
        gray, 255,
        cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
        cv2.THRESH_BINARY_INV,
        blockSize=15, C=8
    )

    # 形态学开运算去除小噪点
    kernel = np.ones((2, 2), np.uint8)
    cleaned = cv2.morphologyEx(binary, cv2.MORPH_OPEN, kernel)

    return img, cleaned


# ============================================================
#  题目自动分割（水平投影 + 相邻块合并）
# ============================================================
def split_questions(binary_img):
    """
    水平投影法分割题目：
    1. 计算每行黑色像素数（投影）
    2. 检测文本行块（超过阈值的连续行）
    3. 合并间距小于40px的相邻块（同一道题可能有多行）
    返回 [(y1, y2), ...] 题目区域列表
    """
    h, w = binary_img.shape[:2]

    # 水平投影
    projection = np.sum(binary_img, axis=1)
    threshold = max(3, w * 0.005)  # 动态阈值，至少3个像素

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
                if y - start > 8:  # 过滤高度小于8px的噪声块
                    raw_blocks.append((max(0, start - 3), min(h, y + 3)))
                in_block = False
    if in_block and h - start > 8:
        raw_blocks.append((max(0, start - 3), h))

    if not raw_blocks:
        return []

    # 合并相邻块（间距小于40px视为同一道题）
    merged = []
    cur_start, cur_end = raw_blocks[0]
    for s, e in raw_blocks[1:]:
        if s - cur_end < 40:
            cur_end = e  # 合并
        else:
            merged.append((cur_start, cur_end))
            cur_start, cur_end = s, e
    merged.append((cur_start, cur_end))

    # 过滤高度小于15px的块（可能是页码、批注等）
    merged = [(s, e) for s, e in merged if e - s >= 15]

    return merged


# ============================================================
#  主流程
# ============================================================
def process_image(image_path):
    """处理单张图片，返回题目列表字典"""
    # 1. 预处理
    img, binary = preprocess(image_path)

    # 2. 分割题目
    blocks = split_questions(binary)
    if not blocks:
        return {"success": True, "count": 0, "questions": [],
                "message": "No text blocks detected"}

    # 3. 逐块OCR
    questions = []
    for i, (y1, y2) in enumerate(blocks):
        roi = img[y1:y2, :]
        text = ocr_recognize(roi).strip()
        # 过滤过短或无意义的结果
        if text and len(text) > 3 and text != "[OCR_NOT_AVAILABLE]":
            questions.append({
                "index": i + 1,
                "content": text,
                "region": {"y": int(y1), "height": int(y2 - y1)}
            })

    return {
        "success": True,
        "count": len(questions),
        "blocks_detected": len(blocks),
        "questions": questions
    }


def main():
    if len(sys.argv) < 2:
        print(json.dumps({
            "success": False,
            "error": "Usage: python ocr_processor.py <image_path>"
        }))
        sys.exit(1)

    image_path = sys.argv[1]
    if not os.path.exists(image_path):
        print(json.dumps({
            "success": False,
            "error": f"Image not found: {image_path}"
        }))
        sys.exit(1)

    try:
        result = process_image(image_path)
        print(json.dumps(result, ensure_ascii=False))
    except Exception as e:
        print(json.dumps({
            "success": False,
            "error": str(e)
        }))
        sys.exit(1)


if __name__ == "__main__":
    main()
