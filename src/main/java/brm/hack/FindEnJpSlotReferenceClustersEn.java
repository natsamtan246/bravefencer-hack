package brm.hack;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import brm.Conf;

public class FindEnJpSlotReferenceClustersEn {

    /*
     * Report-only.
     *
     * Exact full table search failed:
     *
     *   EN hits: 0
     *   JP hits: only repeated JP-only likely false positives
     *
     * So this searches for CLUSTERS of individual start/end references.
     *
     * The idea:
     *
     *   If a hidden event structure references several nearby dialogue slots,
     *   we may not see the full table in order, but we may still see many
     *   target slot values clustered near each other.
     */

    private static final int BUCKET_SIZE = 0x200;

    private static final String TARGET_REL_FILE = "006/0.4";

    private static final long LOCAL_BASE = 0x060000L;

    private static final long[] EN_STARTS = new long[] {
            0x06083CL, 0x060868L, 0x060890L, 0x0608E0L, 0x06092CL,
            0x060978L, 0x0609C4L, 0x0609FCL, 0x060AA4L, 0x060BA0L,
            0x060C48L, 0x060C7CL, 0x060DF8L, 0x060EB0L, 0x060F1CL,
            0x060F54L, 0x060F84L, 0x060FC4L, 0x0610B0L, 0x06111CL,
            0x0611B0L, 0x061288L, 0x06134CL, 0x0613A0L
    };

    private static final long[] EN_LENGTHS = new long[] {
            44, 40, 80, 76, 76,
            76, 56, 168, 252, 168,
            52, 380, 184, 108, 56,
            48, 64, 236, 108, 148,
            216, 196, 84, 100
    };

    private static final long[] JP_STARTS = new long[] {
            0x060660L, 0x060678L, 0x06069CL, 0x0606F8L, 0x060740L,
            0x060788L, 0x0607E4L, 0x060814L, 0x0608ACL, 0x060988L,
            -1L,       0x060A44L, 0x060C00L, 0x060CA0L, 0x060CF4L,
            0x060D28L, 0x060D4CL, 0x060D88L, -1L,       0x060E90L,
            0x060F14L, 0x060FA4L, 0x061044L, 0x06108CL
    };

    private static final long[] JP_LENGTHS = new long[] {
            24, 36, 92, 72, 72,
            92, 48, 152, 220, 188,
            -1, 444, 160, 84, 52,
            36, 60, 264, -1, 132,
            144, 160, 72, 100
    };

    public static void main(String[] args) throws Exception {
        File enSc01Dir = new File(Conf.desktop + "brmen/SC01/");
        File jpSc01Dir = new File(Conf.desktop + "brmjp/SC01/");
        File report = new File(Conf.desktop + "en-jp-slot-reference-clusters.txt");

        PrintWriter out = new PrintWriter(report, "UTF-8");

        out.println("Brave Fencer Musashi EN/JP Slot Reference Cluster Search");
        out.println("=======================================================");
        out.println();
        out.println("This report does not modify anything.");
        out.println();
        out.println("Purpose:");
        out.println("  Search EN and JP SC01 files for clusters of individual");
        out.println("  target-slot START and END references.");
        out.println();
        out.println("Why:");
        out.println("  The exact table search found no EN table hits.");
        out.println("  This looser search may find event records or scattered metadata.");
        out.println();

        out.println("INPUTS");
        out.println("------");
        out.println("EN SC01 dir: " + enSc01Dir.getAbsolutePath() + " exists=" + enSc01Dir.exists());
        out.println("JP SC01 dir: " + jpSc01Dir.getAbsolutePath() + " exists=" + jpSc01Dir.exists());
        out.println();

        if (!enSc01Dir.exists()) {
            out.close();
            throw new RuntimeException("Missing EN SC01 dir: " + enSc01Dir.getAbsolutePath());
        }

        if (!jpSc01Dir.exists()) {
            out.close();
            throw new RuntimeException("Missing JP SC01 dir: " + jpSc01Dir.getAbsolutePath());
        }

        List<ValueSpec> enSpecs = buildSpecs("EN", EN_STARTS, EN_LENGTHS);
        List<ValueSpec> jpSpecs = buildSpecs("JP", JP_STARTS, JP_LENGTHS);

        out.println("SPECS");
        out.println("-----");
        out.println("EN value specs: " + enSpecs.size());
        out.println("JP value specs: " + jpSpecs.size());
        out.println("Bucket size:    " + hex(BUCKET_SIZE));
        out.println();

        Map<ClusterKey, Cluster> enClusters = scanLanguage("EN", enSc01Dir, enSpecs);
        Map<ClusterKey, Cluster> jpClusters = scanLanguage("JP", jpSc01Dir, jpSpecs);

        List<Cluster> enList = toSortedClusterList(enClusters);
        List<Cluster> jpList = toSortedClusterList(jpClusters);

        printTopClusters(out, "EN", enList);
        printTopClusters(out, "JP", jpList);
        printPossiblePairs(out, enList, jpList);

        out.println("INTERPRETATION");
        out.println("--------------");
        out.println("Strong candidate:");
        out.println("  - same relative file in EN and JP");
        out.println("  - same format");
        out.println("  - multiple overlapping line indices");
        out.println("  - target file 006/0.4 preferred");
        out.println();
        out.println("If a cluster appears only in JP or only in EN, treat it cautiously.");
        out.println("If a cluster hits tiny repeated graphics-like data, ignore it.");
        out.println();
        out.println("Upload this report before patching anything.");

        out.close();

        System.out.println("EN/JP slot reference cluster report written to:");
        System.out.println(report.getAbsolutePath());
    }

    private static List<ValueSpec> buildSpecs(String language, long[] starts, long[] lengths) {
        List<ValueSpec> specs = new ArrayList<ValueSpec>();

        for (int i = 0; i < starts.length; i++) {
            long start = starts[i];
            long length = lengths[i];

            if (start < 0 || length < 0) {
                continue;
            }

            long end = start + length;
            long localStart = start - LOCAL_BASE;
            long localEnd = end - LOCAL_BASE;

            addStartEndSpecs(specs, language, i, "START", start, localStart);
            addStartEndSpecs(specs, language, i, "END", end, localEnd);
        }

        return specs;
    }

    private static void addStartEndSpecs(
            List<ValueSpec> specs,
            String language,
            int lineIndex,
            String kind,
            long full,
            long local
    ) {
        /*
         * Full file offsets.
         */
        addSpec(specs, language, lineIndex, kind, "FULL_RAW", full, Format.U24_LE);
        addSpec(specs, language, lineIndex, kind, "FULL_RAW", full, Format.U24_BE);
        addSpec(specs, language, lineIndex, kind, "FULL_RAW", full, Format.U32_LE);
        addSpec(specs, language, lineIndex, kind, "FULL_RAW", full, Format.U32_BE);

        /*
         * Local offsets relative to 0x060000.
         */
        addSpec(specs, language, lineIndex, kind, "LOCAL_RAW", local, Format.U16_LE);
        addSpec(specs, language, lineIndex, kind, "LOCAL_RAW", local, Format.U16_BE);
        addSpec(specs, language, lineIndex, kind, "LOCAL_RAW", local, Format.U24_LE);
        addSpec(specs, language, lineIndex, kind, "LOCAL_RAW", local, Format.U24_BE);
        addSpec(specs, language, lineIndex, kind, "LOCAL_RAW", local, Format.U32_LE);
        addSpec(specs, language, lineIndex, kind, "LOCAL_RAW", local, Format.U32_BE);

        /*
         * Local / 2.
         */
        if ((local % 2) == 0) {
            long div2 = local / 2;

            addSpec(specs, language, lineIndex, kind, "LOCAL_DIV2", div2, Format.U16_LE);
            addSpec(specs, language, lineIndex, kind, "LOCAL_DIV2", div2, Format.U16_BE);
            addSpec(specs, language, lineIndex, kind, "LOCAL_DIV2", div2, Format.U24_LE);
            addSpec(specs, language, lineIndex, kind, "LOCAL_DIV2", div2, Format.U24_BE);
            addSpec(specs, language, lineIndex, kind, "LOCAL_DIV2", div2, Format.U32_LE);
            addSpec(specs, language, lineIndex, kind, "LOCAL_DIV2", div2, Format.U32_BE);
        }

        /*
         * Local / 4.
         */
        if ((local % 4) == 0) {
            long div4 = local / 4;

            addSpec(specs, language, lineIndex, kind, "LOCAL_DIV4", div4, Format.U16_LE);
            addSpec(specs, language, lineIndex, kind, "LOCAL_DIV4", div4, Format.U16_BE);
            addSpec(specs, language, lineIndex, kind, "LOCAL_DIV4", div4, Format.U24_LE);
            addSpec(specs, language, lineIndex, kind, "LOCAL_DIV4", div4, Format.U24_BE);
            addSpec(specs, language, lineIndex, kind, "LOCAL_DIV4", div4, Format.U32_LE);
            addSpec(specs, language, lineIndex, kind, "LOCAL_DIV4", div4, Format.U32_BE);
        }

        /*
         * RAM-looking pointers.
         */
        long ram8011 = 0x80110000L + local;

        addSpec(specs, language, lineIndex, kind, "RAM_80110000_PLUS_LOCAL", ram8011, Format.U32_LE);
        addSpec(specs, language, lineIndex, kind, "RAM_80110000_PLUS_LOCAL", ram8011, Format.U32_BE);
    }

    private static void addSpec(
            List<ValueSpec> specs,
            String language,
            int lineIndex,
            String kind,
            String transform,
            long value,
            Format format
    ) {
        if (!formatCanHold(format, value)) {
            return;
        }

        ValueSpec spec = new ValueSpec();
        spec.language = language;
        spec.lineIndex = lineIndex;
        spec.kind = kind;
        spec.transform = transform;
        spec.value = value;
        spec.format = format;

        specs.add(spec);
    }

    private static Map<ClusterKey, Cluster> scanLanguage(
            String language,
            File rootDir,
            List<ValueSpec> specs
    ) throws Exception {
        Map<Format, Map<Long, List<ValueSpec>>> lookup = buildLookup(specs);

        List<File> files = new ArrayList<File>();
        collectFiles(rootDir, files);

        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getAbsolutePath().compareTo(b.getAbsolutePath());
            }
        });

        Map<ClusterKey, Cluster> clusters = new LinkedHashMap<ClusterKey, Cluster>();

        for (File file : files) {
            String relativeFile = rel(rootDir, file);
            byte[] data = readAll(file);

            scanFile(language, relativeFile, data, lookup, clusters);
        }

        return clusters;
    }

    private static Map<Format, Map<Long, List<ValueSpec>>> buildLookup(List<ValueSpec> specs) {
        Map<Format, Map<Long, List<ValueSpec>>> lookup =
                new LinkedHashMap<Format, Map<Long, List<ValueSpec>>>();

        for (ValueSpec spec : specs) {
            Map<Long, List<ValueSpec>> byValue = lookup.get(spec.format);

            if (byValue == null) {
                byValue = new LinkedHashMap<Long, List<ValueSpec>>();
                lookup.put(spec.format, byValue);
            }

            Long key = Long.valueOf(spec.value);
            List<ValueSpec> list = byValue.get(key);

            if (list == null) {
                list = new ArrayList<ValueSpec>();
                byValue.put(key, list);
            }

            list.add(spec);
        }

        return lookup;
    }

    private static void scanFile(
            String language,
            String relativeFile,
            byte[] data,
            Map<Format, Map<Long, List<ValueSpec>>> lookup,
            Map<ClusterKey, Cluster> clusters
    ) {
        Format[] formats = new Format[] {
                Format.U16_LE,
                Format.U16_BE,
                Format.U24_LE,
                Format.U24_BE,
                Format.U32_LE,
                Format.U32_BE
        };

        for (int f = 0; f < formats.length; f++) {
            Format format = formats[f];
            Map<Long, List<ValueSpec>> byValue = lookup.get(format);

            if (byValue == null) {
                continue;
            }

            if (data.length < format.width) {
                continue;
            }

            for (int offset = 0; offset <= data.length - format.width; offset++) {
                long value = readValue(data, offset, format);
                List<ValueSpec> specs = byValue.get(Long.valueOf(value));

                if (specs == null) {
                    continue;
                }

                for (int i = 0; i < specs.size(); i++) {
                    ValueSpec spec = specs.get(i);

                    /*
                     * Basic sanity:
                     * ignore wildly broad repeated values in non-target files
                     * unless they are start/end refs in a local-ish transform.
                     */
                    addHitToCluster(
                            clusters,
                            language,
                            relativeFile,
                            offset,
                            format,
                            spec
                    );
                }
            }
        }
    }

    private static void addHitToCluster(
            Map<ClusterKey, Cluster> clusters,
            String language,
            String relativeFile,
            int offset,
            Format format,
            ValueSpec spec
    ) {
        int bucket = (offset / BUCKET_SIZE) * BUCKET_SIZE;

        ClusterKey key = new ClusterKey();
        key.language = language;
        key.relativeFile = relativeFile;
        key.bucket = bucket;
        key.format = format;

        Cluster cluster = clusters.get(key);

        if (cluster == null) {
            cluster = new Cluster();
            cluster.key = key;
            clusters.put(key, cluster);
        }

        cluster.hitCount++;
        cluster.lineIndices.add(Integer.valueOf(spec.lineIndex));
        cluster.kindTransforms.add(spec.kind + " " + spec.transform);

        if (cluster.sampleHits.size() < 40) {
            Hit hit = new Hit();
            hit.offset = offset;
            hit.spec = spec;
            cluster.sampleHits.add(hit);
        }
    }

    private static List<Cluster> toSortedClusterList(Map<ClusterKey, Cluster> clusters) {
        List<Cluster> list = new ArrayList<Cluster>(clusters.values());

        Collections.sort(list, new Comparator<Cluster>() {
            @Override
            public int compare(Cluster a, Cluster b) {
                int scoreDiff = b.score() - a.score();

                if (scoreDiff != 0) {
                    return scoreDiff;
                }

                int fileDiff = a.key.relativeFile.compareTo(b.key.relativeFile);

                if (fileDiff != 0) {
                    return fileDiff;
                }

                return a.key.bucket - b.key.bucket;
            }
        });

        return list;
    }

    private static void printTopClusters(PrintWriter out, String language, List<Cluster> clusters) {
        out.println("TOP " + language + " CLUSTERS");
        out.println("----------------");

        int printed = 0;

        for (Cluster cluster : clusters) {
            if (cluster.lineIndices.size() < 2) {
                continue;
            }

            if (printed >= 80) {
                out.println("Stopped after 80 clusters.");
                break;
            }

            printCluster(out, cluster);
            printed++;
        }

        if (printed == 0) {
            out.println("No clusters with at least 2 distinct line indices.");
        }

        out.println();
    }

    private static void printCluster(PrintWriter out, Cluster cluster) {
        out.println("Language:       " + cluster.key.language);
        out.println("File:           " + cluster.key.relativeFile);
        out.println("Bucket:         " + hex(cluster.key.bucket)
                + " - " + hex(cluster.key.bucket + BUCKET_SIZE - 1));
        out.println("Format:         " + cluster.key.format);
        out.println("Score:          " + cluster.score());
        out.println("Hit count:      " + cluster.hitCount);
        out.println("Distinct lines: " + cluster.lineIndices.size()
                + " " + cluster.lineIndices.toString());
        out.println("Kinds:          " + cluster.kindTransforms.toString());
        out.println("Sample hits:");

        for (int i = 0; i < cluster.sampleHits.size(); i++) {
            Hit hit = cluster.sampleHits.get(i);

            out.println(
                    "  "
                            + hex(hit.offset)
                            + " line="
                            + hit.spec.lineIndex
                            + " "
                            + hit.spec.kind
                            + " "
                            + hit.spec.transform
                            + " value="
                            + hexLong(hit.spec.value)
            );
        }

        out.println();
    }

    private static void printPossiblePairs(PrintWriter out, List<Cluster> enClusters, List<Cluster> jpClusters) {
        out.println("POSSIBLE EN/JP CLUSTER PAIRS");
        out.println("----------------------------");
        out.println("Pairs require same relative file, same format, and at least 2 overlapping line indices.");
        out.println();

        List<Pair> pairs = new ArrayList<Pair>();

        for (int i = 0; i < enClusters.size(); i++) {
            Cluster en = enClusters.get(i);

            if (en.lineIndices.size() < 2) {
                continue;
            }

            for (int j = 0; j < jpClusters.size(); j++) {
                Cluster jp = jpClusters.get(j);

                if (jp.lineIndices.size() < 2) {
                    continue;
                }

                if (!en.key.relativeFile.equals(jp.key.relativeFile)) {
                    continue;
                }

                if (en.key.format != jp.key.format) {
                    continue;
                }

                TreeSet<Integer> overlap = new TreeSet<Integer>();
                overlap.addAll(en.lineIndices);
                overlap.retainAll(jp.lineIndices);

                if (overlap.size() < 2) {
                    continue;
                }

                Pair pair = new Pair();
                pair.en = en;
                pair.jp = jp;
                pair.overlap = overlap;

                pairs.add(pair);
            }
        }

        Collections.sort(pairs, new Comparator<Pair>() {
            @Override
            public int compare(Pair a, Pair b) {
                int scoreDiff = b.score() - a.score();

                if (scoreDiff != 0) {
                    return scoreDiff;
                }

                int fileDiff = a.en.key.relativeFile.compareTo(b.en.key.relativeFile);

                if (fileDiff != 0) {
                    return fileDiff;
                }

                return Math.abs(a.en.key.bucket - a.jp.key.bucket)
                        - Math.abs(b.en.key.bucket - b.jp.key.bucket);
            }
        });

        if (pairs.size() == 0) {
            out.println("No paired clusters found.");
            out.println();
            return;
        }

        int printed = 0;

        for (int i = 0; i < pairs.size(); i++) {
            if (printed >= 60) {
                out.println("Stopped after 60 pairs.");
                break;
            }

            Pair pair = pairs.get(i);

            out.println("PAIR");
            out.println("  Score:       " + pair.score());
            out.println("  File:        " + pair.en.key.relativeFile);
            out.println("  Format:      " + pair.en.key.format);
            out.println("  Overlap:     " + pair.overlap.size() + " " + pair.overlap.toString());
            out.println("  EN bucket:   " + hex(pair.en.key.bucket)
                    + " lines=" + pair.en.lineIndices.toString()
                    + " hits=" + pair.en.hitCount);
            out.println("  JP bucket:   " + hex(pair.jp.key.bucket)
                    + " lines=" + pair.jp.lineIndices.toString()
                    + " hits=" + pair.jp.hitCount);
            out.println("  Bucket diff: " + signed(pair.jp.key.bucket - pair.en.key.bucket));
            out.println();

            out.println("  EN sample:");
            for (int h = 0; h < pair.en.sampleHits.size() && h < 12; h++) {
                Hit hit = pair.en.sampleHits.get(h);

                out.println("    "
                        + hex(hit.offset)
                        + " line="
                        + hit.spec.lineIndex
                        + " "
                        + hit.spec.kind
                        + " "
                        + hit.spec.transform
                        + " value="
                        + hexLong(hit.spec.value));
            }

            out.println("  JP sample:");
            for (int h = 0; h < pair.jp.sampleHits.size() && h < 12; h++) {
                Hit hit = pair.jp.sampleHits.get(h);

                out.println("    "
                        + hex(hit.offset)
                        + " line="
                        + hit.spec.lineIndex
                        + " "
                        + hit.spec.kind
                        + " "
                        + hit.spec.transform
                        + " value="
                        + hexLong(hit.spec.value));
            }

            out.println();

            printed++;
        }

        out.println();
    }

    private static boolean formatCanHold(Format format, long value) {
        if (value < 0) {
            return false;
        }

        if (format == Format.U16_LE || format == Format.U16_BE) {
            return value <= 0xFFFFL;
        }

        if (format == Format.U24_LE || format == Format.U24_BE) {
            return value <= 0xFFFFFFL;
        }

        if (format == Format.U32_LE || format == Format.U32_BE) {
            return value <= 0xFFFFFFFFL;
        }

        return false;
    }

    private static long readValue(byte[] data, int offset, Format format) {
        if (format == Format.U16_LE) {
            return (data[offset] & 0xFFL)
                    | ((data[offset + 1] & 0xFFL) << 8);
        }

        if (format == Format.U16_BE) {
            return ((data[offset] & 0xFFL) << 8)
                    | (data[offset + 1] & 0xFFL);
        }

        if (format == Format.U24_LE) {
            return (data[offset] & 0xFFL)
                    | ((data[offset + 1] & 0xFFL) << 8)
                    | ((data[offset + 2] & 0xFFL) << 16);
        }

        if (format == Format.U24_BE) {
            return ((data[offset] & 0xFFL) << 16)
                    | ((data[offset + 1] & 0xFFL) << 8)
                    | (data[offset + 2] & 0xFFL);
        }

        if (format == Format.U32_LE) {
            return (data[offset] & 0xFFL)
                    | ((data[offset + 1] & 0xFFL) << 8)
                    | ((data[offset + 2] & 0xFFL) << 16)
                    | ((data[offset + 3] & 0xFFL) << 24);
        }

        if (format == Format.U32_BE) {
            return ((data[offset] & 0xFFL) << 24)
                    | ((data[offset + 1] & 0xFFL) << 16)
                    | ((data[offset + 2] & 0xFFL) << 8)
                    | (data[offset + 3] & 0xFFL);
        }

        throw new RuntimeException("Unknown format: " + format);
    }

    private static void collectFiles(File dir, List<File> files) {
        File[] children = dir.listFiles();

        if (children == null) {
            return;
        }

        for (File child : children) {
            if (child.isDirectory()) {
                collectFiles(child, files);
            } else {
                String name = child.getName().toLowerCase();

                if (name.endsWith(".bak")) {
                    continue;
                }

                files.add(child);
            }
        }
    }

    private static byte[] readAll(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        byte[] data = new byte[(int) file.length()];

        int offset = 0;

        while (offset < data.length) {
            int read = in.read(data, offset, data.length - offset);

            if (read < 0) {
                break;
            }

            offset += read;
        }

        in.close();

        return data;
    }

    private static String rel(File root, File file) {
        String rootPath = root.getAbsolutePath().replace("\\", "/");
        String filePath = file.getAbsolutePath().replace("\\", "/");

        if (!rootPath.endsWith("/")) {
            rootPath = rootPath + "/";
        }

        if (filePath.startsWith(rootPath)) {
            return filePath.substring(rootPath.length());
        }

        return filePath;
    }

    private static String signed(int value) {
        if (value >= 0) {
            return "+" + value;
        }

        return String.valueOf(value);
    }

    private static String hex(int value) {
        return "0x" + Integer.toHexString(value).toUpperCase();
    }

    private static String hexLong(long value) {
        return "0x" + Long.toHexString(value).toUpperCase();
    }

    private enum Format {
        U16_LE(2),
        U16_BE(2),
        U24_LE(3),
        U24_BE(3),
        U32_LE(4),
        U32_BE(4);

        final int width;

        Format(int width) {
            this.width = width;
        }
    }

    private static class ValueSpec {
        String language;
        int lineIndex;
        String kind;
        String transform;
        long value;
        Format format;
    }

    private static class ClusterKey {
        String language;
        String relativeFile;
        int bucket;
        Format format;

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ClusterKey)) {
                return false;
            }

            ClusterKey other = (ClusterKey) obj;

            return safeEquals(language, other.language)
                    && safeEquals(relativeFile, other.relativeFile)
                    && bucket == other.bucket
                    && format == other.format;
        }

        @Override
        public int hashCode() {
            int ret = 17;

            ret = ret * 31 + (language == null ? 0 : language.hashCode());
            ret = ret * 31 + (relativeFile == null ? 0 : relativeFile.hashCode());
            ret = ret * 31 + bucket;
            ret = ret * 31 + (format == null ? 0 : format.hashCode());

            return ret;
        }

        private static boolean safeEquals(Object a, Object b) {
            if (a == null) {
                return b == null;
            }

            return a.equals(b);
        }
    }

    private static class Hit {
        int offset;
        ValueSpec spec;
    }

    private static class Cluster {
        ClusterKey key;
        int hitCount;
        TreeSet<Integer> lineIndices = new TreeSet<Integer>();
        TreeSet<String> kindTransforms = new TreeSet<String>();
        List<Hit> sampleHits = new ArrayList<Hit>();

        int score() {
            int score = 0;

            score += lineIndices.size() * 10000;
            score += kindTransforms.size() * 1000;
            score += hitCount * 10;

            if (TARGET_REL_FILE.equalsIgnoreCase(key.relativeFile)) {
                score += 5000;
            }

            if (key.bucket < 0x70000) {
                score += 500;
            }

            return score;
        }
    }

    private static class Pair {
        Cluster en;
        Cluster jp;
        TreeSet<Integer> overlap;

        int score() {
            int score = 0;

            score += overlap.size() * 20000;
            score += Math.min(en.lineIndices.size(), jp.lineIndices.size()) * 5000;
            score += en.kindTransforms.size() * 1000;
            score += jp.kindTransforms.size() * 1000;

            if (TARGET_REL_FILE.equalsIgnoreCase(en.key.relativeFile)) {
                score += 10000;
            }

            int bucketDiff = Math.abs(jp.key.bucket - en.key.bucket);

            if (bucketDiff < 0x1000) {
                score += 3000;
            }

            return score;
        }
    }
}