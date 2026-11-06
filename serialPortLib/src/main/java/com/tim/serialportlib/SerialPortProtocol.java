package com.tim.serialportlib;

/**
 * 一帧数据的协议描述，决定“怎样才算收到一帧完整数据”以及“怎样校验这一帧”。
 * <p>
 * 通过 {@link SerialPortManager#setProtocol(SerialPortProtocol)} 设置默认接收协议，
 * 或在发送命令时随命令传入（每条命令的协议会覆盖默认协议，实现多设备多协议并存）。
 * <p>
 * 典型的三种配置：
 * <pre>
 * 1. 固定长度          setFixedLength(20)
 * 2. 帧头 + 帧尾       setFrameHeader(0x09C, 0xC9); setFrameEnd(0x0E, 0x0A);
 * 3. 帧头 + 长度字段   setFrameHeader(0x96, 0x69); setDataLenIndex(2); setUselessLength(6);
 * </pre>
 * 三种配置都可以再叠加 {@link #setCRC(CRC_MODEL, int, int)}。
 * <p>
 * <b>注意</b>：本类是可变的，且解析过程中 {@link SerialPortManager} 会写入
 * {@link #setBufferLength(int)}、{@link #setDataLength(int)} 等中间状态。
 * 同一个实例不要在多条命令之间交叉使用；多设备场景请为每类设备各建一个实例。
 */
public class SerialPortProtocol {

    /**
     * 切帧方式。
     */
    public enum PROTOCOL_MODEL {
        /**
         * 固定长度：收满指定字节数即认为是一帧，多出来的字节视为干扰。
         */
        FIXED,
        /**
         * 可变长度：依靠帧尾、长度字段或超时粘包来确定帧边界。默认值。
         */
        VARIABLE
    }

    /**
     * 校验方式。
     */
    public enum CRC_MODEL {
        /**
         * 不做校验。
         */
        NULL,
        /**
         * 逐字节异或，校验位 1 字节。
         */
        BCC,
        /**
         * 累加和取反，校验位 2 字节，高字节在前。
         */
        CHECKSUM,
        /**
         * Modbus CRC-16，校验位 2 字节，高字节在前。
         */
        MODBUS_16,
        /**
         * Modbus CRC-16，校验位 2 字节，低字节在前（Modbus RTU 线上顺序）。
         */
        MODBUS_16_RTU
    }

    /** 帧头字节，{@code null} 表示未设置 */
    private byte[]         frameHeader;
    /** 帧尾字节，{@code null} 表示未设置 */
    private byte[]         frameEnd;
    /** 固定长度模式下的整帧长度，0 表示未设置 */
    private int            frameLength   = 0;
    /** 切帧方式，默认可变长度 */
    private PROTOCOL_MODEL protocolModel = PROTOCOL_MODEL.VARIABLE;
    /** 校验方式，默认不校验 */
    private CRC_MODEL      crcModel      = CRC_MODEL.NULL;
    /** 校验范围，{@code [0]} 为起始下标，{@code [1]} 为结束位置（可为负，表示从帧尾倒数） */
    private int[]          crcArea       = new int[2];
    /** 校验位字节数，BCC 为 1，其余为 2 */
    private int            crcLength     = 0;
    /** 长度字段所在下标，0 表示没有长度字段 */
    private int            dataLenIndex  = 0;
    /** 数据区长度，由长度字段解析得到或由调用方手工指定 */
    private int            dataLength    = 0;
    /** 除数据、帧头、帧尾、校验位之外的其它固定字节数 */
    private int            uselessLength = 0;
    /** 当前接收缓冲区中已填充的字节数，由 {@link SerialPortManager} 写入 */
    private int            bufferLength  = 0;

    /**
     * @return 帧头字节，未设置时为 {@code null}
     */
    public byte[] getFrameHeader() {
        return frameHeader;
    }

    /**
     * 设置帧头。支持多字节帧头。
     *
     * @param frameHeader 帧头字节，例如 {@code setFrameHeader((byte) 0xEA)}
     */
    public void setFrameHeader(byte... frameHeader) {
        this.frameHeader = frameHeader;
    }

    /**
     * @return 是否配置了帧头
     */
    public boolean isSetFrameHeader() {
        return frameHeader != null;
    }

    /**
     * @return 帧头字节数，未设置时为 0
     */
    public int getFrameHeaderLength() {
        return frameHeader == null ? 0 : frameHeader.length;
    }

    /**
     * @return 帧尾字节，未设置时为 {@code null}
     */
    public byte[] getFrameEnd() {
        return frameEnd;
    }

    /**
     * @return 帧尾字节数，未设置时为 0
     */
    public int getFrameEndLength() {
        return frameEnd == null ? 0 : frameEnd.length;
    }

    /**
     * 设置帧尾。支持多字节帧尾。
     * <p>
     * 帧尾之后允许存在少量干扰字节，解析时会自动丢弃。
     *
     * @param frameEnd 帧尾字节，例如 {@code setFrameEnd((byte) 0x0E, (byte) 0x0A)}
     */
    public void setFrameEnd(byte... frameEnd) {
        this.frameEnd = frameEnd;
    }

    /**
     * @return 是否配置了帧尾
     */
    public boolean isSetFrameEnd() {
        return frameEnd != null;
    }

    /**
     * 切换为固定长度模式。
     * <p>
     * 此时 {@code frameLength} 表示<b>整帧长度</b>（含帧头、帧尾和校验位）。
     *
     * @param frameLength 整帧字节数，必须大于 0
     */
    public void setFixedLength(int frameLength) {
        this.protocolModel = PROTOCOL_MODEL.FIXED;
        this.frameLength   = frameLength;
    }

    /**
     * @return 当前切帧方式
     */
    public PROTOCOL_MODEL getProtocolModel() {
        return protocolModel;
    }

    /**
     * 设置校验方式与校验范围。
     * <p>
     * 校验范围为左闭右开区间 {@code [start, end)}，其中 {@code end} 允许为负数，
     * 表示“从帧尾往前数的偏移”，用于帧长可变的场景。例如帧尾占 2 字节时，
     * {@code setCRC(MODBUS_16, 2, -4)} 表示从第 2 字节算到倒数第 4 字节。
     * <p>
     * 校验位固定位于帧尾之前：BCC 占 1 字节，其余占 2 字节。
     *
     * @param crcModel 校验方式，传 {@link CRC_MODEL#NULL} 表示不校验
     * @param start    计算起始下标（含）
     * @param end      计算结束下标（不含），负数表示相对帧尾的偏移
     */
    public void setCRC(CRC_MODEL crcModel, int start, int end) {
        this.crcModel = crcModel;
        this.crcArea  = new int[]{start, end};
        //noinspection SwitchStatementWithTooFewBranches
        switch (crcModel) {
            case BCC:
                crcLength = 1;
                break;
            default:
                crcLength = 2;
                break;
        }
    }

    /**
     * @return 当前校验方式
     */
    public CRC_MODEL getCRCModel() {
        return crcModel;
    }

    /**
     * @return 校验范围 {@code [start, end]}，其中 {@code end} 可能为负数
     */
    public int[] getCRCArea() {
        return crcArea;
    }

    /**
     * 获取不含校验位的帧长。
     * <p>
     * 三种情况：
     * <ul>
     *   <li>配置了长度字段（{@link #setDataLenIndex(int)}）：返回
     *       {@code uselessLength + dataLength + 帧头长 + 帧尾长}，
     *       <b>不包含长度字段本身</b>，解析时需额外加 1；</li>
     *   <li>可变长度且未指定 {@link #setFixedLength(int)}：返回当前缓冲区已填充长度；</li>
     *   <li>其它：返回 {@code frameLength}。</li>
     * </ul>
     *
     * @return 不含校验位的帧长
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
     * 获取整帧长度（含校验位）。
     * <p>
     * 可变长度模式下为 {@code getFrameLength() + 帧头长 + 帧尾长 + 校验位长}；
     * 固定长度模式下直接用 {@code frameLength} 作为整帧长度。
     *
     * @return 整帧字节数
     */
    public int getLength() {
        if (protocolModel == PROTOCOL_MODEL.VARIABLE) {
            return getFrameLength() + getFrameHeaderLength() + getFrameEndLength() + crcLength;
        }
        return getFrameLength();
    }

    /**
     * 最小数据长度判断，大于此长度时才判断帧尾是否符合要求。
     * <p>
     * 目的是避免帧尾字节恰好出现在数据区时被提前截断。
     *
     * @return 判定帧尾所需的最小长度
     */
    public int getMinDataLength() {
        if (dataLength > 0) {
            return uselessLength + dataLength + getFrameHeaderLength() + getFrameEndLength();
        }
        return frameLength / 2 + getFrameHeaderLength() + getFrameEndLength();
    }

    /**
     * @return 长度字段所在下标，0 表示没有长度字段
     */
    public int getDataLenIndex() {
        return dataLenIndex;
    }

    /**
     * 设置数据长度字段下标。
     * <p>
     * 长度字段为单字节，表示数据区字节数。设置后解析器会在收到足够字节时
     * 自动读取该字段并算出整帧长度。
     *
     * @param dataLenIndex 长度字段在帧中的下标，0 表示不使用长度字段
     */
    public void setDataLenIndex(int dataLenIndex) {
        this.dataLenIndex = dataLenIndex;
    }

    /**
     * 设置数据区长度。
     * <p>
     * 配置了长度字段时无需手工调用，解析器会自动写入并在每帧结束后复位；
     * 没有长度字段时可用于手工指定数据区长度。
     *
     * @param dataLength 数据区字节数
     */
    public void setDataLength(int dataLength) {
        this.dataLength = dataLength;
    }

    /**
     * @return 数据区字节数
     */
    public int getDataLength() {
        return dataLength;
    }

    /**
     * 设置除数据字段及帧头帧尾 CRC 外的数据长度。
     * <p>
     * 仅在“帧头 + 长度字段”这类协议中需要：长度字段统计的是数据区，
     * 而实际帧里还有命令字、地址等固定字节，这些字节数就是 {@code uselessLength}。
     *
     * @param uselessLength 未指定的固定字节数
     */
    public void setUselessLength(int uselessLength) {
        this.uselessLength = uselessLength;
    }

    /**
     * 记录当前接收缓冲区已填充的长度，由 {@link SerialPortManager} 在解析时写入。
     *
     * @param bufferLength 已填充字节数
     */
    public void setBufferLength(int bufferLength) {
        this.bufferLength = bufferLength;
    }
}
