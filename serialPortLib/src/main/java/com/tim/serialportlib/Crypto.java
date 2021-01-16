package com.tim.serialportlib;

public class Crypto {

    static public byte[] crc_modbus_16(byte[] data, int start, int end) {
        char sum        = 0xFFFF;
        char polynomial = 0xA001;
        int  i, j;
        for (i = start; i < end; i++) {
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

    static public byte crc_bcc(byte[] data, int start, int end) {
        byte bcc = 0x00;
        for (int i = start; i < end; i++) {
            bcc = (byte) (bcc ^ data[i]);
        }
        return bcc;
    }

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
