package com.mxcloud;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 错题数据管理类
 * 负责：增删改查、序列化持久化、备份、导出文本、统计
 */
public class QuestionDB {
    private static final String DATA_FILE = "wrong_questions.dat";
    private static final String BACKUP_FILE = "wrong_questions.dat.bak";
    private static final String EXPORT_FILE = "wrong_questions_export.txt";

    private List<WrongQuestion> data;
    private int nextId;

    public QuestionDB() {
        this.data = new ArrayList<>();
        this.nextId = 1;
    }

    // ==================== CRUD ====================

    public int add(WrongQuestion q) {
        q.setId(nextId++);
        data.add(q);
        return q.getId();
    }

    public int findIndexById(int id) {
        for (int i = 0; i < data.size(); i++) {
            if (data.get(i).getId() == id) return i;
        }
        return -1;
    }

    public WrongQuestion getById(int id) {
        int idx = findIndexById(id);
        return idx >= 0 ? data.get(idx) : null;
    }

    public boolean removeById(int id) {
        int idx = findIndexById(id);
        if (idx < 0) return false;
        data.remove(idx);
        return true;
    }

    public int size() { return data.size(); }
    public List<WrongQuestion> getAll() { return data; }
    public int getNextId() { return nextId; }

    // ==================== 持久化 ====================

    @SuppressWarnings("unchecked")
    public boolean load() {
        File f = new File(DATA_FILE);
        if (!f.exists()) return false;
        try (InputStream fin = new FileInputStream(f);
             ObjectInputStream ois = new SafeObjectInputStream(fin)) {
            Object obj = ois.readObject();
            if (!(obj instanceof List)) {
                System.err.println("[QuestionDB] Invalid data file: top-level object is not a list.");
                return false;
            }
            // 逐元素校验类型，拒绝混入的任意对象（防反序列化注入）
            List<?> raw = (List<?>) obj;
            List<WrongQuestion> loaded = new ArrayList<>(raw.size());
            for (Object o : raw) {
                if (!(o instanceof WrongQuestion)) {
                    System.err.println("[QuestionDB] Invalid data file: unexpected element type "
                            + (o == null ? "null" : o.getClass().getName()) + " rejected.");
                    return false;
                }
                WrongQuestion q = (WrongQuestion) o;
                normalizeAndValidate(q);
                loaded.add(q);
            }
            this.data = loaded;
            int maxId = 0;
            for (WrongQuestion q : data) {
                if (q.getId() > maxId) maxId = q.getId();
            }
            this.nextId = maxId + 1;
            return true;
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("[QuestionDB] Failed to load data file: " + e.getMessage());
        }
        return false;
    }

    /**
     * 反序列化安全包装流。
     * 通过 ObjectInputFilter 设置类白名单：
     *   - 放行 java.base 模块内置类（String / 容器等 JDK 基础类，此类无第三方 gadget 风险）；
     *   - 仅允许本应用实体类 com.mxcloud.WrongQuestion；
     *   - 其余一切第三方类一律拒绝，阻止恶意数据文件触发任意类实例化（修复 CWE-502）。
     * 同时施加深度/引用/数组/字节数上限，防止反序列化 DoS。
     */
    private static final class SafeObjectInputStream extends ObjectInputStream {
        private static final ObjectInputFilter FILTER = info -> {
            Class<?> clazz = info.serialClass();
            if (clazz == null) {
                // 资源限制请求（serialClass 为空）：施加深度/引用/数组/字节上限
                if (info.depth() > 20) return ObjectInputFilter.Status.REJECTED;
                if (info.references() > 200_000) return ObjectInputFilter.Status.REJECTED;
                if (info.arrayLength() >= 0 && info.arrayLength() > 100_000) return ObjectInputFilter.Status.REJECTED;
                if (info.streamBytes() > 200_000_000L) return ObjectInputFilter.Status.REJECTED;
                return ObjectInputFilter.Status.UNDECIDED;
            }
            String module = clazz.getModule() == null ? "" : clazz.getModule().getName();
            if ("java.base".equals(module)) return ObjectInputFilter.Status.ALLOWED;
            if ("com.mxcloud.WrongQuestion".equals(clazz.getName())) return ObjectInputFilter.Status.ALLOWED;
            return ObjectInputFilter.Status.REJECTED;
        };

        SafeObjectInputStream(InputStream in) throws IOException {
            super(in);
            setObjectInputFilter(FILTER);
        }
    }

    /** 校验并规范化反序列化得到的实体，防止被篡改的数据造成 NPE / 数据异常 */
    private static void normalizeAndValidate(WrongQuestion q) {
        q.setSubject(q.getSubject() == null ? "" : q.getSubject());
        q.setContent(q.getContent() == null ? "" : q.getContent());
        q.setWrongAns(q.getWrongAns() == null ? "" : q.getWrongAns());
        q.setRightAns(q.getRightAns() == null ? "" : q.getRightAns());
        q.setTip(q.getTip() == null ? "" : q.getTip());
        if (q.getWrongCount() < 0) q.setWrongCount(0);
    }

    public boolean save() {
        // 1. 先写入临时文件，写入成功后才替换正式文件，避免写入中途失败破坏原数据
        File tmp = new File(DATA_FILE + ".tmp");
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(tmp))) {
            oos.writeObject(new ArrayList<>(data));
            oos.flush();
        } catch (IOException e) {
            tmp.delete();
            System.err.println("[QuestionDB] Save failed: " + e.getMessage());
            return false;
        }

        // 2. 备份现有数据（备份失败仅告警，不阻断保存流程，且不破坏原文件）
        File old = new File(DATA_FILE);
        if (old.exists()) {
            try {
                File bak = new File(BACKUP_FILE);
                Files.deleteIfExists(bak.toPath());
                Files.move(old.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.err.println("[QuestionDB] Backup failed (continuing): " + e.getMessage());
            }
        }

        // 3. 原子替换正式文件
        try {
            Files.move(tmp.toPath(), old.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            System.err.println("[QuestionDB] Save failed: " + e.getMessage());
            tmp.delete();
            return false;
        }
    }

    // ==================== 导出文本 ====================

    public boolean exportTxt() {
        if (data.isEmpty()) return false;
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(EXPORT_FILE), "UTF-8"))) {
            pw.println("==================================================");
            pw.println("          WRONG QUESTION BOOK - EXPORT");
            pw.println("==================================================");
            pw.println("Export time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            pw.println("Total questions: " + data.size());
            pw.println();
            for (WrongQuestion q : data) {
                pw.println("--------------------------------------------------");
                pw.println("[" + q.getId() + "] Subject: " + q.getSubject()
                        + "    Wrong count: " + q.getWrongCount());
                pw.println("Question: " + q.getContent());
                pw.println("Your wrong answer: " + (q.getWrongAns().isEmpty() ? "(not filled)" : q.getWrongAns()));
                pw.println("Correct answer: " + q.getRightAns());
                pw.println("Note / tip: " + (q.getTip().isEmpty() ? "(none)" : q.getTip()));
                pw.println();
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    // ==================== 统计 ====================

    public static class StatsResult {
        public int total;
        public double avgWrong;
        public Map<String, Integer> subjectCount;
        public List<WrongQuestion> topFrequent;

        public StatsResult(int total, double avgWrong, Map<String, Integer> subjectCount, List<WrongQuestion> topFrequent) {
            this.total = total;
            this.avgWrong = avgWrong;
            this.subjectCount = subjectCount;
            this.topFrequent = topFrequent;
        }
    }

    public StatsResult getStatistics() {
        if (data.isEmpty()) return null;
        Map<String, Integer> subjCount = new LinkedHashMap<>();
        int totalWrong = 0;
        for (WrongQuestion q : data) {
            totalWrong += q.getWrongCount();
            subjCount.merge(q.getSubject(), 1, Integer::sum);
        }
        double avg = (double) totalWrong / data.size();

        List<WrongQuestion> sorted = new ArrayList<>(data);
        sorted.sort((a, b) -> Integer.compare(b.getWrongCount(), a.getWrongCount()));
        List<WrongQuestion> top = sorted.subList(0, Math.min(5, sorted.size()));

        return new StatsResult(data.size(), avg, subjCount, top);
    }

    // ==================== 检索 ====================

    public List<WrongQuestion> searchBySubject(String subject) {
        List<WrongQuestion> result = new ArrayList<>();
        for (WrongQuestion q : data) {
            if (q.getSubject().equalsIgnoreCase(subject)) result.add(q);
        }
        return result;
    }

    public List<WrongQuestion> searchByKeyword(String keyword) {
        List<WrongQuestion> result = new ArrayList<>();
        if (keyword == null || keyword.isEmpty()) {
            result.addAll(data);
            return result;
        }
        String kw = keyword.toLowerCase();
        for (WrongQuestion q : data) {
            if (q.getContent().toLowerCase().contains(kw)
                    || q.getSubject().toLowerCase().contains(kw)) {
                result.add(q);
            }
        }
        return result;
    }

    public List<WrongQuestion> getFrequentQuestions(int minCount) {
        List<WrongQuestion> result = new ArrayList<>();
        for (WrongQuestion q : data) {
            if (q.getWrongCount() >= minCount) result.add(q);
        }
        return result;
    }
}
