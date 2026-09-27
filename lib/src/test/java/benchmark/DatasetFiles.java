package benchmark;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** One explicit manifest shared by coverage, round-trip tests and benchmark. Never filters missing files. */
public final class DatasetFiles {
    private DatasetFiles() {}
    public record Entry(String name, Path functional, Path standard, Path mis128) {
        public Path input(String variant) { return variant.equals("standard") ? standard : mis128; }
    }

    public static Path projectRoot() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle.kts"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Run from the protocowl-owlapi project");
        return root;
    }

    public static Path datasetRoot() {
        String configured = System.getProperty("dataset.dir", System.getenv("DATASET_DIR"));
        if (configured != null && !configured.isBlank()) return Path.of(configured).toAbsolutePath().normalize();
        Path parent = projectRoot().getParent();
        for (String name : List.of("dataset", "dataset_onto")) {
            Path path = parent.resolve(name);
            if (Files.isDirectory(path)) return path;
        }
        throw new IllegalStateException("Dataset not found. Set DATASET_DIR to its absolute path.");
    }

    public static List<Entry> entries() throws IOException { return entries(datasetRoot()); }

    public static List<Entry> entries(Path root) throws IOException {
        String configured = System.getProperty("metadata.file", System.getenv("METADATA_FILE"));
        Path metadata = configured != null && !configured.isBlank() ? Path.of(configured)
                : Files.isRegularFile(root.resolve("metadata.csv")) ? root.resolve("metadata.csv")
                : projectRoot().resolve("benchmark/metadata.csv");
        List<String> lines = Files.readAllLines(metadata);
        int column = csv(lines.getFirst()).indexOf("filename");
        if (column < 0) throw new IOException("Missing filename column: " + metadata);
        List<Entry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            String filename = Path.of(csv(line).get(column)).getFileName().toString();
            String name = baseName(filename);
            if (!names.add(name)) throw new IOException("Duplicate metadata ontology: " + name);
            Path functional = choose(root.resolve("functional"), filename, name + ".ofn", name + ".owl");
            Path standardDir = Files.isDirectory(root.resolve("protocowl/standard"))
                    ? root.resolve("protocowl/standard") : root.resolve("protocowl/std");
            entries.add(new Entry(name, functional,
                    choose(standardDir, name + ".oprt", name + "_protocowl.owl"),
                    choose(root.resolve("protocowl/MIS_128"), name + ".oprt", name + "_protocowl.owl")));
        }
        if (entries.size() != 100) throw new IOException("Expected 100 metadata ontologies, found " + entries.size());
        entries.sort(Comparator.comparing(Entry::name));
        return entries;
    }

    private static Path choose(Path directory, String... names) {
        for (String name : names) if (Files.isRegularFile(directory.resolve(name))) return directory.resolve(name);
        return directory.resolve(names[0]); // Retain a missing input so callers report it as a failure.
    }

    public static String baseName(String filename) {
        for (String suffix : List.of("_functional.owl", "_protocowl.owl", ".oprt", ".ofn")) {
            if (filename.endsWith(suffix)) return filename.substring(0, filename.length() - suffix.length());
        }
        return filename.endsWith(".owl") ? filename.substring(0, filename.length() - 4) : filename;
    }

    public static List<String> csv(String line) {
        List<String> fields = new ArrayList<>(); StringBuilder field = new StringBuilder(); boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { field.append('"'); i++; }
                else quoted = !quoted;
            } else if (c == ',' && !quoted) { fields.add(field.toString()); field.setLength(0); }
            else field.append(c);
        }
        if (quoted) throw new IllegalArgumentException("Unterminated CSV quote");
        fields.add(field.toString()); return fields;
    }

    public static void main(String[] args) throws IOException {
        List<Entry> entries = entries();
        List<String> missing = new ArrayList<>();
        for (Entry entry : entries) for (Path path : List.of(entry.functional(), entry.standard(), entry.mis128()))
            if (!Files.isRegularFile(path)) missing.add(entry.name() + ": " + path);
        if (!missing.isEmpty()) throw new IOException("Missing dataset inputs:\n" + String.join("\n", missing));
        for (Entry entry : entries)
            System.out.printf("%s\t%s\t%s\t%s%n", entry.name(), entry.functional(), entry.standard(), entry.mis128());
    }
}
