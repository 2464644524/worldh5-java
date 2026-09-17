package com.liang.world.desktop;

public enum Channel {
    TIANYU("天宇", "https://m.tianyuyou.cn/index/h5game_jump.html?game_id=66953&islandscape=1&tianyuyou_agent_id=10114"),
    GUANFANG("官版", "http://worldh5.gamehz.cn/version/world/publish/channel/res/index.html?xameid=1000&xhannel=121"),
    XIAOQI("小七", "http://www.x7sy.com/h5game_play/182.html");

    private final String displayName;
    private final String loginUrl;

    Channel(String displayName, String loginUrl) {
        this.displayName = displayName;
        this.loginUrl = loginUrl;
    }

    public String displayName() {
        return displayName;
    }

    public String loginUrl() {
        return loginUrl;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
