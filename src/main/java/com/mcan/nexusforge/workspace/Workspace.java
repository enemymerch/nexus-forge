package com.mcan.nexusforge.workspace;

import com.mcan.nexusforge.tool.ToolRegistry;
import com.mcan.nexusforge.tool.ToolOutput;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
public final class Workspace {
    private final Path root;
    private static final Set<String> BLOCKED = Set.of(".git", ".idea", ".gradle", ".kotlin", ".tmp-run", "build", "target", "dist", "out", "node_modules", ".ssh", ".aws", ".azure", "credentials", "secrets");
    public Workspace(Path root) throws Exception {
        this.root = root.toRealPath();
        if (!Files.isDirectory(this.root)) throw new IllegalArgumentException("Workspace must be a directory.");
    }
    public Path root() { return root; }
    private void allowed(Path path) {
        for (Path part : root.relativize(path)) {
            String n = part.toString().toLowerCase(Locale.ROOT);
            if (BLOCKED.contains(n) || n.startsWith(".env") || n.contains("credential") || n.contains("secret") || n.contains("token") || n.contains("password") || n.equals(".npmrc") || n.equals(".netrc") || n.equals(".pypirc") || n.equals("settings.xml") || n.startsWith("id_rsa") || n.startsWith("id_ed25519") || n.matches(".*\\.(pem|key|p12|pfx|jks|keystore|class|jar|war|zip|gz|exe|dll|so|png|jpg|jpeg|gif|pdf|ico|woff2?|ttf|bin|db|sqlite)$"))
                throw new IllegalArgumentException("File or directory is excluded.");
        }
    }
    public Path resolve(String relative) throws Exception {
        if (relative == null || relative.isBlank()) throw new IllegalArgumentException("path must not be blank.");
        Path input = Path.of(relative);
        if (input.isAbsolute()) throw new IllegalArgumentException("Use a relative path.");
        Path lexical = root.resolve(input).normalize();
        if (!lexical.startsWith(root)) throw new IllegalArgumentException("Access outside the workspace is denied.");
        allowed(lexical);
        Path real = lexical.toRealPath();
        if (!real.startsWith(root)) throw new IllegalArgumentException("Link target outside the workspace is denied.");
        allowed(real);
        return real;
    }
    public String list(String path) throws Exception {
        Path dir = resolve(path);
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException("Path is not a directory.");
        List<JsonObject> children = new ArrayList<>();
        boolean truncated = false;
        try (var entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                Path real;
                try { real = resolve(root.relativize(entry).toString()); } catch (Exception ignored) { continue; }
                if (!Files.isDirectory(real) && !Files.isRegularFile(real)) continue;
                if (children.size() == 100) { truncated = true; break; }
                JsonObject child = new JsonObject();
                child.addProperty("path", relative(real));
                child.addProperty("type", Files.isDirectory(real) ? "directory" : "file");
                children.add(child);
            }
        }
        children.sort(Comparator.comparing(child -> child.get("path").getAsString()));
        JsonArray entries = new JsonArray();
        children.forEach(entries::add);
        JsonObject result = new JsonObject();
        result.addProperty("path", relative(dir));
        result.add("entries", entries);
        result.addProperty("truncated", truncated);
        return ToolOutput.bound(result.toString(), ToolRegistry.OUTPUT_LIMIT);
    }
    public String read(String path) throws Exception {
        Path file = resolve(path);
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Path is not a regular file.");
        byte[] bytes;
        try (var in = Files.newInputStream(file)) { bytes = in.readNBytes(64001); }
        if (bytes.length > 64000) throw new IllegalArgumentException("File exceeds the 64000-byte read limit.");
        String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        if (text.chars().anyMatch(c -> c < 32 && c != 9 && c != 10 && c != 13)) throw new IllegalArgumentException("Binary content cannot be read.");
        List<String> sourceLines = text.lines().toList();
        JsonArray lines = new JsonArray();
        for (int i = 0; i < sourceLines.size(); i++) {
            JsonObject line = new JsonObject();
            line.addProperty("line", i + 1);
            line.addProperty("text", sourceLines.get(i));
            lines.add(line);
        }
        JsonObject result = new JsonObject();
        result.addProperty("path", relative(file));
        result.add("lines", lines);
        result.addProperty("totalLines", sourceLines.size());
        result.addProperty("truncated", false);
        return ToolOutput.bound(result.toString(), ToolRegistry.OUTPUT_LIMIT);
    }
    private String relative(Path path) {
        String value = root.relativize(path).toString().replace('\\', '/');
        return value.isEmpty() ? "." : value;
    }
}
