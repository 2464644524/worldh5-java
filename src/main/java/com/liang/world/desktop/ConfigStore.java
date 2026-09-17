package com.liang.world.desktop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ConfigStore {
    public static final int ACCOUNT_COUNT = 10;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    private static final Type LIST_TYPE = new TypeToken<List<AccountConfig>>() {}.getType();

    private final Path configFile;
    private List<AccountConfig> accounts;

    public ConfigStore(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建数据目录: " + dataDir, e);
        }
        this.configFile = dataDir.resolve("accounts.json");
        this.accounts = load();
    }

    public List<AccountConfig> accounts() {
        return accounts;
    }

    public AccountConfig account(int index) {
        return accounts.get(index);
    }

    public Path profileDir(int index) {
        return configFile.getParent().resolve("profiles").resolve(String.format("account-%02d", index + 1));
    }

    public synchronized void save() {
        try {
            Files.createDirectories(configFile.getParent());
            try (Writer writer = Files.newBufferedWriter(configFile)) {
                GSON.toJson(accounts, writer);
            }
        } catch (IOException e) {
            throw new IllegalStateException("保存配置失败", e);
        }
    }

    private List<AccountConfig> load() {
        if (!Files.exists(configFile)) {
            return defaults();
        }
        try (Reader reader = Files.newBufferedReader(configFile)) {
            List<AccountConfig> loaded = GSON.fromJson(reader, LIST_TYPE);
            if (loaded == null || loaded.size() != ACCOUNT_COUNT) {
                return defaults();
            }
            for (int i = 0; i < ACCOUNT_COUNT; i++) {
                AccountConfig config = loaded.get(i);
                if (config == null) {
                    loaded.set(i, new AccountConfig(i));
                } else {
                    config.setIndex(i);
                    if (config.getChannel() == null) {
                        config.setChannel(Channel.TIANYU);
                    }
                    if (config.getTitle() == null || config.getTitle().isBlank()) {
                        config.setTitle(String.valueOf(i + 1));
                    }
                    if (config.getCustomUrl() == null) {
                        config.setCustomUrl("");
                    }
                }
            }
            return loaded;
        } catch (Exception e) {
            return defaults();
        }
    }

    private List<AccountConfig> defaults() {
        List<AccountConfig> result = new ArrayList<>();
        for (int i = 0; i < ACCOUNT_COUNT; i++) {
            result.add(new AccountConfig(i));
        }
        return result;
    }
}
