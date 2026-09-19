package com.liang.world.desktop;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExcelAccount {
    private static final Pattern DATE_PATTERN =
            Pattern.compile("(\\d{4})\\D+(\\d{1,2})\\D+(\\d{1,2})");

    private final int rowNumber;
    private final String username;
    private final String password;
    private final String title;
    private final String channelText;
    private final String url;
    private final String finishDate;

    public ExcelAccount(int rowNumber, String username, String password, String title) {
        this(rowNumber, username, password, title, "", "");
    }

    public ExcelAccount(int rowNumber, String username, String password, String title,
                        String channelText, String url) {
        this(rowNumber, username, password, title, channelText, url, "");
    }

    public ExcelAccount(int rowNumber, String username, String password, String title,
                        String channelText, String url, String finishDate) {
        this.rowNumber = rowNumber;
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password.trim();
        this.title = title == null ? "" : title.trim();
        this.channelText = channelText == null ? "" : channelText.trim();
        this.url = url == null ? "" : url.trim();
        this.finishDate = finishDate == null ? "" : finishDate.trim();
    }

    public int getRowNumber() {
        return rowNumber;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getTitle() {
        return title;
    }

    public String getChannelText() {
        return channelText;
    }

    public String getUrl() {
        return url;
    }

    public String getFinishDate() {
        return finishDate;
    }

    public boolean isFinishedToday() {
        return isDateToday(finishDate);
    }

    public static boolean isDateToday(String value) {
        return parseDate(value) != null && parseDate(value).equals(LocalDate.now());
    }

    public static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        String compact = text.replaceAll("\\D", "");
        if (compact.length() == 8) {
            try {
                return LocalDate.parse(compact.substring(0, 4) + "-"
                        + compact.substring(4, 6) + "-" + compact.substring(6, 8));
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
        Matcher matcher = DATE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return LocalDate.of(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
        } catch (Exception ignored) {
            return null;
        }
    }

    public Channel getChannel() {
        String value = channelText.toLowerCase(Locale.ROOT).replace(" ", "");
        if (value.contains("天宇") || value.contains("天谕") || value.contains("ty")) {
            return Channel.TIANYU;
        }
        if (value.contains("小七") || value.contains("x7") || value.contains("xiaoqi")) {
            return Channel.XIAOQI;
        }
        if (value.contains("官") || value.contains("guan") || value.contains("official")) {
            return Channel.GUANFANG;
        }
        return url.isBlank() ? Channel.GUANFANG : Channel.TIANYU;
    }

    public boolean isOfficialAccountLogin() {
        return getChannel() == Channel.GUANFANG;
    }

    public String getDisplayName() {
        if (title != null && !title.isBlank()) {
            return title;
        }
        if (isOfficialAccountLogin() && !username.isBlank()) {
            return username;
        }
        return getChannel().displayName() + "-" + rowNumber;
    }
}
