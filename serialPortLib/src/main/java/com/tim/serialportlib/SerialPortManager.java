package com.tim.serialportlib;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.LinkedList;

import me.f1reking.serialportlib.SerialPortHelper;
import me.f1reking.serialportlib.listener.IOpenSerialPortListener;
import me.f1reking.serialportlib.listener.ISerialPortDataListener;
import me.f1reking.serialportlib.listener.Status;

/**
 * 串口通讯管理器：打开串口、按队列下发命令、按协议从字节流中切出应答帧并回调结果。
 *
 * <h3>收发模型</h3>
 * <p>
 * 所有通过 {@code sendBytes(byte[], SerialPortProtocol, int, OnReportListener)} 系列方法
 * 下发的命令都会进入同一个 FIFO 队列，<b>发一条、等一条应答，再发下一条</b>。
 * 调用方可以连续入队多条命令，不必自己串行化；两条命令之间的最小间隔由
 * {@link #setSendInterval(int)} 控制。
 * <p>
 * 一条命令的结束条件（三者之一）：
 * <ul>
 *   <li>收到符合协议且校验通过的完整帧，回调 {@link OnReportListener#onSuccess}；</li>
 *   <li>超过 {@link #setReceivedTimeout(int)} 仍未收全，回调
 *       {@link SerialPortError#RECEIVED_TIMEOUT}；</li>
 *   <li>校验失败、发送失败、发送数据为空、缓冲区溢出等，回调对应的 {@link SerialPortError}。</li>
 * </ul>
 * 无论哪种情况，最后都会回调 {@link OnReportListener#onComplete()}（未提供监听器或调用
 * {@link #close()} 时除外），队列随即继续。
 *
 * <h3>线程模型</h3>
 * <ul>
 *   <li>主线程：构造、{@link #open}、{@link #close}、入队发送；</li>
 *   <li>工作线程（HandlerThread）：超时计时、队列推进、粘包输出；</li>
 *   <li>底层收发线程：真正读写串口。</li>
 * </ul>
 * 除 {@link OnOpenListener} 外，所有回调都在工作线程或底层接收线程上执行，<b>不是主线程</b>，
 * 需要操作 UI 请自行切线程。
 *
 * <h3>生命周期</h3>
 * <p>
 * 一个实例对应一个串口。{@link #close()} 之后可以再次 {@link #open}，工作线程会自动重建；
 * 但 close() 会丢弃队列中未完成的命令，这些命令不会收到任何回调。
 * 建议在 Activity / Fragment 销毁时调用 {@link #close()}，避免线程与监听器泄漏。
 *
 * <h3>协议</h3>
 * <p>
 * 接收协议默认取 {@link #setProtocol(SerialPortProtocol)}，每条命令下发时会切换成命令自带的协议，
 * 从而实现多设备多协议并存。协议对象在解析过程中会被写入中间状态，队列是串行的所以可以复用同一实例，
 * 但不要在接收过程中从其它线程修改它。
 */
public class SerialPortManager {

    private final    String                        TAG             = getClass().getSimpleName();
    private          SerialPortProtocol            protocol        = new SerialPortProtocol();
    private          int                           bufferSize      = 2048;
    private          byte[]                        buffer          = new byte[bufferSize];
    private          int                           bufferLength    = 0;
    // 使用 HandlerThread 和 Handler 替代 Timer 和 Thread.sleep
    private final    Handler                       mMainHandler;
    private          Handler                       mWorkHandler;
    private          HandlerThread                 mWorkThread;
    // 当前已武装的超时任务, 用于 cancelReceivedTimer() 精确移除
    private volatile Runnable                      pendingTimeout;
    // 相邻两条命令的发送间隔(ms)
    private          int                           sendInterval    = 50;
    // 接收数据间隔, 当没有帧头帧尾时有效
    private          int                           receivedTimeout = 350;
    // 底层 sendBytes 只是把数据入队, 真正写串口在发送线程里, 且写失败不会回调 onDataSend.
    // sendNext 里先按 receivedTimeout + 此宽限时间兜底, onDataSend 回来后再收紧到 receivedTimeout.
    // 多留 1s 是为了避免低波特率下长帧还没写完就被判超时(正常路径的计时起点仍在 onDataSend).
    private static final int                       SEND_WATCHDOG_GRACE = 1000;
    // 帧尾之后允许的干扰字节数上限.
    // 解析时只在末尾这个窗口内找帧尾: 一是符合"允许几个字节干扰"的语义,
    // 二是避免长帧分包时每个 read 都对整个缓冲区做一遍 CRC 试探.
    private static final int                       MAX_TRAILING_GARBAGE = 32;
    // 发送队列
    private final    LinkedList<SerialPortCommand> queueList       = new LinkedList<>();
    // 工作标志
    private volatile boolean                       onWorking       = false;
    // 一条命令的应答收完之后, 到下次下发之前, 线路上来的字节都当作干扰丢弃.
    // 不丢的话, 尾随干扰(尤其是下一次 read 才到的那些)会留在缓冲区里,
    // 把下一条命令的帧头挤歪, 表现为"偶发 CRC 错误/超时".
    private volatile boolean                       discardInput    = true;
    private          boolean                       debug           = false;
    SerialPortHelper  serialPortHelper;
    // 跨线程读取, 用 volatile 保证超时任务的可见性
    volatile SerialPortCommand command;
    OnOpenListener    onOpenListener;
    OnDataListener    onDataListener;

    /**
     * 创建一个串口管理器。此时串口尚未打开，工作线程已就绪。
     */
    public SerialPortManager() {
        serialPortHelper = new SerialPortHelper();
        mMainHandler = new Handler(Looper.getMainLooper());

        // 初始化后台线程和 Handler
        mWorkThread = new HandlerThread("SerialPortManager-WorkThread");
        mWorkThread.start();
        mWorkHandler = new Handler(mWorkThread.getLooper());
    }

    /**
     * 设置串口打开结果监听器。回调在主线程。
     *
     * @param listener 监听器，可为 {@code null}
     */
    public void setOnOpenListener(OnOpenListener listener) {
        this.onOpenListener = listener;
    }

    /**
     * 设置全局数据监听器，用于接收总线上的数据帧和发送完成通知。
     * <p>
     * 与命令级的 {@link OnReportListener} 不冲突，两者都会收到数据。
     *
     * @param listener 监听器，可为 {@code null}
     */
    public void setOnDataListener(OnDataListener listener) {
        this.onDataListener = listener;
    }

    /**
     * 设置相邻两条命令之间的发送间隔。
     * <p>
     * 一条命令结束后会等待该间隔再下发下一条，给慢速设备留出处理时间。
     * 默认 50 ms。
     *
     * @param interval 间隔毫秒数
     */
    public void setSendInterval(int interval) {
        sendInterval = interval;
    }

    /**
     * 设置应答超时时间。
     * <p>
     * 双重含义：
     * <ul>
     *   <li>命令下发后超过该时间仍未收到完整帧，判定为
     *       {@link SerialPortError#RECEIVED_TIMEOUT}；</li>
     *   <li>未配置任何协议（无帧头）时，作为粘包间隔：总线静默该时间后把已收到的字节作为一帧输出。</li>
     * </ul>
     * 默认 350 ms。低波特率下长帧的传输时间可能超过该值，需要适当调大。
     *
     * @param timeout 超时毫秒数
     */
    public void setReceivedTimeout(int timeout) {
        receivedTimeout = timeout;
    }

    /**
     * 设置接收缓冲区大小，默认 2048 字节。
     * <p>
     * 缓冲区满仍未解析出完整帧时会判定为数据异常：回调
     * {@link SerialPortError#ACHIEVE_ERROR} 并清空缓冲区。
     * 帧长可能超过 2048 字节的协议需要调大此值。
     *
     * @param bufferSize 缓冲区字节数，必须大于 0，否则忽略本次设置
     */
    public void setBufferSize(int bufferSize) {
        if (bufferSize <= 0) {
            Log.e(TAG, "bufferSize must be greater than 0: " + bufferSize);
            return;
        }
        this.bufferSize = bufferSize;
        synchronized (this) { // 加锁防止重置时并发
            this.buffer = new byte[bufferSize];
            this.bufferLength = 0;
        }
    }

    /**
     * 设置默认接收协议。
     * <p>
     * 该协议用于“不需要应答的接收场景”以及未随命令传入协议的情况；
     * 通过 {@code sendBytes(..., SerialPortProtocol, ...)} 下发命令时，会被命令自带的协议覆盖。
     *
     * @param protocol 协议描述对象
     */
    public void setProtocol(SerialPortProtocol protocol) {
        this.protocol = protocol;
    }

    /**
     * 移除串口打开结果监听器。
     */
    public void removeOnOpenListener() {
        this.onOpenListener = null;
    }

    /**
     * 移除全局数据监听器。
     */
    public void removeOnDataListener() {
        this.onDataListener = null;
    }

    /**
     * 获取系统上可用的串口设备路径，例如 {@code /dev/ttyS0}。
     * <p>
     * 结果依赖底层实现与设备权限，部分机型可能返回空数组。
     *
     * @return 串口设备路径数组，不会为 {@code null}
     */
    public String[] getAllDevicesPath() {
        return serialPortHelper.getAllDeicesPath();
    }

    /**
     * 以 8 数据位、无校验、1 停止位打开串口。
     *
     * @param port     串口设备路径，例如 {@code /dev/ttyS0}
     * @param baudRate 波特率，例如 115200
     * @see #open(String, int, int, int, int)
     */
    public void open(String port, int baudRate) {
        open(port, baudRate, 8, 0, 1);
    }

    /**
     * 打开串口。
     * <p>
     * 打开结果通过 {@link OnOpenListener} 在主线程回调（失败不会抛异常）；重复调用会打印错误日志并直接返回，
     * 需要先 {@link #close()}。
     * <p>
     * 打开后、下发第一条命令之前，总线上到达的字节会被丢弃，避免把上一次通信的残留数据
     * 当成本次应答。
     *
     * @param port     串口设备路径，例如 {@code /dev/ttyS0}
     * @param baudRate 波特率
     * @param dataBits 数据位，通常为 8
     * @param parity   校验位，0 表示无校验
     * @param stopBits 停止位，通常为 1
     */
    public void open(String port, int baudRate, int dataBits, int parity, int stopBits) {
        if (serialPortHelper.isOpen()) {
            Log.e(TAG, "串口已打开, 请先调用 close()");
            return;
        }
        // close() 会结束工作线程, 重新打开时重建
        ensureWorkThread();
        // 打开后、下发第一条命令之前, 线路上来的字节都丢弃
        discardInput = true;
        serialPortHelper.setPort(port);
        serialPortHelper.setBaudRate(baudRate);
        serialPortHelper.setDataBits(dataBits);
        serialPortHelper.setParity(parity);
        serialPortHelper.setStopBits(stopBits);
        serialPortHelper.setIOpenSerialPortListener(new IOpenSerialPortListener() {
            @Override
            public void onSuccess(final File device) {
                // 先取局部变量, 否则 post 执行前调用 removeOnOpenListener() 会空指针
                final OnOpenListener listener = onOpenListener;
                if (listener != null) {
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onSuccess(device);
                        }
                    });
                }
            }

            @Override
            public void onFail(final File device, Status status) {
                final OnOpenListener listener = onOpenListener;
                if (listener != null) {
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onFailure(device, SerialPortError.ACHIEVE_ERROR);
                        }
                    });
                }
            }
        });
        // 底层 open() 内部就会启动接收线程, 监听必须先挂上, 否则打开瞬间到达的数据会被丢弃
        serialPortHelper.setISerialPortDataListener(new ISerialPortDataListener() {
            @Override
            public void onDataReceived(byte[] bytes) {
                try {
                    logcat("onDataReceived(132):" + BytesUtil.toHexString(bytes));
                    processDataReceived(bytes);
                } catch (Exception e) {
                    err("onDataReceivedError(135):" + e.getMessage());
                    notifyFailure(SerialPortError.ACHIEVE_ERROR);
                    clean();
                }
            }

            @Override
            public void onDataSend(byte[] bytes) {
                Log.d("onDataSend", BytesUtil.toHexString(bytes));
                OnDataListener dataListener = onDataListener;
                if (dataListener != null) {
                    dataListener.onDataSend(bytes);
                }
                // 写入完成后重新开始计超时, 起点更准确.
                // 必须确认这次回调属于当前命令: 发送线程可能在上一条命令结束、下一条已启动之后才回调,
                // 直接按 command 字段挂计时器会把下一条命令的超时缩短成 receivedTimeout.
                SerialPortCommand current = command;
                if (current != null && current.getHexData() == bytes) {
                    // 写出去的这一刻清一次缓冲区: 入队到真正写出去之间可能还有上一条命令的尾巴到达.
                    // 用数组引用确认归属; 万一底层实现换了引用, 也只是少这一次清理, 不影响接收.
                    synchronized (SerialPortManager.this) {
                        bufferLength = 0;
                    }
                    armReceivedTimer(current);
                }
            }
        });
        serialPortHelper.open();
    }

    /**
     * 每次 open() 都换一条新的工作线程.
     * <p>
     * close() 里的 quitSafely() 是异步的: 紧接着 open() 时旧线程的 isAlive() 可能还是 true,
     * 但它已经拒绝接收新消息(sendMessageDelayed 返回 false), 复用会让命令永远发不出去,
     * 且不会有任何回调. 这个窗口在"断开后立刻重连"的场景必现, 所以这里不做存活判断, 直接重建.
     */
    private synchronized void ensureWorkThread() {
        if (mWorkThread != null) {
            mWorkThread.quitSafely();
        }
        mWorkThread = new HandlerThread("SerialPortManager-WorkThread");
        mWorkThread.start();
        mWorkHandler = new Handler(mWorkThread.getLooper());
    }

    /**
     * 武装响应超时, 只对当前命令有效
     */
    private void armReceivedTimer(SerialPortCommand target) {
        armReceivedTimer(target, receivedTimeout);
    }

    /**
     * 武装响应超时，只对当前命令有效。
     *
     * @param target 目标命令
     * @param delay  延迟毫秒数
     */
    private void armReceivedTimer(SerialPortCommand target, int delay) {
        // 发包和武装超时之间, 接收线程可能已经处理完这条命令(甚至开始下一条).
        // 这里只允许给自己仍在处理的命令挂计时器, 避免把下一条命令的计时器取消掉.
        if (target == null || target != command) {
            return;
        }
        cancelReceivedTimer();
        // 每条命令一个 Runnable 并捕获自己的 target:
        // 共享一个 Runnable 时, 万一它被取出后线程被长时间挂起, 复查读到的会是下一条命令的 target,
        // 从而给新命令误报超时. 捕获后这个窗口不存在.
        final SerialPortCommand armed = target;
        Runnable timeout = new Runnable() {
            @Override
            public void run() {
                // 命令已完成/已切换, 说明这是个迟到的超时, 直接丢弃, 不能影响当前命令
                if (armed != command) {
                    return;
                }
                Log.e(TAG, "RECEIVED_TIMEOUT for flag: " + armed.getFlag());
                notifyFailure(SerialPortError.RECEIVED_TIMEOUT, armed);
                clean();
            }
        };
        pendingTimeout = timeout;
        mWorkHandler.postDelayed(timeout, delay);
    }

    /**
     * 粘包定时任务：总线静默 receivedTimeout 后，把已收到的字节作为一帧输出。
     * 仅在未配置帧头的场景使用。
     */
    private final Runnable mStickyPacketRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (SerialPortManager.this) {
                output();
            }
        }
    };

    /**
     * 对当前命令回调失败。
     *
     * @param error 失败原因
     */
    private void notifyFailure(SerialPortError error) {
        notifyFailure(error, command);
    }

    /**
     * 对指定命令回调失败，回调顺序为 onFailure -> onComplete。
     *
     * @param error  失败原因
     * @param target 目标命令，为 {@code null} 时不做任何事
     */
    private void notifyFailure(SerialPortError error, SerialPortCommand target) {
        // 只读一次, 避免读的过程中 command 被其他线程置空
        if (target == null) {
            return;
        }
        OnReportListener listener = target.getListener();
        if (listener != null) {
            listener.onFailure(error, target.getFlag());
            listener.onComplete();
        }
    }

    /**
     * 直接发送数据，<b>不经过命令队列</b>，也不会触发超时与应答解析。
     * <p>
     * 适合主动上报、心跳等不需要回复的场景。由于绕过了队列，与
     * {@code sendBytes(..., OnReportListener)} 混用会打乱“发一条等一条”的时序，
     * 建议只在不使用队列时才用它。
     *
     * @param bytes 待发送的字节
     * @return {@code true} 表示已交给底层发送线程；串口未打开或入队失败返回 {@code false}
     */
    public boolean sendBytes(byte[] bytes) {
        if (serialPortHelper.isOpen()) {
            // 直发不经过队列, 没有"命令结束"的时机, 这里恢复接收, 保持原有行为
            discardInput = false;
            return serialPortHelper.sendBytes(bytes);
        } else {
            Log.e(TAG, "串口未打开");
            return false;
        }
    }

    /**
     * 直接发送十六进制字符串，不经过命令队列。
     *
     * @param hex 十六进制字符串，允许含空格
     * @return {@code true} 表示已交给底层发送线程
     * @see #sendBytes(byte[])
     */
    public boolean sendHexString(String hex) {
        return sendBytes(BytesUtil.toByteArray(hex));
    }

    /**
     * 把一条需要应答的命令加入队列。
     * <p>
     * 命令按入队顺序逐条下发，前一条结束后（成功、失败或超时）才发下一条。
     * 结果通过 {@code listener} 回调，{@code flag} 用于在同一监听器实例上区分命令。
     *
     * @param bytes    待发送的字节
     * @param protocol 本条命令的应答解析协议
     * @param flag     命令标识，随回调原样返回
     * @param listener 结果回调，可为 {@code null}（此时只发送不通知）
     */
    public void sendBytes(byte[] bytes, SerialPortProtocol protocol, int flag, OnReportListener listener) {
        synchronized (queueList) {
            queueList.offer(new SerialPortCommand(bytes, protocol, flag, listener));
        }
        triggerSendNext();
    }

    /**
     * 把一条十六进制字符串形式的命令加入队列。
     *
     * @param hexString 十六进制字符串，允许含空格
     * @param protocol  本条命令的应答解析协议
     * @param flag      命令标识，随回调原样返回
     * @param listener  结果回调，可为 {@code null}
     */
    public void sendHexString(String hexString, SerialPortProtocol protocol, int flag, OnReportListener listener) {
        sendBytes(BytesUtil.toByteArray(hexString), protocol, flag, listener);
    }

    /**
     * 把一条需要应答的命令加入队列，命令标识固定为 0。
     *
     * @param bytes    待发送的字节
     * @param protocol 本条命令的应答解析协议
     * @param listener 结果回调，可为 {@code null}
     */
    public void sendBytes(byte[] bytes, SerialPortProtocol protocol, OnReportListener listener) {
        sendBytes(bytes, protocol, 0, listener);
    }

    /**
     * 把一条十六进制字符串形式的命令加入队列，命令标识固定为 0。
     *
     * @param hexString 十六进制字符串，允许含空格
     * @param protocol  本条命令的应答解析协议
     * @param listener  结果回调，可为 {@code null}
     */
    public synchronized void sendHexString(String hexString, SerialPortProtocol protocol, OnReportListener listener) {
        sendHexString(hexString, protocol, 0, listener);
    }

    /**
     * 触发一次队列推进，在不持有锁的线程上调用 sendNext。
     * <p>
     * 工作线程已结束（close 之后又发送）时不能静默丢命令，否则调用方永远等不到回调，
     * 这里会把剩余命令一次性按 {@link SerialPortError#ACHIEVE_ERROR} 回调掉。
     */
    private void triggerSendNext() {
        boolean posted = mWorkHandler.post(new Runnable() {
            @Override
            public void run() {
                sendNext();
            }
        });
        if (!posted) {
            // 工作线程已结束(close 之后又调用发送). 不能静默丢命令, 否则调用方永远等不到回调
            Log.e(TAG, "工作线程已结束, 命令未发送");
            synchronized (queueList) {
                SerialPortCommand pending;
                while ((pending = queueList.poll()) != null) {
                    notifyFailure(SerialPortError.ACHIEVE_ERROR, pending);
                }
            }
        }
    }

    /**
     * 从队列取出一条命令并下发，由工作线程调用。
     * <p>
     * 上一条命令还没结束时直接返回；数据为空或底层拒绝发送时立即按
     * {@link SerialPortError#ACHIEVE_ERROR} 收尾，否则队列会永久卡住。
     */
    synchronized private void sendNext() {
        if (onWorking) {
            return;
        }
        synchronized (queueList) {
            command = queueList.poll();
        }
        if (command == null) {
            return;
        }
        onWorking = true;
        protocol = command.getProtocol();
        bufferLength = 0;
        byte[] data = command.getHexData();
        if (data == null || data.length == 0) {
            // 空数据底层不会真正写出, 也就不会回调 onDataSend, 必须在这里收尾, 否则队列永久卡死
            Log.e(TAG, "发送数据为空, flag:" + command.getFlag());
            notifyFailure(SerialPortError.ACHIEVE_ERROR, command);
            clean();
            return;
        }
        // 新命令开始接收应答
        discardInput = false;
        // 兜底: 底层写失败时不会回调 onDataSend, 没有这道超时队列会永久卡死.
        // 必须在 sendBytes 之前武装: 发送线程可能先回调 onDataSend, 先武装才能保证它一定能把计时收紧.
        armReceivedTimer(command, receivedTimeout + SEND_WATCHDOG_GRACE);
        if (!serialPortHelper.sendBytes(data)) {
            notifyFailure(SerialPortError.ACHIEVE_ERROR, command);
            clean();
        }
    }

    /**
     * @return 串口是否已打开
     */
    public boolean isOpen() {
        return serialPortHelper.isOpen();
    }

    /**
     * 处理底层收到的一段字节流：先做帧头对齐，再按当前协议判断是否凑齐一帧。
     * <p>
     * 为兼容前导干扰，缓冲区前 N 个字节与帧头不一致时会整体左移 1 字节重新对齐；
     * 帧尾之后多余的干扰字节由 {@link #findFrameEnd()} 截断丢弃。
     * <p>
     * 本方法在底层接收线程上执行，整体加锁，与 {@link #output()}、{@link #clean()} 互斥。
     *
     * @param bytes 本次读到的字节，长度取决于底层 read 的实际返回
     */
    private synchronized void processDataReceived(byte[] bytes) {
        if (discardInput) {
            logcat("丢弃帧后干扰:" + BytesUtil.toHexString(bytes));
            return;
        }
        // 粘包处理: 逐字节入缓冲区, 并通过左移让帧头对齐, 兼容帧头前有多余字节的情况
        if (protocol.isSetFrameHeader()) {
            for (byte aByte : bytes) {
                if (bufferLength < bufferSize) {
                    buffer[bufferLength++] = aByte;
                } else {
                    // 缓冲区满了还没解析出完整包，说明数据异常，强制清理
                    Log.e(TAG, "接收缓冲区溢出, bufferSize:" + bufferSize);
                    notifyFailure(SerialPortError.ACHIEVE_ERROR);
                    clean();
                    break;
                }
                // 查找帧头, 可以兼容帧头有多余字节功能
                if (bufferLength == protocol.getFrameHeaderLength()) {
                    for (int i = 0; i < bufferLength; i++) {
                        if (!(buffer[i] == protocol.getFrameHeader()[i])) {
                            // 发现第一个字节不是帧头，整体左移1位
                            System.arraycopy(buffer, 1, buffer, 0, bufferLength - 1);
                            bufferLength--;
                            // 注意：这里需要跳出内层循环，让外层循环继续填充 buffer
                            break;
                        }
                    }
                }
            }
        } else {
            for (byte aByte : bytes) {
                if (bufferLength < bufferSize) {
                    buffer[bufferLength++] = aByte;
                } else {
                    // 缓冲区满了还没解析出完整包，说明数据异常，强制清理
                    Log.e(TAG, "接收缓冲区溢出, bufferSize:" + bufferSize);
                    notifyFailure(SerialPortError.ACHIEVE_ERROR);
                    clean();
                    break;
                }
            }
        }
        protocol.setBufferLength(bufferLength);
        if (protocol.getDataLenIndex() > 0 && protocol.getDataLength() == 0 && bufferLength > protocol.getDataLenIndex()) {
            protocol.setDataLength(BytesUtil.byte2int(buffer[protocol.getDataLenIndex()]));
        }
        // 可变长度模式
        if (protocol.getProtocolModel() == SerialPortProtocol.PROTOCOL_MODEL.VARIABLE) {
            if (protocol.isSetFrameHeader()) {
                if (protocol.isSetFrameEnd()) {
                    // 帧尾正好落在末尾: 直接输出, 兼容数据区本身就含帧尾字节的协议
                    if (bufferLength >= protocol.getMinDataLength()
                            && isFrameEndAt(bufferLength)
                            && bufferLength == protocol.getFrameLength()) {
                        logcat("有帧头帧尾模式");
                        output(bufferLength);
                    } else {
                        // 帧尾后面可能跟了干扰字节, 从最小长度开始找第一个校验也通过的帧尾
                        int frameLength = findFrameEnd();
                        if (frameLength > 0) {
                            logcat("有帧头帧尾模式(帧尾后带干扰)");
                            output(frameLength);
                        }
                    }
                } else if (bufferLength - 1 >= protocol.getFrameLength()) {
                    // 有帧头 + 指定长度信息, 无帧尾; 长度字段本身占 1 字节
                    logcat("有帧头指定长度信息模式");
                    output(protocol.getFrameLength() + 1);
                }
            } else {
                // 没有定义帧头则认为没有定义协议, 启用超时粘包
                cancelReceivedTimer();
                mWorkHandler.postDelayed(mStickyPacketRunnable, receivedTimeout);
            }
        } else {
            // 固定长度模式, 收满即可输出, 多出来的字节视为干扰
            if (protocol.getLength() > 0 && bufferLength >= protocol.getLength()) {
                logcat("固定长度模式");
                output(protocol.getLength());
            }
        }
    }

    /**
     * 判断 pos 位置(不含)往前 frameEndLength 个字节是否为帧尾
     */
    private boolean isFrameEndAt(int pos) {
        int endLength = protocol.getFrameEndLength();
        if (endLength <= 0) {
            // setFrameEnd() 传了空数组: 没有帧尾字节, 等价于"末尾即帧尾"(与原逻辑 isFindEnd 的初始值一致)
            return true;
        }
        if (pos < endLength) {
            return false;
        }
        byte[] frameEnd = protocol.getFrameEnd();
        for (int j = 0; j < endLength; j++) {
            if (buffer[pos - endLength + j] != frameEnd[j]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 从后往前找最近的合法帧尾, 返回帧尾结束位置(即帧长), 找不到返回 -1.
     * <p>
     * 只在配置了 CRC 时启用: 没有校验位就无法区分"数据区里的帧尾字节"和"真正的帧尾",
     * 贸然截断会把好帧切坏, 这种情况沿用原逻辑(只认末尾的帧尾).
     * 从后往前找可以保证数据区中间的帧尾即使校验碰巧通过(位置一定更靠前)也不会被选中.
     */
    private int findFrameEnd() {
        if (protocol.getCRCModel() == SerialPortProtocol.CRC_MODEL.NULL) {
            return -1;
        }
        int endLength = protocol.getFrameEndLength();
        if (endLength <= 0) {
            // 没有帧尾字节就无从截断
            return -1;
        }
        int from = Math.max(protocol.getMinDataLength(), endLength);
        // 只在末尾的小窗口内找, 避免每次分包都做大范围 CRC 试探
        int tail = Math.max(from, bufferLength - MAX_TRAILING_GARBAGE);
        for (int pos = bufferLength; pos >= tail; pos--) {
            if (isFrameEndAt(pos) && !isCrcError(pos)) {
                return pos;
            }
        }
        return -1;
    }

    /**
     * 只计算不通知, 因为解析时需要拿不同的帧长反复试探(见 findFrameEnd)
     *
     * @param length 待校验帧的长度
     * @return true 表示校验不通过, 或长度不足以容纳校验位
     */
    private boolean isCrcError(int length) {
        SerialPortProtocol.CRC_MODEL model = protocol.getCRCModel();
        if (model == SerialPortProtocol.CRC_MODEL.NULL) {
            return false;
        }
        int offset    = protocol.getFrameEndLength();
        int crcLength = (model == SerialPortProtocol.CRC_MODEL.BCC) ? 1 : 2;
        if (length < offset + crcLength) {
            // 长度装不下校验位(例如只配了帧尾没配帧头, 由粘包超时触发), 直接判失败, 避免下标越界
            return true;
        }
        int[] area = protocol.getCRCArea();
        int   start = area[0];
        int   end   = area[1] > area[0] ? area[1] : length + area[1];
        if (end > length) {
            end = length;
        }
        switch (model) {
            case BCC:
                logcat("CRC area:" + start + " - " + end);
                return Crypto.crc_bcc(buffer, start, end) != buffer[length - 1 - offset];
            case CHECKSUM: {
                byte[] crc = Crypto.crc_check_sum(buffer, start, end);
                return !(crc[0] == buffer[length - 2 - offset] && crc[1] == buffer[length - 1 - offset]);
            }
            case MODBUS_16: {
                byte[] crc = Crypto.crc_modbus_16(buffer, start, end);
                return !(crc[0] == buffer[length - 2 - offset] && crc[1] == buffer[length - 1 - offset]);
            }
            case MODBUS_16_RTU: {
                byte[] crc = Crypto.crc_modbus_16(buffer, start, end);
                return !(crc[0] == buffer[length - 1 - offset] && crc[1] == buffer[length - 2 - offset]);
            }
            default:
                return false;
        }
    }

    /**
     * 按当前缓冲区长度输出一帧。
     */
    private void output() {
        output(bufferLength);
    }

    /**
     * 按指定长度输出一帧：截取、校验、回调，然后清空状态继续下一条命令。
     * <p>
     * 只截取前 {@code length} 个字节，之后的字节（帧尾后的干扰）直接丢弃。
     *
     * @param length 帧长，必须为正数；非正数说明没有数据可解析，直接返回
     */
    private void output(int length) {
        if (length <= 0) {
            // 没有数据可解析. close() 之后残留的粘包任务可能走到这里, 不能当成校验失败
            return;
        }
        cancelReceivedTimer();
        byte[] dest = new byte[length];
        System.arraycopy(buffer, 0, dest, 0, length);
        Log.d("output", BytesUtil.toHexString(buffer, length));
        if (isCrcError(length)) {
            Log.e(TAG, "RECEIVED_CRC_ERROR :" + BytesUtil.toHexString(dest));
            notifyFailure(SerialPortError.RECEIVED_CRC_ERROR);
        } else {
            // 局部变量读取, close() 会把监听器和 command 置空, 直接读字段可能在判空和调用之间变成空指针
            OnDataListener dataListener = onDataListener;
            if (dataListener != null) {
                dataListener.onDataReceived(dest);
            }
            SerialPortCommand current = command;
            OnReportListener listener = (current != null) ? current.getListener() : null;
            if (listener != null) {
                listener.onSuccess(dest, current.getFlag());
                listener.onComplete();
            }
        }
        clean();
    }

    /**
     * 结束当前命令：取消计时器、清空缓冲区、复位协议中间状态，并在间隔后推进队列。
     * <p>
     * 清空缓冲区的同时会把后续到达的字节置为“丢弃”，直到下一条命令真正写出为止，
     * 防止上一条命令的尾随干扰把下一条命令的帧头挤歪。
     */
    synchronized private void clean() {
        cancelReceivedTimer();
        bufferLength = 0;
        // 应答已结束, 之后到下次下发之间到的字节都是干扰(可能还有半截在路上), 丢弃
        discardInput = true;
        // 复位长度字段, 否则第一条帧解析出的 dataLength 会一直沿用, 变长协议后续长度不同的帧永远匹配不上.
        // 只复位由长度下标自动解析出来的值, 不动调用方手工配置的 dataLength.
        if (protocol != null && protocol.getDataLenIndex() > 0) {
            protocol.setDataLength(0);
        }
        onWorking = false;
        command = null;
        boolean hasNext;
        synchronized (queueList) {
            hasNext = !queueList.isEmpty();
        }
        if (hasNext) {
            mWorkHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    triggerSendNext();
                }
            }, sendInterval);
        }
    }

    /**
     * 取消当前的应答超时任务和粘包任务。
     * <p>
     * 一般不需要手动调用，{@link #clean()} 与 {@link #close()} 内部已处理。
     */
    public void cancelReceivedTimer() {
        Runnable pending = pendingTimeout;
        pendingTimeout = null;
        if (mWorkHandler != null) {
            if (pending != null) {
                mWorkHandler.removeCallbacks(pending);
            }
            mWorkHandler.removeCallbacks(mStickyPacketRunnable);
        }
    }

    /**
     * 打开或关闭调试日志。
     * <p>
     * 开启后会打印收到的原始字节、CRC 计算区间、解析分支等，便于现场排查；
     * 生产环境建议关闭，避免日志刷屏。
     *
     * @param debug {@code true} 表示输出调试日志
     */
    public void enableDebug(boolean debug) {
        this.debug = debug;
    }

    /**
     * 输出调试日志，仅当 {@link #enableDebug(boolean)} 打开时生效。
     *
     * @param msg 日志内容
     */
    private void logcat(String msg) {
        if (debug) {
            Log.d(TAG, msg);
        }
    }

    /**
     * 输出错误日志，仅当 {@link #enableDebug(boolean)} 打开时生效。
     *
     * @param msg 日志内容
     */
    private void err(String msg) {
        if (debug) {
            Log.e(TAG, msg);
        }
    }

    /**
     * 关闭串口并释放资源。
     * <p>
     * 会清空发送队列、移除监听器、结束工作线程。队列中未完成的命令（含正在等待应答的那条）
     * 不会收到任何回调，调用方如果依赖 {@link OnReportListener#onComplete()} 做状态复位，
     * 需要在关闭时自行处理。
     * <p>
     * 关闭后可以再次 {@link #open}，工作线程会自动重建。
     */
    public void close() {
        if (serialPortHelper != null) {
            serialPortHelper.close();
        }
        removeOnDataListener();
        removeOnOpenListener();

        synchronized (queueList) {
            queueList.clear();
        }

        if (mWorkHandler != null) {
            mWorkHandler.removeCallbacksAndMessages(null);
        }
        if (mWorkThread != null) {
            mWorkThread.quitSafely();
        }

        // 复位状态, close() 之后可以重新 open(), open() 里会重建工作线程
        pendingTimeout = null;
        onWorking = false;
        command = null;
        bufferLength = 0;
    }
}
