package com.navigon.hud;

import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.OutputStream;

/** Simple transport adapter for Android BluetoothSocket RFCOMM connections. */
public final class BluetoothRfcommTransport implements GarminHudClient.PacketTransport {
    private final OutputStream outputStream;

    public BluetoothRfcommTransport(BluetoothSocket socket) throws IOException {
        this.outputStream = socket.getOutputStream();
    }

    @Override
    public void send(byte[] packet) throws IOException {
        outputStream.write(packet);
        outputStream.flush();
    }
}
