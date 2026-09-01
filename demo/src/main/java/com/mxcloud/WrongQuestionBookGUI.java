package com.mxcloud;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 错题集 Swing 可视化主界面
 */
public class WrongQuestionBookGUI extends JFrame {
    private QuestionDB db;
    private List<WrongQuestion> currentList; // 当前表格显示的列表（全部或筛选结果）
    private QuestionTableModel tableModel;
    private JTable table;
    private JTextField searchField;
    private JLabel statusLabel;

    public WrongQuestionBookGUI() {
        db = new QuestionDB();
        boolean loaded = db.load();
        currentList = new ArrayList<>(db.getAll());

        setTitle("Wrong Question Book - C++ (Java Swing Edition)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 600);
        setLocationRelativeTo(null);

        // 关闭窗口时自动保存
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                db.save();
            }
        });

        initUI();

        if (!loaded) {
            statusLabel.setText("No existing data. New question book created.");
        } else {
            updateStatus();
        }
    }

    private void initUI() {
        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(new EmptyBorder(8, 8, 8, 8));

        // ---- 顶部工具栏 ----
        JPanel toolBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));

        JButton btnAdd = new JButton("Add");
        JButton btnEdit = new JButton("Edit");
        JButton btnDelete = new JButton("Delete");
        JButton btnReview = new JButton("Review");
        JButton btnStats = new JButton("Statistics");
        JButton btnExport = new JButton("Export");
        JButton btnRefresh = new JButton("Refresh");
        JButton btnScan = new JButton("Scan & Import");

        searchField = new JTextField(15);
        searchField.setToolTipText("Search by keyword or subject");
        JButton btnSearch = new JButton("Search");
        JButton btnClearSearch = new JButton("All");

        toolBar.add(btnAdd);
        toolBar.add(btnEdit);
        toolBar.add(btnDelete);
        toolBar.add(Box.createHorizontalStrut(10));
        toolBar.add(searchField);
        toolBar.add(btnSearch);
        toolBar.add(btnClearSearch);
        toolBar.add(Box.createHorizontalStrut(10));
        toolBar.add(btnReview);
        toolBar.add(btnStats);
        toolBar.add(btnExport);
        toolBar.add(btnRefresh);
        toolBar.add(Box.createHorizontalStrut(10));
        toolBar.add(btnScan);

        // ---- 中部表格 ----
        tableModel = new QuestionTableModel();
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(28);
        table.getColumnModel().getColumn(0).setPreferredWidth(50);
        table.getColumnModel().getColumn(1).setPreferredWidth(100);
        table.getColumnModel().getColumn(2).setPreferredWidth(500);
        table.getColumnModel().getColumn(3).setPreferredWidth(80);

        // 双击编辑
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    editSelected();
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(table);

        // ---- 底部状态栏 ----
        statusLabel = new JLabel(" ");
        statusLabel.setBorder(new EmptyBorder(3, 5, 3, 5));

        mainPanel.add(toolBar, BorderLayout.NORTH);
        mainPanel.add(scrollPane, BorderLayout.CENTER);
        mainPanel.add(statusLabel, BorderLayout.SOUTH);

        setContentPane(mainPanel);

        // ---- 事件绑定 ----
        btnAdd.addActionListener(e -> addQuestion());
        btnEdit.addActionListener(e -> editSelected());
        btnDelete.addActionListener(e -> deleteSelected());
        btnReview.addActionListener(e -> startReview());
        btnStats.addActionListener(e -> showStatistics());
        btnExport.addActionListener(e -> exportData());
        btnRefresh.addActionListener(e -> refreshAll());
        btnScan.addActionListener(e -> startScanImport());
        btnSearch.addActionListener(e -> doSearch());
        btnClearSearch.addActionListener(e -> refreshAll());
        searchField.addActionListener(e -> doSearch());
    }

    // ==================== 表格模型 ====================

    private class QuestionTableModel extends AbstractTableModel {
        private final String[] columns = {"ID", "Subject", "Question", "Wrong Count"};

        @Override
        public int getRowCount() { return currentList.size(); }

        @Override
        public int getColumnCount() { return columns.length; }

        @Override
        public String getColumnName(int col) { return columns[col]; }

        @Override
        public Object getValueAt(int row, int col) {
            WrongQuestion q = currentList.get(row);
            switch (col) {
                case 0: return q.getId();
                case 1: return q.getSubject();
                case 2: return q.getContent();
                case 3: return q.getWrongCount();
                default: return "";
            }
        }

        @Override
        public boolean isCellEditable(int row, int col) { return false; }
    }

    // ==================== 操作方法 ====================

    private void refreshTable() {
        tableModel.fireTableDataChanged();
        updateStatus();
    }

    private void updateStatus() {
        statusLabel.setText("Total: " + db.size() + " questions | Showing: " + currentList.size());
    }

    private void refreshAll() {
        currentList = new ArrayList<>(db.getAll());
        searchField.setText("");
        refreshTable();
    }

    private void doSearch() {
        String kw = searchField.getText().trim();
        currentList = db.searchByKeyword(kw);
        refreshTable();
    }

    private WrongQuestion getSelectedQuestion() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= currentList.size()) return null;
        return currentList.get(row);
    }

    private void addQuestion() {
        AddEditDialog dialog = new AddEditDialog(this, null);
        dialog.setVisible(true);
        if (dialog.isConfirmed()) {
            WrongQuestion q = dialog.getQuestion();
            int id = db.add(q);
            db.save();
            refreshAll();
            JOptionPane.showMessageDialog(this, "Question added. ID: #" + id, "Success", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void editSelected() {
        WrongQuestion q = getSelectedQuestion();
        if (q == null) {
            JOptionPane.showMessageDialog(this, "Please select a question first.", "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }
        AddEditDialog dialog = new AddEditDialog(this, q);
        dialog.setVisible(true);
        if (dialog.isConfirmed()) {
            WrongQuestion updated = dialog.getQuestion();
            q.setSubject(updated.getSubject());
            q.setContent(updated.getContent());
            q.setWrongAns(updated.getWrongAns());
            q.setRightAns(updated.getRightAns());
            q.setTip(updated.getTip());
            db.save();
            refreshTable();
            JOptionPane.showMessageDialog(this, "Question updated.", "Success", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void deleteSelected() {
        WrongQuestion q = getSelectedQuestion();
        if (q == null) {
            JOptionPane.showMessageDialog(this, "Please select a question first.", "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Delete question #" + q.getId() + "?\n" + q.getContent(),
                "Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm == JOptionPane.YES_OPTION) {
            db.removeById(q.getId());
            db.save();
            refreshAll();
            JOptionPane.showMessageDialog(this, "Question deleted.", "Success", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void startScanImport() {
        ScanImportDialog dialog = new ScanImportDialog(this, db);
        dialog.setVisible(true);
        refreshAll();
    }

    private void startReview() {
        if (db.size() == 0) {
            JOptionPane.showMessageDialog(this, "No questions to review.", "Info", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        ReviewDialog dialog = new ReviewDialog(this, db);
        if (dialog.isCancelled()) return;
        dialog.setVisible(true);
        refreshAll();
    }

    private void showStatistics() {
        QuestionDB.StatsResult stats = db.getStatistics();
        if (stats == null) {
            JOptionPane.showMessageDialog(this, "No data yet.", "Info", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        StatisticsDialog dialog = new StatisticsDialog(this, stats);
        dialog.setVisible(true);
    }

    private void exportData() {
        if (db.size() == 0) {
            JOptionPane.showMessageDialog(this, "No questions to export.", "Info", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        boolean ok = db.exportTxt();
        if (ok) {
            JOptionPane.showMessageDialog(this,
                    "Exported " + db.size() + " questions to wrong_questions_export.txt",
                    "Export Success", JOptionPane.INFORMATION_MESSAGE);
        } else {
            JOptionPane.showMessageDialog(this, "Export failed. Check permissions.",
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ==================== 录入/编辑对话框 ====================

    private static class AddEditDialog extends JDialog {
        private JTextField subjectField;
        private JTextArea contentArea;
        private JTextField wrongAnsField;
        private JTextField rightAnsField;
        private JTextField tipField;
        private boolean confirmed = false;
        private WrongQuestion question;

        public AddEditDialog(JFrame parent, WrongQuestion existing) {
            super(parent, existing == null ? "Add Question" : "Edit Question #" + existing.getId(), true);
            setSize(500, 480);
            setLocationRelativeTo(parent);

            question = existing != null ? existing : new WrongQuestion();

            JPanel panel = new JPanel(new GridBagLayout());
            panel.setBorder(new EmptyBorder(12, 12, 12, 12));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(4, 4, 4, 4);
            gbc.anchor = GridBagConstraints.WEST;

            // 科目
            gbc.gridx = 0; gbc.gridy = 0;
            panel.add(new JLabel("Subject *:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            subjectField = new JTextField(question.getSubject(), 20);
            panel.add(subjectField, gbc);

            // 题干
            gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            panel.add(new JLabel("Question *:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.BOTH; gbc.weightx = 1.0; gbc.weighty = 1.0;
            contentArea = new JTextArea(question.getContent(), 5, 20);
            contentArea.setLineWrap(true);
            contentArea.setWrapStyleWord(true);
            panel.add(new JScrollPane(contentArea), gbc);

            // 错误答案
            gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0; gbc.weighty = 0;
            panel.add(new JLabel("Your answer:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            wrongAnsField = new JTextField(question.getWrongAns(), 20);
            panel.add(wrongAnsField, gbc);

            // 正确答案
            gbc.gridx = 0; gbc.gridy = 3; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            panel.add(new JLabel("Correct answer *:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            rightAnsField = new JTextField(question.getRightAns(), 20);
            panel.add(rightAnsField, gbc);

            // 备注
            gbc.gridx = 0; gbc.gridy = 4; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            panel.add(new JLabel("Note / tip:"), gbc);
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            tipField = new JTextField(question.getTip(), 20);
            panel.add(tipField, gbc);

            // 按钮
            JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 8));
            JButton btnOk = new JButton("OK");
            JButton btnCancel = new JButton("Cancel");
            btnPanel.add(btnOk);
            btnPanel.add(btnCancel);

            gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL;
            panel.add(btnPanel, gbc);

            setContentPane(panel);

            btnOk.addActionListener(e -> {
                if (subjectField.getText().trim().isEmpty()) {
                    JOptionPane.showMessageDialog(this, "Subject cannot be empty.", "Validation", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (contentArea.getText().trim().isEmpty()) {
                    JOptionPane.showMessageDialog(this, "Question cannot be empty.", "Validation", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (rightAnsField.getText().trim().isEmpty()) {
                    JOptionPane.showMessageDialog(this, "Correct answer cannot be empty.", "Validation", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                // 输入长度上限，防止超长文本导致界面/数据文件异常膨胀
                if (subjectField.getText().trim().length() > 100) {
                    JOptionPane.showMessageDialog(this, "Subject too long (max 100 chars).", "Validation", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (contentArea.getText().trim().length() > 20000) {
                    JOptionPane.showMessageDialog(this, "Question too long (max 20000 chars).", "Validation", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (wrongAnsField.getText().trim().length() > 5000
                        || rightAnsField.getText().trim().length() > 5000
                        || tipField.getText().trim().length() > 5000) {
                    JOptionPane.showMessageDialog(this, "Answer / note too long (max 5000 chars each).",
                            "Validation", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                question.setSubject(subjectField.getText().trim());
                question.setContent(contentArea.getText().trim());
                question.setWrongAns(wrongAnsField.getText().trim());
                question.setRightAns(rightAnsField.getText().trim());
                question.setTip(tipField.getText().trim());
                confirmed = true;
                dispose();
            });
            btnCancel.addActionListener(e -> dispose());

            getRootPane().setDefaultButton(btnOk);
        }

        public boolean isConfirmed() { return confirmed; }
        public WrongQuestion getQuestion() { return question; }
    }

    // ==================== 复习对话框 ====================

    private static class ReviewDialog extends JDialog {
        private QuestionDB db;
        private List<WrongQuestion> reviewList;
        private int currentIndex;
        private int wrongAgain;
        private boolean cancelled = false;
        private JLabel progressLabel;
        private JLabel subjectLabel;
        private JTextArea questionArea;
        private JTextArea answerArea;
        private JButton btnReveal;
        private JButton btnWrong;
        private JButton btnCorrect;
        private JButton btnNext;

        public ReviewDialog(JFrame parent, QuestionDB db) {
            super(parent, "Review Mode", true);
            this.db = db;
            setSize(550, 500);
            setLocationRelativeTo(parent);

            // 选择复习范围
            String[] options = {"All questions", "Frequent (wrong >= 2)"};
            int choice = JOptionPane.showOptionDialog(this,
                    "Select review scope:", "Review Mode",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                    null, options, options[0]);
            if (choice == JOptionPane.CLOSED_OPTION) {
                cancelled = true;
                return;
            }

            if (choice == 1) {
                reviewList = new ArrayList<>(db.getFrequentQuestions(2));
                if (reviewList.isEmpty()) {
                    JOptionPane.showMessageDialog(this, "No frequent questions. Using all.", "Info", JOptionPane.INFORMATION_MESSAGE);
                    reviewList = new ArrayList<>(db.getAll());
                }
            } else {
                reviewList = new ArrayList<>(db.getAll());
            }

            // 随机打乱
            java.util.Collections.shuffle(reviewList);

            // 限制最多20题
            if (reviewList.size() > 20) {
                reviewList = new ArrayList<>(reviewList.subList(0, 20));
            }

            currentIndex = 0;
            wrongAgain = 0;

            initUI();
            showQuestion();
        }

        private void initUI() {
            JPanel panel = new JPanel(new BorderLayout(5, 5));
            panel.setBorder(new EmptyBorder(10, 10, 10, 10));

            // 顶部信息
            JPanel topPanel = new JPanel(new BorderLayout());
            progressLabel = new JLabel("", SwingConstants.CENTER);
            progressLabel.setFont(progressLabel.getFont().deriveFont(Font.BOLD, 14f));
            subjectLabel = new JLabel("", SwingConstants.CENTER);
            subjectLabel.setForeground(Color.BLUE);
            topPanel.add(progressLabel, BorderLayout.NORTH);
            topPanel.add(subjectLabel, BorderLayout.SOUTH);

            // 题干
            JPanel qPanel = new JPanel(new BorderLayout());
            qPanel.setBorder(BorderFactory.createTitledBorder("Question"));
            questionArea = new JTextArea(4, 30);
            questionArea.setEditable(false);
            questionArea.setLineWrap(true);
            questionArea.setWrapStyleWord(true);
            questionArea.setFont(questionArea.getFont().deriveFont(14f));
            qPanel.add(new JScrollPane(questionArea), BorderLayout.CENTER);

            // 答案
            JPanel aPanel = new JPanel(new BorderLayout());
            aPanel.setBorder(BorderFactory.createTitledBorder("Answer (hidden)"));
            answerArea = new JTextArea(5, 30);
            answerArea.setEditable(false);
            answerArea.setLineWrap(true);
            answerArea.setWrapStyleWord(true);
            aPanel.add(new JScrollPane(answerArea), BorderLayout.CENTER);

            // 按钮
            JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
            btnReveal = new JButton("Reveal Answer");
            btnWrong = new JButton("Got Wrong (+1)");
            btnCorrect = new JButton("Got Correct");
            btnNext = new JButton("Next >>");
            btnWrong.setEnabled(false);
            btnCorrect.setEnabled(false);
            btnNext.setEnabled(false);

            btnPanel.add(btnReveal);
            btnPanel.add(btnWrong);
            btnPanel.add(btnCorrect);
            btnPanel.add(btnNext);

            panel.add(topPanel, BorderLayout.NORTH);
            panel.add(qPanel, BorderLayout.CENTER);
            panel.add(aPanel, BorderLayout.SOUTH);

            JPanel bottomPanel = new JPanel(new BorderLayout());
            bottomPanel.add(btnPanel, BorderLayout.CENTER);
            panel.add(bottomPanel, BorderLayout.SOUTH);

            setContentPane(panel);

            btnReveal.addActionListener(e -> revealAnswer());
            btnWrong.addActionListener(e -> markWrong());
            btnCorrect.addActionListener(e -> markCorrect());
            btnNext.addActionListener(e -> nextQuestion());
        }

        private void showQuestion() {
            if (currentIndex >= reviewList.size()) {
                finishReview();
                return;
            }
            WrongQuestion q = reviewList.get(currentIndex);
            progressLabel.setText("Question " + (currentIndex + 1) + " / " + reviewList.size());
            subjectLabel.setText("[" + q.getSubject() + "]  ID #" + q.getId() + "  (history wrong: " + q.getWrongCount() + "x)");
            questionArea.setText(q.getContent());
            answerArea.setText("");
            answerArea.setBorder(null);
            btnReveal.setEnabled(true);
            btnWrong.setEnabled(false);
            btnCorrect.setEnabled(false);
            btnNext.setEnabled(false);
            getRootPane().setDefaultButton(btnReveal);
        }

        private void revealAnswer() {
            WrongQuestion q = reviewList.get(currentIndex);
            StringBuilder sb = new StringBuilder();
            sb.append("Your wrong answer: ").append(q.getWrongAns().isEmpty() ? "(not filled)" : q.getWrongAns()).append("\n\n");
            sb.append("Correct answer: ").append(q.getRightAns()).append("\n\n");
            sb.append("Note: ").append(q.getTip().isEmpty() ? "(none)" : q.getTip());
            answerArea.setText(sb.toString());
            btnReveal.setEnabled(false);
            btnWrong.setEnabled(true);
            btnCorrect.setEnabled(true);
            getRootPane().setDefaultButton(btnCorrect);
        }

        private void markWrong() {
            WrongQuestion q = reviewList.get(currentIndex);
            q.incrementWrongCount();
            wrongAgain++;
            db.save();
            btnWrong.setEnabled(false);
            btnCorrect.setEnabled(false);
            btnNext.setEnabled(true);
            getRootPane().setDefaultButton(btnNext);
            JOptionPane.showMessageDialog(this,
                    "Question #" + q.getId() + " wrong count updated to " + q.getWrongCount(),
                    "Recorded", JOptionPane.INFORMATION_MESSAGE);
        }

        private void markCorrect() {
            btnWrong.setEnabled(false);
            btnCorrect.setEnabled(false);
            btnNext.setEnabled(true);
            getRootPane().setDefaultButton(btnNext);
        }

        private void nextQuestion() {
            currentIndex++;
            showQuestion();
        }

        private void finishReview() {
            int total = reviewList.size();
            int correct = total - wrongAgain;
            double rate = total > 0 ? (double) correct / total * 100.0 : 0.0;
            String msg = String.format(
                    "Review Complete!\n\n" +
                    "Questions reviewed: %d\n" +
                    "Got wrong again:    %d\n" +
                    "Got correct:        %d\n" +
                    "Accuracy:           %.1f%%",
                    total, wrongAgain, correct, rate);
            JOptionPane.showMessageDialog(this, msg, "Review Complete", JOptionPane.INFORMATION_MESSAGE);
            dispose();
        }

        public boolean isCancelled() { return cancelled; }
    }

    // ==================== 统计对话框 ====================

    private static class StatisticsDialog extends JDialog {
        public StatisticsDialog(JFrame parent, QuestionDB.StatsResult stats) {
            super(parent, "Statistics", true);
            setSize(500, 500);
            setLocationRelativeTo(parent);

            JTextArea area = new JTextArea();
            area.setEditable(false);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            area.setBorder(new EmptyBorder(10, 10, 10, 10));

            StringBuilder sb = new StringBuilder();
            sb.append("================ STATISTICS ================\n\n");
            sb.append("Total questions:    ").append(stats.total).append("\n");
            sb.append(String.format("Average wrong count: %.2f\n\n", stats.avgWrong));

            sb.append("Distribution by subject:\n");
            sb.append("----------------------------------------\n");
            for (Map.Entry<String, Integer> entry : stats.subjectCount.entrySet()) {
                double pct = (double) entry.getValue() / stats.total * 100.0;
                int bars = (int) (pct / 5.0);
                StringBuilder bar = new StringBuilder();
                for (int i = 0; i < bars; i++) bar.append('#');
                sb.append(String.format("  %-12s %3d (%5.1f%%) %s%n",
                        entry.getKey(), entry.getValue(), pct, bar));
            }

            sb.append("\nTop 5 frequent questions:\n");
            sb.append("----------------------------------------\n");
            for (int i = 0; i < stats.topFrequent.size(); i++) {
                WrongQuestion q = stats.topFrequent.get(i);
                String brief = q.getContent().length() > 40 ? q.getContent().substring(0, 40) + "..." : q.getContent();
                sb.append(String.format("  #%d [ID:%d][%s] wrong %dx - %s%n",
                        i + 1, q.getId(), q.getSubject(), q.getWrongCount(), brief));
            }
            sb.append("\n============================================");

            area.setText(sb.toString());
            area.setCaretPosition(0);

            JPanel panel = new JPanel(new BorderLayout());
            panel.add(new JScrollPane(area), BorderLayout.CENTER);

            JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
            JButton btnClose = new JButton("Close");
            btnClose.addActionListener(e -> dispose());
            btnPanel.add(btnClose);
            panel.add(btnPanel, BorderLayout.SOUTH);

            setContentPane(panel);
            getRootPane().setDefaultButton(btnClose);
        }
    }
}
