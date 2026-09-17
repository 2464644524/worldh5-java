package com.liang.world.desktop;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * 把“存号”结果写回固定的 data/账号.xlsx。
 * 列：渠道、链接、账号、密码、备注。
 * 官服写账号/密码；天宇写链接。优先按导号时记录的 Excel 行号定位，否则按账号/备注匹配，再没有就追加。
 */
public final class ExcelAccountWriter {
    private static final int MAX_HEADER_SCAN_ROWS = 40;

    private ExcelAccountWriter() {
    }

    public static int saveAccount(Path file, AccountConfig config) throws Exception {
        if (file == null) {
            throw new IllegalArgumentException("账号表路径为空");
        }
        if (!Files.isRegularFile(file)) {
            ExcelAccountReader.writeTemplate(file);
        }

        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        int rowNumber;
        try (Workbook workbook = WorkbookFactory.create(file.toFile())) {
            Sheet sheet = workbook.getSheetAt(0);
            Header header = findHeader(sheet);
            if (header == null) {
                throw new IllegalStateException("账号表缺少表头：渠道、链接、账号、密码、备注");
            }

            rowNumber = locateRow(sheet, header, config);
            Row row = sheet.getRow(rowNumber);
            boolean newRow = row == null;
            if (newRow) {
                row = sheet.createRow(rowNumber);
            }
            boolean existing = !newRow && !isRowBlank(row);
            boolean official = config.getChannel() == Channel.GUANFANG;

            if (!existing) {
                // 仅对新追加的空行补渠道和备注，方便在表格里辨认；已有行不动这些列。
                setCell(row, header.channelCol, excelChannelName(config.getChannel()));
                if (header.titleCol >= 0 && !safe(config.getTitle()).isBlank()) {
                    setCell(row, header.titleCol, safe(config.getTitle()));
                }
            }
            // 只更新该渠道对应的凭据列，绝不清空其它列，避免覆盖表格里已有的备注/账号等。
            if (official) {
                setCell(row, header.accountCol, safe(config.getUsername()));
                setCell(row, header.passwordCol, safe(config.getPassword()));
            } else {
                setCell(row, header.urlCol, safe(config.getCustomUrl()));
            }

            try (var out = Files.newOutputStream(temp)) {
                workbook.write(out);
            }
        }

        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Files.deleteIfExists(temp);
            throw new IllegalStateException("写入账号表失败，请先关闭正在打开的 Excel：" + e.getMessage(), e);
        }
        return rowNumber + 1; // 返回 1 基行号
    }

    private static String excelChannelName(Channel channel) {
        if (channel == Channel.GUANFANG) {
            return "官服";
        }
        if (channel == Channel.XIAOQI) {
            return "小七";
        }
        return "天宇";
    }

    private static void setCell(Row row, int col, String value) {
        if (col < 0) {
            return;
        }
        Cell cell = row.getCell(col);
        if (cell == null) {
            cell = row.createCell(col);
        }
        cell.setCellValue(value == null ? "" : value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static int locateRow(Sheet sheet, Header header, AccountConfig config) {
        int firstData = header.rowNumber + 1;
        int last = sheet.getLastRowNum();

        // 1) 导号时记录的行号（0 基 -> 索引）
        int saved = config.getExcelRow() - 1;
        if (saved >= firstData && saved <= last && sheet.getRow(saved) != null) {
            return saved;
        }

        // 2) 按身份匹配：官服匹配账号，天宇匹配备注
        boolean official = config.getChannel() == Channel.GUANFANG;
        for (int r = firstData; r <= last; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            if (official) {
                String acc = read(row, header.accountCol);
                if (!acc.isBlank() && acc.equals(safe(config.getUsername()))) {
                    return r;
                }
            } else {
                String title = read(row, header.titleCol);
                if (!title.isBlank() && title.equals(safe(config.getTitle()))) {
                    return r;
                }
            }
        }

        // 3) 追加到第一个空行（优先填空行）
        for (int r = firstData; r <= last; r++) {
            Row row = sheet.getRow(r);
            if (row == null || isRowBlank(row)) {
                return r;
            }
        }
        return Math.max(last + 1, firstData);
    }

    private static boolean isRowBlank(Row row) {
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            Cell cell = c >= 0 ? row.getCell(c) : null;
            if (cell != null && !readCell(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String read(Row row, int col) {
        if (col < 0) {
            return "";
        }
        Cell cell = row.getCell(col);
        return cell == null ? "" : readCell(cell).trim();
    }

    private static String readCell(Cell cell) {
        try {
            return new org.apache.poi.ss.usermodel.DataFormatter(Locale.CHINA)
                    .formatCellValue(cell);
        } catch (Exception e) {
            return "";
        }
    }

    private static Header findHeader(Sheet sheet) {
        int limit = Math.min(sheet.getLastRowNum(), MAX_HEADER_SCAN_ROWS - 1);
        for (int r = 0; r <= limit; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            Header h = new Header();
            h.rowNumber = r;
            for (int c = 0; c < Math.max(1, row.getLastCellNum()); c++) {
                Cell cell = row.getCell(c);
                if (cell == null) {
                    continue;
                }
                String text = normalize(readCell(cell));
                if (text.isBlank()) {
                    continue;
                }
                if (h.channelCol < 0 && (text.equals("渠道") || text.equals("平台"))) {
                    h.channelCol = c;
                } else if (h.urlCol < 0 && (text.equals("链接") || text.equals("网址")
                        || text.equals("直链") || text.equals("游戏地址") || text.equals("url"))) {
                    h.urlCol = c;
                } else if (h.accountCol < 0 && (text.equals("账号") || text.equals("账户")
                        || text.equals("帐号"))) {
                    h.accountCol = c;
                } else if (h.passwordCol < 0 && text.equals("密码")) {
                    h.passwordCol = c;
                } else if (h.titleCol < 0 && (text.equals("备注") || text.equals("名称")
                        || text.equals("标记"))) {
                    h.titleCol = c;
                }
            }
            if (h.channelCol >= 0 || h.urlCol >= 0 || h.accountCol >= 0) {
                return h;
            }
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT)
                .replace(" ", "").replace("　", "")
                .replace("_", "").replace("-", "")
                .replace(":", "").replace("：", "");
    }

    private static final class Header {
        private int rowNumber;
        private int channelCol = -1;
        private int urlCol = -1;
        private int accountCol = -1;
        private int passwordCol = -1;
        private int titleCol = -1;
    }
}