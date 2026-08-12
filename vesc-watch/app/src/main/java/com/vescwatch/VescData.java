package com.vescwatch;

/**
 * Parsed VESC telemetry from COMM_GET_VALUES response.
 * All values are converted to human-readable units.
 */
public class VescData {
    public double tempMotor;      // °C
    public double dutyCycle;      // 0.0 – 1.0
    public int    rpm;
    public double voltage;        // V

    // --- Derived values (set by caller after config) ---

    public double speedMph;
    public double batteryPct;     // 0 – 100

    /** Absolute duty cycle as 0–100 percentage. */
    public double dutyPct() {
        return Math.abs(dutyCycle) * 100.0;
    }
}
