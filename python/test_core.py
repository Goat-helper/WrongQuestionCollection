# -*- coding: utf-8 -*-
"""
test_core.py — 无 GUI 核心逻辑测试
验证：QuestionDB 数据层 + image_scanner 图像分割算法
运行：python test_core.py
"""
import os
import sys
import tempfile

import cv2
import numpy as np

# 被测模块（自动装依赖，已安装）
import image_scanner as scanner
from wrong_question_book import QuestionDB, new_question

PASS = 0
FAIL = 0


def check(name, ok):
    global PASS, FAIL
    if ok:
        PASS += 1
        print("[PASS]", name)
    else:
        FAIL += 1
        print("[FAIL]", name)


# ============================================================
#  QuestionDB 数据层
# ============================================================
def test_db():
    work = tempfile.mkdtemp(prefix="wqb_test_")
    data_file = os.path.join(work, "test.json")
    print("DB work dir:", work)

    # 1. 空库 load 返回 False
    db = QuestionDB(data_file)
    check("empty load -> False", not db.load())

    # 2. CRUD round-trip
    db.add(new_question("数学", "1+1=?", "3", "2", "小学"))
    db.add(new_question("Java", "null 检查", "", "Objects.nonNull", ""))
    db.add(new_question("数学", "2+2=?", "5", "4", ""))
    check("add: size == 3", db.size() == 3)
    check("add: next_id == 4", db.get_next_id() == 4)
    check("save()", db.save())

    db2 = QuestionDB(data_file)
    check("load round-trip", db2.load())
    check("loaded size == 3", db2.size() == 3)
    check("loaded next_id == 4", db2.get_next_id() == 4)
    q = db2.get_by_id(2)
    check("get_by_id(2) subject == Java", q is not None and q["subject"] == "Java")

    # 3. 搜索
    r = db2.search_by_keyword("数学")
    check("search '数学' -> 2", len(r) == 2)
    r = db2.search_by_keyword("")
    check("search '' -> all 3", len(r) == 3)

    # 4. 统计
    st = db2.get_statistics()
    check("stats total == 3", st["total"] == 3)
    check("stats avg == 1.0", abs(st["avg_wrong"] - 1.0) < 1e-9)
    check("stats subject_count[数学] == 2", st["subject_count"].get("数学") == 2)
    check("stats top5 size == 3", len(st["top_frequent"]) == 3)

    # 5. 高频
    db2.get_by_id(1)["wrong_count"] = 3
    db2.save()
    db3 = QuestionDB(data_file)
    db3.load()
    freq = db3.get_frequent(2)
    check("frequent(>=2) -> 1", len(freq) == 1 and freq[0]["id"] == 1)

    # 6. 导出
    db3.get_by_id(2)["wrong_count"] = 0
    exp = os.path.join(work, "export.txt")
    check("export_txt", db3.export_txt(exp))
    with open(exp, encoding="utf-8") as f:
        content = f.read()
    check("export contains question text", "1+1" in content)

    # 7. 损坏数据：含非 dict 元素 -> 拒绝
    bad = os.path.join(work, "bad.json")
    with open(bad, "w", encoding="utf-8") as f:
        f.write('[{"id":1,"subject":"数学"}, [1,2,3]]')
    db_bad = QuestionDB(bad)
    check("reject non-dict element", not db_bad.load())

    # 8. 篡改数据：null/负值 -> 规范化
    tampered = os.path.join(work, "tampered.json")
    with open(tampered, "w", encoding="utf-8") as f:
        f.write('[{"id":1,"subject":null,"content":"x","wrong_count":-5}]')
    db_t = QuestionDB(tampered)
    check("load tampered data", db_t.load())
    qq = db_t.get_all()[0]
    check("null subject -> ''", qq["subject"] == "")
    check("negative wrong_count -> 0", qq["wrong_count"] == 0)

    # 9. 顶层非 list -> 拒绝
    bad2 = os.path.join(work, "bad2.json")
    with open(bad2, "w", encoding="utf-8") as f:
        f.write('{"a":1}')
    db_bad2 = QuestionDB(bad2)
    check("reject non-list top-level", not db_bad2.load())


# ============================================================
#  image_scanner 图像处理
# ============================================================
def make_test_image():
    """生成一张人造扫描图：白底 + 三个文字块（黑矩形模拟），块间留白"""
    W, H = 800, 900
    img = np.full((H, W, 3), 255, dtype=np.uint8)
    blocks = [(100, 60, 700, 90), (100, 300, 650, 80), (100, 600, 720, 100)]
    for i, (x, y, w, h) in enumerate(blocks):
        cv2.rectangle(img, (x, y), (x + w, y + h), (0, 0, 0), -1)
        # 在块内留白制造纹理，避免整块黑
        cv2.rectangle(img, (x + 15, y + 12), (x + w - 15, y + h - 12), (255, 255, 255), -1)
        cv2.rectangle(img, (x + 20, y + 18), (x + w - 100, y + h - 18), (30, 30, 30), -1)
    return img


def test_scanner():
    img = make_test_image()
    _, binary = scanner.preprocess(img)
    blocks = scanner.split_questions(binary)
    check("split detects >= 3 blocks (got %d)" % len(blocks), len(blocks) >= 3)

    # 分块高度合理（过滤噪声）
    too_small = [b for b in blocks if b[1] - b[0] < 15]
    check("no too-small noise blocks", not too_small)

    # load_image 对不存在文件报错
    try:
        scanner.load_image("C:/definitely_not_exist_xyz.png")
        check("load_image raises on missing file", False)
    except ValueError:
        check("load_image raises on missing file", True)

    # process_image 集成（OCR 引擎缺失时也应返回结构而非崩溃）
    tmp_dir = tempfile.mkdtemp()
    tmp = os.path.join(tmp_dir, "test_img.png")
    # 用 imencode 写入，兼容中文路径
    ok, buf = cv2.imencode(".png", img)
    buf.tofile(tmp)
    res = scanner.process_image(tmp)
    check("process_image returns dict with success", isinstance(res, dict) and res.get("success") is True)
    check("process_image questions is list", isinstance(res.get("questions"), list))

    # 中文路径下 load_image 应正常工作（imdecode）
    cn_dir = os.path.join(tmp_dir, "中文目录")
    os.makedirs(cn_dir, exist_ok=True)
    cn_path = os.path.join(cn_dir, "扫描图.png")
    buf.tofile(cn_path)
    try:
        img2 = scanner.load_image(cn_path)
        check("load_image works with Chinese path", img2 is not None)
    except Exception as e:
        check("load_image works with Chinese path (error: %s)" % e, False)


if __name__ == "__main__":
    test_db()
    test_scanner()
    print("\n==== RESULT: %d passed, %d failed ====" % (PASS, FAIL))
    sys.exit(1 if FAIL else 0)
