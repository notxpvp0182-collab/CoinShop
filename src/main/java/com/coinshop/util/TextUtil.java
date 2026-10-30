package com.coinshop.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Adventure Component formatting and safe identifier validation utility.
 */
public final class TextUtil {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.builder()
            .character('&')
            .hexCharacter('#')
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private static final Pattern SAFE_ID_PATTERN = Pattern.compile("^[a-z0-9_-]{1,48}$");

    private TextUtil() {
    }

    /**
     * Parses legacy & and &#RRGGBB color codes into an Adventure Component with italic disabled by default for GUI items.
     */
    public static Component colorize(String text) {
        if (text == null) {
            return Component.empty();
        }
        String normalized = text.replace('§', '&');
        return SERIALIZER.deserialize(normalized).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> colorizeList(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<Component> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(colorize(line));
        }
        return result;
    }

    public static String applyPlaceholders(String template, Map<String, String> placeholders) {
        if (template == null || template.isEmpty() || placeholders == null || placeholders.isEmpty()) {
            return template == null ? "" : template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue() == null ? "" : entry.getValue();
            result = result.replace(key, value);
        }
        return result;
    }

    /**
     * Validates category IDs, item IDs, and filenames to prevent path traversal or invalid YAML keys.
     */
    public static boolean isValidId(String id) {
        return id != null && SAFE_ID_PATTERN.matcher(id).matches() && !id.contains("..");
    }
}
