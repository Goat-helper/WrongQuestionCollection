import com.mxcloud.QuestionDB;
import com.mxcloud.WrongQuestion;
import java.util.List;

/**
 * 只读兼容性验证：加载真实 wrong_questions.dat，仅读取不写盘。
 * 用于确认反序列化安全加固后，用户现有数据文件仍可正常加载。
 */
public class LoadCheck {
    public static void main(String[] args) {
        QuestionDB db = new QuestionDB();
        boolean ok = db.load();
        System.out.println("load() = " + ok);
        if (ok) {
            List<WrongQuestion> all = db.getAll();
            System.out.println("total questions = " + all.size());
            System.out.println("nextId = " + db.getNextId());
            if (!all.isEmpty()) {
                WrongQuestion first = all.get(0);
                System.out.println("first: id=" + first.getId()
                        + " subject=" + first.getSubject()
                        + " wrongCount=" + first.getWrongCount());
            }
        }
        System.out.println(ok && db.size() >= 0 ? "LOAD_OK" : "LOAD_FAIL");
    }
}
