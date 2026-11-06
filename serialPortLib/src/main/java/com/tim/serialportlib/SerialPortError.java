package com.tim.serialportlib;

/**
 * 串口命令的失败原因。
 *
 * @see OnReportListener#onFailure(SerialPortError, int)
 * @see OnOpenListener#onFailure(java.io.File, SerialPortError)
 */
public enum SerialPortError {

    /**
     * 应答超时：命令已下发，但在超时时间内没有收到符合协议的完整帧。
     * <p>
     * 超时时长由 {@link SerialPortManager#setReceivedTimeout(int)} 设置。
     */
    RECEIVED_TIMEOUT,

    /**
     * 应答校验失败：收到了完整帧，但 CRC / 校验和 / BCC 校验不通过。
     */
    RECEIVED_CRC_ERROR,

    /**
     * 收发过程出错，属于兜底错误类型，包括：
     * <ul>
     *   <li>串口未打开或已关闭；</li>
     *   <li>发送数据为空，底层不会真正写出；</li>
     *   <li>底层拒绝写入（串口异常断开）；</li>
     *   <li>接收缓冲区溢出；</li>
     *   <li>串口打开失败。</li>
     * </ul>
     */
    ACHIEVE_ERROR
}
