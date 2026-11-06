package com.tim.serialportlib;

/**
 * 十六进制字符串与字节数组之间的转换工具。
 * <p>
 * 统一采用“大写、字节之间用单个空格分隔”的格式，例如 {@code "6A A6 01 0D 0A"}。
 * 解析时不区分大小写，也允许没有空格。
 */
public class BytesUtil {

    // 查表法，避免计算
    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

    /**
     * 把有符号 byte 当作无符号数处理。
     *
     * @param n1 原始字节
     * @return 0 ~ 255 的整数值
     */
    static public int byte2int(byte n1) {
        return n1 < 0 ? n1 + 256 : n1;
    }

    /**
     * 把高、低两个字节拼成一个 16 位无符号整数。
     *
     * @param high 高字节
     * @param low  低字节
     * @return 0 ~ 65535 的整数值
     */
    static public int bytesToShort(byte high, byte low) {
        return (byte2int(high) << 8) | byte2int(low);
    }

    /**
     * 把字节数组整体转成十六进制字符串。
     *
     * @param bytes 待转换的数组，为 {@code null} 时返回空串
     * @return 大写、空格分隔的十六进制字符串
     */
    public static String toHexString(byte[] bytes) {
        if (bytes == null) return "";
        return toHexString(bytes, bytes.length);
    }

    /**
     * 把字节数组的前 {@code length} 个字节转成十六进制字符串。
     * <p>
     * 常用于打印接收缓冲区，只输出其中已填充的部分。
     *
     * @param bytes  待转换的数组，为 {@code null} 时返回空串
     * @param length 需要转换的字节数，超出数组长度或不为正数时返回空串
     * @return 大写、空格分隔的十六进制字符串
     */
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

    /**
     * 把十六进制字符串解析成字节数组。
     * <p>
     * 解析前会去掉所有空格并统一转成大写，因此 {@code "6a a6"} 与 {@code "6AA6"} 等价。
     * <b>注意</b>：
     * <ul>
     *   <li>长度为奇数时在<b>左侧补 0</b>，例如 {@code "ABC"} 会解析成 {@code 0A BC}；</li>
     *   <li>遇到非法的十六进制字符不会抛异常，{@link Character#digit(char, int)} 返回的 -1
     *       会参与位运算，得到的是无意义的字节。传入内容务必保证合法。</li>
     * </ul>
     *
     * @param hexString 十六进制字符串，允许含空格
     * @return 解析结果；入参为 {@code null} 或空串时返回 {@code null}
     */
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
