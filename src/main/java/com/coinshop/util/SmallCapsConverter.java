package com.coinshop.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Converts standard ASCII characters into Unicode small-caps glyphs where supported by Minecraft's default font.
 * Preserves color codes (&x, &#RRGGBB, §x) and placeholders (%placeholder%).
 */
public final class SmallCapsConverter {

    private static final Map<Character, Character> SMALL_CAPS_MAP = new HashMap<>();

    static {
        String normal = "abcdefghijklmnopqrstuvwxyz";
        String small  = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";
        for (int i = 0; i < normal.length(); i++) {
            SMALL_CAPS_MAP.put(normal.charAt(i), small.charAt(i));
            SMALL_CAPS_MAP.put(Character.toUpperCase(normal.charAt(i)), small.charAt(i));
        }
    }

    private SmallCapsConverter() {
    }

    /**
     * Converts text to small caps while skipping Minecraft color codes and %...% placeholders.
     */
    public static String toSmallCaps(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(input.length());
        boolean inPlaceholder = false;
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);

            // Handle placeholders %...%
            if (c == '%') {
                inPlaceholder = !inPlaceholder;
                out.append(c);
                i++;
                continue;
            }
            if (inPlaceholder) {
                out.append(c);
                i++;
                continue;
            }

            // Handle color codes (& or §)
            if ((c == '&' || c == '§') && i + 1 < input.length()) {
                char next = input.charAt(i + 1);
                if (next == '#' && i + 7 < input.length()) {
                    // Hex code &#RRGGBB
                    out.append(input, i, i + 8);
                    i += 8;
                    continue;
                } else if (isColorCodeChar(next)) {
                    out.append(c).append(next);
                    i += 2;
                    continue;
                }
            }

            out.append(SMALL_CAPS_MAP.getOrDefault(c, c));
            i++;
        }
        return out.toString();
    }

    private static boolean isColorCodeChar(char c) {
        char lower = Character.toLowerCase(c);
        return (lower >= '0' && lower <= '9')
                || (lower >= 'a' && lower <= 'f')
                || (lower >= 'k' && lower <= 'o')
                || lower == 'r'
                || lower == 'x';
    }
}
