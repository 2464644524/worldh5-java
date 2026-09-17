package com.liang.world.desktop;

public class AccountConfig {
    private int index;
    private String title = "";
    private Channel channel = Channel.TIANYU;
    private String customUrl = "";
    private String username = "";
    private String password = "";
    private boolean autoRepair = true;
    private boolean autoReward = true;
    private boolean autoClearBag = false;
    private int excelRow = 0;

    public AccountConfig() {
    }

    public AccountConfig(int index) {
        this.index = index;
        this.title = String.valueOf(index + 1);
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public String getCustomUrl() {
        return customUrl;
    }

    public void setCustomUrl(String customUrl) {
        this.customUrl = customUrl == null ? "" : customUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username == null ? "" : username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password == null ? "" : password;
    }

    public boolean isAutoRepair() {
        return autoRepair;
    }

    public void setAutoRepair(boolean autoRepair) {
        this.autoRepair = autoRepair;
    }

    public boolean isAutoReward() {
        return autoReward;
    }

    public void setAutoReward(boolean autoReward) {
        this.autoReward = autoReward;
    }

    public boolean isAutoClearBag() {
        return autoClearBag;
    }

    public void setAutoClearBag(boolean autoClearBag) {
        this.autoClearBag = autoClearBag;
    }

    public int getExcelRow() {
        return excelRow;
    }

    public void setExcelRow(int excelRow) {
        this.excelRow = excelRow;
    }

    public String startupUrl() {
        if (customUrl != null && !customUrl.isBlank()) {
            return customUrl.trim();
        }
        return channel.loginUrl();
    }

    public String displayName() {
        return String.format("%02d %s", index + 1, title == null || title.isBlank() ? String.valueOf(index + 1) : title);
    }
}
