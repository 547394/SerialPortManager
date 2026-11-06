package com.tim.serialportlib;

/**
 * 校验算法集合。
 * <p>
 * 所有方法的计算区间都是左闭右开：{@code [start, end)}，即包含 {@code start}、不包含 {@code end}。
 * 调用方需自行保证 {@code 0 <= start <= end <= data.length}，本类不做越界检查。
 */
public class Crypto {

    /**
     * 计算 Modbus RTU 的 CRC-16（多项式 0xA001，初值 0xFFFF）。
     * <p>
     * 返回顺序为<b>高字节在前</b>；而 Modbus RTU 线上传输是低字节在前，
     * 因此 {@link SerialPortProtocol.CRC_MODEL#MODBUS_16_RTU} 在比较时会交换两个字节。
     *
     * @param data  数据缓冲区
     * @param start 计算起始下标（含）
     * @param end   计算结束下标（不含）
     * @return 长度 2 的校验结果，{@code [0]} 为高字节、{@code [1]} 为低字节
     */
    static public byte[] crc_modbus_16(byte[] data, int start, int end) {
        char sum        = 0xFFFF;
        char polynomial = 0xA001;
        int  i, j;
        for (i = start; i < end; i++) {
            //noinspection LossyConversionCompoundAssignment
            sum ^= ((int) data[i] & 0x00FF);
            for (j = 0; j < 8; j++) {
                if ((sum & 0x0001) != 0) {
                    sum >>= 1;
                    sum ^= polynomial;
                } else {
                    sum >>= 1;
                }
            }
        }
        byte[] crc = new byte[2];
        crc[0] = (byte) (sum >> 8);
        crc[1] = (byte) (sum & 0xFF);
        return crc;
    }

    /**
     * 计算 BCC 校验（逐字节异或）。
     *
     * @param data  数据缓冲区
     * @param start 计算起始下标（含）
     * @param end   计算结束下标（不含）
     * @return 单字节校验值
     */
    static public byte crc_bcc(byte[] data, int start, int end) {
        byte bcc = 0x00;
        for (int i = start; i < end; i++) {
            bcc = (byte) (bcc ^ data[i]);
        }
        return bcc;
    }

    /**
     * 计算累加和校验（各字节求和后取反，保留低 16 位）。
     *
     * @param data  数据缓冲区
     * @param start 计算起始下标（含）
     * @param end   计算结束下标（不含）
     * @return 长度 2 的校验结果，{@code [0]} 为高字节、{@code [1]} 为低字节
     */
    static public byte[] crc_check_sum(byte[] data, int start, int end) {
        int sum = 0;
        for (int i = start; i < end; i++) {
            sum = sum + BytesUtil.byte2int(data[i]);
        }
        sum = ~sum & 0xFFFF;
        byte[] crc = new byte[2];
        crc[0] = (byte) (sum >> 8);
        crc[1] = (byte) (sum & 0xFF);
        return crc;
    }
}
