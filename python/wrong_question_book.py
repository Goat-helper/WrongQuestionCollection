# -*- coding: utf-8 -*-
"""
wrong_question_book.py — 错题本（Python + OpenCV 版）
======================================================
用 Python 重写原 Java Swing 错题本项目，图像处理基于 OpenCV。

功能：
  - 错题增删改查、关键词搜索
  - 模拟复习（随机抽题 / 高频专项，答案揭晓，标记做错自动 +1）
  - 统计分析（总题量、平均做错次数、各科占比条形图、高频 TOP5）
  - 导出文本
  - 扫描导入（OpenCV 图像预处理 + 题目自动分割 + OCR 识别）
  - 数据 JSON 持久化（启动加载、修改/退出自动保存、写入前备份）

依赖（脚本运行时自动安装）：
  - 必需：numpy、opencv-python（图像处理）、Pillow（GUI 图片显示）
  - 可选：paddleocr / pytesseract（OCR 引擎，缺失时扫描导入可手动输入）

运行：  python wrong_question_book.py
"""

import sys
import os
import json
import shutil
import importlib
import subprocess
import datetime
import random

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


# 核心依赖
ensure_package("numpy", "numpy")
ensure_package("opencv-python", "cv2")
ensure_package("Pillow", "PIL")

import tkinter as tk
from tkinter import ttk, filedialog, messagebox
from PIL import Image, ImageTk

# OpenCV 图像处理模块（同目录）
import image_scanner as scanner

# ============================================================
#  数据层：错题实体（用 dict 承载）与数据库管理
# ============================================================
# 字段：id / subject / content / wrong_ans / right_ans / tip / wrong_count

# 录入长度上限（防止超长文本拖垮界面与数据文件）
LIMIT = {"subject": 100, "content": 20000,
         "wrong_ans": 5000, "right_ans": 5000, "tip": 5000}


def new_question(subject="", content="", wrong_ans="", right_ans="", tip="", wrong_count=1):
    return {"id": 0, "subject": subject, "content": content,
            "wrong_ans": wrong_ans, "right_ans": right_ans,
            "tip": tip, "wrong_count": wrong_count}


class QuestionDB:
    """错题数据管理：JSON 持久化 + 备份 + CRUD + 统计 + 导出 + 检索"""

    def __init__(self, data_file="wrong_questions.json"):
        self.data_file = data_file
        self.backup_file = data_file + ".bak"
        self.data = []          # list[dict]
        self.next_id = 1

    # ---------- 持久化 ----------
    def load(self):
        """从 JSON 加载数据；文件不存在返回 False。JSON 天然避免反序列化代码执行风险。"""
        if not os.path.exists(self.data_file):
            return False
        try:
            with open(self.data_file, "r", encoding="utf-8") as f:
                raw = json.load(f)
            if not isinstance(raw, list):
                print("[DB] Invalid data file: top-level is not a list.")
                return False
            loaded = []
            for item in raw:
                if not isinstance(item, dict):
                    print("[DB] Invalid data file: unexpected element type %s rejected."
                          % type(item).__name__)
                    return False
                loaded.append(self._normalize(item))
            self.data = loaded
            self.next_id = max([q.get("id", 0) for q in self.data], default=0) + 1
            return True
        except (ValueError, OSError) as e:
            print("[DB] Failed to load data file: %s" % e)
            return False

    def save(self):
        """保存：临时文件写入 -> 备份 -> 原子替换，避免中途失败破坏原数据"""
        tmp_file = self.data_file + ".tmp"
        try:
            with open(tmp_file, "w", encoding="utf-8") as f:
                json.dump(self.data, f, ensure_ascii=False, indent=2)
        except OSError as e:
            print("[DB] Save failed: %s" % e)
            return False

        if os.path.exists(self.data_file):
            try:
                if os.path.exists(self.backup_file):
                    os.remove(self.backup_file)
                shutil.move(self.data_file, self.backup_file)
            except OSError as e:
                print("[DB] Backup failed (continuing): %s" % e)

        try:
            shutil.move(tmp_file, self.data_file)
            return True
        except OSError as e:
            print("[DB] Save failed: %s" % e)
            if os.path.exists(tmp_file):
                os.remove(tmp_file)
            return False

    @staticmethod
    def _normalize(item):
        """校验并规范化数据，防止篡改/空值造成异常"""
        q = new_question()
        q["id"] = int(item.get("id", 0) or 0)
        for k in ("subject", "content", "wrong_ans", "right_ans", "tip"):
            v = item.get(k)
            q[k] = v if isinstance(v, str) else ""
        wc = item.get("wrong_count", 1)
        q["wrong_count"] = int(wc) if isinstance(wc, (int, float)) and int(wc) >= 0 else 0
        return q

    # ---------- CRUD ----------
    def add(self, q):
        q["id"] = self.next_id
        self.next_id += 1
        self.data.append(q)
        return q["id"]

    def find_index_by_id(self, qid):
        for i, q in enumerate(self.data):
            if q["id"] == qid:
                return i
        return -1

    def get_by_id(self, qid):
        i = self.find_index_by_id(qid)
        return self.data[i] if i >= 0 else None

    def remove_by_id(self, qid):
        i = self.find_index_by_id(qid)
        if i < 0:
            return False
        self.data.pop(i)
        return True

    def size(self):
        return len(self.data)

    def get_all(self):
        return self.data

    def get_next_id(self):
        return self.next_id

    # ---------- 导出 ----------
    def export_txt(self, export_file="wrong_questions_export.txt"):
        if not self.data:
            return False
        with open(export_file, "w", encoding="utf-8") as pw:
            pw.write("==================================================\n")
            pw.write("          WRONG QUESTION BOOK - EXPORT\n")
            pw.write("==================================================\n")
            pw.write("Export time: %s\n" % datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"))
            pw.write("Total questions: %d\n\n" % len(self.data))
            for q in self.data:
                pw.write("--------------------------------------------------\n")
                pw.write("[%d] Subject: %s    Wrong count: %d\n"
                         % (q["id"], q["subject"], q["wrong_count"]))
                pw.write("Question: %s\n" % q["content"])
                pw.write("Your wrong answer: %s\n"
                         % (q["wrong_ans"] if q["wrong_ans"] else "(not filled)"))
                pw.write("Correct answer: %s\n" % q["right_ans"])
                pw.write("Note / tip: %s\n" % (q["tip"] if q["tip"] else "(none)"))
                pw.write("\n")
        return True

    # ---------- 统计 ----------
    def get_statistics(self):
        if not self.data:
            return None
        total_wrong = sum(q["wrong_count"] for q in self.data)
        avg = total_wrong / len(self.data)
        subject_count = {}
        for q in self.data:
            subject_count[q["subject"]] = subject_count.get(q["subject"], 0) + 1
        top = sorted(self.data, key=lambda q: q["wrong_count"], reverse=True)[:5]
        return {"total": len(self.data), "avg_wrong": avg,
                "subject_count": subject_count, "top_frequent": top}

    # ---------- 检索 ----------
    def search_by_keyword(self, keyword):
        kw = (keyword or "").strip().lower()
        if not kw:
            return list(self.data)
        result = []
        for q in self.data:
            if kw in q["content"].lower() or kw in q["subject"].lower():
                result.append(q)
        return result

    def get_frequent(self, min_count=2):
        return [q for q in self.data if q["wrong_count"] >= min_count]


# ============================================================
#  主界面
# ============================================================
class WrongQuestionBookApp(tk.Tk):
    FONT = ("Microsoft YaHei UI", 10)

    def __init__(self):
        super().__init__()
        self.title("错题本 - Python + OpenCV 版")
        self.geometry("980x620")
        self.minsize(820, 500)

        self.db = QuestionDB()
        loaded = self.db.load()
        self.current_list = list(self.db.get_all())

        self._build_ui()

        if not loaded:
            self.status_var.set("未找到数据文件，已新建错题本。")
        else:
            self._update_status()

        # 关闭窗口时自动保存
        self.protocol("WM_DELETE_WINDOW", self._on_close)

    # ---------- 界面 ----------
    def _build_ui(self):
        self.status_var = tk.StringVar(value=" ")
        default_font = self.FONT
        self.option_add("*Font", default_font)

        toolbar = tk.Frame(self)
        toolbar.pack(side=tk.TOP, fill=tk.X, padx=6, pady=4)

        def btn(text, cmd):
            b = tk.Button(toolbar, text=text, command=cmd, padx=8)
            b.pack(side=tk.LEFT, padx=2)
            return b

        btn("添加", self.add_question)
        btn("编辑", self.edit_selected)
        btn("删除", self.delete_selected)
        tk.Frame(toolbar, width=12).pack(side=tk.LEFT)

        self.search_var = tk.StringVar()
        entry = tk.Entry(toolbar, textvariable=self.search_var, width=18)
        entry.pack(side=tk.LEFT, padx=2)
        entry.bind("<Return>", lambda e: self.do_search())
        btn("搜索", self.do_search)
        btn("全部", self.refresh_all)

        tk.Frame(toolbar, width=12).pack(side=tk.LEFT)
        btn("复习", self.start_review)
        btn("统计", self.show_statistics)
        btn("导出", self.export_data)
        btn("刷新", self.refresh_all)
        tk.Frame(toolbar, width=12).pack(side=tk.LEFT)
        btn("扫描导入", self.start_scan_import)

        # 表格
        columns = ("id", "subject", "content", "wrong_count")
        self.tree = ttk.Treeview(self, columns=columns, show="headings", selectmode="browse")
        headers = {"id": "ID", "subject": "科目", "content": "题目", "wrong_count": "错次"}
        widths = {"id": 60, "subject": 120, "content": 560, "wrong_count": 70}
        for c in columns:
            self.tree.heading(c, text=headers[c])
            self.tree.column(c, width=widths[c], anchor="w",
                             stretch=(c == "content"))
        vsb = ttk.Scrollbar(self, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=vsb.set)
        self.tree.pack(side=tk.TOP, fill=tk.BOTH, expand=True, padx=6)
        vsb.pack(side=tk.RIGHT, fill=tk.Y)

        self.tree.bind("<Double-1>", lambda e: self.edit_selected())

        status = tk.Label(self, textvariable=self.status_var, anchor="w",
                          relief=tk.SUNKEN, bd=1)
        status.pack(side=tk.BOTTOM, fill=tk.X)

    # ---------- 数据展示 ----------
    def _refresh_table(self):
        for item in self.tree.get_children():
            self.tree.delete(item)
        for q in self.current_list:
            self.tree.insert("", "end", values=(
                q["id"], q["subject"], q["content"], q["wrong_count"]))
        self._update_status()

    def _update_status(self):
        self.status_var.set("共 %d 题 | 当前显示 %d 条" % (self.db.size(), len(self.current_list)))

    def refresh_all(self):
        self.current_list = list(self.db.get_all())
        self.search_var.set("")
        self._refresh_table()

    def do_search(self):
        self.current_list = self.db.search_by_keyword(self.search_var.get())
        self._refresh_table()

    def _selected(self):
        sel = self.tree.selection()
        if not sel:
            return None
        idx = self.tree.index(sel[0])
        if 0 <= idx < len(self.current_list):
            return self.current_list[idx]
        return None

    # ---------- 增删改 ----------
    def add_question(self):
        dlg = AddEditDialog(self, None)
        self.wait_window(dlg)
        if dlg.confirmed:
            qid = self.db.add(dlg.result)
            self.db.save()
            self.refresh_all()
            messagebox.showinfo("成功", "题目已添加，ID: #%d" % qid)

    def edit_selected(self):
        q = self._selected()
        if q is None:
            messagebox.showwarning("提示", "请先选中一道题目。")
            return
        dlg = AddEditDialog(self, q)
        self.wait_window(dlg)
        if dlg.confirmed:
            nq = dlg.result
            q.update(nq)
            self.db.save()
            self._refresh_table()
            messagebox.showinfo("成功", "题目已更新。")

    def delete_selected(self):
        q = self._selected()
        if q is None:
            messagebox.showwarning("提示", "请先选中一道题目。")
            return
        if messagebox.askyesno("确认删除",
                               "确定删除题目 #%d？\n%s" % (q["id"], q["content"])):
            self.db.remove_by_id(q["id"])
            self.db.save()
            self.refresh_all()
            messagebox.showinfo("成功", "题目已删除。")

    # ---------- 复习 / 统计 / 导出 / 扫描 ----------
    def start_review(self):
        if self.db.size() == 0:
            messagebox.showinfo("提示", "暂无题目可复习。")
            return
        dlg = ReviewDialog(self, self.db)
        self.wait_window(dlg)
        self.refresh_all()

    def show_statistics(self):
        stats = self.db.get_statistics()
        if stats is None:
            messagebox.showinfo("提示", "暂无数据。")
            return
        StatisticsDialog(self, stats)

    def export_data(self):
        if self.db.size() == 0:
            messagebox.showinfo("提示", "暂无题目可导出。")
            return
        if self.db.export_txt():
            messagebox.showinfo("导出成功",
                                "已导出 %d 道题到 wrong_questions_export.txt" % self.db.size())
        else:
            messagebox.showerror("错误", "导出失败，请检查文件权限。")

    def start_scan_import(self):
        dlg = ScanImportDialog(self, self.db)
        self.wait_window(dlg)
        self.refresh_all()

    def _on_close(self):
        self.db.save()
        self.destroy()


# ============================================================
#  录入 / 编辑对话框
# ============================================================
class AddEditDialog(tk.Toplevel):
    def __init__(self, parent, existing):
        super().__init__(parent)
        self.title("编辑题目 #%d" % existing["id"] if existing else "添加题目")
        self.resizable(False, False)
        self.confirmed = False
        self.result = None

        src = existing if existing else new_question()

        self.subj_var = tk.StringVar(value=src["subject"])
        self.content_var = tk.StringVar(value=src["content"])
        self.wrong_var = tk.StringVar(value=src["wrong_ans"])
        self.right_var = tk.StringVar(value=src["right_ans"])
        self.tip_var = tk.StringVar(value=src["tip"])

        pad = {"padx": 8, "pady": 4}
        body = tk.Frame(self)
        body.pack(fill=tk.BOTH, expand=True)

        tk.Label(body, text="科目 *：").grid(row=0, column=0, sticky="w", **pad)
        tk.Entry(body, textvariable=self.subj_var, width=40).grid(row=0, column=1, sticky="we", **pad)

        tk.Label(body, text="题干 *：").grid(row=1, column=0, sticky="nw", **pad)
        self.content_txt = tk.Text(body, width=40, height=6, wrap="word")
        self.content_txt.insert("1.0", src["content"])
        self.content_txt.grid(row=1, column=1, sticky="we", **pad)

        tk.Label(body, text="我的答案：").grid(row=2, column=0, sticky="w", **pad)
        tk.Entry(body, textvariable=self.wrong_var, width=40).grid(row=2, column=1, sticky="we", **pad)

        tk.Label(body, text="正确答案 *：").grid(row=3, column=0, sticky="w", **pad)
        tk.Entry(body, textvariable=self.right_var, width=40).grid(row=3, column=1, sticky="we", **pad)

        tk.Label(body, text="备注：").grid(row=4, column=0, sticky="w", **pad)
        tk.Entry(body, textvariable=self.tip_var, width=40).grid(row=4, column=1, sticky="we", **pad)

        btns = tk.Frame(self)
        btns.pack(fill=tk.X, pady=8)
        tk.Button(btns, text="确定", width=10, command=self._on_ok).pack(side=tk.RIGHT, padx=8)
        tk.Button(btns, text="取消", width=10, command=self.destroy).pack(side=tk.RIGHT)

        self.transient(parent)
        self.grab_set()
        self.center_on(parent)

    def center_on(self, parent):
        self.update_idletasks()
        w, h = self.winfo_width(), self.winfo_height()
        x = parent.winfo_rootx() + (parent.winfo_width() - w) // 2
        y = parent.winfo_rooty() + (parent.winfo_height() - h) // 2
        self.geometry("+%d+%d" % (max(0, x), max(0, y)))

    def _on_ok(self):
        subj = self.subj_var.get().strip()
        content = self.content_txt.get("1.0", "end").strip()
        wrong = self.wrong_var.get().strip()
        right = self.right_var.get().strip()
        tip = self.tip_var.get().strip()

        if not subj:
            messagebox.showwarning("校验", "科目不能为空。", parent=self)
            return
        if not content:
            messagebox.showwarning("校验", "题干不能为空。", parent=self)
            return
        if not right:
            messagebox.showwarning("校验", "正确答案不能为空。", parent=self)
            return
        if len(subj) > LIMIT["subject"]:
            messagebox.showwarning("校验", "科目过长（最多 %d 字）。" % LIMIT["subject"], parent=self)
            return
        if len(content) > LIMIT["content"]:
            messagebox.showwarning("校验", "题干过长（最多 %d 字）。" % LIMIT["content"], parent=self)
            return
        if len(wrong) > LIMIT["wrong_ans"] or len(right) > LIMIT["right_ans"] or len(tip) > LIMIT["tip"]:
            messagebox.showwarning("校验", "答案/备注过长（各最多 5000 字）。", parent=self)
            return

        self.result = new_question(subj, content, wrong, right, tip)
        self.confirmed = True
        self.destroy()


# ============================================================
#  复习对话框
# ============================================================
class ReviewDialog(tk.Toplevel):
    def __init__(self, parent, db):
        super().__init__(parent)
        self.title("复习模式")
        self.geometry("560x480")
        self.db = db

        # 选择复习范围
        choice = messagebox.askquestion(
            "复习范围", "选择「高频（错>=2次）」复习？\n选“否”则复习全部题目。",
            icon="question", parent=self)
        if choice == "yes":
            review = db.get_frequent(2)
            if not review:
                messagebox.showinfo("提示", "暂无高频题目，改为复习全部。", parent=self)
                review = list(db.get_all())
        else:
            review = list(db.get_all())

        random.shuffle(review)
        self.review_list = review[:20]
        self.current_index = 0
        self.wrong_again = 0

        top = tk.Frame(self)
        top.pack(fill=tk.X, pady=6)
        self.progress_var = tk.StringVar()
        self.subject_var = tk.StringVar()
        tk.Label(top, textvariable=self.progress_var, font=("Microsoft YaHei UI", 12, "bold")).pack()
        tk.Label(top, textvariable=self.subject_var, fg="blue").pack()

        q_frame = tk.LabelFrame(self, text="题目")
        q_frame.pack(fill=tk.BOTH, expand=True, padx=8)
        self.question_txt = tk.Text(q_frame, wrap="word", font=("Microsoft YaHei UI", 12))
        self.question_txt.pack(fill=tk.BOTH, expand=True, padx=4, pady=4)

        a_frame = tk.LabelFrame(self, text="答案（隐藏）")
        a_frame.pack(fill=tk.BOTH, expand=True, padx=8, pady=6)
        self.answer_txt = tk.Text(a_frame, wrap="word", font=("Microsoft YaHei UI", 11))
        self.answer_txt.pack(fill=tk.BOTH, expand=True, padx=4, pady=4)

        btns = tk.Frame(self)
        btns.pack(fill=tk.X, pady=8)
        self.btn_reveal = tk.Button(btns, text="显示答案", width=12, command=self.reveal_answer)
        self.btn_wrong = tk.Button(btns, text="做错了(+1)", width=12, command=self.mark_wrong, state=tk.DISABLED)
        self.btn_correct = tk.Button(btns, text="做对了", width=12, command=self.mark_correct, state=tk.DISABLED)
        self.btn_next = tk.Button(btns, text="下一题 >>", width=12, command=self.next_question, state=tk.DISABLED)
        for b in (self.btn_reveal, self.btn_wrong, self.btn_correct, self.btn_next):
            b.pack(side=tk.LEFT, padx=6)

        self._set_readonly(self.question_txt)
        self._set_readonly(self.answer_txt)
        self.show_question()

    @staticmethod
    def _set_readonly(txt):
        txt.configure(state=tk.NORMAL)

    def show_question(self):
        if self.current_index >= len(self.review_list):
            self.finish_review()
            return
        q = self.review_list[self.current_index]
        self.progress_var.set("第 %d / %d 题" % (self.current_index + 1, len(self.review_list)))
        self.subject_var.set("[%s]  ID #%d  （历史错 %d 次）"
                             % (q["subject"], q["id"], q["wrong_count"]))
        self.question_txt.delete("1.0", "end")
        self.question_txt.insert("1.0", q["content"])
        self.answer_txt.delete("1.0", "end")
        self.btn_reveal.configure(state=tk.NORMAL)
        self.btn_wrong.configure(state=tk.DISABLED)
        self.btn_correct.configure(state=tk.DISABLED)
        self.btn_next.configure(state=tk.DISABLED)
        self.btn_reveal.focus_set()

    def reveal_answer(self):
        q = self.review_list[self.current_index]
        text = "我的错误答案：%s\n\n正确答案：%s\n\n备注：%s" % (
            q["wrong_ans"] if q["wrong_ans"] else "（未填写）",
            q["right_ans"],
            q["tip"] if q["tip"] else "（无）")
        self.answer_txt.delete("1.0", "end")
        self.answer_txt.insert("1.0", text)
        self.btn_reveal.configure(state=tk.DISABLED)
        self.btn_wrong.configure(state=tk.NORMAL)
        self.btn_correct.configure(state=tk.NORMAL)
        self.btn_correct.focus_set()

    def mark_wrong(self):
        q = self.review_list[self.current_index]
        q["wrong_count"] += 1
        self.wrong_again += 1
        self.db.save()
        self.btn_wrong.configure(state=tk.DISABLED)
        self.btn_correct.configure(state=tk.DISABLED)
        self.btn_next.configure(state=tk.NORMAL)
        messagebox.showinfo("已记录", "题目 #%d 错次已更新为 %d。" % (q["id"], q["wrong_count"]), parent=self)

    def mark_correct(self):
        self.btn_wrong.configure(state=tk.DISABLED)
        self.btn_correct.configure(state=tk.DISABLED)
        self.btn_next.configure(state=tk.NORMAL)

    def next_question(self):
        self.current_index += 1
        self.show_question()

    def finish_review(self):
        total = len(self.review_list)
        correct = total - self.wrong_again
        rate = correct / total * 100.0 if total else 0.0
        messagebox.showinfo(
            "复习完成",
            "复习完成！\n\n本次复习：%d 题\n再次做错：%d 题\n答对：%d 题\n正确率：%.1f%%"
            % (total, self.wrong_again, correct, rate),
            parent=self)
        self.destroy()


# ============================================================
#  统计对话框（含各科占比条形图）
# ============================================================
class StatisticsDialog(tk.Toplevel):
    def __init__(self, parent, stats):
        super().__init__(parent)
        self.title("统计")
        self.geometry("560x520")
        self.resizable(False, False)

        info = ("共 %d 题 | 平均错 %.2f 次" % (stats["total"], stats["avg_wrong"]))
        tk.Label(self, text=info, font=("Microsoft YaHei UI", 12, "bold")).pack(pady=8)

        # 各科占比条形图（Canvas 绘制）
        canvas_frame = tk.LabelFrame(self, text="各科占比")
        canvas_frame.pack(fill=tk.BOTH, expand=True, padx=10)
        canvas = tk.Canvas(canvas_frame, height=200, bg="white")
        canvas.pack(fill=tk.BOTH, expand=True, padx=6, pady=6)

        sc = stats["subject_count"]
        if sc:
            n = len(sc)
            chart_w = 520
            bar_h = 24
            gap = 8
            total_h = n * (bar_h + gap) + 10
            max_count = max(sc.values())
            for i, (subj, cnt) in enumerate(sc.items()):
                y = 10 + i * (bar_h + gap)
                pct = cnt / stats["total"] * 100.0
                bar_w = int((chart_w - 160) * cnt / max_count)
                canvas.create_rectangle(150, y, 150 + bar_w, y + bar_h,
                                        fill="#4f81bd", outline="")
                canvas.create_text(10, y + bar_h / 2, anchor="w",
                                   text="%s" % subj, font=("Microsoft YaHei UI", 9))
                canvas.create_text(150 + bar_w + 8, y + bar_h / 2, anchor="w",
                                   text="%d 题 (%.1f%%)" % (cnt, pct),
                                   font=("Microsoft YaHei UI", 9))
            canvas.configure(scrollregion=(0, 0, chart_w, max(220, total_h)))

        # 高频 TOP5
        top_frame = tk.LabelFrame(self, text="高频 TOP5")
        top_frame.pack(fill=tk.BOTH, expand=True, padx=10, pady=6)
        top_txt = tk.Text(top_frame, wrap="word", height=6)
        top_txt.pack(fill=tk.BOTH, expand=True, padx=6, pady=6)
        for i, q in enumerate(stats["top_frequent"], 1):
            brief = q["content"][:40] + ("..." if len(q["content"]) > 40 else "")
            top_txt.insert("end", "#%d [ID:%d][%s] 错%d次 - %s\n"
                           % (i, q["id"], q["subject"], q["wrong_count"], brief))
        top_txt.configure(state=tk.DISABLED)

        tk.Button(self, text="关闭", width=12, command=self.destroy).pack(pady=8)


# ============================================================
#  扫描导入对话框（OpenCV 图像处理 + OCR）
# ============================================================
class ScanImportDialog(tk.Toplevel):
    def __init__(self, parent, db):
        super().__init__(parent)
        self.title("扫描导入 - OpenCV 图像处理 + OCR")
        self.geometry("1050x720")
        self.db = db

        self.img_bgr = None            # OpenCV BGR 原图
        self.cropped_bgr = None        # 裁剪选区
        self.selection = None          # 显示坐标系下的 (x1,y1,x2,y2)
        self.scale_x = 1.0
        self.scale_y = 1.0
        self.tk_img = None             # 保持引用防止被 GC

        self._build_ui()

        if not scanner.ocr_available():
            self.status_var.set("警告：未检测到 OCR 引擎，可裁剪后手动输入（或安装 paddleocr/pytesseract）。")

    # ---------- 界面 ----------
    def _build_ui(self):
        self.status_var = tk.StringVar(value=" ")
        toolbar = tk.Frame(self)
        toolbar.pack(side=tk.TOP, fill=tk.X, padx=6, pady=4)

        def btn(text, cmd):
            b = tk.Button(toolbar, text=text, command=cmd, padx=8)
            b.pack(side=tk.LEFT, padx=2)
            return b

        btn("加载图片", self.load_image)
        btn("裁剪选区", self.crop_selection)
        btn("OCR识别", self.do_ocr)
        btn("录入", self.add_to_book)
        tk.Frame(toolbar, width=12).pack(side=tk.LEFT)
        btn("自动分割", self.auto_split)
        tk.Frame(toolbar, width=12).pack(side=tk.LEFT)
        btn("关闭", self.destroy)

        # 左右分栏
        main = tk.PanedWindow(self, orient=tk.HORIZONTAL, sashwidth=5)
        main.pack(fill=tk.BOTH, expand=True, padx=6)

        # 左：图片显示 + 框选
        left = tk.Frame(main)
        self.canvas = tk.Canvas(left, bg="#666666", width=640, height=560,
                                cursor="crosshair")
        self.canvas.pack(fill=tk.BOTH, expand=True)
        self.canvas.bind("<Button-1>", self._on_mouse_down)
        self.canvas.bind("<B1-Motion>", self._on_mouse_drag)
        self.canvas.bind("<ButtonRelease-1>", self._on_mouse_up)
        main.add(left, stretch="always")

        # 右：预览 + OCR + 科目
        right = tk.Frame(main, width=360)
        preview = tk.LabelFrame(right, text="裁剪预览")
        preview.pack(fill=tk.X, padx=4, pady=4)
        self.preview_label = tk.Label(preview, text="（无裁剪）", width=44, height=8,
                                      bg="#eeeeee")
        self.preview_label.pack(padx=4, pady=4)

        ocr = tk.LabelFrame(right, text="OCR 结果（可编辑，请校对）")
        ocr.pack(fill=tk.BOTH, expand=True, padx=4, pady=4)
        self.ocr_txt = tk.Text(ocr, wrap="word", width=40, height=12,
                               font=("Consolas", 10))
        self.ocr_txt.pack(fill=tk.BOTH, expand=True, padx=4, pady=4)

        subj = tk.Frame(right)
        subj.pack(fill=tk.X, padx=4, pady=4)
        tk.Label(subj, text="科目：").pack(side=tk.LEFT)
        self.subj_var = tk.StringVar(value="导入")
        tk.Entry(subj, textvariable=self.subj_var, width=14).pack(side=tk.LEFT, padx=4)

        status = tk.Label(self, textvariable=self.status_var, anchor="w",
                          relief=tk.SUNKEN, bd=1)
        status.pack(side=tk.BOTTOM, fill=tk.X)
        main.add(right, stretch="never")

    # ---------- 图片加载与显示 ----------
    def load_image(self):
        path = filedialog.askopenfilename(
            title="选择图片", filetypes=[("图片", "*.jpg *.jpeg *.png *.bmp *.tiff *.tif")])
        if not path:
            return
        try:
            self.img_bgr = scanner.load_image(path)
        except ValueError as e:
            messagebox.showerror("错误", str(e), parent=self)
            return
        self.selection = None
        self.cropped_bgr = None
        self.preview_label.configure(image="", text="（无裁剪）")
        self.ocr_txt.delete("1.0", "end")
        self._show_image(self.img_bgr)
        h, w = self.img_bgr.shape[:2]
        self.status_var.set("已加载：%s（%dx%d）" % (os.path.basename(path), w, h))

    def _show_image(self, bgr):
        """在 Canvas 中显示 BGR 图像（缩放适配），并记录坐标换算比例"""
        rgb = bgr[:, :, ::-1]  # BGR -> RGB
        pil = Image.fromarray(rgb)
        self.canvas.delete("all")
        cw, ch = self.canvas.winfo_width(), self.canvas.winfo_height()
        if cw < 50 or ch < 50:
            cw, ch = 640, 560
        iw, ih = pil.size
        scale = min(cw / iw, ch / ih, 1.5)
        disp_w, disp_h = max(1, int(iw * scale)), max(1, int(ih * scale))
        pil = pil.resize((disp_w, disp_h), Image.LANCZOS)
        self.tk_img = ImageTk.PhotoImage(pil)
        self.canvas.create_image(0, 0, anchor="nw", image=self.tk_img)
        self.scale_x = disp_w / iw
        self.scale_y = disp_h / ih

    # ---------- 框选 ----------
    def _on_mouse_down(self, e):
        if self.img_bgr is None:
            return
        self._drag_start = (e.x, e.y)

    def _on_mouse_drag(self, e):
        if self.img_bgr is None or not hasattr(self, "_drag_start"):
            return
        x1, y1 = self._drag_start
        x2, y2 = e.x, e.y
        self.canvas.delete("sel_rect")
        self.canvas.create_rectangle(min(x1, x2), min(y1, y2),
                                     max(x1, x2), max(y1, y2),
                                     outline="blue", width=2, tags="sel_rect")

    def _on_mouse_up(self, e):
        if self.img_bgr is None or not hasattr(self, "_drag_start"):
            return
        x1, y1 = self._drag_start
        x2, y2 = e.x, e.y
        self._drag_start = None
        if abs(x2 - x1) < 3 or abs(y2 - y1) < 3:
            return
        self.selection = (min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
        w = abs(x2 - x1)
        h = abs(y2 - y1)
        self.status_var.set("已选区域：%dx%d 像素（显示坐标），点击「裁剪选区」。" % (w, h))

    def _to_original(self, x1, y1, x2, y2):
        """把显示坐标换算回原图坐标，并夹紧到图像边界"""
        h, w = self.img_bgr.shape[:2]
        ox1 = max(0, int(x1 / self.scale_x))
        oy1 = max(0, int(y1 / self.scale_y))
        ox2 = min(w, int(x2 / self.scale_x))
        oy2 = min(h, int(y2 / self.scale_y))
        return ox1, oy1, ox2, oy2

    # ---------- 裁剪 / OCR / 录入 ----------
    def crop_selection(self):
        if self.img_bgr is None:
            messagebox.showwarning("提示", "请先加载图片。", parent=self)
            return
        if self.selection is None:
            messagebox.showwarning("提示", "请先在图上拖拽框选区域。", parent=self)
            return
        x1, y1, x2, y2 = self._to_original(*self.selection)
        if x2 - x1 < 5 or y2 - y1 < 5:
            messagebox.showwarning("提示", "选区太小。", parent=self)
            return
        self.cropped_bgr = self.img_bgr[y1:y2, x1:x2]
        self._show_preview(self.cropped_bgr)
        h, w = self.cropped_bgr.shape[:2]
        self.status_var.set("已裁剪：%dx%d，点击「OCR识别」。" % (w, h))

    def _show_preview(self, bgr):
        rgb = bgr[:, :, ::-1]
        pil = Image.fromarray(rgb)
        pil.thumbnail((300, 150), Image.LANCZOS)
        photo = ImageTk.PhotoImage(pil)
        self.preview_label.configure(image=photo, text="")
        self.preview_label.image = photo

    def do_ocr(self):
        if self.cropped_bgr is None:
            if self.img_bgr is None:
                messagebox.showwarning("提示", "请先加载并裁剪图片。", parent=self)
                return
            self.cropped_bgr = self.img_bgr
        if not scanner.ocr_available():
            messagebox.showwarning(
                "OCR 不可用",
                "未检测到 OCR 引擎。\n\n"
                "请安装其一：\n"
                "  1. pip install paddlepaddle paddleocr   （中文识别率高）\n"
                "  2. pip install pytesseract + 系统 Tesseract\n\n"
                "或在下方区域手动输入题干。", parent=self)
            return
        self.status_var.set("正在识别…请稍候")
        self.update_idletasks()
        try:
            text = scanner.ocr_recognize(self.cropped_bgr).strip()
        except Exception as e:
            messagebox.showerror("OCR 失败", str(e), parent=self)
            self.status_var.set(" ")
            return
        self.ocr_txt.delete("1.0", "end")
        self.ocr_txt.insert("1.0", text)
        self.status_var.set("识别完成：%d 字符，请校对后点击「录入」。" % len(text))

    def add_to_book(self):
        content = self.ocr_txt.get("1.0", "end").strip()
        if not content:
            messagebox.showwarning("提示", "OCR 结果为空，请先识别或手动输入。", parent=self)
            return
        content = content[:LIMIT["content"]]
        subject = self.subj_var.get().strip() or "导入"
        q = new_question(subject, content, "", "", "从扫描图片导入")
        qid = self.db.add(q)
        self.db.save()
        # 清空，准备下一道题
        self.cropped_bgr = None
        self.selection = None
        self.preview_label.configure(image="", text="（无裁剪）")
        self.ocr_txt.delete("1.0", "end")
        self.canvas.delete("sel_rect")
        self.status_var.set("已录入题目 #%d，可继续框选下一道。" % qid)
        messagebox.showinfo("已录入",
                            "题目 #%d 已加入错题本。\n可继续框选下一道题导入。" % qid,
                            parent=self)

    # ---------- 自动分割 ----------
    def auto_split(self):
        if self.img_bgr is None:
            messagebox.showwarning("提示", "请先加载图片。", parent=self)
            return
        if not messagebox.askyesno(
                "自动分割",
                "自动分割将按水平投影检测文本块并逐块 OCR 录入。\n\n"
                "适用：排版清晰的扫描件（一题一块）。\n"
                "排版杂乱请改用手动框选。\n\n是否继续？", parent=self):
            return
        if not scanner.ocr_available():
            messagebox.showwarning("OCR 不可用",
                                   "未检测到 OCR 引擎，无法自动分割。请手动框选或安装 OCR。",
                                   parent=self)
            return
        self.status_var.set("自动分割中…请稍候")
        self.update_idletasks()
        try:
            _, binary = scanner.preprocess(self.img_bgr)
            blocks = scanner.split_questions(binary)
            added = 0
            for i, (y1, y2) in enumerate(blocks):
                roi = self.img_bgr[y1:y2, :]
                text = scanner.ocr_recognize(roi).strip()
                if text and len(text) > 3:
                    q = new_question("自动导入", text[:LIMIT["content"]],
                                     "", "", "自动分割自扫描图（块 %d）" % (i + 1))
                    self.db.add(q)
                    added += 1
            if added > 0:
                self.db.save()
            self.status_var.set("自动分割完成：检测 %d 块，录入 %d 题。" % (len(blocks), added))
            messagebox.showinfo("自动分割完成",
                                "检测到 %d 个文本块，已录入 %d 道题。\n\n"
                                "请到主列表逐条编辑、补充正确答案。" % (len(blocks), added),
                                parent=self)
        except Exception as e:
            messagebox.showerror("自动分割失败", str(e), parent=self)
            self.status_var.set(" ")


# ============================================================
#  入口
# ============================================================
def main():
    # tkinter 可用性检测：缺 Tcl/Tk 运行库的精简 Python 无法创建窗口，提前给出友好提示
    try:
        import tkinter as tk
        _probe = tk.Tk()
        _probe.destroy()
    except Exception as e:
        print("=" * 56, file=sys.stderr)
        print("[错误] 当前 Python 的 tkinter 无法创建窗口（缺少 Tcl/Tk 运行库）。", file=sys.stderr)
        print("  请改用完整安装的 Python 运行，例如：", file=sys.stderr)
        print("      py -3.x wrong_question_book.py", file=sys.stderr)
        print("  详细信息：%s" % e, file=sys.stderr)
        print("=" * 56, file=sys.stderr)
        sys.exit(1)
    app = WrongQuestionBookApp()
    app.mainloop()


if __name__ == "__main__":
    main()
