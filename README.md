# Wrong Question Book - Java Swing 可视化版 (v1.2.0)

## 项目结构

```
├── pom.xml                          # Maven 配置（tess4j + org.json 依赖）
├── ocr_processor.py                 # Python OCR 脚本（OpenCV预处理+题目分割+OCR）
├── com/
│   └── mxcloud/
│       ├── Main.java                  # 程序入口
│       ├── WrongQuestion.java         # 错题实体类
│       ├── QuestionDB.java            # 数据管理（CRUD/持久化/导出/统计）
│       ├── WrongQuestionBookGUI.java  # Swing 主界面
│       └── ScanImportDialog.java      # 扫描导入组件（Java OCR + Python OCR）
├── build.bat                          # Windows 一键编译打包脚本
├── build.sh                           # Linux/macOS 编译脚本
└── README.md                          # 本文档
```

## 功能说明

| 功能 | 说明 |
|------|------|
| 添加/编辑/删除错题 | 表单录入，支持双击表格行快速编辑 |
| 关键词搜索 | 搜索框匹配题干和科目 |
| 模拟复习 | 随机抽题/高频专项，答案隐藏→揭晓→标记做错自动+1 |
| 统计分析 | 总题量、平均做错次数、各科占比、高频TOP5 |
| 导出文本 | 一键导出为 `wrong_questions_export.txt` |
| 扫描导入 (Java OCR) | 手动框选/自动分割 + Tesseract OCR 识别 |
| **扫描导入 (Python OCR)** | 调用Python脚本，OpenCV预处理+PaddleOCR识别，准确率更高 |
| 数据持久化 | 启动自动加载，修改/退出自动保存，写入前备份 |

---

## 两种 OCR 方案对比

| 维度 | Java OCR (Tess4J) | Python OCR (OpenCV + PaddleOCR) |
|------|-------------------|----------------------------------|
| 依赖 | tess4j + Tesseract | Python + opencv-python + paddleocr |
| 图像预处理 | 基础裁剪 | 灰度化、自适应二值化、形态学去噪 |
| 题目分割 | 简单水平投影 | 水平投影+动态阈值+相邻块合并 |
| OCR引擎 | Tesseract | PaddleOCR（中文识别率更高） |
| 准确率 | 中等 | 较高（尤其中文和公式） |
| 集成方式 | 同进程调用 | ProcessBuilder 跨进程调用，JSON通信 |
| 适用场景 | 简单清晰的扫描件 | 复杂排版、光照不均、手写标注 |

---

## 环境要求

### Java 端
- **JDK 25 LTS**
- **Maven 3.9+**

### Python 端（Python OCR 功能需要）
- **Python 3.7+**，需在系统 PATH 中
- **opencv-python, numpy**（脚本首次运行会自动尝试安装）
- **paddleocr**（推荐，中文识别率高）或 **pytesseract + tesseract**

---

## Python OCR 环境配置

### 1. 安装 Python

下载安装 Python 3.7+：https://www.python.org/downloads/
安装时勾选 **"Add Python to PATH"**。

验证：
```cmd
python --version
pip --version
```

### 2. 安装依赖（手动安装，推荐）

```cmd
REM 基础依赖（必需）
pip install opencv-python numpy

REM OCR引擎（二选一，推荐PaddleOCR）
REM 方案A：PaddleOCR（推荐，中文识别率高，首次运行自动下载模型）
pip install paddlepaddle paddleocr

REM 方案B：pytesseract（轻量，需额外安装Tesseract引擎）
pip install pytesseract
REM 还需安装 Tesseract OCR 并配置 TESSDATA_PREFIX
```

> **注意**：脚本首次运行时会自动检测并尝试安装缺失的 `opencv-python` 和 `numpy`。
> 如果自动安装失败（如网络问题），请手动执行上述 pip 命令。

### 3. PaddleOCR 首次运行

首次调用 PaddleOCR 时会自动下载中英文模型（约100MB），需要联网。
下载完成后后续运行无需联网。

### 4. 脚本位置

`ocr_processor.py` 必须放在以下任一位置：
1. 程序运行的**当前工作目录**
2. JAR 文件所在目录
3. 通过环境变量 `PYTHON_OCR_SCRIPT` 指定绝对路径

---

## 编译与运行

### Maven（推荐）

```bash
# 编译
mvn compile

# 运行
mvn exec:java -Dexec.mainClass="com.mxcloud.Main"

# 打包（含所有依赖的 fat jar）
mvn package
# 生成 target/wrong-question-book-1.2.0-jar-with-dependencies.jar
```

### 运行打包后的 JAR

```bash
java -jar target/wrong-question-book-1.2.0-jar-with-dependencies.jar
```

> **重要**：运行时确保 `ocr_processor.py` 在当前目录或JAR同目录，Python OCR功能才能使用。

---

## Python OCR 使用流程

1. 主界面点击 **[Scan & Import]**
2. 点击 **[Load Image]** 加载扫描图片
3. 点击 **[Python OCR]** 按钮
4. 确认对话框后，程序自动：
   - 保存临时图片
   - 调用 `python ocr_processor.py <图片路径>`
   - Python端：OpenCV预处理 → 水平投影分割题目 → PaddleOCR识别
   - 返回JSON结果，Java端解析并批量录入
5. 录入完成后，到主列表**逐条编辑**，补充正确答案和错误答案

### 通信协议

Java → Python（命令行参数）：
```
python ocr_processor.py "C:\temp\scan_001.png"
```

Python → Java（stdout，JSON）：
```json
{
  "success": true,
  "count": 3,
  "blocks_detected": 5,
  "questions": [
    {
      "index": 1,
      "content": "1. 下列关于数据结构的说法正确的是...",
      "region": {"y": 50, "height": 120}
    }
  ]
}
```

错误时：
```json
{"success": false, "error": "Cannot read image: ..."}
```

---

## Java OCR (Tess4J) 使用说明

如果不使用Python OCR，也可以使用内置的Java OCR：

- **手动框选**：拖拽框选 → Crop → OCR Recognize → 校对 → Add to Book
- **自动分割**：Auto Split All（水平投影自动检测文本块，批量Tesseract识别）

需要安装 Tesseract OCR 并配置 `TESSDATA_PREFIX`，详见 v1.1.0 文档。

---

## 打包成 EXE

### jpackage（JDK 14+）

```bash
jpackage --name WrongQuestionBook ^
         --input target ^
         --main-jar wrong-question-book-1.2.0-jar-with-dependencies.jar ^
         --main-class com.mxcloud.Main ^
         --type exe ^
         --win-dir-chooser ^
         --win-menu ^
         --win-shortcut ^
         --app-version 1.2.0
```

> **注意**：EXE运行时仍需要系统安装 Java 和 Python（Python OCR功能）。
> 如需完全免安装Java，先用 `jlink` 生成精简JRE，再加 `--runtime-image` 参数。

---

## 数据文件

| 文件 | 说明 |
|------|------|
| `wrong_questions.dat` | 错题数据（Java序列化二进制） |
| `wrong_questions.dat.bak` | 自动备份 |
| `wrong_questions_export.txt` | 导出的文本错题集 |

数据文件保存在**程序运行的当前工作目录**。

---

## 常见问题

**Q: 点击 Python OCR 提示 "Python is not available in PATH"？**
A: 安装Python时勾选"Add Python to PATH"，或手动将Python安装目录加入系统PATH。重启命令行/IDE后重试。

**Q: Python OCR 运行失败，控制台有报错？**
A: 检查：1) opencv-python和numpy是否安装成功；2) PaddleOCR或pytesseract是否安装；3) 图片路径是否包含中文（建议用英文路径）。

**Q: PaddleOCR 首次运行很慢？**
A: 首次运行会自动下载中英文模型（约100MB），请耐心等待。后续运行会快很多。

**Q: 识别准确率低？**
A: 提高扫描分辨率(300DPI+)、确保图片端正、光线均匀。识别后务必人工校对，尤其是数学公式。

**Q: 数学公式识别乱码？**
A: PaddleOCR对普通文本效果好，但复杂数学公式仍有限。建议公式部分手动补充，或使用专门的公式OCR工具（如Mathpix）。

**Q: Maven 下载依赖慢？**
A: 配置阿里云Maven镜像，在 `settings.xml` 中添加：
```xml
<mirror>
  <id>aliyun</id>
  <mirrorOf>central</mirrorOf>
  <url>https://maven.aliyun.com/repository/central</url>
</mirror>
```

**Q: ocr_processor.py 找不到？**
A: 将脚本放在JAR同目录或程序运行目录，或设置环境变量 `PYTHON_OCR_SCRIPT` 指向脚本绝对路径。
