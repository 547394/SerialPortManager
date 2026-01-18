package com.tim.serialportlib;

public class BytesUtil {
    // 查表法，避免计算
    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

    static public int byte2int(byte n1) {
        return n1 < 0 ? n1 + 256 : n1;
    }

    static public int bytesToShort(byte high, byte low) {
        return (byte2int(high) << 8) | byte2int(low);
    }

    public static String toHexString(byte[] bytes) {
        if (bytes == null) return "";
        return toHexString(bytes, bytes.length);
    }

    public static String toHexString(byte[] bytes, int length) {
        if (bytes == null || length <= 0 || length > bytes.length) return "";

        // 预分配字符数组长度：每个byte转成 "XX " (3个字符)
        char[] hexChars = new char[length * 3];
        for (int i = 0; i < length; i++) {
            int v = bytes[i] & 0xFF;
            hexChars[i * 3] = HEX_ARRAY[v >>> 4];     // 高4位
            hexChars[i * 3 + 1] = HEX_ARRAY[v & 0x0F]; // 低4位
            hexChars[i * 3 + 2] = ' ';                 // 空格
        }
        // 去掉最后一个空格
        return new String(hexChars, 0, hexChars.length - 1);
    }

    public static byte[] toByteArray(String hexString) {
        if (hexString == null || hexString.isEmpty()) return null;
        hexString = hexString.replace(" ", "").toUpperCase();
        int len = hexString.length();
        if (len % 2 != 0) {
            hexString = "0" + hexString; // 补齐
            len++;
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4)
                    + Character.digit(hexString.charAt(i + 1), 16));
        }
        return data;
    }
}
