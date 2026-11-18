package com.mcan.nexusforge.workspace.run;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Shared, deliberately conservative policy for copies and generated source changes. */
final class RunFilePolicy {
    static final long MAX_FILE_BYTES = 1_048_576;
    private static final Set<String> BLOCKED = Set.of(".git", ".idea", ".gradle", ".kotlin", ".tmp-run",
            ".local-backup", ".local-tools", ".agent-runs", "build", "target", "dist", "out", "node_modules", ".ssh",
            ".aws", ".azure", "credentials", "secrets", ".venv", "venv", "__pycache__", ".next", "coverage");
    private static final String BINARY = ".*\\.(pem|key|p12|pfx|jks|keystore|class|jar|war|zip|gz|7z|rar|tar|exe|dll|so|dylib|png|jpg|jpeg|gif|webp|pdf|ico|woff2?|ttf|bin|db|sqlite|mp[34]|wav|mov|avi|xlsx?|docx?|pptx?|lockb)$";

    private RunFilePolicy() { }

    static boolean allowed(Path relative) {
        for (Path part : relative) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (BLOCKED.contains(name) || name.startsWith(".env") || name.contains("credential")
                    || name.contains("secret") || name.contains("token") || name.contains("password")
                    || name.equals(".npmrc") || name.equals(".netrc") || name.equals(".pypirc")
                    || name.equals("settings.xml") || name.startsWith("id_rsa") || name.startsWith("id_ed25519")
                    || name.matches(BINARY)) return false;
        }
        return true;
    }

    static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    static String sourcePath(String value) {
        if (value == null || value.isBlank() || value.length() > 500 || value.indexOf(':') >= 0
                || value.indexOf('\0') >= 0) throw new IllegalArgumentException("A non-empty relative source path is required.");
        String normalized = value.replace('\\', '/');
        Path path = Path.of(normalized);
        if (path.isAbsolute() || normalized.startsWith("/") || normalized.endsWith("/"))
            throw new IllegalArgumentException("Use a relative source path.");
        boolean sourceDirectory = false;
        for (String part : normalized.split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals(".."))
                throw new IllegalArgumentException("Path traversal and empty path components are denied.");
            if (part.equals("src")) sourceDirectory = true;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!sourceDirectory || !(lower.endsWith(".java") || lower.endsWith(".kt") || (normalized.startsWith("frontend/src/") && (lower.endsWith(".ts") || lower.endsWith(".tsx") || lower.endsWith(".css")))) || !allowed(path))
            throw new IllegalArgumentException("Only approved JVM sources or frontend/src TS/TSX/CSS sources can be changed.");
        return normalized;
    }

    static boolean linked(Path path) throws IOException {
        return Files.isSymbolicLink(path) || !path.toRealPath().equals(path.toAbsolutePath().normalize());
    }

    static void requireNoLinks(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) break;
            if (linked(current)) throw new IOException("Symbolic links and junctions are not allowed: " + current);
        }
    }

    static Path safeTarget(Path root, String relative) throws IOException {
        requireNoLinks(root);
        Path result = root.resolve(relative).normalize();
        if (!result.startsWith(root) || result.equals(root)) throw new IOException("Access outside the isolated workspace is denied.");
        requireNoLinks(result);
        Path existing = result;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (existing == null || !existing.toRealPath().startsWith(root)) throw new IOException("Path escapes the isolated workspace.");
        return result;
    }

    static String text(byte[] bytes) throws CharacterCodingException {
        String value = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        if (value.chars().anyMatch(c -> c < 32 && c != '\t' && c != '\r' && c != '\n'))
            throw new IllegalArgumentException("Binary content is excluded.");
        return value;
    }

    static byte[] utf8(String text) throws CharacterCodingException {
        ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text));
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }
}
