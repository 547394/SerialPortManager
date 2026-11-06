package com.tim.serialport;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import com.tim.serialportlib.BytesUtil;
import com.tim.serialportlib.OnDataListener;
import com.tim.serialportlib.OnReportListener;
import com.tim.serialportlib.SerialPortError;
import com.tim.serialportlib.SerialPortManager;
import com.tim.serialportlib.SerialPortProtocol;

import java.util.Timer;
import java.util.TimerTask;

/**
 * 串口库的示例页面，演示各种协议配置与调用方式。
 * <p>
 * onCreate 中固定打开 {@code /dev/ttyS0}（115200 8N1），然后调用其中一个 model 方法。
 * 各方法的适用场景：
 * <ul>
 *   <li>{@link #model0()} —— 帧头 + 长度字段 + 帧尾 + BCC，循环下发；</li>
 *   <li>{@link #model1()} —— 无协议，靠超时粘包；</li>
 *   <li>{@link #model2()} —— 固定长度，无帧头帧尾；</li>
 *   <li>{@link #model3()} —— 固定长度 + 帧头；</li>
 *   <li>{@link #model4()} —— 帧头 + 帧尾 + Modbus CRC16，循环下发；</li>
 *   <li>{@link #model5()} —— 两条命令交替使用不同协议，验证多设备多协议；</li>
 *   <li>{@link #model6()} —— 帧头 + 长度字段 + Modbus CRC16（RTU 字节序）；</li>
 *   <li>{@link #model9()} —— 帧头 + 帧尾 + BCC 的最小配置；</li>
 *   <li>{@link #unitTest()} —— 连续发送并统计往返耗时。</li>
 * </ul>
 * 示例代码为演示用途：其中的 {@link Timer} 与循环线程没有做退出处理，
 * 正式项目请改用可取消的定时任务，并在页面销毁时调用
 * {@link SerialPortManager#close()} 释放串口。
 */
public class MainActivity extends Activity {

    private final String TAG = getClass().getSimpleName();
    /** 串口管理器，一个实例对应一个串口 */
    SerialPortManager  serialPortManager = new SerialPortManager();
    /** 演示用协议对象，各 model 方法会就地修改它 */
    SerialPortProtocol protocol          = new SerialPortProtocol();

    /**
     * 打开串口并执行演示逻辑。
     *
     * @param savedInstanceState 页面重建时保存的状态
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // model1();
        serialPortManager.enableDebug(true);
        serialPortManager.open("/dev/ttyS0", 115200);

        model9();
    }

    /**
     * 最小配置：单字节帧头 0x02、单字节帧尾 0x03、BCC 校验（从第 1 字节到倒数第 2 字节）。
     */
    private void model9() {
        protocol.setFrameHeader((byte) 0x02);
        protocol.setFrameEnd((byte) 0x03);
        protocol.setCRC(SerialPortProtocol.CRC_MODEL.BCC, 1, -2);

        serialPortManager.sendHexString("6A A6", protocol, new OnReportListener() {
            @Override
            public void onSuccess(byte[] bytes, int flag) {
                super.onSuccess(bytes, flag);
            }
        });
    }

    /** 已完成的次数 */
    private long    tick     = 1;
    /** 累计耗时，用于算平均值 */
    private long    total    = 0;
    /** 最小往返耗时 */
    private long    min      = 1000;
    /** 最大往返耗时 */
    private long    max      = 0;
    /** 本轮发送的起始时间戳 */
    private long    ts;
    /** 本次统计周期内是否出现过失败 */
    private boolean hasError = false;

    /**
     * 连续发送同一条命令并统计往返耗时，遇到失败即停止。
     * <p>
     * 依赖 {@link OnReportListener#onComplete()} 触发下一次发送，形成一个串行链；
     * 这也说明命令队列本身是串行的，不必在外部再排队。
     */
    protected void unitTest() {
        ts = System.currentTimeMillis();
        // AA 55 02 01 5E 0D 0A
        // 6A A6 01 01 01 09 CC 7A 01 01 00 90 21 3A AA E0 54 0D 0A
        serialPortManager.sendHexString("6A A6 01 01 01 09 CC 7A 01 01 00 90 21 3A AA E0 54 0D 0A", protocol, new OnReportListener() {
            //noinspection RedundantMethodOverride
            @Override
            public void onSuccess(byte[] bytes, int flag) {
            }

            @Override
            public void onFailure(SerialPortError error, int flag) {
                hasError = true;
            }

            @Override
            public void onComplete() {
                long that = System.currentTimeMillis() - ts - 50;
                min = Math.min(that, min);
                max = Math.max(that, max);
                total += that;
                Log.i("log", String.format("最小值 %d, 最大值 %d, 平均值 %d, 本次  %d, 共 %d 次", min, max, total / tick, that, tick));
                if (!hasError) {
                    tick++;
                    unitTest();
                }
            }
        });
    }

    /**
     * 帧头 + 长度字段 + 帧尾 + BCC 的组合协议，每 2 秒下发一次。
     * <p>
     * 长度字段下标为 3，{@code uselessLength} 为 2（命令字与长度字段之外的固定字节），
     * 校验范围为第 3 字节到倒数第 2 字节（帧尾占 1 字节）。
     */
    private void model0() {
        protocol.setFrameHeader((byte) 0xEA);
        protocol.setFrameEnd((byte) 0xF5);
        protocol.setDataLenIndex(3);   // 数据长度下标
        protocol.setUselessLength(2);
        protocol.setCRC(SerialPortProtocol.CRC_MODEL.BCC, 3, -2);
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                //noinspection InfiniteLoopStatement
                while (true) {
                    serialPortManager.sendHexString("01 EA D1 01 04 FF 11 EA F5", protocol, 4, new OnReportListener() {
                        @Override
                        public void onSuccess(byte[] bytes, int flag) {
                            // 自动粘包, 不返回帧头帧尾和CRC部分
                            Log.i(TAG, BytesUtil.toHexString(bytes) + " flag:" + flag);
                        }

                        @Override
                        public void onFailure(SerialPortError error, int flag) {
                            Log.i("onFailure", error.toString());
                        }

                        //noinspection RedundantMethodOverride
                        @Override
                        public void onComplete() {
                        }
                    });
                    try {
                        //noinspection BusyWait
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        //noinspection ThrowablePrintStackTrace
                        e.printStackTrace();
                    }
                }
            }
        }, 2000);
    }

    /**
     * 自动粘包：无帧头、无帧尾、无校验。
     * <p>
     * 从收到第一个字节开始计时，总线静默 {@code receivedTimeout} 毫秒后把已收到的数据
     * 作为一帧回调。适合“一问一答但帧长不固定且没有帧边界”的设备。
     */
    private void model1() {
        // 从收到第一个字节开始, 50毫秒后自动粘包
        serialPortManager.setReceivedTimeout(50);
        serialPortManager.setOnDataListener(new OnDataListener() {
            @Override
            public void onDataReceived(byte[] bytes) {
                Log.i(TAG, BytesUtil.toHexString(bytes));
            }
        });
    }

    /**
     * 固定长度：无帧头、无帧尾、无校验。
     * <p>
     * 每收满 20 字节输出一帧，多出来的字节视为干扰丢弃。
     */
    private void model2() {
        protocol.setFixedLength(20);
        serialPortManager.setProtocol(protocol);
        serialPortManager.setOnDataListener(new OnDataListener() {
            @Override
            public void onDataReceived(byte[] bytes) {
                Log.i(TAG, BytesUtil.toHexString(bytes));
            }
        });
    }

    /**
     * 固定长度 + 帧头：无帧尾、无校验。
     * <p>
     * 帧头用于在连续字节流中定位帧起点，帧长固定为 20 字节（含帧头）。
     */
    private void model3() {
        protocol.setFrameHeader((byte) 0x02, (byte) 0x10);
        protocol.setFixedLength(20);
        serialPortManager.setProtocol(protocol);
        serialPortManager.setOnDataListener(new OnDataListener() {
            @Override
            public void onDataReceived(byte[] bytes) {
                Log.i(TAG, BytesUtil.toHexString(bytes));
            }
        });
    }

    /**
     * 可变长度 + 帧头 + 帧尾 + Modbus CRC16，每 2 秒下发一次。
     * <p>
     * 校验范围从第 2 字节到倒数第 4 字节（帧尾占 2 字节，CRC 占 2 字节）。
     * 回调发生在串口线程，不在主线程。
     */
    private void model4() {
        // 设置帧头
        protocol.setFrameHeader((byte) 0x09C, (byte) 0xC9);
        // 设置帧尾
        protocol.setFrameEnd((byte) 0x0E, (byte) 0x0A);
        // 设置CRC计算方式和范围, 结束范围可为负值
        protocol.setCRC(SerialPortProtocol.CRC_MODEL.MODBUS_16, 2, -4);
        // 设置数据超时时间, 超过此时间如果终端没有回复数据则调用 onFailure 方法
        serialPortManager.setReceivedTimeout(350);
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                //noinspection InfiniteLoopStatement
                while (true) {
                    serialPortManager.sendHexString("6A A6 01 07 01 01 00 E4 48 0D 0A", protocol, new OnReportListener() {
                        @Override
                        public void onSuccess(byte[] bytes, int flag) {
                            Log.i(TAG, BytesUtil.toHexString(bytes));
                        }

                        @Override
                        public void onFailure(SerialPortError error, int flag) {
                            Log.i("onFailure", error.toString());
                        }

                        //noinspection RedundantMethodOverride
                        @Override
                        public void onComplete() {
                        }
                    });
                    try {
                        //noinspection BusyWait
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        //noinspection ThrowablePrintStackTrace
                        e.printStackTrace();
                    }
                }
            }
        }, 2000);
    }

    /**
     * 多设备多协议：连续入队两条使用不同协议的命令，各 50 轮。
     * <p>
     * 协议 1：帧头 + 长度字段，无帧尾，累加和校验；
     * 协议 2：帧头 + 帧尾，无校验。
     * 两种协议对象互不复用，命令进入同一队列后按入队顺序串行下发。
     */
    private void model5() {
        // 多设备多协议
        final SerialPortProtocol protocol1 = new SerialPortProtocol();
        // 设置帧头
        protocol1.setFrameHeader((byte) 0x096, (byte) 0x69);
        protocol1.setDataLenIndex(2);   // 数据长度下标
        protocol1.setUselessLength(6);  // 除数据长度剩余长度, 不含帧头及数据本身, 因为此协议没有帧尾, 所以长度需要加上CRC的长度
        // 设置CRC计算方式和范围, 结束范围可为负值
        protocol1.setCRC(SerialPortProtocol.CRC_MODEL.CHECKSUM, 2, -2);
        // 启用协议
        // serialPortManager.setProtocol(protocol1);
        // 设置数据超时时间, 超过此时间如果终端没有回复数据则调用 onFailure 方法
        // serialPortManager.setReceivedTimeout(350);
        // 协议2
        final SerialPortProtocol protocol2 = new SerialPortProtocol();
        protocol2.setFrameHeader((byte) 0xAA);
        protocol2.setFrameEnd((byte) 0x55);
        //
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                for (int i = 0; i < 50; i++) {
                    final int finalI = i;
                    serialPortManager.sendHexString("96 69 01 FE 01 C0 05 08 32 FE", protocol1, new OnReportListener() {
                        @Override
                        public void onSuccess(byte[] bytes, int flag) {
                            Log.i(TAG, "i=" + finalI);
                        }

                        @Override
                        public void onFailure(SerialPortError error, int flag) {
                            Log.e(TAG, "i=" + finalI + ":" + error.toString());
                        }
                    });
                    serialPortManager.sendHexString("AA 01 02 00 00 00 03 55", protocol2, new OnReportListener() {
                        @Override
                        public void onSuccess(byte[] bytes, int flag) {
                            Log.i(TAG, "j=" + finalI);
                        }

                        @Override
                        public void onFailure(SerialPortError error, int flag) {
                            Log.e(TAG, "j=" + finalI + ":" + error.toString());
                        }
                    });
                }
                Log.i(TAG, "执行 for 完成");
            }
        }, 2000);
    }

    /**
     * 帧头 + 长度字段 + Modbus CRC16（RTU 字节序），每 2 秒下发一次。
     * <p>
     * 校验范围从第 0 字节到倒数第 2 字节；RTU 模式下 CRC 低字节在前，
     * 与 {@link SerialPortProtocol.CRC_MODEL#MODBUS_16} 的区别仅在比较顺序。
     */
    private void model6() {
        protocol.setFrameHeader((byte) 0x80, (byte) 0x03);
        protocol.setDataLenIndex(2);
        protocol.setUselessLength(2);
        protocol.setCRC(SerialPortProtocol.CRC_MODEL.MODBUS_16_RTU, 0, -2);
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                //noinspection InfiniteLoopStatement
                while (true) {
                    serialPortManager.sendHexString("80 03 00 00 00 02 DA 1A", protocol, new OnReportListener() {
                        @Override
                        public void onSuccess(byte[] bytes, int flag) {
                            Log.i(TAG, BytesUtil.toHexString(bytes));
                        }
                    });
                    try {
                        //noinspection BusyWait
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        //noinspection ThrowablePrintStackTrace
                        e.printStackTrace();
                    }
                }
            }
        }, 1000);
    }
}