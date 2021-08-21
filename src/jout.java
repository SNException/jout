import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public final class jout {
    private jout() {}

    private static String[] listAllFiles(final Path dir, final String prefix) {
        try (final Stream<Path> stream = Files.walk(dir, Integer.MAX_VALUE)) {
            final List<String> files = stream.map(String::valueOf).sorted().collect(Collectors.toList());
            final ArrayList<String> result = new ArrayList<>(files.size());

            for (int i = 0, l = files.size(); i < l; ++i) {
                final String file = files.get(i);

                if (Files.isDirectory(Paths.get(file))) {
                    continue;
                }

                if (prefix != null) {
                    if (!file.endsWith(prefix)) {
                        continue;
                    }
                }

                result.add(file);
            }
            return result.toArray(String[]::new);
        } catch (final IOException ex) {
            return null;
        }
    }

    private static String[] listAllDirectories(final Path dir) {
        if (Files.notExists(dir)) {
            return null;
        }

        try (final Stream<Path> stream = Files.walk(dir, Integer.MAX_VALUE)) {
            final List<String> files = stream.map(String::valueOf).sorted().collect(Collectors.toList());
            final ArrayList<String> result = new ArrayList<>(files.size());

            for (int i = 0, l = files.size(); i < l; ++i) {
                final String file = files.get(i);

                if (Files.isDirectory(Paths.get(file))) {
                    result.add(file);
                }
            }
            return result.toArray(String[]::new);
        } catch (final IOException ex) {
            return null;
        }
    }

    private static Integer runShellCommand(final StringBuilder buffer, final String...cmdLine) {
        Process process = null;
        try {
            final ProcessBuilder pb = new ProcessBuilder(cmdLine);
            pb.redirectErrorStream(true);

            process = pb.start();

            final BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            for (;;) {
                final String line = reader.readLine();
                if (line == null) {
                    break;
                }
                buffer.append(line).append("\n");
            }
        } catch (final IOException ex) {
            return null;
        }
        return process.exitValue();
    }

    private static final class ByteCodeInfo {
        public final long instCount;
        public final long newInstCount;
        public ByteCodeInfo(final long instCount, final long newInstCount) {
            this.instCount    = instCount;
            this.newInstCount = newInstCount;
        }
    }

    private static ByteCodeInfo parseJavapOutput(final StringBuilder buffer) {
        long instCount    = 0;
        long newInstCount = 0;
        final String[] lines = buffer.toString().split("\n");

        final Function<String, Boolean> isNumericLambda = (str) -> {
            try {
                Integer.parseInt(str);
                return true;
            } catch (final NumberFormatException ex) {
                return false;
            }
        };

        for (final String line : lines) {
            final String modLine = line.strip();

            final String[] modLineSplit = modLine.split(":");
            if (modLineSplit != null && modLineSplit.length >= 2) {
                final String instPart = modLineSplit[1].strip();
                if (isNumericLambda.apply(instPart)) {
                    // Not an instruction!
                    // This can happen with 'tableswitch' and 'lookupswitch' for example,
                    // since they will open a block containing the adress where we expect the instruction.
                    continue;
                }
                final String inst = instPart.split(" ")[0].strip();
                if (inst.equals("new") || inst.equals("newarray") || inst.equals("multianewarray") || inst.equals("anewarray")) {
                    newInstCount +=1;
                }
                instCount += 1;
            }
        }

        return new ByteCodeInfo(instCount, newInstCount);
    }

    private static final class OutFilesInfo {
        public final long binSize;
        public final long outCount;
        public final long innerClassCount;
        public final long anonymousClassCount;
        public OutFilesInfo(final long binSize, final long outCount, final long innerClassCount, final long anonymousClassCount) {
            this.binSize             = binSize;
            this.outCount            = outCount;
            this.innerClassCount     = innerClassCount;
            this.anonymousClassCount = anonymousClassCount;
        }
    }

    private static OutFilesInfo analyzeOutFiles(final Path dir) {
        final String[] files = listAllFiles(dir, ".class");

        long binSize             = 0;
        long outCount            = files.length;
        long innerClassCount     = 0;
        long anonymousClassCount = 0;

        for (final String file : files) {
            int idx = file.indexOf("$");
            if (idx != -1) {
                final char c = file.charAt(idx + 1);
                if (Character.isDigit(c)) {
                    anonymousClassCount += 1;
                } else {
                    innerClassCount += 1;
                }
            }
            try {
                final double bytes = (double) Files.size(Paths.get(file));
                binSize += bytes;
            } catch (final IOException ex) {
                binSize = -1;
                break;
            }
        }
        return new OutFilesInfo(binSize, outCount, innerClassCount, anonymousClassCount);
    }

    public static void main(final String[] args) {
        if (args.length != 1) {
            System.out.println("Usage:");
            System.out.println("jout <out_dir>");
            System.exit(1);
        }

        final Path baseOutDirectory = Paths.get(args[0]);
        if (Files.notExists(baseOutDirectory)) {
            System.out.printf("The specified output directory '%s' does not exist.\n", baseOutDirectory.toString());
            System.exit(1);
        }

        final String[] outDirs = listAllDirectories(baseOutDirectory);
        if (outDirs == null) {
            System.out.printf("Failed to get all directories inside '%s'.\n", baseOutDirectory.toString());
            System.exit(1);
        }

        System.out.println("Gathering information...");
        System.out.println();

        final StringBuilder buffer = new StringBuilder();
        final String[] javapCommandLine = new String[3 + outDirs.length];
        final Path javapExecutable = Paths.get(System.getProperty("java.home") + File.separator + "bin" + File.separator + "javap.exe");
        if (Files.notExists(javapExecutable)) {
            System.out.println("It seems like you do not java 'javap.exe' installed on your system.");
            System.exit(1);
        }
        javapCommandLine[0] = javapExecutable.toAbsolutePath().toString();
        javapCommandLine[1] = "-c";
        javapCommandLine[2] = "-p";
        int cursor = 3;
        for (final String dir : outDirs) {
            javapCommandLine[cursor++] = dir + File.separator + "*.class";
        }
        final int code = runShellCommand(buffer, javapCommandLine);

        ByteCodeInfo byteCodeInfo = null;
        if (code == 0) {
            byteCodeInfo = parseJavapOutput(buffer);
        } else {
            System.out.printf("Failed to gather bytecode information.\n");
            System.exit(1);
        }

        final OutFilesInfo outFilesInfo = analyzeOutFiles(baseOutDirectory);

        System.out.printf("Total output size is in kilobytes        : %.1f\n", outFilesInfo.binSize / 1024.0d);
        System.out.printf("Total amount of output files             : %s\n", outFilesInfo.outCount);
        System.out.printf("Total amount of inner output classes     : %s\n", outFilesInfo.innerClassCount);
        System.out.printf("Total amount of anonymous output classes : %s\n", outFilesInfo.anonymousClassCount);
        System.out.printf("Total amount of bytecode instructions    : %s\n", byteCodeInfo.instCount);
        System.out.printf("Total amount of new allocations          : %s\n", byteCodeInfo.newInstCount);
    }
}
