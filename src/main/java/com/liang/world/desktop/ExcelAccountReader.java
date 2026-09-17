package com.liang.world.desktop;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ExcelAccountReader {
    private static final int MAX_HEADER_SCAN_ROWS = 40;

    private ExcelAccountReader() {
    }

    public static List<ExcelAccount> read(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("Excel 文件不存在: " + file);
        }

        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".csv")) {
            return readCsv(file);
        }
        if (!fileName.endsWith(".xlsx") && !fileName.endsWith(".xlsm")
                && !fileName.endsWith(".xls")) {
            throw new IOException("仅支持 .xlsx、.xlsm、.xls、.csv 格式: " + file.getFileName());
        }

        try (Workbook workbook = WorkbookFactory.create(file.toFile())) {
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                HeaderLocation header = findHeader(sheet, formatter, evaluator);
                if (header != null) {
                    return readRows(sheet, header, formatter, evaluator);
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("读取 Excel 失败: " + e.getMessage(), e);
        }

        throw new IOException("没有找到可用列，请确认表头包含“渠道、链接、账号、密码、备注”；天宇至少填写渠道和链接，官服填写账号和密码");
    }

    public static void writeTemplate(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("账号");
            Row header = sheet.createRow(0);
            String[] titles = {"渠道", "链接", "账号", "密码", "备注"};
            for (int i = 0; i < titles.length; i++) {
                header.createCell(i).setCellValue(titles[i]);
                sheet.setColumnWidth(i, (i == 1 ? 58 : 18) * 256);
            }
            try (var output = Files.newOutputStream(file)) {
                workbook.write(output);
            }
        }
    }

    private static HeaderLocation findHeader(Sheet sheet, DataFormatter formatter,
                                              FormulaEvaluator evaluator) {
        int lastRow = sheet.getLastRowNum();
        int scanLimit = Math.min(lastRow, MAX_HEADER_SCAN_ROWS - 1);
        HeaderLocation best = null;
        int bestScore = Integer.MIN_VALUE;

        for (int rowIndex = 0; rowIndex <= scanLimit; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }

            Scores scores = new Scores();
            for (int col = 0; col < Math.max(1, row.getLastCellNum()); col++) {
                String header = normalize(cellValue(row.getCell(col), formatter, evaluator));
                if (header.isBlank()) {
                    continue;
                }
                scores.consider(col, header,
                        channelHeaderScore(header),
                        urlHeaderScore(header),
                        accountHeaderScore(header),
                        passwordHeaderScore(header),
                        titleHeaderScore(header));
            }

            if (scores.isUsableHeader()) {
                int score = scores.totalScore();
                if (score > bestScore) {
                    bestScore = score;
                    best = scores.toHeaderLocation(rowIndex);
                }
            }
        }
        return best;
    }

    private static List<ExcelAccount> readRows(Sheet sheet, HeaderLocation header,
                                               DataFormatter formatter, FormulaEvaluator evaluator) {
        List<ExcelAccount> accounts = new ArrayList<>();
        for (int rowIndex = header.rowNumber + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }

            String channelText = value(row, header.channelCol, formatter, evaluator);
            String url = value(row, header.urlCol, formatter, evaluator);
            String username = value(row, header.accountCol, formatter, evaluator);
            String password = value(row, header.passwordCol, formatter, evaluator);
            String title = value(row, header.titleCol, formatter, evaluator);

            ExcelAccount account = buildAccount(rowIndex + 1, username, password, title, channelText, url);
            if (account != null) {
                accounts.add(account);
            }
        }
        return accounts;
    }

    private static ExcelAccount buildAccount(int rowNumber, String username, String password,
                                             String title, String channelText, String url) {
        boolean allBlank = isBlank(channelText) && isBlank(url) && isBlank(username)
                && isBlank(password) && isBlank(title);
        if (allBlank) {
            return null;
        }

        ExcelAccount candidate = new ExcelAccount(rowNumber, username, password, title, channelText, url);
        Channel channel = candidate.getChannel();
        if (channel == Channel.GUANFANG) {
            if (isBlank(username) || isBlank(password)) {
                return null;
            }
        } else if (isBlank(url)) {
            return null;
        }
        return candidate;
    }

    private static String value(Row row, int col, DataFormatter formatter, FormulaEvaluator evaluator) {
        return col >= 0 ? cellValue(row.getCell(col), formatter, evaluator).trim() : "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static int channelHeaderScore(String header) {
        if (header.equals("渠道") || header.equals("平台") || header.equals("服")
                || header.equals("channel")) {
            return 100;
        }
        if (header.contains("渠道") || header.contains("平台")) {
            return 80;
        }
        return -1;
    }

    private static int urlHeaderScore(String header) {
        if (header.equals("链接") || header.equals("地址") || header.equals("网址")
                || header.equals("登录链接") || header.equals("游戏链接") || header.equals("url")) {
            return 100;
        }
        if (header.contains("链接") || header.contains("地址") || header.contains("网址")
                || header.contains("url") || header.startsWith("http")) {
            return 75;
        }
        return -1;
    }

    private static int accountHeaderScore(String header) {
        if (header.equals("账号") || header.equals("账户") || header.equals("用户名")
                || header.equals("account") || header.equals("username")) {
            return 100;
        }
        if (header.equals("游戏账号") || header.equals("登录账号") || header.equals("游戏账户")) {
            return 95;
        }
        if (header.contains("游戏账号") || header.contains("登录账号")
                || header.contains("游戏账户") || header.contains("登录账户")) {
            return 90;
        }
        if (header.contains("账号") || header.contains("账户") || header.contains("用户名")) {
            return 70;
        }
        return -1;
    }

    private static int passwordHeaderScore(String header) {
        if (header.equals("密码") || header.equals("password") || header.equals("passwd")
                || header.equals("pwd")) {
            return 100;
        }
        if (header.equals("登录密码") || header.equals("游戏密码")) {
            return 90;
        }
        if (header.contains("确认") || header.contains("重复") || header.contains("二次")
                || header.contains("二级") || header.contains("安全码") || header.contains("交易")
                || header.contains("仓库") || header.contains("背包") || header.contains("锁码")
                || header.contains("原密码") || header.contains("新密码")
                || header.contains("pwd2") || header.contains("password2")) {
            return -1;
        }
        if (header.contains("密码") || header.contains("password") || header.contains("pwd")) {
            return 70;
        }
        return -1;
    }

    private static int titleHeaderScore(String header) {
        if (header.equals("备注") || header.equals("角色名") || header.equals("昵称")
                || header.equals("名称") || header.equals("标题")) {
            return 90;
        }
        if (header.contains("备注") || header.contains("角色") || header.contains("昵称")
                || header.contains("名称") || header.contains("名字")) {
            return 70;
        }
        if (header.equals("title") || header.equals("name") || header.equals("remark")
                || header.equals("note")) {
            return 60;
        }
        return -1;
    }

    private static String cellValue(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        try {
            CellType type = cell.getCellType();
            if (type == CellType.FORMULA) {
                try {
                    type = cell.getCachedFormulaResultType();
                } catch (Exception ignored) {
                    type = CellType.STRING;
                }
            }

            if (type == CellType.NUMERIC) {
                double number = cell.getNumericCellValue();
                if (!Double.isInfinite(number) && Math.rint(number) == number
                        && Math.abs(number) <= 9_000_000_000_000_000_000.0) {
                    return Long.toString((long) number);
                }
            }

            String value = formatter.formatCellValue(cell, evaluator);
            return value == null ? "" : value.trim();
        } catch (Exception e) {
            try {
                String value = formatter.formatCellValue(cell);
                return value == null ? "" : value.trim();
            } catch (Exception ignored) {
                return "";
            }
        }
    }

    private static List<ExcelAccount> readCsv(Path file) throws IOException {
        List<List<String>> table = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, detectTextCharset(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
                    line = line.substring(1);
                }
                table.add(parseCsvLine(line));
            }
        }

        HeaderLocation header = null;
        for (int rowIndex = 0; rowIndex < Math.min(table.size(), MAX_HEADER_SCAN_ROWS); rowIndex++) {
            List<String> row = table.get(rowIndex);
            Scores scores = new Scores();
            for (int col = 0; col < row.size(); col++) {
                String value = normalize(row.get(col));
                scores.consider(col, value,
                        channelHeaderScore(value),
                        urlHeaderScore(value),
                        accountHeaderScore(value),
                        passwordHeaderScore(value),
                        titleHeaderScore(value));
            }
            if (scores.isUsableHeader()) {
                header = scores.toHeaderLocation(rowIndex);
                break;
            }
        }
        if (header == null) {
            throw new IOException("CSV 中没有找到可用列，请包含“渠道、链接、账号、密码、备注”");
        }

        List<ExcelAccount> accounts = new ArrayList<>();
        for (int rowIndex = header.rowNumber + 1; rowIndex < table.size(); rowIndex++) {
            List<String> row = table.get(rowIndex);
            ExcelAccount account = buildAccount(rowIndex + 1,
                    getCsvValue(row, header.accountCol),
                    getCsvValue(row, header.passwordCol),
                    getCsvValue(row, header.titleCol),
                    getCsvValue(row, header.channelCol),
                    getCsvValue(row, header.urlCol));
            if (account != null) {
                accounts.add(account);
            }
        }
        return accounts;
    }

    private static String getCsvValue(List<String> row, int col) {
        return col >= 0 && col < row.size() ? row.get(col).trim() : "";
    }

    private static Charset detectTextCharset(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        try {
            java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            decoder.decode(java.nio.ByteBuffer.wrap(bytes));
            return StandardCharsets.UTF_8;
        } catch (Exception ignored) {
            return Charset.forName("GBK");
        }
    }

    private static List<String> parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    current.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        result.add(current.toString());
        return result;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("　", "")
                .replace("_", "")
                .replace("-", "")
                .replace(":", "")
                .replace("：", "");
    }

    private record HeaderLocation(int rowNumber, int channelCol, int urlCol,
                                  int accountCol, int passwordCol, int titleCol) {
    }

    private static final class Scores {
        private int channelCol = -1;
        private int urlCol = -1;
        private int accountCol = -1;
        private int passwordCol = -1;
        private int titleCol = -1;
        private int channelScore = -1;
        private int urlScore = -1;
        private int accountScore = -1;
        private int passwordScore = -1;
        private int titleScore = -1;

        private void consider(int col, String header, int channel, int url,
                              int account, int password, int title) {
            if (channel > channelScore) {
                channelScore = channel;
                channelCol = col;
            }
            if (url > urlScore) {
                urlScore = url;
                urlCol = col;
            }
            if (account > accountScore) {
                accountScore = account;
                accountCol = col;
            }
            if (password > passwordScore) {
                passwordScore = password;
                passwordCol = col;
            }
            if (title > titleScore) {
                titleScore = title;
                titleCol = col;
            }
        }

        private boolean isUsableHeader() {
            boolean accountLogin = accountCol >= 0 && passwordCol >= 0
                    && accountCol != passwordCol;
            boolean urlLogin = urlCol >= 0;
            return accountLogin || urlLogin;
        }

        private int totalScore() {
            return Math.max(channelScore, 0) + Math.max(urlScore, 0)
                    + Math.max(accountScore, 0) + Math.max(passwordScore, 0)
                    + Math.max(titleScore, 0);
        }

        private HeaderLocation toHeaderLocation(int rowNumber) {
            return new HeaderLocation(rowNumber, channelCol, urlCol,
                    accountCol, passwordCol, titleCol);
        }
    }
}