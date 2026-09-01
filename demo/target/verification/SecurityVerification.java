import com.mxcloud.QuestionDB;
import com.mxcloud.WrongQuestion;
import java.io.*;
import java.util.*;

/**
 * 安全修复验证程序（在临时工作目录运行，不影响真实数据文件）
 * 验证：
 *  1. 正常保存/加载 round-trip
 *  2. 拒绝包含非 WrongQuestion 元素的数据文件
 *  3. 拒绝白名单之外的任意类（反序列化 gadget 注入）
 *  4. 篡改数据（null 字段 / 负数）被规范化
 *  5. 拒绝顶层非 List 的数据文件
 */
public class SecurityVerification {
    static int pass = 0, fail = 0;

    static class EvilGadget implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Working dir: " + new File(".").getAbsolutePath());

        // Test 1: 正常 round-trip
        {
            QuestionDB db = new QuestionDB();
            WrongQuestion q1 = new WrongQuestion();
            q1.setSubject("Math"); q1.setContent("1+1=?"); q1.setRightAns("2");
            db.add(q1);
            WrongQuestion q2 = new WrongQuestion();
            q2.setSubject("Java"); q2.setContent("null check"); q2.setRightAns("Objects.nonNull");
            db.add(q2);
            check("save()", db.save());
            QuestionDB loaded = new QuestionDB();
            check("load() round-trip", loaded.load());
            check("loaded size == 2", loaded.size() == 2);
        }

        // Test 2: 元素类型混入（HashMap）→ 拒绝
        {
            List<Object> evil = new ArrayList<>();
            evil.add(new HashMap<>());
            writeDataFile(evil);
            QuestionDB loaded = new QuestionDB();
            check("reject non-WrongQuestion element (HashMap)", !loaded.load());
        }

        // Test 3: 白名单外任意类（gadget 注入）→ ObjectInputFilter 拒绝
        {
            List<Object> evil = new ArrayList<>();
            evil.add(new EvilGadget());
            writeDataFile(evil);
            QuestionDB loaded = new QuestionDB();
            check("reject unknown class via ObjectInputFilter (EvilGadget)", !loaded.load());
        }

        // Test 4: 篡改数据（null 字段 / 负 wrongCount）→ 加载成功且被规范化
        {
            WrongQuestion q = new WrongQuestion();
            q.setSubject(null); q.setContent(null); q.setWrongAns(null);
            q.setRightAns(null); q.setTip(null); q.setWrongCount(-5);
            List<WrongQuestion> list = new ArrayList<>();
            list.add(q);
            writeDataFile(list);
            QuestionDB loaded = new QuestionDB();
            check("load tampered data", loaded.load());
            WrongQuestion lq = loaded.getAll().isEmpty() ? null : loaded.getAll().get(0);
            check("null subject normalized to empty", lq != null && "".equals(lq.getSubject()));
            check("negative wrongCount normalized to 0", lq != null && lq.getWrongCount() == 0);
        }

        // Test 5: 顶层非 List → 拒绝
        {
            writeDataFile("not-a-list");
            QuestionDB loaded = new QuestionDB();
            check("reject non-List top-level", !loaded.load());
        }

        System.out.println("\n==== RESULT: " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }

    static void writeDataFile(Object o) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream("wrong_questions.dat"))) {
            oos.writeObject(o);
        }
    }

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }
}
