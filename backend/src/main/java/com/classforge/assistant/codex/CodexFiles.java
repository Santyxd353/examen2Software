package com.classforge.assistant.codex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Private request artifacts; no credentials are copied into request directories. */
final class CodexFiles {
    private CodexFiles() {}

    static Path directory() throws IOException {
        Path path = Files.createTempDirectory("classforge-codex-").toAbsolutePath().normalize();
        try { restrict(path, true); return path; }
        catch (IOException failure) { Files.deleteIfExists(path); throw failure; }
    }

    static Path write(Path path, String content) throws IOException { return write(path, content.getBytes(StandardCharsets.UTF_8)); }

    static Path write(Path path, byte[] content) throws IOException {
        Files.createFile(path);
        restrict(path, false);
        return Files.write(path, content);
    }

    private static void restrict(Path path, boolean directory) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            Set<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            if (directory) permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (acl == null) throw new IOException("Private files are not supported");
            AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (directory) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
            acl.setAcl(List.of(entry.build()));
        }
    }

    static void cleanup(Path root) {
        if (root == null) return;
        try {
            // walkFileTree does not follow symbolic links, including any unexpected output link.
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    Files.deleteIfExists(directory); return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failure) {
            // The directory remains private if the operating system still holds a file open.
            root.toFile().deleteOnExit();
        }
    }
}
