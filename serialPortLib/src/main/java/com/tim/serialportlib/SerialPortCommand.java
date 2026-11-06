package com.tim.serialportlib;

/**
 * 发送队列中的一条命令。
 * <p>
 * 每次调用 {@code sendBytes(..., SerialPortProtocol, int, OnReportListener)} 都会生成一个实例，
 * 由 {@link SerialPortManager} 按先进先出顺序逐条下发：发一条、等一条应答，再发下一条。
 * <p>
 * 本类不对外暴露，仅库内部使用。
 */
class SerialPortCommand {

    /** 待发送的原始字节，由 {@link BytesUtil#toByteArray(String)} 或调用方直接传入 */
    private final byte[]           hexData;
    /**
     * 本命令的结果回调，强引用。
     * <p>
     * 命令在队列中期间会持有该引用，因此监听器里不要直接持有 Activity 等长生命周期对象以外的
     * 上下文；如果命令长期得不到应答，最迟在超时回调后才释放。
     */
    private final OnReportListener listener;
    /** 本命令的应答解析规则，下发时会被设为当前生效协议 */
    private final SerialPortProtocol protocol;
    /** 命令标识，随回调原样返回，便于同一监听器区分多条命令 */
    private       int              flag = 0;

    /**
     * 创建一个不带标识的命令。
     *
     * @param hexData  待发送的原始字节
     * @param protocol 应答解析规则
     * @param listener 结果回调，可为 {@code null}
     */
    public SerialPortCommand(byte[] hexData, SerialPortProtocol protocol, OnReportListener listener) {
        this.hexData = hexData;
        this.protocol = protocol;
        this.listener = listener;
    }

    /**
     * 创建一个带标识的命令。
     *
     * @param hexData  待发送的原始字节
     * @param protocol 应答解析规则
     * @param flag     命令标识，随回调原样返回
     * @param listener 结果回调，可为 {@code null}
     */
    public SerialPortCommand(byte[] hexData, SerialPortProtocol protocol, int flag, OnReportListener listener) {
        this.hexData = hexData;
        this.protocol = protocol;
        this.flag = flag;
        this.listener = listener;
    }

    /**
     * @return 命令标识，默认 0
     */
    public int getFlag() {
        return flag;
    }

    /**
     * @param flag 命令标识
     */
    public void setFlag(int flag) {
        this.flag = flag;
    }

    /**
     * @return 待发送的原始字节，可能为 {@code null}
     */
    public byte[] getHexData() {
        return hexData;
    }

    /**
     * @return 本命令使用的应答解析规则
     */
    public SerialPortProtocol getProtocol() {
        return protocol;
    }

    /**
     * @return 结果回调，可能为 {@code null}
     */
    public OnReportListener getListener() {
        return listener;
    }
}
