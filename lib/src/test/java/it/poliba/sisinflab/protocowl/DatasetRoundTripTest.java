package it.poliba.sisinflab.protocowl;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.functional.parser.OWLFunctionalSyntaxOWLParserFactory;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/** Dataset-wide round-trip tests, kept separate from the fast unit tests. */
public class DatasetRoundTripTest {

    private static final String[] INPUT_VARIANTS = {"std", "MIS_128"};

    @DataProvider(name = "ontologyVariants")
    public Object[][] ontologyVariants() throws IOException {
        Path datasetRoot = resolveDatasetRoot();
        Path functionalRoot = datasetRoot.resolve("functional");
        List<Object[]> cases = new ArrayList<>();

        try (var files = Files.list(functionalRoot)) {
            files.filter(path -> path.getFileName().toString().endsWith("_functional.owl"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(functionalFile -> {
                        String baseName = stripSuffix(functionalFile.getFileName().toString(), "_functional.owl");
                        for (String variant : INPUT_VARIANTS) {
                            Path inputFile = datasetRoot.resolve("protocowl")
                                    .resolve(variant)
                                    .resolve(baseName + "_protocowl.owl");
                            if (Files.isRegularFile(inputFile)) {
                                cases.add(new Object[]{baseName, variant,
                                        functionalFile.toString(), inputFile.toString()});
                            }
                        }
                    });
        }

        Assert.assertFalse(cases.isEmpty(), "Nessuna coppia ontologia/variante trovata nel dataset");
        return cases.toArray(Object[][]::new);
    }

    @Test(dataProvider = "ontologyVariants", groups = "dataset")
    public void testOntologyVariantRoundTrip(String ontologyName, String variant,
                                              String functionalPath, String inputPath) throws Exception {
        String caseName = ontologyName + " [" + variant + "]";
        OWLOntology functional = runPhase("a", caseName, () ->
                ProtocOWLTest.loadOntology(functionalPath, new OWLFunctionalSyntaxOWLParserFactory()));
        OWLOntology parsedInput = runPhase("a", caseName, () ->
                ProtocOWLTest.loadOntology(inputPath, new ProtocOWLParserFactory()));
        
        // Assicura che parsedInput abbia caricato lo stesso numero di assiomi
        Assert.assertEquals(parsedInput.getAxiomCount(), functional.getAxiomCount(), 
                "Numero di assiomi non coincidente per " + caseName);

        runPhase("c", caseName, () -> {
            Path protocowlRoundTrip = Files.createTempFile("dataset_protocowl_", ".oprt");
            try {
                writeProtocOWL(functional, protocowlRoundTrip);
                OWLOntology reparsed = ProtocOWLTest.loadOntology(
                        protocowlRoundTrip.toString(), new ProtocOWLParserFactory());
                ProtocOWLTest.assertEquals(functional, reparsed);
            } finally {
                Files.deleteIfExists(protocowlRoundTrip);
            }
            return null;
        });
    }

    private static <T> T runPhase(String phase, String caseName, ThrowingSupplier<T> action) throws Exception {
        try {
            return action.get();
        } catch (Throwable error) {
            Assert.fail("Fase " + phase + " fallita per " + caseName + ": " + message(error), error);
            return null;
        }
    }

    private static void assertPhase(String phase, String caseName, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable error) {
            Assert.fail("Fase " + phase + " fallita per " + caseName + ": " + message(error), error);
        }
    }

    private static void writeFunctional(OWLOntology ontology, Path output)
            throws OWLOntologyStorageException, IOException {
        var manager = OWLManager.createOWLOntologyManager();
        var format = new FunctionalSyntaxDocumentFormat();
        if (ontology.getFormat() != null && ontology.getFormat().isPrefixOWLDocumentFormat()) {
            format.copyPrefixesFrom(ontology.getFormat().asPrefixOWLDocumentFormat());
        }
        try (var stream = new BufferedOutputStream(new FileOutputStream(output.toFile()))) {
            manager.saveOntology(ontology, format, stream);
        }
    }

    private static void writeProtocOWL(OWLOntology ontology, Path output)
            throws OWLOntologyStorageException, IOException {
        var manager = OWLManager.createOWLOntologyManager();
        manager.setOntologyStorers(java.util.Set.of(new ProtocOWLStorerFactory()));
        var format = new ProtocOWLDocumentFormat();
        if (ontology.getFormat() != null && ontology.getFormat().isPrefixOWLDocumentFormat()) {
            format.copyPrefixesFrom(ontology.getFormat().asPrefixOWLDocumentFormat());
        }
        try (var stream = new BufferedOutputStream(new FileOutputStream(output.toFile()))) {
            manager.saveOntology(ontology, format, stream);
        }
    }

    private static Path resolveDatasetRoot() {
        List<Path> candidates = List.of(
                Paths.get(System.getProperty("user.dir"), "..", "dataset_onto"),
                Paths.get(System.getProperty("user.dir"), "..", "..", "FLC step 2", "dataset_onto"),
                Paths.get("/Users/mariocassano/Desktop/POLIBA/FLC/FLC step 2/dataset_onto")
        );
        return candidates.stream()
                .map(Path::normalize)
                .filter(path -> Files.isDirectory(path.resolve("functional"))
                        && Files.isDirectory(path.resolve("protocowl")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Dataset non trovato: dataset_onto"));
    }

    private static String stripSuffix(String name, String suffix) {
        return name.endsWith(suffix) ? name.substring(0, name.length() - suffix.length()) : name;
    }

    private static String message(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && (current.getMessage() == null || current.getMessage().isBlank())) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}