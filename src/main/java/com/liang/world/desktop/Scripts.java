package com.liang.world.desktop;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public final class Scripts {
    public static final String SPEED = "scripts/speed.js";
    public static final String LOTTERY_CIRCLE = "scripts/lottery_circle.js";
    public static final String AUTO_CLEAR_BAG = "scripts/auto_clear_bag.js";
    public static final String ONLINE_REWARD_1 = "scripts/online_reward_part_1.js";
    public static final String ONLINE_REWARD_2 = "scripts/online_reward_part_2.js";
    public static final String LOGIN_LOTTERY_DRAW = "scripts/login_lottery_draw.js";
    public static final String REFRESH_GAME = "scripts/refresh_game.js";
    public static final String LOOP_GAME = "scripts/loop_game.js";
    public static final String AUTO_GAME = "scripts/auto_game.js";
    public static final String TIANYU_IFRAME = "scripts/tianyu_iframe.js";
    public static final String XIAOQI_IFRAME = "scripts/xiaoqi_iframe.js";
    public static final String TOUCH_BRIDGE = "scripts/touch_bridge.js";
    public static final String GAME_SCALE = "scripts/game_scale.js";
    public static final String SYNC_CAPTURE = "scripts/sync_capture.js";
    public static final String SYNC_REPLAY = "scripts/sync_replay.js";
    public static final String MISSION_LOG = "scripts/mission_log.js";

    private Scripts() {
    }

    public static String load(String resourcePath) {
        try (var input = Scripts.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalStateException("缺少脚本资源: " + resourcePath);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
