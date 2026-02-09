package org.sawiq.collins.paper.util;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class Lang {

    private final JavaPlugin plugin;
    private final String language;
    private final FileConfiguration messages;
    private final String prefix;

    public Lang(JavaPlugin plugin, String language) {
        this.plugin = plugin;
        this.language = (language == null || language.isBlank()) ? "en" : language.trim().toLowerCase();

        this.messages = loadLangYaml(this.language);
        this.prefix = color(messages.getString("prefix", "&8[&bCollins&8]&r"));
    }

    private FileConfiguration loadLangYaml(String lang) {
        try {
            File langDir = new File(plugin.getDataFolder(), "lang");
            if (!langDir.exists() && !langDir.mkdirs()) {
                plugin.getLogger().warning("Failed to create lang folder");
            }

            File outFile = new File(langDir, lang + ".yml");
            if (!outFile.exists()) {
                plugin.saveResource("lang/" + lang + ".yml", false);
            }

            YamlConfiguration defaults = new YamlConfiguration();
            try (var in = plugin.getResource("lang/" + lang + ".yml")) {
                if (in != null) {
                    defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
            }

            if (defaults.getKeys(true).isEmpty()) {
                try (var in = plugin.getResource("lang/en.yml")) {
                    if (in != null) {
                        defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                    }
                } catch (Exception ignored) {
                }
            }

            YamlConfiguration fileCfg = outFile.exists()
                    ? YamlConfiguration.loadConfiguration(outFile)
                    : new YamlConfiguration();

            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (!fileCfg.contains(key)) {
                    fileCfg.set(key, defaults.get(key));
                    changed = true;
                }
            }

            // держим префикс синхронизированным (чтобы обновления были видны сразу)
            if (defaults.contains("prefix")) {
                String dp = defaults.getString("prefix");
                if (dp != null && !dp.equals(fileCfg.getString("prefix"))) {
                    fileCfg.set("prefix", dp);
                    changed = true;
                }
            }

            String ds = defaults.getString("cmd.seeked");
            String fs = fileCfg.getString("cmd.seeked");
            if (ds != null && fs != null && fs.contains("{pos}") && ds.contains("{from}") && ds.contains("{to}")) {
                fileCfg.set("cmd.seeked", ds);
                changed = true;
            }

            if (changed) {
                try {
                    fileCfg.save(outFile);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to save lang file '" + lang + "': " + e.getMessage());
                }
            }

            return fileCfg;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load lang file '" + lang + "': " + e.getMessage());
        }

        try (var in = plugin.getResource("lang/en.yml")) {
            if (in != null) {
                return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }

        return new YamlConfiguration();
    }

    /**
     * Получает строку из конфига, обрабатывая вложенные пути (точки в ключах)
     */
    private String getString(String key) {
        Object val = messages.get(key);
        if (val instanceof String s) {
            return s;
        }
        // Bukkit может вернуть MemorySection если ключ содержит точки
        // Пробуем получить как есть
        String s = messages.getString(key);
        return s;
    }

    public String tr(String key) {
        String raw = messages.getString(key);
        if (raw == null) raw = key;

        raw = raw.replace("{prefix}", prefix);
        return color(raw);
    }

    /**
     * Возвращает локализованную строку без prefix (для использования как часть другой строки)
     */
    public String raw(String key) {
        String raw = getString(key);
        if (raw == null) raw = key;
        return color(raw);
    }

    public String tr(String key, Map<String, String> vars) {
        String raw = messages.getString(key);
        if (raw == null) raw = key;

        raw = raw.replace("{prefix}", prefix);
        if (vars != null) {
            for (var e : vars.entrySet()) {
                raw = raw.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
        }
        return color(raw);
    }

    public void send(CommandSender to, String key) {
        String msg = tr(key);
        if (msg.indexOf('\n') >= 0) {
            for (String line : msg.split("\\n", -1)) {
                if (!line.isEmpty()) to.sendMessage(line);
            }
            return;
        }
        to.sendMessage(msg);
    }

    public void send(CommandSender to, String key, Map<String, String> vars) {
        String msg = tr(key, vars);
        if (msg.indexOf('\n') >= 0) {
            for (String line : msg.split("\\n", -1)) {
                if (!line.isEmpty()) to.sendMessage(line);
            }
            return;
        }
        to.sendMessage(msg);
    }

    public Map<String, String> vars(Object... kv) {
        Map<String, String> m = new HashMap<>();
        if (kv == null) return m;

        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), String.valueOf(kv[i + 1]));
        }
        return m;
    }

    private static String color(String s) {
        if (s == null) return "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
