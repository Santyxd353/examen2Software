package com.classforge.assistant.codex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounds all process pipes and the complete request deadline, including writes to stdin. */
final class CodexProcessRunner {
    record Result(int exitCode, String stdout, String stderr) {}
    private final CodexCompletionClient.ProcessLauncher launcher;

    CodexProcessRunner(CodexCompletionClient.ProcessLauncher launcher) { this.launcher = launcher; }

    Result run(List<String> command, Path directory, Map<String, String> environment, String stdin, long deadline) {
        Process process = null;
        var readers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            process = launcher.start(command, directory, environment);
            Process owned = process;
            Future<byte[]> stdout = readers.submit(() -> read(owned.getInputStream(), 1024 * 1024, owned));
            Future<byte[]> stderr = readers.submit(() -> read(owned.getErrorStream(), 64 * 1024, owned));
            Future<?> input = readers.submit(() -> {
                try (var stream = owned.getOutputStream()) { stream.write(stdin.getBytes(StandardCharsets.UTF_8)); }
                return null;
            });
            if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) throw new TimeoutException();
            input.get(remaining(deadline), TimeUnit.NANOSECONDS);
            byte[] out = stdout.get(remaining(deadline), TimeUnit.NANOSECONDS);
            byte[] err = stderr.get(remaining(deadline), TimeUnit.NANOSECONDS);
            return new Result(process.exitValue(), new String(out, StandardCharsets.UTF_8), new String(err, StandardCharsets.UTF_8));
        } catch (TimeoutException expired) {
            throw new CodexApiException(503, 0, "Codex superó el tiempo de espera. Puedes reintentar la solicitud.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CodexApiException(503, 0, "La solicitud a Codex fue interrumpida. Puedes reintentar.");
        } catch (IOException | ExecutionException failure) {
            throw new CodexApiException(503, 0, "No se pudo obtener una respuesta completa de Codex.");
        } finally {
            if (process != null) {
                if (process.isAlive()) terminate(process);
                close(process.getOutputStream());
                close(process.getInputStream());
                close(process.getErrorStream());
            }
            readers.shutdownNow();
        }
    }

    private byte[] read(InputStream stream, int maximum, Process owned) throws IOException {
        try (stream; var result = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int size;
            while ((size = stream.read(buffer)) >= 0) {
                if (result.size() + size > maximum) {
                    terminate(owned);
                    throw new IOException("Output limit");
                }
                result.write(buffer, 0, size);
            }
            return result.toByteArray();
        }
    }

    private long remaining(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException();
        return remaining;
    }

    static void terminate(Process process) {
        // These handles come only from the process created for this request; no name/PID-wide kill.
        List<ProcessHandle> children;
        try { children = process.toHandle().descendants().toList(); }
        catch (RuntimeException unavailable) { children = List.of(); }
        for (int index = children.size() - 1; index >= 0; index--) {
            try { children.get(index).destroyForcibly(); } catch (RuntimeException ignored) { }
        }
        try { process.destroyForcibly(); } catch (RuntimeException ignored) { }
        // Give the OS time to release private artifacts. The inference deadline has already ended.
        boolean interrupted = Thread.interrupted();
        try { process.waitFor(250, TimeUnit.MILLISECONDS); }
        catch (InterruptedException cancellation) { interrupted = true; }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private void close(java.io.Closeable stream) {
        try { stream.close(); } catch (IOException ignored) { }
    }
}
