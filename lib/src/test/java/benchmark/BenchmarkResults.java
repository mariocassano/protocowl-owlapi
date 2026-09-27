package benchmark;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Validate the complete benchmark, including the output sizes measured during phase 2. */
public final class BenchmarkResults {
    public static final String HEADER = "Task,Format,Ontology,TimeMs,InputSizeBytes,OutputSizeBytes,MRSS_KB,CompressionRatioVsProtocOWL,SpaceSavingVsProtocOWL";
    private BenchmarkResults() {}

    public static void main(String[] args) throws IOException {
        validate(Path.of(args[0]), Path.of(args[1]), DatasetFiles.entries());
    }

    static void validate(Path csv, Path sizesCsv, List<DatasetFiles.Entry> entries) throws IOException {
        Map<String, DatasetFiles.Entry> byName = new HashMap<>();
        Set<String> expected = new TreeSet<>();
        for (var entry : entries) {
            byName.put(entry.name(), entry);
            for (String format : List.of("Functional", "ProtocOWL", "ProtocOWL_128")) {
                expected.add("parse," + format + "," + entry.name());
                if (!format.equals("ProtocOWL_128")) expected.add("render," + format + "," + entry.name());
            }
        }
        Map<String, Long> standardSizes = new HashMap<>();
        Set<String> sizeKeys = new HashSet<>();
        List<String> errors = new ArrayList<>();
        List<String> sizeLines = Files.readAllLines(sizesCsv);
        for (String line : sizeLines.subList(1, sizeLines.size())) {
            List<String> f = DatasetFiles.csv(line);
            if (f.size() != 3 || !sizeKeys.add(f.get(0) + "," + f.get(1))) {
                errors.add("Invalid/duplicate round-trip size row: " + line); continue;
            }
            if (f.get(1).equals("standard")) standardSizes.put(f.get(0), Long.parseLong(f.get(2)));
        }
        for (var entry : entries) for (String variant : List.of("standard", "MIS_128"))
            if (!sizeKeys.contains(entry.name() + "," + variant)) errors.add("Missing phase-2 result: " + entry.name() + "," + variant);
        List<String> lines = Files.readAllLines(csv);
        if (lines.isEmpty() || !lines.getFirst().equals(HEADER)) errors.add("Incorrect benchmark CSV header");
        if (lines.size() != expected.size() + 1) errors.add("Expected " + expected.size() + " data rows; found " + (lines.size() - 1));
        Set<String> seen = new HashSet<>();
        for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
            List<String> f = DatasetFiles.csv(line);
            if (f.size() != 9) { errors.add("Invalid CSV row: " + line); continue; }
            String task = f.get(0), format = f.get(1), name = f.get(2);
            String key = String.join(",", f.subList(0, 3));
            if (!seen.add(key)) errors.add("Duplicate: " + key);
            if (!expected.contains(key)) { errors.add("Unexpected combination: " + key); continue; }
            var entry = byName.get(name);
            Path input = switch (format) {
                case "Functional" -> entry.functional(); case "ProtocOWL" -> entry.standard(); default -> entry.mis128();
            };
            try {
                long inputSize = Files.size(input), standardSize = Files.size(entry.standard());
                long outputSize = Long.parseLong(f.get(5));
                double time = Double.parseDouble(f.get(3));
                if (!Double.isFinite(time) || time < 0 || Long.parseLong(f.get(6)) <= 0) errors.add("Invalid time/MRSS: " + key);
                if (Long.parseLong(f.get(4)) != inputSize) errors.add("Input size mismatch: " + key);
                if (task.equals("parse") && outputSize != 0 || task.equals("render") && outputSize <= 0) errors.add("Invalid output size: " + key);
                if (task.equals("render") && format.equals("ProtocOWL") && !Objects.equals(standardSizes.get(name), outputSize))
                    errors.add("Output size differs from phase 2: " + key + " (" + outputSize + " vs " + standardSizes.get(name) + ")");
                double ratio = Double.parseDouble(f.get(7)), saving = Double.parseDouble(f.get(8));
                if (!Double.isFinite(ratio) || !Double.isFinite(saving)
                        || Math.abs(ratio - (double) inputSize / standardSize) > 0.000001
                        || Math.abs(saving - (double) (inputSize - standardSize) / inputSize) > 0.000001)
                    errors.add("Incorrect dimensional metrics: " + key);
            } catch (NumberFormatException ex) { errors.add("Non-numeric metric: " + key); }
        }
        expected.removeAll(seen);
        expected.forEach(key -> errors.add("MISSING: " + key));
        if (!errors.isEmpty()) throw new IOException(String.join("\n", errors));
        System.out.println("Benchmark verified: " + seen.size() + " rows, all expected combinations and phase-2 sizes match.");
    }
}
