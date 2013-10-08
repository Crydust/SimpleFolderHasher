package com.mycompany.simplefolderhasher;

import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Hello world!
 *
 */
public class App {

    public static void main(String[] args) throws IOException, InterruptedException {
        System.out.println("Loading ...");
        long startNanoTime = System.nanoTime();

        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance();
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        DecimalFormat df = new DecimalFormat("###,##0.000", symbols);

        final Map<String, String> map = new ConcurrentHashMap<>();

        final Path startPath = Paths.get("C:", "temp");
        final List<Runnable> runnables = new ArrayList<>();
        System.out.println("before walkFileTree");

        if (startPath == null) {
            System.err.println("Error, path not found");
            System.exit(1);
        }

        Files.walkFileTree(startPath, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir != null && attrs != null && attrs.isDirectory()) {
                    if (dir.endsWith(".hg")
                            || dir.endsWith(".hg")
                            || dir.endsWith(".git")
                            || dir.endsWith("CVS")
                            || dir.endsWith(".svn")
                            || dir.endsWith(".Trashes")
                            || dir.endsWith(".fseventsd")
                            || dir.endsWith(".Spotlight-V100")) {
                        String canonicalPath;
                        try {
                            canonicalPath = dir.toFile().getCanonicalPath();
                        } catch (IOException ex) {
                            canonicalPath = ex.getMessage();
                        }
                        System.out.printf("skipped %s%n", canonicalPath);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                System.out.printf("visitFileFailed %s%n", file.toFile().getCanonicalPath());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                //System.out.println("visitFile");
                if (attrs.isRegularFile()) {
                    if (file.endsWith("Thumbs.db")) {
                        String canonicalPath;
                        try {
                            canonicalPath = file.toFile().getCanonicalPath();
                        } catch (IOException ex) {
                            canonicalPath = ex.getMessage();
                        }
                        System.out.printf("skipped %s%n", canonicalPath);
                        return FileVisitResult.CONTINUE;
                    }
                    runnables.add(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                String relativePath = startPath.relativize(file).toString();
                                //String md5Hex = com.google.common.io.Files.hash(file.toFile(), Hashing.md5()).toString();
                                //map.put(relativePath, md5Hex);
                                String sha1Hex = com.google.common.io.Files.hash(file.toFile(), Hashing.sha1()).toString();
                                map.put(relativePath, sha1Hex);
                            } catch (IOException ex) {
                                String canonicalPath;
                                try {
                                    canonicalPath = file.toFile().getCanonicalPath();
                                } catch (IOException ex2) {
                                    canonicalPath = ex2.getMessage();
                                }
                                System.out.printf("skipped %s%n", canonicalPath);
                            }
                        }
                    });
                }
                return FileVisitResult.CONTINUE;
            }
        });
        long afterWalkFileTreeNanoTime = System.nanoTime();
        long doneCount = map.size();
        long nanoTime = afterWalkFileTreeNanoTime;
        final long totalCount = runnables.size();
        System.out.printf("%s files found, begin hashing ...%n", totalCount);

        int threads = Runtime.getRuntime().availableProcessors();
        threads = threads - 1;
        if (threads < 1) {
            threads = 1;
        }
        threads = 2;
        final ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (Runnable runnable : runnables) {
            executor.execute(runnable);
        }
        executor.shutdown();

        double smoothingFactor = 1 / 10;
        double workingAverage = -1;
        while (!executor.awaitTermination(5L, TimeUnit.SECONDS)) {
            long previousDoneCount = doneCount;
            doneCount = map.size();
            long deltaDoneCount = doneCount - previousDoneCount;
            long previousNanoTime = nanoTime;
            nanoTime = System.nanoTime();
            long deltaNanoTime = nanoTime - previousNanoTime;
            if (deltaDoneCount > 1 && deltaNanoTime > 1e4) {
                double newValue = (deltaNanoTime / deltaDoneCount);
                if (workingAverage < 0) {
                    workingAverage = newValue;
                } else {
                    workingAverage = Math.max(workingAverage, (nanoTime - afterWalkFileTreeNanoTime) * 1.0 / doneCount);
                    workingAverage = newValue * smoothingFactor * 1.0 + workingAverage * (1.0 - smoothingFactor);
                }
                final long remainingCount = totalCount - doneCount;
                final double remainingSeconds = 1e-9 * remainingCount * workingAverage;
                System.out.printf("approximately %.1f seconds remaining%n", remainingSeconds);
            }
        }

        System.out.printf("hashing done, writing to disk ...%n", totalCount);
        long afterProcessingNanoTime = System.nanoTime();
        StringBuilder sb = new StringBuilder();
        sb.append("# made with SimpleFolderHasher\n");
        SortedMap<String, String> sortedMap = new TreeMap<>(map);
        for (Map.Entry<String, String> entry : sortedMap.entrySet()) {
            sb.append(entry.getValue()).append(" *").append(entry.getKey()).append('\n');
        }
        File outputFile = startPath.resolve(startPath.getFileName() + ".hash").toFile();
        com.google.common.io.Files.write(sb, outputFile, Charsets.UTF_8);
        long afterWriteNanoTime = System.nanoTime();

        System.out.printf("walk filetree time: %10s s%n", df.format((afterWalkFileTreeNanoTime - startNanoTime) * 1e-9));
        System.out.printf("processing time:    %10s s%n", df.format((afterProcessingNanoTime - afterWalkFileTreeNanoTime) * 1e-9));
        System.out.printf("writing time:       %10s s%n", df.format((afterWriteNanoTime - afterProcessingNanoTime) * 1e-9));
        System.out.printf("total time:         %10s s%n", df.format((afterWriteNanoTime - startNanoTime) * 1e-9));

    }

}
