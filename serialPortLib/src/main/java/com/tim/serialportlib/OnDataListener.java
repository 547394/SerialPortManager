package com.tim.serialportlib;

/**
 * 串口数据监听器。
 * <p>
 * 通过 {@link SerialPortManager#setOnDataListener(OnDataListener)} 注册，用于接收总线上的
 * 数据帧以及已经写入串口的数据。与 {@link OnReportListener} 的区别是：
 * 本监听器是全局的，不区分具体命令。
 * <p>
 * <b>线程说明</b>：
 * <ul>
 *   <li>{@link #onDataReceived(byte[])} 在底层串口接收线程或工作线程上回调，<b>不是主线程</b>，
 *       不要在其中直接操作 UI；</li>
 *   <li>{@link #onDataSend(byte[])} 在底层串口发送线程上回调，同样不是主线程。</li>
 * </ul>
 * <b>异常说明</b>：回调中抛出的异常会中断接收线程，导致该串口后续静默收不到数据，
 * 请务必在实现内部自行捕获。
 */
abstract public class OnDataListener {

    /**
     * 收到一帧数据时回调。
     * <p>
     * 传入的是总线上的<b>原始整帧</b>，包含帧头、帧尾和校验位，本库不会剥离。
     * 切帧规则由当前生效的 {@link SerialPortProtocol} 决定：
     * <ul>
     *   <li>配置了帧头/帧尾：按帧头和帧尾切分，帧尾之后的干扰字节被丢弃；</li>
     *   <li>配置了固定长度：收满指定长度即回调，多余的字节视为干扰；</li>
     *   <li>只有帧头 + 长度字段：按长度字段切分；</li>
     *   <li>未配置任何协议：按 {@link SerialPortManager#setReceivedTimeout(int)} 超时粘包。</li>
     * </ul>
     *
     * @param bytes 一帧数据，数组为新建副本，调用方可以自由修改或长期持有
     */
    public void onDataReceived(byte[] bytes) {
    }

    /**
     * 数据真正写入串口之后回调。
     * <p>
     * 底层是把数据交给发送线程异步写出的，因此本回调不保证发生在发送方法返回之前。
     * 写失败时底层不会回调此方法（此时由 {@link OnReportListener#onFailure} 兜底）。
     *
     * @param bytes 已写出的数据，与发送时传入的数组是同一个引用，请勿修改其内容
     */
    public void onDataSend(byte[] bytes) {
    }
}
