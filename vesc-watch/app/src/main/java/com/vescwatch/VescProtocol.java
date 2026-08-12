package com.vescwatch;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * VESC UART/BLE packet protocol.
 *
 * Packet format:
 *   Short: [0x02] [len:1] [payload] [crc16:2] [0x03]
 *   Long:  [0x03] [len:2] [payload] [crc16:2] [0x03]
 *
 * We send COMM_GET_VALUES (0x04) and parse the response.
 */
public class VescProtocol {

    public static final byte COMM_GET_VALUES = 0x04;
    public static final byte COMM_GET_VALUES_SELECTIVE = 50;

    // Mask for selective values:
    // Bit 1: Motor Temp
    // Bit 6: Duty Cycle
    // Bit 7: RPM
    // Bit 8: Input Voltage
    public static final int MASK_SELECTIVE = (1 << 1) | (1 << 6) | (1 << 7) | (1 << 8);

    // ---- Board configuration (adjust for your setup) ----

    /** Number of motor pole pairs. Common: 30 poles = 15 pairs for hub motors. */
    public static int motorPoles = 30;

    /** Wheel diameter in meters. ~11 inch for onewheel-style. */
    public static double wheelDiameterM = 0.280;

    /** Gear ratio (motor:wheel). 1.0 for direct-drive hub motors. */
    public static double gearRatio = 1.0;

    /** Battery cell count in series. 0 = auto-detect from voltage. */
    public static int cellCountS = 0;

    /** Per-cell voltage range. */
    public static double cellFull = 4.2;
    public static double cellEmpty = 3.0;

    // -------------------------------------------------------

    /**
     * Build a COMM_GET_VALUES_SELECTIVE request packet.
     */
    public static byte[] buildGetValues() {
        byte[] payload = new byte[5];
        payload[0] = COMM_GET_VALUES_SELECTIVE;
        payload[1] = (byte) ((MASK_SELECTIVE >> 24) & 0xFF);
        payload[2] = (byte) ((MASK_SELECTIVE >> 16) & 0xFF);
        payload[3] = (byte) ((MASK_SELECTIVE >> 8) & 0xFF);
        payload[4] = (byte) (MASK_SELECTIVE & 0xFF);
        return wrapPacket(payload);
    }

    /**
     * Parse a COMM_GET_VALUES_SELECTIVE response payload (after unwrapping packet framing).
     * Returns null if payload is too short or wrong command ID.
     */
    public static VescData parseGetValues(byte[] payload) {
        if (payload == null || payload.length < 15) return null;

        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);

        byte cmdId = buf.get();
        if (cmdId != COMM_GET_VALUES_SELECTIVE) return null;

        int mask = buf.getInt();
        if (mask != MASK_SELECTIVE) return null; // Ensure it matches what we requested

        VescData d = new VescData();

        // Order of fields in response matches the bit order in the mask
        // Bit 1: Motor Temp (float16)
        d.tempMotor = buf.getShort() / 10.0;

        // Bit 6: Duty Cycle (float16)
        d.dutyCycle = buf.getShort() / 1000.0;

        // Bit 7: RPM (float32)
        d.rpm = buf.getInt();

        // Bit 8: Input Voltage (float16)
        d.voltage = buf.getShort() / 10.0;

        // Derived values
        d.speedMph = calcSpeedMph(d.rpm);
        d.batteryPct = calcBatteryPct(d.voltage);

        return d;
    }

    /** RPM → mph using wheel diameter and pole count. */
    public static double calcSpeedMph(int rpm) {
        // ERPM → mechanical RPM → wheel RPM → speed
        double mechRpm = Math.abs(rpm) / (motorPoles / 2.0);
        double wheelRpm = mechRpm / gearRatio;
        double circumference = Math.PI * wheelDiameterM;
        double speedMps = wheelRpm * circumference / 60.0;
        return speedMps * 2.23694; // m/s to mph
    }

    /** Common Li-ion series cell counts. */
    private static final int[] COMMON_S_COUNTS = {
        15, 18, 20, 24, 28, 30
    };

    /**
     * Auto-detect cell count from voltage. Picks the series count
     * whose nominal range (cellEmpty*s .. cellFull*s) best fits the reading.
     */
    public static int detectCellCount(double voltage) {
        if (voltage <= 0) return 12;
        // Divide by nominal 3.7V per cell, round to nearest common count
        double raw = voltage / 3.7;
        int best = COMMON_S_COUNTS[0];
        double bestDist = Double.MAX_VALUE;
        for (int s : COMMON_S_COUNTS) {
            double dist = Math.abs(s - raw);
            if (dist < bestDist) {
                bestDist = dist;
                best = s;
            }
        }
        return best;
    }

    /** Voltage → battery percentage (linear between cellEmpty and cellFull). */
    public static double calcBatteryPct(double voltage) {
        int cells = cellCountS > 0 ? cellCountS : detectCellCount(voltage);
        double packFull = cellFull * cells;
        double packEmpty = cellEmpty * cells;
        if (voltage >= packFull) return 100.0;
        if (voltage <= packEmpty) return 0.0;
        return (voltage - packEmpty) / (packFull - packEmpty) * 100.0;
    }

    // ---- Packet framing ----

    /**
     * Wrap a payload into a VESC packet with start byte, length, CRC, end byte.
     */
    public static byte[] wrapPacket(byte[] payload) {
        int len = payload.length;
        int crc = crc16(payload);

        byte[] packet;
        if (len < 256) {
            packet = new byte[len + 5];
            packet[0] = 0x02;
            packet[1] = (byte) len;
            System.arraycopy(payload, 0, packet, 2, len);
            packet[2 + len]     = (byte) ((crc >> 8) & 0xFF);
            packet[2 + len + 1] = (byte) (crc & 0xFF);
            packet[2 + len + 2] = 0x03;
        } else {
            packet = new byte[len + 6];
            packet[0] = 0x03;
            packet[1] = (byte) ((len >> 8) & 0xFF);
            packet[2] = (byte) (len & 0xFF);
            System.arraycopy(payload, 0, packet, 3, len);
            packet[3 + len]     = (byte) ((crc >> 8) & 0xFF);
            packet[3 + len + 1] = (byte) (crc & 0xFF);
            packet[3 + len + 2] = 0x03;
        }
        return packet;
    }

    /**
     * Try to extract a complete VESC packet payload from a byte buffer.
     * Returns the payload if found, null if incomplete.
     * Consumes the packet bytes from the buffer by returning the new start offset.
     */
    public static int[] findPacketBounds(byte[] data, int offset, int length) {
        if (length - offset < 5) return null;

        for (int i = offset; i < length; i++) {
            int start = data[i] & 0xFF;

            if (start == 0x02) {
                // Short packet
                if (i + 1 >= length) return null;
                int payloadLen = data[i + 1] & 0xFF;
                int totalLen = payloadLen + 5; // start + len + payload + crc(2) + end
                if (i + totalLen > length) return null;

                // Verify end byte
                if ((data[i + totalLen - 1] & 0xFF) != 0x03) continue;

                // Verify CRC
                int crcExpected = ((data[i + 2 + payloadLen] & 0xFF) << 8)
                               | (data[i + 3 + payloadLen] & 0xFF);
                int crcActual = crc16(data, i + 2, payloadLen);
                if (crcExpected != crcActual) continue;

                // payload starts at i+2, length = payloadLen
                return new int[] { i + 2, payloadLen, i + totalLen };

            } else if (start == 0x03 && i + 2 < length) {
                // Could be long packet start — check if next bytes make sense as length
                int payloadLen = ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
                if (payloadLen > 0 && payloadLen < 4096) {
                    int totalLen = payloadLen + 6;
                    if (i + totalLen > length) return null;
                    if ((data[i + totalLen - 1] & 0xFF) != 0x03) continue;

                    // Verify CRC
                    int crcExpected = ((data[i + 3 + payloadLen] & 0xFF) << 8)
                                   | (data[i + 4 + payloadLen] & 0xFF);
                    int crcActual = crc16(data, i + 3, payloadLen);
                    if (crcExpected != crcActual) continue;

                    return new int[] { i + 3, payloadLen, i + totalLen };
                }
            }
        }
        return null;
    }

    /** CRC16-XMODEM. */
    public static int crc16(byte[] data) {
        return crc16(data, 0, data.length);
    }

    public static int crc16(byte[] data, int offset, int length) {
        int crc = 0;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }
            }
            crc &= 0xFFFF;
        }
        return crc;
    }
}
