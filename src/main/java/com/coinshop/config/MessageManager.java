package com.coinshop.config;

import com.coinshop.util.TextUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Loads messages.yml and dispatches formatted Adventure components with placeholder substitution.
 */
public final class MessageManager {

    private final JavaPlugin plugin;
    private YamlConfiguration messages;

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        this.messages = YamlConfiguration.loadConfiguration(file);
    }

    public String getPrefix() {
        return messages != null ? messages.getString("prefix", "&8[&#FFAA00ᴄᴏɪɴꜱʜᴏᴘ&8] &r") : "";
    }

    public String getRaw(String path) {
        if (messages == null) {
            return path;
        }
        return messages.getString(path, path);
    }

    public void send(CommandSender sender, String path) {
        send(sender, path, Map.of());
    }

    public void send(CommandSender sender, String path, Map<String, String> placeholders) {
        if (messages != null && messages.isList(path)) {
            List<String> lines = messages.getStringList(path);
            for (String line : lines) {
                String formatted = TextUtil.applyPlaceholders(line, placeholders);
                sender.sendMessage(TextUtil.colorize(getPrefix() + formatted));
            }
            return;
        }
        String raw = getRaw(path);
        String formatted = TextUtil.applyPlaceholders(raw, placeholders);
        sender.sendMessage(TextUtil.colorize(getPrefix() + formatted));
    }
}
