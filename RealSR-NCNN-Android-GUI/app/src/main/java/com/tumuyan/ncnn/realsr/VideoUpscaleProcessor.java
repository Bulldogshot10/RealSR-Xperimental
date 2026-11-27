package com.tumuyan.ncnn.realsr;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decodes video frames with FFmpeg, upscales small frame batches with NCNN
 * Real-ESRGAN, then encodes an H.264/AAC MP4.
 */
public final class VideoUpscaleProcessor {
    public interface ProgressListener {
        void onProgress(String message, int processedFrames, int estimatedFrames);
    }

    private static final int BATCH_SIZE = 8;
    private static final int TILE_SIZE = 128;
    private static final byte[] PNG_SIGNATURE = {
            (byte) 137, 80, 78, 71, 13, 10, 26, 10
    };

    private final File runtimeDir;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile Process decoder;
    private volatile Process encoder;
    private volatile Process modelProcess;

    public VideoUpscaleProcessor(File runtimeDir) {
        this.runtimeDir = runtimeDir;
    }

    public void cancel() {
        cancelled.set(true);
        destroy(decoder);
        destroy(modelProcess);
        destroy(encoder);
    }

    public void upscale(File inputFile, File outputFile, String modelFolder, int outputScale,
                        int modelScale, ProgressListener listener) throws Exception {
        cancelled.set(false);
        if (inputFile == null || !inputFile.isFile() || inputFile.length() == 0) {
            throw new IOException("Input video is missing or empty.");
        }
        if (!runtimeDir.isDirectory()) {
            throw new IOException("RealSR runtime directory is missing.");
        }
        if (outputScale < 2 || outputScale > 4) {
            throw new IllegalArgumentException("Output scale must be 2, 3 or 4.");
        }
        if (modelScale < 2 || modelScale > 4) {
            throw new IllegalArgumentException("Model scale must be 2, 3 or 4.");
        }

        File ffmpeg = new File(runtimeDir, "ffmpeg");
        File ffprobe = new File(runtimeDir, "ffprobe");
        File realsr = new File(runtimeDir, "realsr-ncnn");
        File model = new File(runtimeDir, modelFolder);
        if (!ffmpeg.isFile() || !ffprobe.isFile() || !realsr.isFile()) {
            throw new IOException("Video runtime is incomplete. FFmpeg or realsr-ncnn is missing.");
        }
        String modelScaleName = "x" + modelScale;
        if (!new File(model, modelScaleName + ".bin").isFile()
                || !new File(model, modelScaleName + ".param").isFile()) {
            throw new IOException("Selected model files are missing: " + modelFolder + "/" + modelScaleName);
        }

        File work = new File(runtimeDir.getParentFile(), "video-upscale-work");
        File inputFrames = new File(work, "input-frames");
        File outputFrames = new File(work, "output-frames");
        File decodeLog = new File(work, "decode.log");
        File encodeLog = new File(work, "encode.log");
        File modelLog = new File(work, "model.log");
        recreateDirectory(inputFrames);
        recreateDirectory(outputFrames);

        File outputParent = outputFile.getParentFile();
        if (outputParent != null && !outputParent.exists() && !outputParent.mkdirs()) {
            throw new IOException("Cannot create output folder.");
        }
        if (outputFile.exists() && !outputFile.delete()) {
            throw new IOException("Cannot overwrite the selected output file.");
        }

        String fps = probeFrameRate(ffprobe, inputFile);
        int estimatedFrames = probeEstimatedFrameCount(ffprobe, inputFile, fps);
        send(listener, "Preparing video decoder…", 0, estimatedFrames);

        List<String> encoderCommand = new ArrayList<>();
        encoderCommand.add(ffmpeg.getAbsolutePath());
        encoderCommand.addAll(Arrays.asList(
                "-hide_banner", "-loglevel", "error", "-y",
                "-f", "image2pipe", "-vcodec", "png", "-framerate", fps,
                "-i", "pipe:0",
                "-i", inputFile.getAbsolutePath(),
                "-map", "0:v:0", "-map", "1:a?",
                "-map_metadata", "1", "-map_chapters", "1",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "192k"
        ));
        if (outputScale != modelScale) {
            String resizeFilter = String.format(Locale.US, "scale=iw*%d/%d:ih*%d/%d:flags=lanczos",
                    outputScale, modelScale, outputScale, modelScale);
            encoderCommand.addAll(Arrays.asList("-vf", resizeFilter));
        }
        encoderCommand.addAll(Arrays.asList(
                "-movflags", "+faststart", "-shortest", outputFile.getAbsolutePath()
        ));

        ProcessBuilder encoderBuilder = new ProcessBuilder(encoderCommand);
        configureEnvironment(encoderBuilder);
        encoderBuilder.redirectError(encodeLog);
        encoder = encoderBuilder.start();
        OutputStream encoderInput = new BufferedOutputStream(encoder.getOutputStream(), 1024 * 1024);

        List<String> decoderCommand = Arrays.asList(
                ffmpeg.getAbsolutePath(), "-hide_banner", "-loglevel", "error",
                "-i", inputFile.getAbsolutePath(),
                "-map", "0:v:0", "-vf", "fps=" + fps,
                "-f", "image2pipe", "-vcodec", "png", "pipe:1"
        );
        ProcessBuilder decoderBuilder = new ProcessBuilder(decoderCommand);
        configureEnvironment(decoderBuilder);
        decoderBuilder.redirectError(decodeLog);
        decoder = decoderBuilder.start();

        int processedFrames = 0;
        int batchCount = 0;
        DataInputStream pngStream = new DataInputStream(
                new BufferedInputStream(decoder.getInputStream(), 1024 * 1024));
        boolean completedNormally = false;

        try {
            while (!cancelled.get()) {
                File inputFrame = new File(inputFrames,
                        String.format(Locale.US, "frame_%08d.png", processedFrames + batchCount));
                if (!writeNextPngFrame(pngStream, inputFrame)) {
                    break;
                }
                batchCount++;

                if (batchCount >= BATCH_SIZE) {
                    processedFrames += processBatch(realsr, model, modelScale, inputFrames, outputFrames,
                            encoderInput, modelLog, listener, processedFrames, estimatedFrames);
                    batchCount = 0;
                }
            }

            checkCancelled();
            if (batchCount > 0) {
                processedFrames += processBatch(realsr, model, modelScale, inputFrames, outputFrames,
                        encoderInput, modelLog, listener, processedFrames, estimatedFrames);
            }

            int decoderExit = decoder.waitFor();
            if (decoderExit != 0) {
                throw new IOException("FFmpeg decoder failed: " + readTail(decodeLog));
            }
            if (processedFrames == 0) {
                throw new IOException("No video frames were decoded.");
            }

            encoderInput.flush();
            encoderInput.close();
            int encoderExit = encoder.waitFor();
            if (encoderExit != 0) {
                throw new IOException("FFmpeg encoder failed: " + readTail(encodeLog));
            }
            if (!outputFile.isFile() || outputFile.length() == 0) {
                throw new IOException("FFmpeg did not create the output video.");
            }

            completedNormally = true;
            send(listener, "Completed " + processedFrames + " frames.", processedFrames, estimatedFrames);
        } finally {
            closeQuietly(pngStream);
            closeQuietly(encoderInput);
            if (!completedNormally) {
                destroy(decoder);
                destroy(modelProcess);
                destroy(encoder);
                if (outputFile.exists()) {
                    outputFile.delete();
                }
            }
            deleteChildren(inputFrames);
            deleteChildren(outputFrames);
        }
    }

    private int processBatch(File realsr, File model, int modelScale,
                             File inputFrames, File outputFrames,
                             OutputStream encoderInput, File modelLog,
                             ProgressListener listener, int processedFrames,
                             int estimatedFrames) throws Exception {
        checkCancelled();
        recreateDirectory(outputFrames);
        int inputCount = countPngFiles(inputFrames);
        if (inputCount == 0) {
            return 0;
        }

        send(listener, "Upscaling a batch of " + inputCount + " frames with " + model.getName() + "…",
                processedFrames, estimatedFrames);

        List<String> command = Arrays.asList(
                realsr.getAbsolutePath(),
                "-i", inputFrames.getAbsolutePath(),
                "-o", outputFrames.getAbsolutePath(),
                "-m", model.getAbsolutePath(),
                "-s", String.valueOf(modelScale),
                "-t", String.valueOf(TILE_SIZE)
        );
        ProcessBuilder builder = new ProcessBuilder(command);
        configureEnvironment(builder);
        builder.redirectErrorStream(true);
        builder.redirectOutput(modelLog);
        modelProcess = builder.start();
        int exitCode = modelProcess.waitFor();
        modelProcess = null;
        checkCancelled();
        if (exitCode != 0) {
            throw new IOException("RealSR model failed: " + readTail(modelLog));
        }

        File[] generatedFrames = outputFrames.listFiles((dir, name) ->
                name.toLowerCase(Locale.ROOT).endsWith(".png"));
        if (generatedFrames == null || generatedFrames.length == 0) {
            throw new IOException("RealSR completed without producing PNG frames.");
        }
        Arrays.sort(generatedFrames, Comparator.comparing(File::getName));
        if (generatedFrames.length != inputCount) {
            throw new IOException("Frame count mismatch: expected " + inputCount
                    + ", received " + generatedFrames.length + ".");
        }

        byte[] buffer = new byte[256 * 1024];
        for (File frame : generatedFrames) {
            checkCancelled();
            try (FileInputStream frameInput = new FileInputStream(frame)) {
                int read;
                while ((read = frameInput.read(buffer)) != -1) {
                    encoderInput.write(buffer, 0, read);
                }
            }
        }
        encoderInput.flush();

        deleteChildren(inputFrames);
        deleteChildren(outputFrames);
        send(listener, "Processed " + (processedFrames + inputCount)
                + (estimatedFrames > 0 ? " / ~" + estimatedFrames : "") + " frames.",
                processedFrames + inputCount, estimatedFrames);
        return inputCount;
    }

    private String probeFrameRate(File ffprobe, File inputFile) throws Exception {
        String output = runCapture(Arrays.asList(
                ffprobe.getAbsolutePath(), "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=avg_frame_rate,duration",
                "-of", "csv=p=0", inputFile.getAbsolutePath()
        ));
        String firstLine = output.trim().split("\\R")[0];
        if (firstLine.isEmpty()) {
            return "30/1";
        }
        String candidate = firstLine.split(",")[0].trim();
        double fps = parseRational(candidate);
        return fps < 1.0 || fps > 120.0 ? "30/1" : candidate;
    }

    private int probeEstimatedFrameCount(File ffprobe, File inputFile, String fps) {
        try {
            String output = runCapture(Arrays.asList(
                    ffprobe.getAbsolutePath(), "-v", "error", "-select_streams", "v:0",
                    "-show_entries", "stream=avg_frame_rate,duration",
                    "-of", "csv=p=0", inputFile.getAbsolutePath()
            ));
            String firstLine = output.trim().split("\\R")[0];
            String[] fields = firstLine.split(",");
            if (fields.length > 1) {
                double duration = Double.parseDouble(fields[1].trim());
                double rate = parseRational(fps);
                if (duration > 0 && rate > 0) {
                    return (int) Math.min(Integer.MAX_VALUE, Math.round(duration * rate));
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static double parseRational(String value) {
        try {
            if (value.contains("/")) {
                String[] parts = value.split("/");
                double numerator = Double.parseDouble(parts[0]);
                double denominator = Double.parseDouble(parts[1]);
                return denominator == 0 ? 0 : numerator / denominator;
            }
            return Double.parseDouble(value);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String runCapture(List<String> command) throws Exception {
        checkCancelled();
        ProcessBuilder builder = new ProcessBuilder(command);
        configureEnvironment(builder);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        int code = process.waitFor();
        if (code != 0) {
            throw new IOException("FFprobe failed: " + output);
        }
        return output.toString();
    }

    private static boolean writeNextPngFrame(DataInputStream input, File destination) throws IOException {
        int first = input.read();
        if (first == -1) {
            return false;
        }
        byte[] signature = new byte[PNG_SIGNATURE.length];
        signature[0] = (byte) first;
        input.readFully(signature, 1, signature.length - 1);
        if (!Arrays.equals(signature, PNG_SIGNATURE)) {
            throw new IOException("FFmpeg image stream did not contain a valid PNG frame.");
        }

        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(destination))) {
            out.write(signature);
            while (true) {
                byte[] header = new byte[8];
                input.readFully(header);
                long chunkLength = Integer.toUnsignedLong(ByteBuffer.wrap(header, 0, 4).getInt());
                if (chunkLength > 512L * 1024L * 1024L) {
                    throw new IOException("PNG frame contains an invalid chunk length.");
                }
                String chunkType = new String(header, 4, 4, StandardCharsets.US_ASCII);
                out.write(header);
                copyExactly(input, out, chunkLength);
                byte[] crc = new byte[4];
                input.readFully(crc);
                out.write(crc);
                if ("IEND".equals(chunkType)) {
                    break;
                }
            }
        } catch (EOFException e) {
            destination.delete();
            throw new IOException("FFmpeg ended in the middle of a PNG frame.", e);
        }
        return true;
    }

    private static void copyExactly(DataInputStream in, OutputStream out, long count) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = count;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new EOFException("Unexpected end of PNG chunk.");
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static int countPngFiles(File directory) {
        File[] files = directory.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".png"));
        return files == null ? 0 : files.length;
    }

    private void configureEnvironment(ProcessBuilder builder) {
        builder.directory(runtimeDir);
        Map<String, String> env = builder.environment();
        String current = env.get("LD_LIBRARY_PATH");
        String runtimePath = runtimeDir.getAbsolutePath();
        env.put("LD_LIBRARY_PATH", current == null || current.isEmpty()
                ? runtimePath : runtimePath + File.pathSeparator + current);
    }

    private void checkCancelled() throws IOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new IOException("Video upscale cancelled.");
        }
    }

    private static void send(ProgressListener listener, String message, int processed, int estimated) {
        if (listener != null) {
            listener.onProgress(message, processed, estimated);
        }
    }

    private static void recreateDirectory(File directory) throws IOException {
        if (directory.exists()) {
            deleteChildren(directory);
        } else if (!directory.mkdirs()) {
            throw new IOException("Cannot create temporary folder: " + directory);
        }
    }

    private static void deleteChildren(File directory) {
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteChildren(child);
                }
                child.delete();
            }
        }
    }

    private static String readTail(File file) {
        if (file == null || !file.isFile()) {
            return "No process log available.";
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            long start = Math.max(0, raf.length() - 4000);
            raf.seek(start);
            byte[] bytes = new byte[(int) (raf.length() - start)];
            raf.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "Unable to read process log.";
        }
    }

    private static void destroy(Process process) {
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(300, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }
}
