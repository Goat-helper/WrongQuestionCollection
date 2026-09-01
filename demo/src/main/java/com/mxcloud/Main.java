package com.mxcloud;

import javax.swing.*;

/**
 * 程序入口
 * 在事件 dispatch thread (EDT) 中启动 Swing 界面
 */
public class Main {
    public static void main(String[] args) {
        // 设置系统外观（可选，更贴近操作系统风格）
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // 失败则使用默认外观，不影响功能
        }

        SwingUtilities.invokeLater(() -> {
            WrongQuestionBookGUI gui = new WrongQuestionBookGUI();
            gui.setVisible(true);
        });
    }
}
