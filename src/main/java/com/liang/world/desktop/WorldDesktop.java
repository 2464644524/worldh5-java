package com.liang.world.desktop;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Properties;
import javax.swing.JSlider;
import javax.swing.event.ChangeListener;
import java.util.ArrayList;
import java.util.List;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class WorldDesktop extends JFrame {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Font SMALL_FONT = new Font("Microsoft YaHei", Font.PLAIN, 11);
    private static final Font ACCOUNT_FONT = new Font("Microsoft YaHei", Font.BOLD, 12);
    private static final Path EXCEL_ACCOUNT_FILE = Path.of("data", "\u8d26\u53f7.xlsx");

    private static final Color COLOR_CLOSED = new Color(230, 230, 230);
    private static final Color COLOR_OPEN = new Color(120, 170, 255);
    private static final Color COLOR_BOOTSTRAPPED = new Color(83, 175, 95);

    private final SessionManager manager;
    private final JButton[] accountButtons = new JButton[ConfigStore.ACCOUNT_COUNT];
    private JButton syncButton;
    private JButton pilotButton;
    private JButton autoButton;
    private JButton refreshButton;
    private JButton loopButton;
    private static final Color COLOR_RUNNING = new Color(83, 175, 95);
    private static final Color COLOR_IDLE = new Color(230, 230, 230);
    private final JLabel currentLabel = new JLabel(" ", JLabel.CENTER);
    private final JTextArea logArea = new JTextArea(6, 14);
    private final Rectangle usableBounds;
    private final Path uiPrefsFile;
    private int sidebarPercent;
    private int selectedIndex;

    public WorldDesktop(SessionManager manager) {
        super("辅助");
        this.manager = manager;
        this.usableBounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        this.uiPrefsFile = Path.of("data", "ui.properties");
        this.sidebarPercent = loadSidebarPercent(uiPrefsFile);
        buildUi();

        Timer timer = new Timer(1000, e -> refreshAccountButtons());
        timer.start();
        refreshAccountButtons();
    }

    private void buildUi() {
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setAlwaysOnTop(true);
        setResizable(true);

        JPanel root = new JPanel(new BorderLayout(2, 3));
        root.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));

        currentLabel.setFont(ACCOUNT_FONT);
        root.add(currentLabel, BorderLayout.NORTH);

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, javax.swing.BoxLayout.Y_AXIS));
        center.add(buildAccountPanel());
        center.add(buildActionPanel());
        center.add(buildBulkPanel());
        root.add(center, BorderLayout.CENTER);
        root.add(buildLogPanel(), BorderLayout.SOUTH);

        setContentPane(root);
        setMinimumSize(new Dimension(130, 420));
    }

    private JPanel buildAccountPanel() {
        JPanel panel = new JPanel(new GridLayout(5, 2, 2, 2));
        panel.setBorder(BorderFactory.createTitledBorder("账号"));
        for (int i = 0; i < ConfigStore.ACCOUNT_COUNT; i++) {
            final int index = i;
            JButton button = new JButton(String.format("%02d", i + 1));
            button.setFont(ACCOUNT_FONT);
            button.setMargin(new Insets(1, 1, 1, 1));
            button.setFocusPainted(false);
            button.addActionListener(e -> selectAccount(index));
            accountButtons[i] = button;
            panel.add(button);
        }
        return panel;
    }

    private JPanel buildActionPanel() {
        JPanel panel = new JPanel(new GridLayout(0, 2, 2, 2));
        panel.setBorder(BorderFactory.createTitledBorder("当前账号"));
        pilotButton = actionButton("托管", "单号一键托管：等待进游戏、注入、进城并开启自动；再点一次停止（保留窗口）", this::toggleAutoPilot);
        pilotButton.setOpaque(true);
        pilotButton.setBorderPainted(true);
        panel.add(pilotButton);
        panel.add(actionButton("打开", "打开当前选中账号", () -> manager.openAccount(selectedIndex)));
        panel.add(actionButton("登录", "进入当前账号的渠道登录页", () -> manager.login(selectedIndex)));
        panel.add(actionButton("刷新", "刷新当前游戏窗口", () -> manager.reload(selectedIndex)));
        panel.add(actionButton("前置", "把当前游戏窗口放到最前面", () -> manager.bringToFront(selectedIndex)));
        panel.add(buildSyncButton());
        panel.add(actionButton("存号", "官服保存账号密码，天宇保存游戏直链", this::saveSelectedUrl));
        panel.add(actionButton("设置", "设置备注、渠道、存号地址", this::editSelectedConfig));
        autoButton = toggleButton("自动", "自动任务/对话/战斗/修理（再点一次关闭）", () -> manager.toggleAuto(selectedIndex));
        panel.add(autoButton);
        refreshButton = toggleButton("刷怪", "定时刷新刷怪（再点一次关闭）", () -> manager.toggleRefresh(selectedIndex));
        panel.add(refreshButton);
        loopButton = toggleButton("跟随", "跟随队长/自动确认押镖（再点一次关闭）", () -> manager.toggleLoop(selectedIndex));
        panel.add(loopButton);
        panel.add(actionButton("停止", "停止当前账号脚本", () -> manager.stopScripts(selectedIndex)));
        panel.add(actionButton("进城", "执行进城", () -> manager.enterCity(selectedIndex)));
        panel.add(actionButton("微端", "领取微端奖励", () -> manager.drawMicroReward(selectedIndex)));
        panel.add(actionButton("清包", "立即清理一次背包垃圾（规则同自动清背包，日志回报出售件数）", () -> manager.clearBagNow(selectedIndex)));
        panel.add(actionButton("关闭", "关闭当前游戏窗口", () -> manager.closeAccount(selectedIndex)));
        return panel;
    }

    private JPanel buildBulkPanel() {
        JPanel panel = new JPanel(new GridLayout(0, 2, 2, 2));
        panel.setBorder(BorderFactory.createTitledBorder("批量"));
        panel.add(actionButton("全开", "依次打开 10 个账号", this::openAll));
        panel.add(actionButton("\u5bfc\u53f7", "\u8bfb\u53d6\u56fa\u5b9a\u8868\u683c\u5e76\u9009\u62e9\u8d26\u53f7", this::importExcelAndLogin));
        panel.add(actionButton("\u8868\u683c", "\u6253\u5f00\u6216\u751f\u6210 data/\u8d26\u53f7.xlsx", this::openFixedAccountFile));
        panel.add(actionButton("比例", "调整游戏区和辅助栏的宽度比例", this::editRatio));
        panel.add(actionButton("尺寸", "调整 Edge 手机仿真宽×高", this::editSize));
        panel.add(actionButton("缩放", "缩小/放大游戏内画面比例（不受窗口最小宽度限制）", this::editGameScale));
        panel.add(actionButton("全托", "从导号队列第一个账号开始：自动登录、进城、自动任务，无主线后自动切到下一个账号",
                this::startBulkAutoPilot));
        panel.add(actionButton("全前置", "恢复所有窗口尺寸并前置当前账号",
                () -> manager.bringAllToFront(selectedIndex)));
        panel.add(actionButton("全自动", "对所有已进入游戏的账号开启自动",
                () -> manager.startAutoAll(selectedIndex)));
        panel.add(actionButton("全刷怪", "对所有已进入游戏的账号开启刷怪",
                () -> manager.startRefreshAll(selectedIndex)));
        panel.add(actionButton("全跟随", "对所有已进入游戏的账号开启跟随",
                () -> manager.startLoopAll(selectedIndex)));
        panel.add(actionButton("全停止", "停止所有已进入游戏账号的脚本",
                () -> manager.stopAllScripts(selectedIndex)));
        panel.add(actionButton("全进城", "对所有已进入游戏的账号执行进城",
                () -> manager.enterCityAll(selectedIndex)));
        panel.add(actionButton("全微端", "对所有已进入游戏的账号领取微端奖励",
                () -> manager.drawMicroRewardAll(selectedIndex)));
        return panel;
    }

    private JScrollPane buildLogPanel() {
        logArea.setEditable(false);
        logArea.setFont(SMALL_FONT);
        logArea.setLineWrap(true);
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setPreferredSize(new Dimension(160, 105));
        return scrollPane;
    }

    private JButton toggleButton(String text, String tip, Runnable action) {
        JButton button = actionButton(text, tip, action);
        button.setOpaque(true);
        button.setBorderPainted(true);
        return button;
    }

    private JButton actionButton(String text, String tip, Runnable action) {
        JButton button = new JButton(text);
        button.setToolTipText(tip);
        button.setFont(SMALL_FONT);
        button.setMargin(new Insets(1, 2, 1, 2));
        button.setFocusPainted(false);
        button.addActionListener(e -> {
            try {
                action.run();
            } catch (RuntimeException ex) {
                String message = "按钮执行失败：" + ex.getClass().getSimpleName() + " " + ex.getMessage();
                appendLog(message);
                ex.printStackTrace();
                JOptionPane.showMessageDialog(this, message + "\n\n请把命令行红色报错发给开发者。",
                        "操作失败", JOptionPane.ERROR_MESSAGE);
            }
        });
        return button;
    }

    private void selectAccount(int index) {
        selectedIndex = index;
        if (manager.isSyncEnabled()) {
            manager.setSyncMaster(index);
        }
        refreshAccountButtons();
        manager.bringToFront(index);
    }

    private JButton buildSyncButton() {
        syncButton = new JButton("同步");
        syncButton.setToolTipText("开启后，操作当前号会把同样的点击/拖动同步到其它已进入游戏的号");
        syncButton.setFont(SMALL_FONT);
        syncButton.setMargin(new Insets(1, 2, 1, 2));
        syncButton.setFocusPainted(false);
        syncButton.setOpaque(true);
        syncButton.addActionListener(e -> {
            boolean nowOn = !manager.isSyncEnabled();
            manager.setSyncEnabled(nowOn, selectedIndex);
            refreshSyncButton();
        });
        return syncButton;
    }

    private void refreshSyncButton() {
        if (syncButton == null) {
            return;
        }
        boolean on = manager.isSyncEnabled();
        syncButton.setText(on
                ? "同步中·" + String.format("%02d", manager.getSyncMaster() + 1)
                : "同步");
        syncButton.setBackground(on ? new Color(255, 153, 0) : COLOR_CLOSED);
    }

    // running=绿色高亮；未进入游戏时按钮置灰并禁用，进入游戏但功能没开=普通灰可点。
    private void paintToggle(JButton button, boolean running, boolean available) {
        if (button == null) {
            return;
        }
        button.setEnabled(available);
        button.setBackground(running ? COLOR_RUNNING : COLOR_IDLE);
    }

    private void refreshAccountButtons() {
        for (int i = 0; i < ConfigStore.ACCOUNT_COUNT; i++) {
            AccountConfig config = manager.store().account(i);
            boolean open = manager.isAccountOpen(i);
            boolean bootstrapped = manager.isAccountBootstrapped(i);

            JButton button = accountButtons[i];
            button.setBackground(bootstrapped ? COLOR_BOOTSTRAPPED : open ? COLOR_OPEN : COLOR_CLOSED);
            button.setOpaque(true);
            button.setBorderPainted(true);
            button.setBorder(i == selectedIndex
                    ? BorderFactory.createLineBorder(Color.ORANGE, 3)
                    : BorderFactory.createLineBorder(Color.GRAY, 1));

            String state = bootstrapped ? "已注入" : open ? "已打开" : "未打开";
            button.setToolTipText(String.format(
                    "<html>账号%02d<br>%s<br>%s</html>", i + 1, config.displayName(), state));
        }

        refreshSyncButton();

        boolean bootstrapped = manager.isAccountBootstrapped(selectedIndex);
        paintToggle(autoButton, bootstrapped && manager.isScriptOn(selectedIndex, AccountSession.FLAG_AUTO), bootstrapped);
        paintToggle(refreshButton, bootstrapped && manager.isScriptOn(selectedIndex, AccountSession.FLAG_REFRESH), bootstrapped);
        paintToggle(loopButton, bootstrapped && manager.isScriptOn(selectedIndex, AccountSession.FLAG_LOOP), bootstrapped);
        refreshAutoPilotButton();

        AccountConfig config = manager.store().account(selectedIndex);
        String state = bootstrapped ? "已注入"
                : manager.isAccountOpen(selectedIndex) ? "已打开" : "未打开";
        String base = String.format("%02d %s · %s",
                selectedIndex + 1, config.getTitle(), state);
        boolean pilotOnThis = manager.isAutoPilotActive()
                && manager.getAutoPilotIndex() == selectedIndex;
        if (pilotOnThis) {
            String pilotStatus = manager.getAutoPilotStatus();
            pilotStatus = pilotStatus.length() > 13 ? pilotStatus.substring(0, 13) : pilotStatus;
            currentLabel.setText("<html><font size=1><center>"
                    + base + "<br>[托管] " + pilotStatus + "</center></font></html>");
        } else {
            currentLabel.setText(base);
        }
    }

    private void refreshAutoPilotButton() {
        if (pilotButton == null) {
            return;
        }
        boolean active = manager.isAutoPilotActive();
        if (active) {
            int queueSize = manager.getAutoPilotQueueSize();
            int queuePosition = manager.getAutoPilotQueuePosition();
            String queueText = queueSize > 1 ? " " + queuePosition + "/" + queueSize : "";
            pilotButton.setText("停管" + queueText + "·" + String.format("%02d", manager.getAutoPilotIndex() + 1));
            pilotButton.setBackground(new Color(255, 153, 0));
            pilotButton.setToolTipText("一键托管中。当前阶段：" + manager.getAutoPilotStatus()
                    + "；点击停止并保留窗口。");
        } else {
            pilotButton.setText("托管");
            pilotButton.setBackground(COLOR_CLOSED);
            pilotButton.setToolTipText("单号一键托管：等待进游戏、注入、进城并开启自动；再点一次停止（保留窗口）");
        }
    }

    private void toggleAutoPilot() {
        if (manager.isAutoPilotActive()) {
            manager.stopAutoPilot();
        } else {
            manager.startAutoPilot(selectedIndex);
        }
        refreshAccountButtons();
    }

    private void startBulkAutoPilot() {
        if (manager.isAutoPilotActive()) {
            appendLog("托管队列已在运行，当前：" + manager.getAutoPilotStatus());
            return;
        }
        int choice = JOptionPane.showConfirmDialog(
                this,
                "将读取固定 Excel 中今天未完成的账号开始托管。\n"
                + "请确认 WPS/Excel 已按 Ctrl+S 保存；未保存的完成日期程序读不到。\n"
                + "当前号连续约60分钟无主线任务后，会重新读取 Excel，自动登录下一个今日未完成账号。是否继续？",
                "批量托管确认",
                JOptionPane.OK_CANCEL_OPTION);
        if (choice == JOptionPane.OK_OPTION) {
            manager.startAutoPilotAll();
            appendLog("开始批量托管：按已保存 Excel 的今日未完成账号接力");
        }
    }

    private void saveSelectedUrl() {
        int index = selectedIndex;
        manager.saveCurrentUrl(index, EXCEL_ACCOUNT_FILE, result -> SwingUtilities.invokeLater(() -> {
            String text = result == null ? "" : result.trim();
            if (text.toLowerCase().startsWith("http")) {
                appendLog("账号 " + (index + 1) + " 存号成功，游戏直链: " + text);
                copyToClipboard(text);
                showLinkDialog(index, text);
            } else {
                appendLog("账号 " + (index + 1) + " " + text);
                JOptionPane.showMessageDialog(this,
                        text, "存号成功", JOptionPane.INFORMATION_MESSAGE);
            }
        }));
    }

    private void copyToClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
        } catch (Exception ignored) {
        }
    }

    private void showLinkDialog(int index, String url) {
        JTextArea area = new JTextArea(url, 5, 36);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFont(SMALL_FONT);
        Object[] messages = {
                "账号 " + (index + 1) + " 的游戏直链已保存（下次点“打开”可直接进入），并已复制到剪贴板：",
                new JScrollPane(area)
        };
        JOptionPane.showMessageDialog(this, messages, "存号成功",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void editSelectedConfig() {
        int index = selectedIndex;
        AccountConfig config = manager.store().account(index);

        JDialog dialog = new JDialog(this, "账号 " + (index + 1) + " 设置", true);
        JPanel panel = new JPanel(new GridLayout(0, 1, 5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JTextField titleField = new JTextField(config.getTitle());
        JComboBox<Channel> channelBox = new JComboBox<>(Channel.values());
        channelBox.setSelectedItem(config.getChannel());
        JTextField urlField = new JTextField(config.getCustomUrl());
        JCheckBox autoClearBagBox = new JCheckBox("自动清背包", config.isAutoClearBag());

        panel.add(new JLabel("备注"));
        panel.add(titleField);
        panel.add(new JLabel("渠道"));
        panel.add(channelBox);
        panel.add(new JLabel("存号地址（留空则打开渠道登录页）"));
        panel.add(urlField);
        panel.add(autoClearBagBox);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        JButton cancelButton = new JButton("取消");
        JButton saveButton = new JButton("保存");
        buttons.add(cancelButton);
        buttons.add(saveButton);
        panel.add(buttons);

        cancelButton.addActionListener(e -> dialog.dispose());
        saveButton.addActionListener(e -> {
            config.setTitle(titleField.getText().trim());
            config.setChannel((Channel) channelBox.getSelectedItem());
            config.setCustomUrl(urlField.getText().trim());
            boolean clearBagChanged = config.isAutoClearBag() != autoClearBagBox.isSelected();
            config.setAutoClearBag(autoClearBagBox.isSelected());
            manager.saveConfig();
            if (clearBagChanged) {
                manager.applyAutoClearBagLive(index);
            }
            appendLog("账号 " + (index + 1) + " 配置已保存");
            refreshAccountButtons();
            dialog.dispose();
        });

        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setSize(360, Math.max(260, dialog.getHeight()));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void importExcelAndLogin() {
        Path file = EXCEL_ACCOUNT_FILE;
        if (!java.nio.file.Files.isRegularFile(file)) {
            try {
                ExcelAccountReader.writeTemplate(file);
                appendLog("固定账号表不存在，已生成: " + file.toAbsolutePath());
                openFileExternally(file);
                JOptionPane.showMessageDialog(this,
                        "固定账号表已生成并打开：\n" + file.toAbsolutePath()
                                + "\n\n请按渠道、链接、账号、密码、备注、完成日期填写保存后，再点一次“导号”。",
                        "请先填写账号表", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                appendLog("生成固定账号表失败: " + e.getMessage());
                JOptionPane.showMessageDialog(this,
                        "生成固定账号表失败：\n" + e.getMessage(),
                        "错误", JOptionPane.ERROR_MESSAGE);
            }
            return;
        }

        appendLog("开始读取固定账号表: " + file.toAbsolutePath());
        new Thread(() -> {
            Path tempFile = null;
            try {
                // 复制到临时文件读取，避免 WPS/Excel 正在打开表格时锁住原文件。
                tempFile = Files.createTempFile("world-accounts-", ".xlsx");
                Files.copy(file, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                List<ExcelAccount> accounts = ExcelAccountReader.read(tempFile);
                long unfinished = accounts.stream().filter(account -> !account.isFinishedToday()).count();
                appendLog("固定账号表读取成功：" + accounts.size() + " 个账号，今日未完成 " + unfinished + " 个，正在打开选择窗口");
                SwingUtilities.invokeLater(() -> showExcelAccountPicker(file.toFile(), accounts));
            } catch (Throwable e) {
                appendLog("读取固定账号表失败: " + e.getClass().getSimpleName() + " " + e.getMessage());
                e.printStackTrace();
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "读取固定账号表失败：\n" + file.toAbsolutePath() + "\n"
                                + e.getClass().getSimpleName() + ": " + e.getMessage(),
                        "读取失败", JOptionPane.ERROR_MESSAGE));
            } finally {
                if (tempFile != null) {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException ignored) {
                    }
                }
            }
        }, "excel-account-reader").start();
    }

    private void openFixedAccountFile() {
        try {
            if (!java.nio.file.Files.isRegularFile(EXCEL_ACCOUNT_FILE)) {
                ExcelAccountReader.writeTemplate(EXCEL_ACCOUNT_FILE);
                appendLog("\u5df2\u751f\u6210\u56fa\u5b9a\u8d26\u53f7\u8868: " + EXCEL_ACCOUNT_FILE.toAbsolutePath());
            }
            openFileExternally(EXCEL_ACCOUNT_FILE);
            appendLog("\u5df2\u6253\u5f00\u56fa\u5b9a\u8d26\u53f7\u8868: " + EXCEL_ACCOUNT_FILE.toAbsolutePath());
        } catch (IOException e) {
            appendLog("\u6253\u5f00\u56fa\u5b9a\u8d26\u53f7\u8868\u5931\u8d25: " + e.getMessage());
            JOptionPane.showMessageDialog(this,
                    "\u6253\u5f00\u56fa\u5b9a\u8d26\u53f7\u8868\u5931\u8d25\uff1a\n" + e.getMessage()
                            + "\n\n\u6587\u4ef6\u4f4d\u7f6e\uff1a" + EXCEL_ACCOUNT_FILE.toAbsolutePath(),
                    "\u9519\u8bef", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openFileExternally(Path file) throws IOException {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(file.toFile());
                return;
            }
        } catch (Exception ignored) {
        }
        new ProcessBuilder("explorer.exe", file.toAbsolutePath().toString()).start();
    }

    private void showExcelAccountPicker(File sourceFile, List<ExcelAccount> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "\u8d26\u53f7\u8868\u91cc\u6ca1\u6709\u53ef\u7528\u8d26\u53f7\uff0c\u8bf7\u786e\u8ba4\u81f3\u5c11\u5305\u542b\u201c\u8d26\u53f7\u201d\u548c\u201c\u5bc6\u7801\u201d\u4e24\u5217\u3002",
                    "\u6ca1\u6709\u8d26\u53f7", JOptionPane.WARNING_MESSAGE);
            return;
        }

        ExcelAccountTableModel tableModel = new ExcelAccountTableModel(accounts);
        JTable table = new JTable(tableModel);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setMaxWidth(48);
        table.getColumnModel().getColumn(1).setMaxWidth(48);
        table.getColumnModel().getColumn(2).setMaxWidth(65);
        table.getColumnModel().getColumn(3).setPreferredWidth(130);
        table.getColumnModel().getColumn(4).setPreferredWidth(320);
        table.getColumnModel().getColumn(5).setMaxWidth(68);
        table.getColumnModel().getColumn(6).setMaxWidth(105);

        JLabel countLabel = new JLabel();
        Runnable updateCount = () -> countLabel.setText(
                "\u5df2\u9009\u62e9 " + tableModel.selectedCount() + " / \u6700\u591a "
                        + ConfigStore.ACCOUNT_COUNT + " \u4e2a\uff0c\u56fa\u5b9a\u6587\u4ef6: " + sourceFile.getName());
        tableModel.addTableModelListener(e -> {
            updateCount.run();
            if (tableModel.selectedCount() > ConfigStore.ACCOUNT_COUNT) {
                JOptionPane.showMessageDialog(this,
                        "\u6700\u591a\u53ea\u80fd\u540c\u65f6\u767b\u5f55 " + ConfigStore.ACCOUNT_COUNT + " \u4e2a\uff0c\u8bf7\u53d6\u6d88\u90e8\u5206\u8d26\u53f7\u3002",
                        "\u8d85\u51fa\u6570\u91cf", JOptionPane.WARNING_MESSAGE);
            }
        });
        updateCount.run();

        JDialog dialog = new JDialog(this, "\u9009\u62e9\u9700\u8981\u767b\u5f55\u7684\u8d26\u53f7", true);
        JPanel root = new JPanel(new BorderLayout(6, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(820, 430));
        root.add(countLabel, BorderLayout.NORTH);
        root.add(tableScroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton selectFirstButton = new JButton("\u9009\u524d10\u4e2a");
        JButton clearButton = new JButton("\u6e05\u7a7a");
        JButton cancelButton = new JButton("\u53d6\u6d88");
        JButton loginButton = new JButton("\u767b\u5f55\u9009\u4e2d\u8d26\u53f7");
        buttons.add(selectFirstButton);
        buttons.add(clearButton);
        buttons.add(cancelButton);
        buttons.add(loginButton);
        root.add(buttons, BorderLayout.SOUTH);

        selectFirstButton.addActionListener(e -> tableModel.selectUnfinished(ConfigStore.ACCOUNT_COUNT));
        clearButton.addActionListener(e -> tableModel.clearSelection());
        cancelButton.addActionListener(e -> dialog.dispose());
        loginButton.addActionListener(e -> {
            List<ExcelAccount> selected = tableModel.selectedAccounts();
            if (selected.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "\u8bf7\u5148\u52fe\u9009\u81f3\u5c11\u4e00\u4e2a\u8d26\u53f7\u3002",
                        "\u672a\u9009\u62e9\u8d26\u53f7", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (selected.size() > ConfigStore.ACCOUNT_COUNT) {
                JOptionPane.showMessageDialog(dialog,
                        "\u6700\u591a\u53ea\u80fd\u540c\u65f6\u767b\u5f55 " + ConfigStore.ACCOUNT_COUNT + " \u4e2a\u3002",
                        "\u8d85\u51fa\u6570\u91cf", JOptionPane.WARNING_MESSAGE);
                return;
            }
            int choice = JOptionPane.showConfirmDialog(dialog,
                    "\u5c06\u6253\u5f00\u9009\u4e2d\u7684 " + selected.size()
                            + " \u4e2a\u72ec\u7acb Edge \u7a97\u53e3\uff0c\u672a\u9009\u4e2d\u7684\u7a97\u53e3\u4f1a\u5173\u95ed\u3002\u786e\u8ba4\u7ee7\u7eed\uff1f",
                    "\u786e\u8ba4\u81ea\u52a8\u4e0a\u53f7", JOptionPane.OK_CANCEL_OPTION);
            if (choice != JOptionPane.OK_OPTION) {
                return;
            }
            dialog.dispose();
            selectedIndex = 0;
            refreshAccountButtons();
            manager.launchExcelAccounts(selected);
            appendLog("\u5df2\u4ece\u56fa\u5b9a\u8d26\u53f7\u8868\u9009\u62e9 " + selected.size() + " \u4e2a\u8d26\u53f7\u5f00\u59cb\u81ea\u52a8\u4e0a\u53f7");
        });

        dialog.setContentPane(root);
        dialog.pack();
        dialog.setSize(900, 560);
        dialog.setLocationRelativeTo(null);
        dialog.setAlwaysOnTop(true);
        dialog.toFront();
        dialog.requestFocus();
        dialog.setVisible(true);
        dialog.toFront();
        dialog.requestFocus();
    }

    private static class ExcelAccountTableModel extends AbstractTableModel {
        private final List<ExcelAccount> accounts;
        private final boolean[] selected;
        private final String[] columns = {"选择", "槽位", "渠道", "备注", "账号/链接", "Excel行", "完成日期"};

        private ExcelAccountTableModel(List<ExcelAccount> accounts) {
            this.accounts = new ArrayList<>(accounts);
            this.selected = new boolean[this.accounts.size()];
            selectUnfinished(ConfigStore.ACCOUNT_COUNT);
        }

        @Override
        public int getRowCount() {
            return accounts.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ExcelAccount account = accounts.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> selected[rowIndex];
                case 1 -> selected[rowIndex] ? String.format("%02d", slotOf(rowIndex)) : "";
                case 2 -> account.getChannel().displayName();
                case 3 -> account.getTitle();
                case 4 -> account.isOfficialAccountLogin() ? account.getUsername() : account.getUrl();
                case 5 -> String.valueOf(account.getRowNumber());
                case 6 -> account.isFinishedToday()
                        ? (account.getFinishDate().isBlank() ? "今日已完成" : account.getFinishDate() + " 已完成")
                        : account.getFinishDate();
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex == 0 && aValue instanceof Boolean value) {
                if (value && selectedCount() >= ConfigStore.ACCOUNT_COUNT) {
                    fireTableCellUpdated(rowIndex, columnIndex);
                    return;
                }
                selected[rowIndex] = value;
                fireTableDataChanged();
            }
        }

        private void selectUnfinished(int count) {
            int chosen = 0;
            for (int i = 0; i < selected.length; i++) {
                selected[i] = chosen < count && !accounts.get(i).isFinishedToday();
                if (selected[i]) {
                    chosen++;
                }
            }
            fireTableDataChanged();
        }

        private int unfinishedCount() {
            int count = 0;
            for (ExcelAccount account : accounts) {
                if (!account.isFinishedToday()) {
                    count++;
                }
            }
            return count;
        }

        private void clearSelection() {
            java.util.Arrays.fill(selected, false);
            fireTableDataChanged();
        }

        private int selectedCount() {
            return selectedAccounts().size();
        }

        private int slotOf(int rowIndex) {
            int slot = 0;
            for (int i = 0; i <= rowIndex && i < selected.length; i++) {
                if (selected[i]) {
                    slot++;
                }
            }
            return slot;
        }

        private List<ExcelAccount> selectedAccounts() {
            List<ExcelAccount> result = new ArrayList<>();
            for (int i = 0; i < accounts.size(); i++) {
                if (selected[i]) {
                    result.add(accounts.get(i));
                }
            }
            return result;
        }
    }



    private Rectangle[] computeLayout(int percent) {
        int pct = Math.max(6, Math.min(40, percent));
        int sidebarWidth = Math.max(110, usableBounds.width * pct / 100);
        sidebarWidth = Math.min(sidebarWidth, usableBounds.width / 2);
        Rectangle game = new Rectangle(
                usableBounds.x, usableBounds.y,
                usableBounds.width - sidebarWidth, usableBounds.height);
        Rectangle sidebar = new Rectangle(
                usableBounds.x + game.width, usableBounds.y,
                sidebarWidth, usableBounds.height);
        return new Rectangle[]{game, sidebar};
    }

    private void applyRatio(int percent) {
        sidebarPercent = Math.max(6, Math.min(40, percent));
        Rectangle[] layout = computeLayout(sidebarPercent);
        setBounds(layout[1]);
        manager.setGameBounds(layout[0]);
    }

    private void editRatio() {
        int before = sidebarPercent;
        JDialog dialog = new JDialog(this, "调整比例（辅助栏宽度占比）", true);
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel valueLabel = new JLabel(" ", JLabel.CENTER);
        JSlider slider = new JSlider(JSlider.HORIZONTAL, 6, 30, before);
        slider.setMajorTickSpacing(6);
        slider.setMinorTickSpacing(2);
        slider.setPaintTicks(true);
        slider.setPaintLabels(true);
        ChangeListener listener = e -> {
            valueLabel.setText("辅助栏 " + slider.getValue() + "%    游戏区 "
                    + (100 - slider.getValue()) + "%");
            applyRatio(slider.getValue());
        };
        slider.addChangeListener(listener);
        listener.stateChanged(null);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton resetButton = new JButton("恢复10%");
        JButton cancelButton = new JButton("取消");
        JButton saveButton = new JButton("保存");
        buttons.add(resetButton);
        buttons.add(cancelButton);
        buttons.add(saveButton);

        resetButton.addActionListener(e -> slider.setValue(10));
        cancelButton.addActionListener(e -> {
            applyRatio(before);
            dialog.dispose();
        });
        saveButton.addActionListener(e -> {
            applyRatio(slider.getValue());
            saveSidebarPercent(uiPrefsFile, slider.getValue());
            appendLog("比例已保存：辅助栏 " + slider.getValue() + "%");
            dialog.dispose();
        });

        root.add(valueLabel, BorderLayout.NORTH);
        root.add(slider, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.pack();
        dialog.setSize(360, dialog.getPreferredSize().height + 30);
        dialog.setLocationRelativeTo(this);
        dialog.setAlwaysOnTop(true);
        dialog.setVisible(true);
    }

    private void editSize() {
        int beforeW = manager.getEmuWidth();
        int beforeH = manager.getEmuHeight();
        JDialog dialog = new JDialog(this, "Edge 手机仿真尺寸（宽 × 高）", true);
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel valueLabel = new JLabel(" ", JLabel.CENTER);
        JSlider widthSlider = new JSlider(JSlider.HORIZONTAL, 280, 900, beforeW);
        JSlider heightSlider = new JSlider(JSlider.HORIZONTAL, 420, 1200, beforeH);
        widthSlider.setMajorTickSpacing(100);
        heightSlider.setMajorTickSpacing(100);
        widthSlider.setPaintTicks(true);
        heightSlider.setPaintTicks(true);

        ChangeListener listener = e -> {
            int w = widthSlider.getValue();
            int h = heightSlider.getValue();
            valueLabel.setText("宽 " + w + "    高 " + h + "（比例 "
                    + String.format(java.util.Locale.ROOT, "%.2f", (double) h / w) + ":1）");
            manager.setEmulationSize(w, h);
        };
        widthSlider.addChangeListener(listener);
        heightSlider.addChangeListener(listener);
        listener.stateChanged(null);

        JPanel sliders = new JPanel(new GridLayout(0, 1, 4, 4));
        sliders.add(new JLabel("宽度（像素）"));
        sliders.add(widthSlider);
        sliders.add(new JLabel("高度（像素）"));
        sliders.add(heightSlider);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton resetButton = new JButton("恢复510×760");
        JButton cancelButton = new JButton("取消");
        JButton saveButton = new JButton("保存");
        buttons.add(resetButton);
        buttons.add(cancelButton);
        buttons.add(saveButton);

        resetButton.addActionListener(e -> {
            widthSlider.setValue(510);
            heightSlider.setValue(760);
        });
        cancelButton.addActionListener(e -> {
            manager.setEmulationSize(beforeW, beforeH);
            dialog.dispose();
        });
        saveButton.addActionListener(e -> {
            manager.setEmulationSize(widthSlider.getValue(), heightSlider.getValue());
            appendLog("仿真尺寸已保存：" + widthSlider.getValue() + " × " + heightSlider.getValue());
            dialog.dispose();
        });

        root.add(valueLabel, BorderLayout.NORTH);
        root.add(sliders, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.pack();
        dialog.setSize(380, dialog.getPreferredSize().height + 20);
        dialog.setLocationRelativeTo(this);
        dialog.setAlwaysOnTop(true);
        dialog.setVisible(true);
    }

    private void editGameScale() {
        double before = manager.getGameScale();
        JDialog dialog = new JDialog(this, "游戏画面缩放（相对窗口）", true);
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel valueLabel = new JLabel(" ", JLabel.CENTER);
        int startPct = (int) Math.round(before * 100);
        JSlider slider = new JSlider(JSlider.HORIZONTAL, 50, 100, startPct);
        slider.setMajorTickSpacing(10);
        slider.setMinorTickSpacing(5);
        slider.setPaintTicks(true);
        slider.setPaintLabels(true);
        ChangeListener listener = e -> {
            int pct = slider.getValue();
            valueLabel.setText("游戏画面 " + pct + "%（窗口大小不变，画面整体缩小）");
            manager.setGameScale(pct / 100.0);
        };
        slider.addChangeListener(listener);
        listener.stateChanged(null);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton resetButton = new JButton("恢复100%");
        JButton cancelButton = new JButton("取消");
        JButton saveButton = new JButton("保存");
        buttons.add(resetButton);
        buttons.add(cancelButton);
        buttons.add(saveButton);

        resetButton.addActionListener(e -> slider.setValue(100));
        cancelButton.addActionListener(e -> {
            manager.setGameScale(before);
            dialog.dispose();
        });
        saveButton.addActionListener(e -> {
            double k = slider.getValue() / 100.0;
            manager.setGameScale(k);
            appendLog("游戏缩放已保存：" + slider.getValue() + "%");
            dialog.dispose();
        });

        root.add(valueLabel, BorderLayout.NORTH);
        root.add(slider, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.pack();
        dialog.setSize(380, dialog.getPreferredSize().height + 20);
        dialog.setLocationRelativeTo(this);
        dialog.setAlwaysOnTop(true);
        dialog.setVisible(true);
    }

    private static int loadSidebarPercent(Path file) {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (Exception ignored) {
            return 10;
        }
        try {
            int value = Integer.parseInt(props.getProperty("sidebarPercent", "10").trim());
            return Math.max(6, Math.min(40, value));
        } catch (Exception e) {
            return 10;
        }
    }

    private static void saveSidebarPercent(Path file, int percent) {
        Properties props = new Properties();
        props.setProperty("sidebarPercent", String.valueOf(Math.max(6, Math.min(40, percent))));
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "world-desktop ui");
            }
        } catch (IOException ignored) {
        }
    }

    private void openAll() {
        int choice = JOptionPane.showConfirmDialog(
                this,
                "将依次打开全部 10 个账号，确认继续？",
                "批量打开确认",
                JOptionPane.OK_CANCEL_OPTION);
        if (choice == JOptionPane.OK_OPTION) {
            manager.openAll(selectedIndex);
            appendLog("开始批量打开 10 个账号");
        }
    }

    public void appendLog(String message) {
        String line = LocalTime.now().format(TIME_FORMAT) + "  " + message;
        SwingUtilities.invokeLater(() -> {
            logArea.append(line + System.lineSeparator());
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    public static void main(String[] args) {
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        Path dataDir = Path.of("data");
        int initialPercent = loadSidebarPercent(dataDir.resolve("ui.properties"));
        int sidebarWidth = Math.max(110, usable.width * initialPercent / 100);
        sidebarWidth = Math.min(sidebarWidth, usable.width / 2);
        Rectangle gameBounds = new Rectangle(
                usable.x,
                usable.y,
                usable.width - sidebarWidth,
                usable.height);
        Rectangle sidebarBounds = new Rectangle(
                usable.x + gameBounds.width,
                usable.y,
                sidebarWidth,
                usable.height);
        SessionManager manager = new SessionManager(dataDir, gameBounds, message -> {
        });
        WorldDesktop window = new WorldDesktop(manager);
        manager.setLogger(window::appendLog);

        Runtime.getRuntime().addShutdownHook(new Thread(manager::close, "shutdown-cleanup"));

        SwingUtilities.invokeLater(() -> {
            window.setBounds(sidebarBounds);
            window.setVisible(true);
            Thread bootstrap = new Thread(() -> {
                try {
                    manager.start();
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(window,
                            "启动失败，请确认系统已安装 Microsoft Edge：\n" + e.getMessage(),
                            "启动错误", JOptionPane.ERROR_MESSAGE));
                }
            }, "playwright-bootstrap");
            bootstrap.setDaemon(true);
            bootstrap.start();
        });
    }
}
