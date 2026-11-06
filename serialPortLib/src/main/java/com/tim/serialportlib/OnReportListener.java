package com.tim.serialportlib;

/**
 * 单条命令的执行结果回调。
 * <p>
 * 每条通过 {@code sendBytes(byte[], SerialPortProtocol, int, OnReportListener)}、
 * {@code sendHexString(String, SerialPortProtocol, int, OnReportListener)} 等方法下发的命令，
 * 都会独占一次回调序列：
 * <pre>
 * 成功：onSuccess(bytes, flag) -&gt; onComplete()
 * 失败：onFailure(error, flag) -&gt; onComplete()
 * </pre>
 * 因此 {@link #onComplete()} 适合做“收尾 / 计数 / 发下一条”的触发点；
 * 调用 {@link SerialPortManager#close()} 会丢弃队列中未完成（含正在等待应答）的命令，
 * 这些命令不会收到任何回调。
 * <p>
 * <b>线程说明</b>：回调在底层接收线程或工作线程上执行，<b>不是主线程</b>。
 * <b>异常说明</b>：回调中抛出的异常会打断串口工作线程，请自行捕获。
 */
abstract public class OnReportListener {

    /**
     * 命令执行成功。
     *
     * @param bytes 收到的应答帧，为原始整帧（含帧头、帧尾、校验位）
     * @param flag  下发命令时传入的标识，用于在同一监听器实例上区分不同命令
     */
    public void onSuccess(byte[] bytes, int flag) {
    }

    /**
     * 命令执行失败。
     *
     * @param error 失败原因，见 {@link SerialPortError}
     * @param flag  下发命令时传入的标识
     */
    public void onFailure(SerialPortError error, int flag) {
    }

    /**
     * 本次命令处理结束，无论成功还是失败都会回调（{@code close()} 除外）。
     */
    public void onComplete() {
    }
}
