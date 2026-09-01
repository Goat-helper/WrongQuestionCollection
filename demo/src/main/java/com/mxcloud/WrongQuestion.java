package com.mxcloud;

import java.io.Serializable;

/**
 * 错题实体类
 */
public class WrongQuestion implements Serializable {
    private static final long serialVersionUID = 1L;

    private int id;
    private String subject;
    private String content;
    private String wrongAns;
    private String rightAns;
    private String tip;
    private int wrongCount;

    public WrongQuestion() {
        this.subject = "";
        this.content = "";
        this.wrongAns = "";
        this.rightAns = "";
        this.tip = "";
        this.wrongCount = 1;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getWrongAns() { return wrongAns; }
    public void setWrongAns(String wrongAns) { this.wrongAns = wrongAns; }

    public String getRightAns() { return rightAns; }
    public void setRightAns(String rightAns) { this.rightAns = rightAns; }

    public String getTip() { return tip; }
    public void setTip(String tip) { this.tip = tip; }

    public int getWrongCount() { return wrongCount; }
    public void setWrongCount(int wrongCount) { this.wrongCount = wrongCount; }
    public void incrementWrongCount() { this.wrongCount++; }
}
