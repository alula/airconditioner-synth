/*
 *            DO WHAT THE FUCK YOU WANT TO PUBLIC LICENSE
 *                    Version 2, December 2004
 *
 * Copyright (C) 2004 Sam Hocevar <sam@hocevar.net>
 *
 * Everyone is permitted to copy and distribute verbatim or modified
 * copies of this license document, and changing it is allowed as long
 * as the name is changed.
 *
 *            DO WHAT THE FUCK YOU WANT TO PUBLIC LICENSE
 *   TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION
 *
 *  0. You just DO WHAT THE FUCK YOU WANT TO.
 */
package alula.acsynth;

public class AirConditioner {
    private final int sampleRate;
    private long t = 0;
    private long beep = 300;
    private float lastData = 0.0f;
    private float speed = 0.6f;
    private float deltaSpeed = 0.0f;

    public AirConditioner(int sampleRate) {
        this.sampleRate = sampleRate;
        setBeep(500);
    }

    public void setSpeed(float speed) {
        this.speed = speed;
    }

    public void setBeep(int millis) {
        this.beep = msToSample(millis);
    }

    public boolean synth(float[] output) {
        float white;
        for (int i = 0; i < output.length; i++) {
            t++;

            // fan noise
            white = (float) (Math.random() * 2.0f - 1.0f);
            output[i] = (lastData + (0.015f * white)) / 1.0005f;
            lastData = output[i];
            output[i] *= deltaSpeed;

            // speed dependent wah-wah
            output[i] *= 1.0f + Math.sin(2 * Math.PI * t * ((1.0f + speed * 4.0f) / sampleRate)) * 0.1f;

            // beeps
            if (beep > 0) {
                beep--;
                output[i] += (float) Math.sin(2 * Math.PI * t * (1200.0f / sampleRate)) * 0.2f;
            }

            // interpolation
            if (deltaSpeed != speed) {
                deltaSpeed += (speed - deltaSpeed) * 0.00001;
            }
        }
        return true;
    }

    private int msToSample(int ms) {
        return (int) (sampleRate * (ms / 1000.0f));
    }
}
