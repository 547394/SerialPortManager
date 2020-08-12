package com.tim.serialportlib;

import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.concurrent.locks.LockSupport;

class SerialPortDataQueue extends Thread {

    private LinkedList<SerialPortCommand> queueList = new LinkedList<>();
    private boolean                       isPark    = false;

    @Override
    public void run() {
        while (!isInterrupted()) {
            SerialPortCommand command;
            try {
                command = queueList.poll();
            } catch (NoSuchElementException e) {
                queueList.clear();
                command = null;
            }
            if (command != null) {
                park();
                // Application.getDevice().sendCommand(command);
                if (isPark) {
                    LockSupport.park();
                }
            }
        }
    }

    public void offer(SerialPortCommand command) {
        queueList.offer(command);
        if (queueList.size() > 10) {
            queueList.clear();
            unPark();
        }
    }

    public void park() {
        isPark = true;
    }

    public void unPark() {
        isPark = false;
        LockSupport.unpark(this);
    }
}
