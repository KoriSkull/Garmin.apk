package com.navigon.hud;

import java.io.IOException;
import java.util.Arrays;

/**
 * Android-friendly Garmin HUD protocol encoder.
 *
 * <p>Based on packet structure discovered in:
 * https://github.com/gabonator/Work-in-progress/tree/master/GarminHud
 *
 * <p>Use with any transport that can send bytes to the HUD (typically RFCOMM Bluetooth socket).
 */
public final class GarminHudClient {

    public interface PacketTransport {
        void send(byte[] packet) throws IOException;
    }

    public enum OutAngle {
        DOWN(0x01),
        SHARP_RIGHT(0x02),
        RIGHT(0x04),
        EASY_RIGHT(0x08),
        STRAIGHT(0x10),
        EASY_LEFT(0x20),
        LEFT(0x40),
        SHARP_LEFT(0x80),
        LEFT_DOWN(0x81),
        RIGHT_DOWN(0x82),
        AS_DIRECTION(0x00);

        private final int code;

        OutAngle(int code) {
            this.code = code;
        }

        int code() {
            return code;
        }
    }

    public enum OutType {
        OFF(0x00),
        LANE(0x01),
        LONGER_LANE(0x02),
        LEFT_ROUNDABOUT(0x04),
        RIGHT_ROUNDABOUT(0x08),
        ARROW_ONLY(0x80);

        private final int code;

        OutType(int code) {
            this.code = code;
        }

        int code() {
            return code;
        }
    }

    public enum Units {
        NONE(0),
        METRES(1),
        KILOMETRES(3),
        MILES(5),
        FOOT(8);

        private final int code;

        Units(int code) {
            this.code = code;
        }

        int code() {
            return code;
        }
    }

    private final PacketTransport transport;

    public GarminHudClient(PacketTransport transport) {
        this.transport = transport;
    }

    public void setDirection(OutAngle direction, OutType type, OutAngle roundaboutOut) throws IOException {
        int typeByte = switch (direction) {
            case LEFT_DOWN -> 0x10;
            case RIGHT_DOWN -> 0x20;
            default -> type.code();
        };

        int roundaboutByte = (type == OutType.RIGHT_ROUNDABOUT || type == OutType.LEFT_ROUNDABOUT)
                ? ((roundaboutOut == OutAngle.AS_DIRECTION) ? direction.code() : roundaboutOut.code())
                : 0x00;

        int arrowByte = (direction == OutAngle.LEFT_DOWN || direction == OutAngle.RIGHT_DOWN)
                ? 0x00
                : direction.code();

        sendHud(new int[]{0x01, typeByte, roundaboutByte, arrowByte});
    }

    public void setLanes(int arrowMask, int outlineMask) throws IOException {
        sendHud(new int[]{0x02, outlineMask, arrowMask});
    }

    public void setDistance(int distance, Units units, boolean decimal, boolean leadingZero) throws IOException {
        int[] payload = new int[]{
                0x03,
                digit(distance / 1000),
                digit(distance / 100),
                digit(distance / 10),
                decimal ? 0xFF : 0x00,
                digit(distance),
                units.code()
        };

        if (!leadingZero) {
            if (payload[1] == 0x0A) {
                payload[1] = 0x00;
                if (payload[2] == 0x0A) {
                    payload[2] = 0x00;
                    if (payload[3] == 0x0A) {
                        payload[3] = 0x00;
                    }
                }
            }
        }

        sendHud(payload);
    }

    public void setTime(int hours, int minutes, boolean colon, boolean flag, boolean traffic) throws IOException {
        sendHud(new int[]{
                0x05,
                traffic ? 0xFF : 0x00,
                digit(hours / 10),
                digit(hours),
                colon ? 0xFF : 0x00,
                digit(minutes / 10),
                digit(minutes),
                flag ? 0x00 : 0xFF,
                0x00
        });
    }

    public void setTimeRaw(int traffic, int h1, int h2, int colon, int m1, int m2, int flag, int suffix) throws IOException {
        sendHud(new int[]{0x05, traffic, h1, h2, colon, m1, m2, flag, suffix});
    }

    public void setSpeedWarning(int speed, int limit, boolean speeding, boolean icon, boolean slash) throws IOException {
        sendHud(new int[]{
                0x06,
                (speed / 100) % 10,
                digit(speed / 10),
                digit(speed),
                slash ? 0xFF : 0x00,
                (limit / 100) % 10,
                digit(limit / 10),
                digit(limit),
                speeding ? 0xFF : 0x00,
                icon ? 0xFF : 0x00
        });
    }

    public void showCameraIcon() throws IOException {
        sendHud(new int[]{0x04, 0x01});
    }

    public void showGpsLabel() throws IOException {
        setGpsLabel(true);
    }

    public void setGpsLabel(boolean enabled) throws IOException {
        sendHud(new int[]{0x07, enabled ? 0x01 : 0x00});
    }

    private void sendHud(int[] payload) throws IOException {
        int crc = 0xEB + payload.length + payload.length;

        byte[] frame = new byte[255];
        int idx = 0;

        frame[idx++] = 0x10;
        frame[idx++] = 0x7B;
        frame[idx++] = (byte) (payload.length + 6);
        if (payload.length == 0x0A) {
            frame[idx++] = 0x10;
        }
        frame[idx++] = (byte) payload.length;
        frame[idx++] = 0x00;
        frame[idx++] = 0x00;
        frame[idx++] = 0x00;
        frame[idx++] = 0x55;
        frame[idx++] = 0x15;

        for (int value : payload) {
            int v = value & 0xFF;
            crc += v;
            frame[idx++] = (byte) v;
            if (v == 0x10) {
                frame[idx++] = 0x10;
            }
        }

        frame[idx++] = (byte) ((-crc) & 0xFF);
        frame[idx++] = 0x10;
        frame[idx++] = 0x03;

        transport.send(Arrays.copyOf(frame, idx));
    }

    private int digit(int n) {
        int normalized = Math.abs(n % 10);
        return normalized == 0 ? 10 : normalized;
    }
}
