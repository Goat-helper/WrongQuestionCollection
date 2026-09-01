package com.mxcloud;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 扫描导入对话框
 * 支持：加载扫描图片 -> 手动框选/自动分割 -> OCR识别 -> 编辑 -> 录入错题集
 */
public class ScanImportDialog extends JDialog {
    // 图像加载安全上限：文件大小 50MB、像素总量 1 亿（约 12000x8300），防止解压炸弹/超大图 OOM（CWE-400）
    private static final long MAX_IMAGE_FILE_BYTES = 50L * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 100_000_000L;
    // 录入文本长度上限，防止超长 OCR 结果导致界面/数据文件膨胀
    private static final int MAX_TEXT_LENGTH = 20000;
    private QuestionDB db;
    private BufferedImage originalImage;
    private BufferedImage croppedImage;
    private Rectangle selection;
    private Point dragStart;

    private JLabel imageLabel;
    private JTextArea ocrResultArea;
    private JTextField subjectField;
    private JLabel cropPreviewLabel;
    private JLabel statusLabel;

    private ITesseract tesseract;
    private boolean ocrReady = false;

    // 用户手动选择的Python脚本路径（保存到配置文件）
    private static final String CONFIG_FILE = "wqb_config.properties";
    private String savedScriptPath = null;

    public ScanImportDialog(JFrame parent, QuestionDB db) {
        super(parent, "Scan & Import - OCR", true);
        this.db = db;
        setSize(1050, 720);
        setLocationRelativeTo(parent);

        loadConfig();
        initOCR();
        initUI();
    }

    /* ==================== OCR 初始化 ==================== */
    private void initOCR() {
        try {
            tesseract = new Tesseract();
            // 语言：中文简体 + 英文
            tesseract.setLanguage("chi_sim+eng");
            // 页面分割模式：自动分割（默认）
            tesseract.setPageSegMode(3);
            // 设置OCR引擎模式：默认LSTM
            tesseract.setOcrEngineMode(1);
            ocrReady = true;
        } catch (Exception e) {
            ocrReady = false;
            // OCR不可用时，允许手动输入
        }
    }

    /* ==================== UI 初始化 ==================== */
    private void initUI() {
        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(new EmptyBorder(8, 8, 8, 8));

        // ---- 顶部工具栏 ----
        JPanel toolBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        JButton btnLoad = new JButton("Load Image");
        JButton btnCrop = new JButton("Crop Selected");
        JButton btnOCR = new JButton("OCR Recognize");
        JButton btnAdd = new JButton("Add to Book");
        JButton btnAutoSplit = new JButton("Auto Split All");
        JButton btnPythonOcr = new JButton("Python OCR");
        JButton btnClose = new JButton("Close");

        toolBar.add(btnLoad);
        toolBar.add(btnCrop);
        toolBar.add(btnOCR);
        toolBar.add(btnAdd);
        toolBar.add(Box.createHorizontalStrut(10));
        toolBar.add(btnAutoSplit);
        toolBar.add(Box.createHorizontalStrut(10));
        toolBar.add(btnPythonOcr);
        toolBar.add(Box.createHorizontalStrut(10));
        toolBar.add(btnClose);

        // ---- 中部：左侧图片 + 右侧结果 ----
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

        // 左侧：图片显示（带滚动）
        imageLabel = new JLabel();
        imageLabel.setHorizontalAlignment(SwingConstants.LEFT);
        imageLabel.setVerticalAlignment(SwingConstants.TOP);
        JScrollPane imageScroll = new JScrollPane(imageLabel);
        imageScroll.setBorder(BorderFactory.createTitledBorder(
                "Scanned Image  -  drag mouse to select a question region"));

        // 鼠标框选
        imageLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (originalImage == null) return;
                dragStart = e.getPoint();
                selection = new Rectangle(dragStart);
                imageLabel.repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (selection != null && originalImage != null) {
                    normalizeSelection();
                    if (selection.width > 3 && selection.height > 3) {
                        statusLabel.setText("Selected: " + selection.width + "x" + selection.height
                                + "  -  click [Crop Selected]");
                    }
                    imageLabel.repaint();
                }
            }
        });

        imageLabel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragStart == null || originalImage == null) return;
                selection = new Rectangle(
                        Math.min(dragStart.x, e.getX()),
                        Math.min(dragStart.y, e.getY()),
                        Math.abs(e.getX() - dragStart.x),
                        Math.abs(e.getY() - dragStart.y)
                );
                imageLabel.repaint();
            }
        });

        // 自定义绘制选区（半透明蓝色填充+蓝色边框）
        imageLabel.setUI(new javax.swing.plaf.basic.BasicLabelUI() {
            @Override
            public void paint(Graphics g, JComponent c) {
                super.paint(g, c);
                if (selection != null && selection.width > 0 && selection.height > 0) {
                    Graphics2D g2d = (Graphics2D) g;
                    g2d.setColor(new Color(0, 120, 255, 70));
                    g2d.fill(selection);
                    g2d.setColor(new Color(0, 80, 200));
                    g2d.setStroke(new BasicStroke(2));
                    g2d.draw(selection);
                }
            }
        });

        // 右侧：结果面板
        JPanel rightPanel = new JPanel(new BorderLayout(5, 5));

        // 裁剪预览
        JPanel cropPanel = new JPanel(new BorderLayout());
        cropPanel.setBorder(BorderFactory.createTitledBorder("Cropped Preview"));
        cropPreviewLabel = new JLabel("No crop yet", SwingConstants.CENTER);
        cropPreviewLabel.setPreferredSize(new Dimension(320, 160));
        cropPanel.add(new JScrollPane(cropPreviewLabel), BorderLayout.CENTER);

        // OCR结果（可编辑）
        JPanel ocrPanel = new JPanel(new BorderLayout());
        ocrPanel.setBorder(BorderFactory.createTitledBorder("OCR Result  -  editable, correct if needed"));
        ocrResultArea = new JTextArea(10, 20);
        ocrResultArea.setLineWrap(true);
        ocrResultArea.setWrapStyleWord(true);
        ocrResultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        ocrPanel.add(new JScrollPane(ocrResultArea), BorderLayout.CENTER);

        // 科目输入
        JPanel subjectPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        subjectPanel.add(new JLabel("Subject:"));
        subjectField = new JTextField(14);
        subjectField.setText("Imported");
        subjectPanel.add(subjectField);

        rightPanel.add(cropPanel, BorderLayout.NORTH);
        rightPanel.add(ocrPanel, BorderLayout.CENTER);
        rightPanel.add(subjectPanel, BorderLayout.SOUTH);

        splitPane.setLeftComponent(imageScroll);
        splitPane.setRightComponent(rightPanel);
        splitPane.setDividerLocation(640);

        // ---- 底部状态栏 ----
        statusLabel = new JLabel(" ");
        statusLabel.setBorder(new EmptyBorder(3, 5, 3, 5));

        mainPanel.add(toolBar, BorderLayout.NORTH);
        mainPanel.add(splitPane, BorderLayout.CENTER);
        mainPanel.add(statusLabel, BorderLayout.SOUTH);

        setContentPane(mainPanel);

        // ---- 事件绑定 ----
        btnLoad.addActionListener(e -> loadImage());
        btnCrop.addActionListener(e -> cropSelection());
        btnOCR.addActionListener(e -> doOCR());
        btnAdd.addActionListener(e -> addToBook());
        btnAutoSplit.addActionListener(e -> autoSplit());
        btnPythonOcr.addActionListener(e -> pythonOcrAutoSplit());
        btnClose.addActionListener(e -> dispose());

        if (!ocrReady) {
            statusLabel.setText("WARNING: Tesseract OCR not available. You can still crop & type manually.");
        }
    }

    /* ==================== 工具方法 ==================== */

    private void normalizeSelection() {
        if (selection == null || originalImage == null) return;
        int x = Math.max(0, selection.x);
        int y = Math.max(0, selection.y);
        int w = Math.min(selection.width, originalImage.getWidth() - x);
        int h = Math.min(selection.height, originalImage.getHeight() - y);
        selection = new Rectangle(x, y, w, h);
    }

    /** 截断超长文本，防止 OCR 结果无限增长拖垮界面与数据文件 */
    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > MAX_TEXT_LENGTH ? s.substring(0, MAX_TEXT_LENGTH) : s;
    }

    /* ==================== 加载图片 ==================== */

    private void loadImage() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Image Files", "jpg", "jpeg", "png", "bmp", "tiff", "tif"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            try {
                // 1. 文件大小上限检查，避免加载超大文件耗尽内存
                if (file.length() > MAX_IMAGE_FILE_BYTES) {
                    JOptionPane.showMessageDialog(this,
                            "Image file too large (limit " + (MAX_IMAGE_FILE_BYTES / (1024 * 1024)) + " MB).",
                            "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                // 2. 仅读取图像头部探测尺寸，超限直接拒绝，避免解码超大图导致 OOM
                try (ImageInputStream iis = ImageIO.createImageInputStream(file)) {
                    Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
                    if (!readers.hasNext()) {
                        JOptionPane.showMessageDialog(this, "Unsupported or unreadable image format.",
                                "Error", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(iis, true, true);
                        int w = reader.getWidth(0);
                        int h = reader.getHeight(0);
                        long pixels = (long) w * h;
                        if (pixels > MAX_IMAGE_PIXELS) {
                            JOptionPane.showMessageDialog(this,
                                    "Image dimensions too large (" + w + "x" + h
                                            + ", limit ~" + (MAX_IMAGE_PIXELS / 1_000_000L) + " megapixels).",
                                    "Error", JOptionPane.ERROR_MESSAGE);
                            return;
                        }
                    } finally {
                        reader.dispose();
                    }
                }
                originalImage = ImageIO.read(file);
                if (originalImage == null) {
                    JOptionPane.showMessageDialog(this, "Cannot read image file.",
                            "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                imageLabel.setIcon(new ImageIcon(originalImage));
                selection = null;
                croppedImage = null;
                cropPreviewLabel.setIcon(null);
                cropPreviewLabel.setText("No crop yet");
                statusLabel.setText("Loaded: " + file.getName()
                        + "  (" + originalImage.getWidth() + "x" + originalImage.getHeight() + ")");
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Error loading image: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /* ==================== 裁剪选区 ==================== */

    private void cropSelection() {
        if (originalImage == null) {
            JOptionPane.showMessageDialog(this, "Load an image first.",
                    "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (selection == null || selection.width < 5 || selection.height < 5) {
            JOptionPane.showMessageDialog(this, "Drag to select a region first.",
                    "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }
        normalizeSelection();
        croppedImage = originalImage.getSubimage(selection.x, selection.y,
                selection.width, selection.height);
        // 缩放预览（保持比例）
        int previewW = Math.min(320, croppedImage.getWidth());
        Image scaled = croppedImage.getScaledInstance(previewW, -1, Image.SCALE_SMOOTH);
        cropPreviewLabel.setIcon(new ImageIcon(scaled));
        cropPreviewLabel.setText("");
        statusLabel.setText("Cropped: " + croppedImage.getWidth() + "x" + croppedImage.getHeight()
                + "  -  click [OCR Recognize]");
    }

    /* ==================== OCR 识别 ==================== */

    private void doOCR() {
        if (croppedImage == null) {
            if (originalImage == null) {
                JOptionPane.showMessageDialog(this, "Load and crop an image first.",
                        "Warning", JOptionPane.WARNING_MESSAGE);
                return;
            }
            croppedImage = originalImage;
        }
        if (!ocrReady) {
            JOptionPane.showMessageDialog(this,
                    "Tesseract OCR is not available.\n\n" +
                            "Please install:\n" +
                            "  1. Tesseract OCR engine\n" +
                            "  2. chi_sim.traineddata (Chinese simplified language pack)\n\n" +
                            "Or type the question text manually in the result area.",
                    "OCR Unavailable", JOptionPane.WARNING_MESSAGE);
            return;
        }

        statusLabel.setText("Recognizing... please wait");
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        SwingWorker<String, Void> worker = new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    return tesseract.doOCR(croppedImage);
                } catch (TesseractException e) {
                    return "OCR Error: " + e.getMessage();
                }
            }

            @Override
            protected void done() {
                try {
                    String result = get();
                    ocrResultArea.setText(result.trim());
                    statusLabel.setText("OCR complete: " + result.trim().length() + " chars. " +
                            "Edit if needed, then [Add to Book].");
                } catch (Exception e) {
                    statusLabel.setText("OCR failed: " + e.getMessage());
                }
                setCursor(Cursor.getDefaultCursor());
            }
        };
        worker.execute();
    }

    /* ==================== 添加到错题集 ==================== */

    private void addToBook() {
        String content = truncate(ocrResultArea.getText().trim());
        if (content.isEmpty()) {
            JOptionPane.showMessageDialog(this, "OCR result is empty. Recognize or type first.",
                    "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String subject = subjectField.getText().trim();
        if (subject.isEmpty()) subject = "Imported";

        WrongQuestion q = new WrongQuestion();
        q.setSubject(subject);
        q.setContent(content);
        q.setWrongAns("");
        q.setRightAns("");
        q.setTip("Imported from scan image");
        q.setWrongCount(1);

        int id = db.add(q);
        db.save();

        // 清空当前裁剪和OCR，准备下一道题
        croppedImage = null;
        selection = null;
        cropPreviewLabel.setIcon(null);
        cropPreviewLabel.setText("No crop yet");
        ocrResultArea.setText("");
        imageLabel.repaint();

        statusLabel.setText("Added question #" + id + ".  Select next region to continue.");
        JOptionPane.showMessageDialog(this, "Question #" + id + " added to book.\n" +
                        "Select next region to continue importing.",
                "Added", JOptionPane.INFORMATION_MESSAGE);
    }

    /* ==================== 自动分割（水平投影法） ==================== */

    private void autoSplit() {
        if (originalImage == null) {
            JOptionPane.showMessageDialog(this, "Load an image first.",
                    "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
                "Auto Split will detect text blocks by horizontal projection\n" +
                        "and OCR each block automatically.\n\n" +
                        "Best for: clear scan with one question per line/block.\n" +
                        "For messy layouts, use manual selection instead.\n\n" +
                        "Proceed?",
                "Auto Split", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        if (!ocrReady) {
            JOptionPane.showMessageDialog(this, "OCR not available. Cannot auto split.",
                    "OCR Unavailable", JOptionPane.WARNING_MESSAGE);
            return;
        }

        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        statusLabel.setText("Auto splitting: analyzing projection...");

        SwingWorker<int[], Void> worker = new SwingWorker<int[], Void>() {
            @Override
            protected int[] doInBackground() {
                return detectBlocks();
            }

            @Override
            protected void done() {
                try {
                    int[] blockCount = get();
                    int detected = blockCount[0];
                    int added = blockCount[1];
                    setCursor(Cursor.getDefaultCursor());
                    statusLabel.setText("Auto split complete: " + detected + " blocks, " + added + " added.");
                    JOptionPane.showMessageDialog(ScanImportDialog.this,
                            "Detected " + detected + " text blocks.\n" +
                                    "Added " + added + " questions to the book.\n\n" +
                                    "Please review and edit imported questions (fill in correct answers).",
                            "Auto Split Complete", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    setCursor(Cursor.getDefaultCursor());
                    statusLabel.setText("Auto split failed: " + e.getMessage());
                }
            }
        };
        worker.execute();
    }

    /**
     * 水平投影检测文本块，对每个块做OCR并录入
     * 返回 [检测到的块数, 成功录入数]
     */
    private int[] detectBlocks() {
        int w = originalImage.getWidth();
        int h = originalImage.getHeight();

        // 1. 计算水平投影（每行的黑色像素数）
        int[] histogram = new int[h];
        for (int y = 0; y < h; y++) {
            int blackCount = 0;
            for (int x = 0; x < w; x++) {
                Color c = new Color(originalImage.getRGB(x, y));
                int gray = (c.getRed() + c.getGreen() + c.getBlue()) / 3;
                if (gray < 140) blackCount++; // 阈值140，适应扫描件
            }
            histogram[y] = blackCount;
        }

        // 2. 找文本行（黑色像素超过宽度0.5%的行）
        int threshold = Math.max(3, w / 200);
        List<int[]> rawBlocks = new ArrayList<>();
        int blockStart = -1;
        for (int y = 0; y < h; y++) {
            if (histogram[y] > threshold) {
                if (blockStart == -1) blockStart = y;
            } else {
                if (blockStart != -1) {
                    int blockEnd = y - 1;
                    if (blockEnd - blockStart > 8) { // 过滤太小的噪声块
                        rawBlocks.add(new int[]{blockStart, blockEnd});
                    }
                    blockStart = -1;
                }
            }
        }
        if (blockStart != -1 && h - 1 - blockStart > 8) {
            rawBlocks.add(new int[]{blockStart, h - 1});
        }

        // 3. 合并相邻块（间距小于40像素的合并为一道题）
        List<int[]> merged = new ArrayList<>();
        if (!rawBlocks.isEmpty()) {
            int[] current = rawBlocks.get(0).clone();
            for (int i = 1; i < rawBlocks.size(); i++) {
                int[] b = rawBlocks.get(i);
                if (b[0] - current[1] < 40) {
                    current[1] = b[1]; // 合并
                } else {
                    merged.add(current);
                    current = b.clone();
                }
            }
            merged.add(current);
        }

        // 4. 对每个块做OCR并录入
        int added = 0;
        for (int i = 0; i < merged.size(); i++) {
            int[] block = merged.get(i);
            int cropY = Math.max(0, block[0] - 5);
            int cropH = Math.min(h - cropY, block[1] - block[0] + 11);
            if (cropH < 10) continue;

            try {
                BufferedImage blockImg = originalImage.getSubimage(0, cropY, w, cropH);
                String text = truncate(tesseract.doOCR(blockImg).trim());
                if (!text.isEmpty() && text.length() > 3) {
                    WrongQuestion q = new WrongQuestion();
                    q.setSubject("Auto Import");
                    q.setContent(text);
                    q.setWrongAns("");
                    q.setRightAns("");
                    q.setTip("Auto-split from scan image (block " + (i + 1) + ")");
                    q.setWrongCount(1);
                    db.add(q);
                    added++;
                }
            } catch (TesseractException e) {
                // 跳过失败的块
            }
        }

        if (added > 0) db.save();
        return new int[]{merged.size(), added};
    }

    /* ==================== Python OCR 自动分割（调用外部Python脚本） ==================== */

    private void pythonOcrAutoSplit() {
        if (originalImage == null) {
            JOptionPane.showMessageDialog(this, "Load an image first.",
                    "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // 查找 ocr_processor.py 脚本位置
        String scriptPath = findPythonScript();
        // 统一校验：执行对象必须是 .py 脚本，防止配置/环境变量被篡改为任意可执行文件
        if (scriptPath != null && !scriptPath.toLowerCase().endsWith(".py")) {
            JOptionPane.showMessageDialog(this,
                    "Invalid script path (must be a .py file): " + scriptPath,
                    "Invalid Script", JOptionPane.ERROR_MESSAGE);
            scriptPath = null;
        }
        if (scriptPath == null) {
            // 找不到时，弹出文件选择对话框让用户手动指定
            int choice = JOptionPane.showConfirmDialog(this,
                    "ocr_processor.py not found automatically.\n\n" +
                            "Current work directory: " + new File(".").getAbsolutePath() + "\n\n" +
                            "Would you like to manually select ocr_processor.py?",
                    "Script Not Found", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);

            if (choice == JOptionPane.YES_OPTION) {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Select ocr_processor.py");
                chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                        "Python Script (*.py)", "py"));
                if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                    File selected = chooser.getSelectedFile();
                    if (selected.getName().equalsIgnoreCase("ocr_processor.py") ||
                            selected.getName().endsWith(".py")) {
                        savedScriptPath = selected.getAbsolutePath();
                        saveConfig();
                        scriptPath = savedScriptPath;
                        statusLabel.setText("Script path saved: " + savedScriptPath);
                    } else {
                        JOptionPane.showMessageDialog(this,
                                "Please select ocr_processor.py file.",
                                "Invalid File", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                } else {
                    return; // 用户取消选择
                }
            } else {
                // 用户选择不手动选择，显示查找路径供参考
                StringBuilder msg = new StringBuilder();
                msg.append("ocr_processor.py not found.\n\n");
                msg.append("Searched paths:\n");
                for (String p : lastSearchedPaths) {
                    msg.append("  - ").append(p).append("\n");
                }
                msg.append("\nPlace ocr_processor.py in one of the above directories,\n");
                msg.append("or set PYTHON_OCR_SCRIPT environment variable.");
                JOptionPane.showMessageDialog(this, msg.toString(),
                        "Script Not Found", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
        }

        // 检查Python是否可用
        if (!isPythonAvailable()) {
            JOptionPane.showMessageDialog(this,
                    "Python is not available in PATH.\n" +
                            "Please install Python 3.7+ and add it to PATH.",
                    "Python Not Found", JOptionPane.ERROR_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
                "Python OCR will call ocr_processor.py with OpenCV + OCR.\n" +
                        "First run may auto-install dependencies (opencv-python, numpy).\n\n" +
                        "Proceed?",
                "Python OCR", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        // 保存当前图片为临时PNG
        File tempImg = null;
        try {
            tempImg = File.createTempFile("wqb_scan_", ".png");
            ImageIO.write(originalImage, "png", tempImg);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Save temp image failed: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        final File finalTempImg = tempImg;
        final String finalScriptPath = scriptPath;
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        statusLabel.setText("Python OCR processing... (first run may install deps)");

        SwingWorker<Integer, Void> worker = new SwingWorker<Integer, Void>() {
                        @Override
            protected Integer doInBackground() {
                int added = 0;
                try {
                    // 获取可用的 Python 命令名
                    String pythonCmd = getPythonCommand();
                    if (pythonCmd == null) {
                        return -1;
                    }
                    // 构建命令：python ocr_processor.py <image_path>
                    ProcessBuilder pb = new ProcessBuilder(
                            pythonCmd, finalScriptPath, finalTempImg.getAbsolutePath()
                    );
                    pb.redirectErrorStream(false);
                    Process process = pb.start();

                    // 读取标准输出（JSON结果）
                    StringBuilder output = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            output.append(line);
                        }
                    }

                    // 读取标准错误（日志信息）
                    StringBuilder error = new StringBuilder();
                    try (BufferedReader errReader = new BufferedReader(
                            new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = errReader.readLine()) != null) {
                            error.append(line).append("\n");
                        }
                    }

                    process.waitFor();

                    String jsonStr = output.toString().trim();
                    if (jsonStr.isEmpty()) {
                        System.err.println("[Python OCR] stderr: " + error);
                        return -1;
                    }

                    JSONObject json = new JSONObject(jsonStr);
                    if (!json.optBoolean("success", false)) {
                        System.err.println("[Python OCR] failed: " + json.optString("error", "unknown"));
                        return -1;
                    }

                    JSONArray questions = json.optJSONArray("questions");
                    if (questions == null) return 0;

                    for (int i = 0; i < questions.length(); i++) {
                        JSONObject q = questions.getJSONObject(i);
                        String content = truncate(q.optString("content", "").trim());
                        if (content.isEmpty() || content.length() < 3) continue;

                        WrongQuestion wq = new WrongQuestion();
                        wq.setSubject("Python OCR");
                        wq.setContent(content);
                        wq.setWrongAns("");
                        wq.setRightAns("");
                        wq.setTip("Imported by Python OpenCV + OCR (block " + (i + 1) + ")");
                        wq.setWrongCount(1);
                        db.add(wq);
                        added++;
                    }

                    if (added > 0) db.save();

                } catch (Exception e) {
                    e.printStackTrace();
                    return -1;
                } finally {
                    if (finalTempImg != null && finalTempImg.exists()) {
                        finalTempImg.delete();
                    }
                }
                return added;
            }
            
            @Override
            protected void done() {
                try {
                    int added = get();
                    setCursor(Cursor.getDefaultCursor());
                    if (added < 0) {
                        statusLabel.setText("Python OCR failed. Check console for details.");
                        JOptionPane.showMessageDialog(ScanImportDialog.this,
                                "Python OCR processing failed.\n" +
                                        "Check that Python, opencv-python and OCR engine are installed.",
                                "Python OCR Error", JOptionPane.ERROR_MESSAGE);
                    } else {
                        statusLabel.setText("Python OCR complete: " + added + " questions added.");
                        JOptionPane.showMessageDialog(ScanImportDialog.this,
                                "Python OCR added " + added + " questions.\n" +
                                        "Please review and edit them (fill in correct answers).",
                                "Python OCR Done", JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception e) {
                    setCursor(Cursor.getDefaultCursor());
                    statusLabel.setText("Python OCR error: " + e.getMessage());
                }
            }
        };
        worker.execute();
    }

    /* ==================== 配置文件读写 ==================== */

    private void loadConfig() {
        File f = new File(CONFIG_FILE);
        if (!f.exists()) return;
        try (java.io.InputStream is = new java.io.FileInputStream(f)) {
            java.util.Properties props = new java.util.Properties();
            props.load(is);
            savedScriptPath = props.getProperty("python_ocr_script");
            if (savedScriptPath != null && !new File(savedScriptPath).exists()) {
                savedScriptPath = null; // 路径已失效，清除
            }
        } catch (Exception e) {
            // 配置读取失败，忽略
        }
    }

    private void saveConfig() {
        if (savedScriptPath == null) return;
        try (java.io.OutputStream os = new java.io.FileOutputStream(CONFIG_FILE)) {
            java.util.Properties props = new java.util.Properties();
            props.setProperty("python_ocr_script", savedScriptPath);
            props.store(os, "Wrong Question Book Config");
        } catch (Exception e) {
            // 保存失败，忽略
        }
    }

    /**
     * 查找 ocr_processor.py 脚本位置
     * 优先级：用户保存的路径 > 环境变量 PYTHON_OCR_SCRIPT > 当前目录 > JAR目录 > 用户主目录 > 源码目录
     */
    private String findPythonScript() {
        java.util.List<String> searched = new ArrayList<>();

        // 0. 用户手动保存的路径（优先级最高）
        if (savedScriptPath != null && !savedScriptPath.isEmpty()) {
            File f = new File(savedScriptPath);
            searched.add("Saved path: " + f.getAbsolutePath());
            if (f.exists()) return f.getAbsolutePath();
        }

        // 1. 环境变量
        String envPath = System.getenv("PYTHON_OCR_SCRIPT");
        if (envPath != null && !envPath.isEmpty()) {
            File f = new File(envPath);
            searched.add("ENV PYTHON_OCR_SCRIPT: " + f.getAbsolutePath());
            if (f.exists()) return f.getAbsolutePath();
        }

        // 2. 当前工作目录
        File local = new File("ocr_processor.py");
        searched.add("Current work dir: " + local.getAbsolutePath());
        if (local.exists()) return local.getAbsolutePath();
        
        // 在 "当前工作目录" 检查之后，增加 demo/ 子目录的检查
        File demoDir = new File("demo/ocr_processor.py");
        searched.add("Demo dir: " + demoDir.getAbsolutePath());
        if (demoDir.exists()) return demoDir.getAbsolutePath();

        // 3. JAR所在目录
        try {
            File jarDir = new File(WrongQuestionBookGUI.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getParentFile();
            File inJarDir = new File(jarDir, "ocr_processor.py");
            searched.add("JAR dir: " + inJarDir.getAbsolutePath());
            if (inJarDir.exists()) return inJarDir.getAbsolutePath();
        } catch (Exception e) {
            searched.add("JAR dir: (cannot resolve)");
        }

        // 4. 用户主目录
        File userHome = new File(System.getProperty("user.home"), "ocr_processor.py");
        searched.add("User home: " + userHome.getAbsolutePath());
        if (userHome.exists()) return userHome.getAbsolutePath();

        // 5. 源码目录 com/mxcloud/
        File srcDir = new File("com/mxcloud/ocr_processor.py");
        searched.add("Source dir: " + srcDir.getAbsolutePath());
        if (srcDir.exists()) return srcDir.getAbsolutePath();

        // 记录查找路径，供调试显示
        lastSearchedPaths = searched;
        return null;
    }

    // 记录最后一次查找的路径列表，用于调试显示
    private java.util.List<String> lastSearchedPaths = new ArrayList<>();

    /**
     * 检查Python是否在PATH中可用，返回可用的命令名
     */
    private String getPythonCommand() {
        String[] candidates = {"py", "python", "python3"};
        for (String cmd : candidates) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.waitFor();
                if (p.exitValue() == 0) {
                    return cmd;
                }
            } catch (Exception e) {
                // 继续下一个候选
            }
        }
        return null; // 全部试完都不行
    }

    /**
     * 检查Python是否在PATH中可用
     */
    private boolean isPythonAvailable() {
        return getPythonCommand() != null;
    }
}
