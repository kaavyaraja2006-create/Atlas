package com.atlas.audit.anchor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One append-only file per organization, one anchor per line, flushed to disk
 * before {@link #append} returns. The line format is fixed and strict: anything
 * else in the file makes {@link #read} fail rather than be ignored.
 */
public final class FileAnchorLog implements AnchorLog {

    private static final Pattern LINE = Pattern.compile(
            "\\{\"org\":\"([0-9a-f-]{36})\",\"index\":(\\d{1,19}),\"hash\":\"([0-9a-f]{64})\","
                    + "\"at\":\"([0-9T:.\\-Z]{20,40})\",\"key\":\"([A-Za-z0-9._-]{1,64})\",\"sig\":\"([A-Za-z0-9+/=]{1,200})\"}");

    private final Path directory;

    public FileAnchorLog(Path directory) {
        this.directory = directory;
    }

    @Override
    public synchronized void append(ChainAnchor a) {
        String line = "{\"org\":\"" + a.organizationId() + "\",\"index\":" + a.chainIndex() + ",\"hash\":\"" + a.hash()
                + "\",\"at\":\"" + a.anchoredAt() + "\",\"key\":\"" + a.keyId() + "\",\"sig\":\"" + a.signature() + "\"}\n";
        try {
            Files.createDirectories(directory);
            try (FileChannel ch = FileChannel.open(file(a.organizationId()), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer buf = ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8));
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not append anchor", e);
        }
    }

    @Override
    public List<ChainAnchor> read(UUID org) {
        Path f = file(org);
        if (!Files.exists(f)) {
            return List.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read anchors", e);
        }
        List<ChainAnchor> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = LINE.matcher(lines.get(i));
            if (!m.matches()) {
                throw new UnreadableAnchorLog("line " + (i + 1) + " of " + f.getFileName() + " is not an anchor");
            }
            try {
                ChainAnchor a = new ChainAnchor(UUID.fromString(m.group(1)), Long.parseLong(m.group(2)), m.group(3),
                        Instant.parse(m.group(4)), m.group(5), m.group(6));
                if (!a.organizationId().equals(org)) {
                    throw new UnreadableAnchorLog("line " + (i + 1) + " belongs to another organization");
                }
                out.add(a);
            } catch (RuntimeException e) {
                if (e instanceof UnreadableAnchorLog u) {
                    throw u;
                }
                throw new UnreadableAnchorLog("line " + (i + 1) + " of " + f.getFileName() + " is malformed");
            }
        }
        return out;
    }

    @Override
    public String describe() {
        return "file:" + directory.toAbsolutePath();
    }

    private Path file(UUID org) {
        return directory.resolve(org + ".anchors.jsonl");
    }
}
