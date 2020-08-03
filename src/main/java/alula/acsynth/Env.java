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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

@SuppressWarnings("squid:S1148")
public class Env {
    private static Map<String, String> environment = new HashMap<>();

    static {
        try {
            load();
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(-1);
            throw new AssertionError();
        }
    }

    private Env() {

    }

    public static void load() throws IOException {
        environment.putAll(System.getenv());
        var envFile = Path.of(".env");
        if (Files.exists(envFile)) {
            var props = new Properties();
            props.load(Files.newInputStream(envFile));
            props.forEach((k, v) -> environment.putIfAbsent((String) k, (String) v));
        }
    }

    @Nonnull
    public static String require(@Nonnull String key) {
        return Objects.requireNonNull(get(key), "Missing key " + key);
    }

    @Nullable
    public static String get(@Nonnull String key) {
        return environment.get(key);
    }

    @Nonnull
    public static String getOrDefault(@Nonnull String key, @Nonnull String defaultValue) {
        return environment.getOrDefault(key, defaultValue);
    }
}
