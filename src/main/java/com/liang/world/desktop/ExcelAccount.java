package com.liang.world.desktop;

import java.util.Locale;

public class ExcelAccount {
    private final int rowNumber;
    private final String username;
    private final String password;
    private final String title;
    private final String channelText;
    private final String url;

    public ExcelAccount(int rowNumber, String username, String password, String title) {
        this(rowNumber, username, password, title, "", "");
    }

    public ExcelAccount(int rowNumber, String username, String password, String title,
                        String channelText, String url) {
        this.rowNumber = rowNumber;
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password.trim();
        this.title = title == null ? "" : title.trim();
        this.channelText = channelText == null ? "" : channelText.trim();
        this.url = url == null ? "" : url.trim();
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