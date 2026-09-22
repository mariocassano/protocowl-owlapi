package benchmark;

import it.poliba.sisinflab.protocowl.ProtocOWLDocumentFormat;
import it.poliba.sisinflab.protocowl.ProtocOWLParserFactory;
import it.poliba.sisinflab.protocowl.ProtocOWLStorerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.functional.parser.OWLFunctionalSyntaxOWLParserFactory;
import org.semanticweb.owlapi.io.OWLParserException;
import org.semanticweb.owlapi.io.OWLParserFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.testng.Assert;
import org.testng.annotations.Test;

public class CoverageAnalyzer {

    private static final String[] INPUT_VARIANTS = {"std", "MIS_128"};

    @Test
    public void generateCoverageReport() throws Exception {
        var optRoot = resolveDatasetRootOptional();
        if (optRoot.isEmpty()) {
            throw new org.testng.SkipException("Dataset dataset_onto non trovato: generazione coverage report saltata.");
        }
        Path datasetRoot = optRoot.get();
        Path outputCsv = Paths.get(System.getProperty("user.dir"), "coverage_report.csv").normalize();
        List<CoverageRow> rows = collectCoverageRows(datasetRoot);
        writeCsv(outputCsv, rows);
        Assert.assertTrue(Files.exists(outputCsv), "coverage_report.csv non generato");
    }

    public static void main(String[] args) throws Exception {
        Path datasetRoot = args.length > 0 ? Paths.get(args[0]) : resolveDatasetRootOptional()
                .orElseThrow(() -> new IllegalStateException("Dataset non trovato. Specificare il path come argomento."));
        Path outputCsv = args.length > 1 ? Paths.get(args[1]) : Paths.get(System.getProperty("user.dir"), "coverage_report.csv").normalize();
        List<CoverageRow> rows = collectCoverageRows(datasetRoot);
        writeCsv(outputCsv, rows);
        System.out.printf("Wrote %d coverage rows to %s%n", rows.size(), outputCsv.toAbsolutePath());
    }

    private static java.util.Optional<Path> resolveDatasetRootOptional() {
        String sysProp = System.getProperty("dataset.dir");
        List<Path> candidates = new ArrayList<>();
        if (sysProp != null && !sysProp.isBlank()) {
            candidates.add(Paths.get(sysProp));
        }
        candidates.add(Paths.get("dataset_onto"));
        candidates.add(Paths.get(System.getProperty("user.dir"), "dataset_onto"));
        candidates.add(Paths.get(System.getProperty("user.dir"), "..", "dataset_onto"));
        candidates.add(Paths.get(System.getProperty("user.dir"), "..", "..", "dataset_onto"));

        for (Path candidate : candidates) {
            Path norm = candidate.toAbsolutePath().normalize();
            if (Files.isDirectory(norm.resolve("functional")) && Files.isDirectory(norm.resolve("protocowl"))) {
                return java.util.Optional.of(norm);
            }
        }

        return java.util.Optional.empty();
    }

    private static List<CoverageRow> collectCoverageRows(Path datasetRoot) throws Exception {
        List<CoverageRow> rows = new ArrayList<>();

        try (Stream<Path> files = Files.list(datasetRoot.resolve("functional"))) {
            List<Path> functionalFiles = files
                    .filter(path -> path.getFileName().toString().endsWith("_functional.owl"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();

            for (Path functionalFile : functionalFiles) {
                String baseName = stripSuffix(functionalFile.getFileName().toString(), "_functional.owl");

                for (String variant : INPUT_VARIANTS) {
                    Path inputFile = datasetRoot.resolve("protocowl").resolve(variant).resolve(baseName + "_protocowl.owl");
                    if (Files.exists(inputFile)) {
                        rows.add(runParseCheck(baseName, variant, inputFile));
                    }
                }

                rows.add(runRenderCheck(baseName, functionalFile));
            }
        }

        rows.sort(Comparator.comparing(CoverageRow::ontology)
                .thenComparing(CoverageRow::input)
                .thenComparing(CoverageRow::operation));
        return rows;
    }

    private static CoverageRow runParseCheck(String ontology, String inputVariant, Path inputFile) {
        try {
            loadOntology(inputFile, new ProtocOWLParserFactory());
            return new CoverageRow(ontology, inputVariant, "parse", "OK", "", "");
        } catch (Exception e) {
            return new CoverageRow(ontology, inputVariant, "parse", "FAIL", inferConstruct(e), normalizeMessage(e));
        }
    }

    private static CoverageRow runRenderCheck(String ontology, Path functionalFile) {
        try {
            OWLOntology owl = loadOntology(functionalFile, new OWLFunctionalSyntaxOWLParserFactory());
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            manager.setOntologyStorers(Set.of(new ProtocOWLStorerFactory()));

            ProtocOWLDocumentFormat format = new ProtocOWLDocumentFormat();
            if (owl.getFormat() != null && owl.getFormat().isPrefixOWLDocumentFormat()) {
                format.copyPrefixesFrom(owl.getFormat().asPrefixOWLDocumentFormat());
            }

            Path tmp = Files.createTempFile("coverage_render_", ".oprt");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                manager.saveOntology(owl, format, out);
            } finally {
                Files.deleteIfExists(tmp);
            }

            return new CoverageRow(ontology, "functional", "render", "OK", "", "");
        } catch (Exception e) {
            return new CoverageRow(ontology, "functional", "render", "FAIL", inferConstruct(e), normalizeMessage(e));
        }
    }

    private static OWLOntology loadOntology(Path file, OWLParserFactory parserFactory) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        manager.setOntologyParsers(Set.of(parserFactory));
        try (InputStream in = Files.newInputStream(file)) {
            return manager.loadOntologyFromOntologyDocument(in);
        }
    }

    private static Throwable getRootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String inferConstruct(Throwable throwable) {
        throwable = getRootCause(throwable);
        if (throwable == null) {
            return "unknown";
        }

        String msg = throwable.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = throwable.toString();
        }

        String message = msg.toLowerCase();
        if (message.contains("classexpression") || message.contains("class expression") || message.contains("unsupported class") || message.contains("class expression type")) {
            return "ClassExpression";
        }
        if (message.contains("frame type") || message.contains("unrecognized frame") || message.contains("reset")) {
            return "Frame";
        }
        if (message.contains("namespace index") || message.contains("namespace")) {
            return "Namespace";
        }
        if (message.contains("identifier") || message.contains("index out of bounds")) {
            return "Identifier";
        }
        if (message.contains("annotation") || message.contains("annotation assertion")) {
            return "Annotation";
        }
        if (message.contains("literal") || message.contains("datatype")) {
            return "Literal";
        }
        if (message.contains("axiom") || message.contains("property")) {
            return "Axiom";
        }
        if (throwable instanceof OWLParserException) {
            return "Parser";
        }
        return throwable.getClass().getSimpleName();
    }

    private static String normalizeMessage(Throwable throwable) {
        throwable = getRootCause(throwable);
        if (throwable == null) {
            return "";
        }
        String msg = throwable.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = throwable.toString();
        }
        return msg.replace("\r", " ").replace("\n", " ").replace(",", ";").trim();
    }

    private static String stripSuffix(String name, String suffix) {
        return name.endsWith(suffix) ? name.substring(0, name.length() - suffix.length()) : name;
    }

    private static void writeCsv(Path outputCsv, List<CoverageRow> rows) throws Exception {
        Path parent = outputCsv.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (BufferedWriter writer = Files.newBufferedWriter(outputCsv, StandardCharsets.UTF_8)) {
            writer.write("Ontology,Input,Operazione,Esito,Costrutto,Messaggio");
            writer.newLine();
            for (CoverageRow row : rows) {
                writer.write(String.join(",",
                        csv(row.ontology()),
                        csv(row.input()),
                        csv(row.operation()),
                        csv(row.esito()),
                        csv(row.costrutto()),
                        csv(row.messaggio())));
                writer.newLine();
            }
        }
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n") || escaped.contains("\r")) {
            return '"' + escaped + '"';
        }
        return escaped;
    }

    private record CoverageRow(String ontology, String input, String operation, String esito, String costrutto, String messaggio) {
    }
}
