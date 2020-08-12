package com.tim.serialportlib;

public class SerialPortProtocol {

    public enum CRC_MODEL {
        NULL,
        BCC,
        CHECKSUM,
        MODBUS_16
    }

    private byte[]    frameHeader;
    private byte[]    frameEnd;
    private int       frameLength = 0;
    private CRC_MODEL crcModel    = CRC_MODEL.NULL;
    private int[]     crcArea     = new int[2];

    public byte[] getFrameHeader() {
        return frameHeader;
    }

    public void setFrameHeader(byte... frameHeader) {
        this.frameHeader = frameHeader;
    }

    public byte[] getFrameEnd() {
        return frameEnd;
    }

    public void setFrameEnd(byte... frameEnd) {
        this.frameEnd = frameEnd;
    }

    public int getFrameLength() {
        return frameLength;
    }

    public void setFrameLength(int frameLength) {
        this.frameLength = frameLength;
    }

    public void setCRC(CRC_MODEL crcModel, int start, int end) {
        this.crcModel = crcModel;
        this.crcArea  = new int[]{start, end};
    }

    public CRC_MODEL getCRCModel() {
        return crcModel;
    }

    public int[] getCRCArea() {
        return crcArea;
    }

    public int getMinLength() {
        int length = frameLength;
        if (frameHeader != null) {
            length += frameHeader.length;
        }
        if (frameEnd != null) {
            length += frameEnd.length;
        }
        return length;
    }
}
