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

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;

public class Main {
    public static void main(String[] args) throws LineUnavailableException {
        float[] buf = new float[2048];
        byte[] byteBuf = new byte[buf.length * 2];

        var ac = new AirConditioner(48000);
        var af = new AudioFormat(48000, 16, 2, true, false);
        var line = AudioSystem.getSourceDataLine(af);

        line.open();
        line.start();

        while (ac.synth(buf)) {
            for (int i = 0; i < buf.length; i++) {
                short sample = (short) Math.min(32767, Math.max(-32768, (int) (buf[i] * 32767.0)));
                byteBuf[i * 2] = (byte) (sample & 0xff);
                byteBuf[i * 2 + 1] = (byte) (sample >> 8 & 0xff);
            }
            line.write(byteBuf, 0, byteBuf.length);
        }

        line.drain();
        line.stop();
        line.close();
    }
}
