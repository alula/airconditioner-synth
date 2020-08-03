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

import club.minnced.opus.util.OpusLibrary;
import com.mewna.catnip.Catnip;
import com.mewna.catnip.CatnipOptions;
import com.mewna.catnip.entity.channel.VoiceChannel;
import com.mewna.catnip.entity.util.Permission;
import com.mewna.catnip.shard.DiscordEvent;
import com.mewna.catnip.shard.GatewayIntent;
import com.sun.jna.ptr.PointerByReference;
import io.netty.buffer.ByteBuf;
import io.reactivex.rxjava3.core.Observable;
import moe.kyokobot.koe.Koe;
import moe.kyokobot.koe.KoeClient;
import moe.kyokobot.koe.VoiceConnection;
import moe.kyokobot.koe.VoiceServerInfo;
import moe.kyokobot.koe.media.OpusAudioFrameProvider;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tomp2p.opuswrapper.Opus;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class DiscordBot {
    private static final Logger logger = LoggerFactory.getLogger(DiscordBot.class);

    private static Catnip catnip;
    private static KoeClient koeClient;
    private static Map<Long, AirConditioner> airConditioners = new HashMap<>();

    public static void main(String[] args) throws IOException {
        Env.load();

        var opts = new CatnipOptions(Env.require("TOKEN"))
                .chunkMembers(false)
                .intents(Set.of(GatewayIntent.GUILDS, GatewayIntent.GUILD_VOICE_STATES, GatewayIntent.GUILD_MESSAGES));

        catnip = Catnip.catnip(opts);
        var koe = Koe.koe();
        var prefix = "ac";

        catnip.on(DiscordEvent.READY, r -> {
            logger.info("Shard {} connected as {} with {} guilds.", r.shardId(), r.user().discordTag(), r.guilds().size());
        });

        catnip.observable(DiscordEvent.MESSAGE_CREATE)
                .filter(message -> message.guildIdAsLong() != 0
                        && !message.author().bot()
                        && message.content().equals(prefix + "ping"))
                .subscribe(message -> {
                    logger.info("ping");
                    message.channel().sendMessage("Pong!");
                });

        catnip.connect();
        Runtime.getRuntime().addShutdownHook(new Thread(DiscordBot::stop));

        var ready = catnip.observable(DiscordEvent.READY).blockingFirst();
        logger.info("Loading Opus library...");
        if (!OpusLibrary.loadFromJar()) {
            throw new IllegalStateException("Opus library not loaded.");
        }

        logger.info("Initializing Koe...");
        koeClient = koe.newClient(ready.user().idAsLong());

        catnip.observable(DiscordEvent.VOICE_STATE_UPDATE)
                .filter(state -> state.userIdAsLong() == catnip.selfUser().idAsLong() && state.channelIdAsLong() == 0)
                .subscribe(leave -> koeClient.destroyConnection(leave.guildIdAsLong()));


        catnip.observable(DiscordEvent.MESSAGE_CREATE)
                .filter(message -> message.guildIdAsLong() != 0
                        && !message.author().bot()
                        && message.content().startsWith(prefix + "speed"))
                .subscribe(message -> {
                    try {
                        var speed = Integer.parseUnsignedInt(message.content().substring((prefix + "speed").length()).strip());
                        if (speed < 16 || speed > 32) throw new NumberFormatException();

                        float n = (36.0f - speed) / 32.0f;
                        var cond = getAirConditionerForGuild(message.guildIdAsLong());
                        cond.setBeep(200);
                        cond.setSpeed(n);

                        message.channel().sendMessage("Temperature set to **" + speed + "°C.**");
                    } catch (NumberFormatException e) {
                        message.channel().sendMessage("Temperature must be a number from 16°C to 32°C!");
                    }
                });


        catnip.observable(DiscordEvent.MESSAGE_CREATE)
                .filter(message -> message.guildIdAsLong() != 0
                        && !message.author().bot()
                        && message.content().startsWith(prefix + "stop"))
                .subscribe(message -> {
                    var voiceState = message.guild().voiceStates().getById(message.author().idAsLong());
                    if (voiceState == null) {
                        message.channel().sendMessage("You need to be in a voice channel!");
                        return;
                    }

                    message.channel().sendMessage("Stopping air conditioning...");
                    var cond = getAirConditionerForGuild(message.guildIdAsLong());
                    cond.setBeep(500);
                    cond.setSpeed(0.0f);
                    Thread.sleep(5000);

                    airConditioners.remove(message.guildIdAsLong());
                    catnip.closeVoiceConnection(message.guildIdAsLong());
                });

        catnip.observable(DiscordEvent.MESSAGE_CREATE)
                .filter(message -> message.guildIdAsLong() != 0
                        && !message.author().bot()
                        && message.content().startsWith(prefix + "start"))
                .subscribe(message -> {
                    if (koeClient == null) return;

                    var voiceState = message.guild().voiceStates().getById(message.author().idAsLong());
                    if (voiceState == null) {
                        message.channel().sendMessage("You need to be in a voice channel!");
                        return;
                    }

                    if (!message.guild().selfMember().hasPermissions(voiceState.channel(), Permission.CONNECT)) {
                        message.channel().sendMessage("I don't have permissions to join your voice channel!");
                        return;
                    }

                    var channel = Objects.requireNonNull(voiceState.channel());

                    if (koeClient.getConnection(voiceState.guildIdAsLong()) == null) {
                        var conn = koeClient.createConnection(voiceState.guildIdAsLong());
                        var airConditioner = getAirConditionerForGuild(voiceState.guildIdAsLong());
                        conn.setAudioSender(new AudioSender(conn, airConditioner));
                        connect(channel);
                        message.channel().sendMessage("Started air conditioning in channel `" + channel.name() + "`!");
                    }
                });

    }

    public static AirConditioner getAirConditionerForGuild(long guildId) {
        return airConditioners.computeIfAbsent(guildId, n -> new AirConditioner(48000));
    }

    private static void connect(VoiceChannel channel) {
        Observable.combineLatest(
                catnip.observable(DiscordEvent.VOICE_STATE_UPDATE)
                        .filter(update -> update.userIdAsLong() == koeClient.getClientId()
                                && update.guildIdAsLong() == channel.guildIdAsLong()),
                catnip.observable(DiscordEvent.VOICE_SERVER_UPDATE)
                        .filter(update -> update.guildIdAsLong() == channel.guildIdAsLong())
                        .debounce(1, TimeUnit.SECONDS),
                Pair::of
        ).subscribe(pair -> {
            var stateUpdate = pair.getLeft();
            var serverUpdate = pair.getRight();
            var conn = koeClient.getConnection(serverUpdate.guildIdAsLong());
            if (conn != null) {
                var info = new VoiceServerInfo(
                        stateUpdate.sessionId(),
                        serverUpdate.endpoint(),
                        serverUpdate.token());
                conn.connect(info).thenAccept(avoid -> logger.info("Koe connection succeeded!"));
            }
        });

        catnip.openVoiceConnection(channel.guildIdAsLong(), channel.idAsLong());
    }

    private static void stop() {
        try {
            logger.info("Shutting down...");
            koeClient.getConnections().forEach((guild, conn) -> catnip.closeVoiceConnection(guild));
            koeClient.close();
            Thread.sleep(250);
            catnip.shutdown();
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class OpusRingBuffer implements Runnable {
        private PointerByReference opusEncoder;
        private final AirConditioner generator;

        private final ByteBuffer[] frames = new ByteBuffer[8];
        private final int[] frameLengths = new int[8];
        private final float[] sampleBuffer;
        private final ShortBuffer shortBuffer;

        private int head = 0;
        private int tail = 0;
        private boolean full = false;
        private boolean running = true;

        public OpusRingBuffer(AirConditioner generator) {
            this.generator = generator;

            this.sampleBuffer = new float[960 * 2];
            this.shortBuffer = ShortBuffer.allocate(sampleBuffer.length);
        }

        @Override
        @SuppressWarnings("squid:S2189")
        public void run() {
            try {
                var error = IntBuffer.allocate(1);
                this.opusEncoder = Opus.INSTANCE.opus_encoder_create(48000, 2, Opus.OPUS_APPLICATION_AUDIO, error);
                if (error.get() != Opus.OPUS_OK && opusEncoder == null) {
                    throw new IllegalStateException("Received error status from opus_encoder_create(...): " + error.get());
                }

                for (int i = 0; i < frames.length; i++) {
                    frames[i] = ByteBuffer.allocate(4096);
                }

                while (running) {
                    synchronized (frames) {
                        while (!full) {
                            generateFrame();
                        }
                        frames.wait();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                Opus.INSTANCE.opus_encoder_destroy(opusEncoder);
                opusEncoder = null;
            }
        }

        public void close() {
            running = false;
        }

        public boolean hasFrame() {
            return !isEmpty();
        }

        public void writeFrame(ByteBuf targetBuffer) {
            if (isEmpty()) return;

            synchronized (frames) {
                targetBuffer.writeBytes(frames[tail].array(), 0, frameLengths[tail]);
                frameLengths[tail] = 0;
                full = false;
                tail = (tail + 1) % frames.length;

                frames.notifyAll();
            }
        }

        private boolean isEmpty() {
            return !full && (head == tail);
        }

        private void generateFrame() {
            var frame = frames[head];

            if (!generator.synth(sampleBuffer)) {
                return;
            }

            var arr = shortBuffer.array();
            for (int i = 0; i < sampleBuffer.length; i++) {
                arr[i] = (short) Math.min(32767, Math.max(-32768, (int) (sampleBuffer[i] * 32767.0)));
            }

            int size = Opus.INSTANCE.opus_encode(opusEncoder, shortBuffer, 960, frame, frame.capacity());
            if (size <= 0) {
                throw new IllegalStateException("Received error status from opus_encode_float(...): " + size);
            }

            frameLengths[head] = size;
            head = (head + 1) % frames.length;
            full = head == tail;
        }
    }

    private static class AudioSender extends OpusAudioFrameProvider {
        private final OpusRingBuffer buffer;

        AudioSender(VoiceConnection connection, AirConditioner conditioner) {
            super(connection);
            this.buffer = new OpusRingBuffer(conditioner);
            new Thread(buffer, "Generator thread").start();
        }

        @Override
        public boolean canProvide() {
            return buffer.hasFrame();
        }

        @Override
        public void retrieveOpusFrame(ByteBuf targetBuffer) {
            buffer.writeFrame(targetBuffer);
        }

        @Override
        public void dispose() {
            this.buffer.close();
            super.dispose();
        }
    }
}
