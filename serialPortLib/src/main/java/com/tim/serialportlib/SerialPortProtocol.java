package com.tim.serialportlib;

public class SerialPortProtocol {

    public enum PROTOCOL_MODEL {
        FIXED,
        VARIABLE
    }

    public enum CRC_MODEL {
        NULL,
        BCC,
        CHECKSUM,
        MODBUS_16,
        MODBUS_16_RTU
    }

    private byte[]         frameHeader;
    private byte[]         frameEnd;
    private int            frameLength   = 0;
    private PROTOCOL_MODEL protocolModel = PROTOCOL_MODEL.VARIABLE;
    private CRC_MODEL      crcModel      = CRC_MODEL.NULL;
    private int[]          crcArea       = new int[2];
    private int            crcLength     = 0;
    private int            dataLenIndex  = 0;
    private int            dataLength    = 0;
    private int            uselessLength = 0;
    private int            bufferLength  = 0;

    public byte[] getFrameHeader() {
        return frameHeader;
    }

    public void setFrameHeader(byte... frameHeader) {
        this.frameHeader = frameHeader;
    }

    public boolean isSetFrameHeader() {
        return frameHeader != null;
    }

    public int getFrameHeaderLength() {
        return frameHeader == null ? 0 : frameHeader.length;
    }

    public byte[] getFrameEnd() {
        return frameEnd;
    }

    public int getFrameEndLength() {
        return frameEnd == null ? 0 : frameEnd.length;
    }

    public void setFrameEnd(byte... frameEnd) {
        this.frameEnd = frameEnd;
    }

    public boolean isSetFrameEnd() {
        return frameEnd != null;
    }

    public void setFixedLength(int frameLength) {
        this.protocolModel = PROTOCOL_MODEL.FIXED;
        this.frameLength   = frameLength;
    }

    public PROTOCOL_MODEL getProtocolModel() {
        return protocolModel;
    }

    public void setCRC(CRC_MODEL crcModel, int start, int end) {
        this.crcModel = crcModel;
        this.crcArea  = new int[]{start, end};
        switch (crcModel) {
            case BCC:
                crcLength = 1;
                break;
            default:
                crcLength = 2;
                break;
        }
    }

    public CRC_MODEL getCRCModel() {
        return crcModel;
    }

    public int[] getCRCArea() {
        return crcArea;
    }

    /**
     * 获取除帧头帧尾CRC部分的数据长度
     */
    public int getFrameLength() {
        if (dataLenIndex > 0) {
            return uselessLength + dataLength + getFrameHeaderLength() + getFrameEndLength();
        }
        if (frameLength == 0 && protocolModel == PROTOCOL_MODEL.VARIABLE) {
            return bufferLength;
        }
        return frameLength;
    }

    /**
     * 获取所有数据长度
     */
    public int getLength() {
        if (protocolModel == PROTOCOL_MODEL.VARIABLE) {
            return getFrameLength() + getFrameHeaderLength() + getFrameEndLength() + crcLength;
        }
        return getFrameLength();
    }

    /**
     * 最小数据长度判断, 大于此长度时才判断帧尾是否符合要求
     */
    public int getMinDataLength() {
        if (dataLength > 0) {
            return uselessLength + dataLength + getFrameHeaderLength() + getFrameEndLength();
        }
        return frameLength / 2 + getFrameHeaderLength() + getFrameEndLength();
    }

    public int getDataLenIndex() {
        return dataLenIndex;
    }

    /**
     * 设置数据长度字段下标
     *
     * @param dataLenIndex 数据长度下标
     */
    public void setDataLenIndex(int dataLenIndex) {
        this.dataLenIndex = dataLenIndex;
    }

    /**
     * 设置数据长度
     *
     * @param dataLength 数据长度
     */
    public void setDataLength(int dataLength) {
        this.dataLength = dataLength;
    }

    public int getDataLength() {
        return dataLength;
    }

    /**
     * 设置除数据字段及帧头帧尾CRC外的数据长度
     *
     * @param uselessLength 未指定的数据长度
     */
    public void setUselessLength(int uselessLength) {
        this.uselessLength = uselessLength;
    }

    public void setBufferLength(int bufferLength) {
        this.bufferLength = bufferLength;
    }
}
