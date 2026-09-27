package it.poliba.sisinflab.protocowl;

import benchmark.DatasetFiles;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.functional.parser.OWLFunctionalSyntaxOWLParserFactory;
import org.semanticweb.owlapi.functional.renderer.FunctionalSyntaxObjectRenderer;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.DefaultPrefixManager;
import org.testng.Assert;
import org.testng.annotations.*;

/** Each input variant must complete a -> b -> c against the authoritative Functional input. */
public class DatasetRoundTripTest {
    private final Map<String, Long> renderedSizes = new ConcurrentSkipListMap<>();

    @DataProvider(name = "ontologyVariants")
    public Object[][] ontologyVariants() throws IOException {
        List<Object[]> cases = new ArrayList<>();
        for (DatasetFiles.Entry entry : DatasetFiles.entries()) {
            for (String variant : List.of("standard", "MIS_128")) {
                cases.add(new Object[]{entry.name(), variant, entry.functional(), entry.input(variant)});
            }
        }
        Assert.assertEquals(cases.size(), 200, "Expected all 100 ontologies and both input variants");
        return cases.toArray(Object[][]::new);
    }

    @Test(dataProvider = "ontologyVariants", groups = "dataset")
    public void testOntologyVariantRoundTrip(String name, String variant, Path functionalPath, Path inputPath) throws Exception {
        String label = name + " [" + variant + "]";
        OWLOntology original = phase("a", label, () -> ProtocOWLTest.loadOntology(
                functionalPath.toString(), new OWLFunctionalSyntaxOWLParserFactory()));
        OWLOntology parsed = phase("a", label, () -> {
            OWLOntology result = ProtocOWLTest.loadOntology(inputPath.toString(), new ProtocOWLParserFactory());
            ProtocOWLTest.assertEquals(original, result);
            return result;
        });
        OWLOntology functional = phase("b", label, () -> {
            Path output = Files.createTempFile("dataset-functional-", ".ofn");
            try {
                writeFunctional(parsed, output);
                OWLOntology result = ProtocOWLTest.loadOntology(output.toString(), new OWLFunctionalSyntaxOWLParserFactory());
                ProtocOWLTest.assertEquals(original, result);
                return result;
            } finally { Files.deleteIfExists(output); }
        });
        phase("c", label, () -> {
            Path output = Files.createTempFile("dataset-protocowl-", ".oprt");
            try {
                writeProtocOWL(functional, output);
                OWLOntology result = ProtocOWLTest.loadOntology(output.toString(), new ProtocOWLParserFactory());
                ProtocOWLTest.assertEquals(original, result);
                renderedSizes.put(name + "," + variant, Files.size(output));
            } finally { Files.deleteIfExists(output); }
            return null;
        });
    }

    @AfterClass(alwaysRun = true)
    public void writeSizeReport() throws IOException {
        Path output = DatasetFiles.projectRoot().resolve("roundtrip_sizes.csv");
        List<String> lines = new ArrayList<>(List.of("Ontology,InputVariant,OutputSizeBytes"));
        renderedSizes.forEach((key, size) -> lines.add(key + "," + size));
        Files.write(output, lines);
    }

    private static <T> T phase(String phase, String label, CheckedSupplier<T> action) throws Exception {
        try { return action.get(); }
        catch (Exception | AssertionError error) {
            Assert.fail("Phase " + phase + " failed for " + label + ": " + error.getMessage(), error);
            return null;
        }
    }

    static void writeFunctional(OWLOntology ontology, Path output) throws IOException {
        // OWLAPI's defaults add declarations and synthesize a default prefix. Neither is
        // appropriate for a lossless test: keep the source's declarations and prefix map.
        try (Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            var renderer = new FunctionalSyntaxObjectRenderer(ontology, writer);
            var prefixes = new DefaultPrefixManager();
            prefixes.clear();
            prefixes.copyPrefixesFrom(ontology.getNonnullFormat().asPrefixOWLDocumentFormat());
            renderer.setPrefixManager(prefixes);
            renderer.setAddMissingDeclarations(false);
            ontology.accept(renderer);
        }
    }

    static void writeProtocOWL(OWLOntology ontology, Path output) throws IOException, OWLOntologyStorageException {
        var manager = OWLManager.createOWLOntologyManager();
        manager.setOntologyStorers(Set.of(new ProtocOWLStorerFactory()));
        var format = new ProtocOWLDocumentFormat();
        format.copyPrefixesFrom(ontology.getNonnullFormat().asPrefixOWLDocumentFormat());
        try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(output))) {
            manager.saveOntology(ontology, format, stream);
        }
    }

    @FunctionalInterface private interface CheckedSupplier<T> { T get() throws Exception; }
}
