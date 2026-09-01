# 错题本 - Python + OpenCV 版

用 Python 重写原 Java Swing 错题本，图像处理基于 **OpenCV**。

## 文件说明

| 文件 | 说明 |
|------|------|
| `wrong_question_book.py` | 主程序（GUI + 数据管理 + 复习 + 统计 + 导出 + 扫描导入），运行时自动安装依赖 |
| `image_scanner.py` | OpenCV 图像处理模块（预处理 / 题目自动分割 / OCR） |
| `test_core.py` | 无 GUI 核心逻辑测试（数据层 + 图像分割算法） |

## 运行

```bash
python wrong_question_book.py
# 或指定 Python 版本
py -3.x wrong_question_book.py
```

首次运行会自动安装依赖：
- 必需：`numpy`、`opencv-python`（图像处理）、`Pillow`（GUI 图片显示）
- 可选（OCR 引擎，二选一，缺失时扫描导入可手动输入）：
  - `pip install paddlepaddle paddleocr`（中文识别率高，推荐）
  - `pip install pytesseract`（需另装系统 Tesseract）

> 若当前 `python` 命令对应的解释器 tkinter 无法创建窗口（缺 Tcl/Tk，如某些精简版/沙箱运行时），
> 请使用完整安装的 Python（官网安装版自带 tkinter）运行。

## 功能

- **错题管理**：增删改查、双击表格编辑、关键词搜索
- **模拟复习**：全部 / 高频（错≥2次），随机抽题最多 20 题，答案揭晓、标记做错自动 +1
- **统计分析**：总题量、平均错次、各科占比条形图、高频 TOP5
- **导出文本**：`wrong_questions_export.txt`
- **扫描导入**：OpenCV 灰度/自适应二值化/形态学去噪 → 水平投影自动分割题目 → OCR 逐块识别；也支持手动框选 + 裁剪 + OCR

## 数据文件

- `wrong_questions.json` — 数据（JSON，安全，无反序列化风险）
- `wrong_questions.json.bak` — 自动备份
- 保存在程序运行的工作目录，修改/退出自动保存

## 安全与健壮性

- 数据用 JSON 而非 pickle（避免反序列化代码执行风险）
- 加载数据逐条类型校验 + 空值/负数规范化
- 图片加载限制：文件 ≤50MB、像素 ≤1 亿（防解压炸弹/OOM）
- 录入字段长度上限（科目 100 / 题干 2 万 / 答案备注各 5000）
- 中文/Unicode 路径兼容（`np.fromfile + imdecode`）
- 自动安装依赖失败时自动改用 `--user` 重试
