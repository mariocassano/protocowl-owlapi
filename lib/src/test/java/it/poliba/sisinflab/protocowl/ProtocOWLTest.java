package it.poliba.sisinflab.protocowl;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.functional.parser.OWLFunctionalSyntaxOWLParserFactory;
import org.semanticweb.owlapi.io.OWLParserFactory;
import org.semanticweb.owlapi.model.*;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ProtocOWLTest {

    // Metodi helper per generare i percorsi dei file dinamicamente
    private String getOwlPath(String name) { return "src/test/resources/" + name + ".owl"; }
    private String getOprtPath(String name) { return "src/test/resources/" + name + ".oprt"; }
    private String getOutputPath(String name) { return "build/out_" + name + ".oprt"; }

    public static void assertEquals(OWLOntology in, OWLOntology out) {
        var axiomsA = in.axioms().collect(Collectors.toSet());
        var axiomsB = out.axioms().collect(Collectors.toSet());
        String diagnostic = comparisonDiagnostic(in, out, axiomsA, axiomsB);

        // 1. Controlla se gli ID delle ontologie sono uguali (incluso il Version IRI)
        Assert.assertEquals(in.isNamed(), out.isNamed(), diagnostic);
        if (in.isNamed()) {
            Assert.assertEquals(in.getOntologyID().getOntologyIRI(), out.getOntologyID().getOntologyIRI(), diagnostic);
            // RAFFORZAMENTO: Controllo del Version IRI 
            Assert.assertEquals(in.getOntologyID().getVersionIRI(), out.getOntologyID().getVersionIRI(), diagnostic);
        }

        // 2. Controlla se i prefissi sono uguali (normalizzando il prefisso di default se derivato dall'Ontology IRI)
        var inFormat = in.getNonnullFormat().asPrefixOWLDocumentFormat();
        var outFormat = out.getNonnullFormat().asPrefixOWLDocumentFormat();
        var inMap = new java.util.HashMap<>(inFormat.getPrefixName2PrefixMap());
        var outMap = new java.util.HashMap<>(outFormat.getPrefixName2PrefixMap());
        if (in.isNamed() && in.getOntologyID().getOntologyIRI().isPresent()) {
            String defaultNs = in.getOntologyID().getOntologyIRI().get() + "#";
            if (defaultNs.equals(outMap.get(":")) && !inMap.containsKey(":")) {
                inMap.put(":", defaultNs);
            }
            if (defaultNs.equals(inMap.get(":")) && !outMap.containsKey(":")) {
                outMap.put(":", defaultNs);
            }
        }
        Assert.assertEquals(inMap, outMap, diagnostic);

        // 3. Controllo delle Annotazioni dell'Ontologia 
        var annA = in.annotations().collect(Collectors.toSet());
        var annB = out.annotations().collect(Collectors.toSet());
        Assert.assertEquals(annA, annB, "Le annotazioni dell'ontologia non coincidono!\n" + diagnostic);

        // 4. Controlla se gli assiomi sono uguali (senza considerare l'ordine)
        Assert.assertEquals(axiomsA, axiomsB, "Gli assiomi non coincidono!\n" + diagnostic);
    }

    private static String comparisonDiagnostic(OWLOntology in, OWLOntology out,
                                               Set<OWLAxiom> axiomsA, Set<OWLAxiom> axiomsB) {
        Set<OWLAxiom> onlyInOriginal = new LinkedHashSet<>(axiomsA);
        onlyInOriginal.removeAll(axiomsB);

        Set<OWLAxiom> onlyInReloaded = new LinkedHashSet<>(axiomsB);
        onlyInReloaded.removeAll(axiomsA);

        return "Differenza semantica:" + System.lineSeparator()
                + "  Assiomi solo nell'originale (" + onlyInOriginal.size() + "):"
                + formatAxioms(onlyInOriginal)
                + "  Assiomi solo nella versione riletta (" + onlyInReloaded.size() + "):"
                + formatAxioms(onlyInReloaded)
                + "  Ontology ID originale: " + in.getOntologyID() + System.lineSeparator()
                + "  Ontology ID riletta: " + out.getOntologyID();
    }

    private static String formatAxioms(Set<OWLAxiom> axioms) {
        if (axioms.isEmpty()) {
            return " nessuno" + System.lineSeparator();
        }
        return System.lineSeparator() + axioms.stream()
                .map(axiom -> "    " + axiom)
                .sorted()
                .collect(Collectors.joining(System.lineSeparator(), "", System.lineSeparator()));
    }

    static OWLOntology loadOntology(String filePath, OWLParserFactory parser) throws OWLOntologyCreationException {
        var manager = OWLManager.createOWLOntologyManager();
        manager.setOntologyParsers(Set.of(parser));
        try (var stream = new BufferedInputStream(new FileInputStream(filePath))) {
            return manager.loadOntologyFromOntologyDocument(stream);
        } catch (IOException e) {
            throw new OWLOntologyCreationException(e);
        }
    }

    private void writeOntology(OWLOntology ontology, String filePath) throws OWLOntologyStorageException {
        var manager = OWLManager.createOWLOntologyManager();
        manager.setOntologyStorers(Set.of(new ProtocOWLStorerFactory()));
        var inFormat = ontology.getNonnullFormat().asPrefixOWLDocumentFormat();
        var format = new ProtocOWLDocumentFormat();
        format.copyPrefixesFrom(inFormat);

        var file = new File(filePath);
        file.getParentFile().mkdirs();

        try (var stream = new BufferedOutputStream(new FileOutputStream(file))) {
            manager.saveOntology(ontology, format, stream);
        } catch (IOException e) {
            throw new OWLOntologyStorageException(e);
        }
    }

    // ========================================================================
    // METODI DI TEST PARAMETRIZZATI
    // ========================================================================

    private void executeRoundTripTest(String ontologyName) throws OWLOntologyCreationException, OWLOntologyStorageException {
        // 1. Parser Test (Confronta .owl con .oprt di riferimento)
        var func = loadOntology(getOwlPath(ontologyName), new OWLFunctionalSyntaxOWLParserFactory());
        var oprt = loadOntology(getOprtPath(ontologyName), new ProtocOWLParserFactory());
        assertEquals(func, oprt);

        // 2. Renderer Test (Scrive in .oprt e rilegge)
        String outPath = getOutputPath(ontologyName);
        writeOntology(func, outPath);
        var reloadedOprt = loadOntology(outPath, new ProtocOWLParserFactory());
        assertEquals(func, reloadedOprt);
    }

    @Test
    public void testHomeOntology() throws Exception {
        executeRoundTripTest("home");
    }

    @Test
    public void testPizzaOntology() throws Exception {
        executeRoundTripTest("pizza");
    }

    /**
     * Valida la serializzazione e il round-trip di espressioni di dati complesse in cui una
     * restrizione con facet (es. minInclusive) e annidata all'interno di un complemento di data range.
     * Questo test assicura che Renderer registri ricorsivamente tutti i facet e datatype interni.
     */
    @Test
    public void testNestedDataRangeWithFacetRestrictionInComplement() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var df = manager.getOWLDataFactory();

        IRI ontIRI = IRI.create("http://example.org/nested-datarange");
        OWLOntology ontology = manager.createOntology(ontIRI);

        var format = new org.semanticweb.owlapi.formats.PrefixDocumentFormatImpl();
        format.setDefaultPrefix("http://example.org/nested-datarange#");
        format.setPrefix("xsd:", "http://www.w3.org/2001/XMLSchema#");
        manager.setOntologyFormat(ontology, format);

        OWLClass cls = df.getOWLClass(IRI.create("http://example.org/nested-datarange#Item"));
        OWLDataProperty prop = df.getOWLDataProperty(IRI.create("http://example.org/nested-datarange#hasValue"));

        // Datatype restriction: xsd:integer con minInclusive 5
        OWLFacetRestriction facet = df.getOWLFacetRestriction(
                org.semanticweb.owlapi.vocab.OWLFacet.MIN_INCLUSIVE,
                df.getOWLLiteral(5)
        );
        OWLDataRange restriction = df.getOWLDatatypeRestriction(df.getIntegerOWLDatatype(), facet);

        // DataComplementOf(DatatypeRestriction(xsd:integer, minInclusive 5))
        OWLDataRange complement = df.getOWLDataComplementOf(restriction);

        // Assioma: SubClassOf(Item, DataSomeValuesFrom(hasValue, complement))
        OWLClassExpression someValues = df.getOWLDataSomeValuesFrom(prop, complement);
        OWLAxiom axiom = df.getOWLSubClassOfAxiom(cls, someValues);
        manager.addAxiom(ontology, axiom);

        // Verifica che la serializzazione e il reload non lancino eccezioni e preservino l'assioma
        File tempOprt = File.createTempFile("nested_datarange", ".oprt");
        try {
            writeOntology(ontology, tempOprt.getAbsolutePath());
            OWLOntology reloaded = loadOntology(tempOprt.getAbsolutePath(), new ProtocOWLParserFactory());
            assertEquals(ontology, reloaded);
        } finally {
            tempOprt.delete();
        }
    }

    // ========================================================================
    // TEST NEGATIVI
    // ========================================================================

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        do {
            int b = value & 0x7F;
            value >>>= 7;
            if (value != 0) {
                b |= 0x80;
            }
            out.write(b);
        } while (value != 0);
    }

    private static void writeString(OutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    @Test
    public void testResetNamespacesOnlyKeepsIdentifiersAndRestartsNamespaceIndexes() throws Exception {
        File resetFile = createResetFile(1, true, true, true);
        try {
            OWLOntology ontology = loadOntology(resetFile.getAbsolutePath(), new ProtocOWLParserFactory());
            Assert.assertEquals(
                    ontology.getOntologyID().getOntologyIRI().orElseThrow().toString(),
                    "http://example.org/new/new");
        } finally {
            resetFile.delete();
        }
    }

    @Test
    public void testResetIdentifiersOnlyRestartsIdentifierIndexes() throws Exception {
        File resetFile = createResetFile(2, false, true, true);
        try {
            OWLOntology ontology = loadOntology(resetFile.getAbsolutePath(), new ProtocOWLParserFactory());
            Assert.assertEquals(
                    ontology.getOntologyID().getOntologyIRI().orElseThrow().toString(),
                    "http://example.org/new");
        } finally {
            resetFile.delete();
        }
    }

    @Test
    public void testResetNamespacesAndIdentifiersRestartsBothIndexes() throws Exception {
        File resetFile = createResetFile(3, true, true, true);
        try {
            OWLOntology ontology = loadOntology(resetFile.getAbsolutePath(), new ProtocOWLParserFactory());
            Assert.assertEquals(
                    ontology.getOntologyID().getOntologyIRI().orElseThrow().toString(),
                    "http://example.org/new/new");
        } finally {
            resetFile.delete();
        }
    }

    @Test(expectedExceptions = OWLOntologyCreationException.class)
    public void testResetRejectsUndeclaredNamespaceReference() throws Exception {
        File resetFile = createResetFile(1, false, true, true);
        try {
            loadOntology(resetFile.getAbsolutePath(), new ProtocOWLParserFactory());
        } finally {
            resetFile.delete();
        }
    }

    @Test(expectedExceptions = OWLOntologyCreationException.class)
    public void testResetRejectsUndeclaredIdentifierReference() throws Exception {
        File resetFile = createResetFile(2, false, false, false);
        try {
            loadOntology(resetFile.getAbsolutePath(), new ProtocOWLParserFactory());
        } finally {
            resetFile.delete();
        }
    }

    private static File createResetFile(int utility, boolean redeclareNamespace,
                                         boolean redeclareIdentifier, boolean useNewIdentifier)
            throws IOException {
        File resetFile = File.createTempFile("reset_mappings", ".oprt");
        try (FileOutputStream fos = new FileOutputStream(resetFile)) {
            fos.write(Constants.PROTOCOWL_VERSION);

            fos.write(Constants.FRAME_NAMESPACE_DECL);
            writeVarInt(fos, 1);
            writeString(fos, "http://example.org/");

            fos.write(Constants.FRAME_IDENTIFIER_DECL | (1 << 6));
            writeVarInt(fos, 1);
            writeVarInt(fos, 5);
            writeString(fos, "old");

            fos.write(Constants.FRAME_RESET | (utility << 6));

            if ((utility & 1) != 0 && redeclareNamespace) {
                fos.write(Constants.FRAME_NAMESPACE_DECL);
                writeVarInt(fos, 1);
                writeString(fos, "http://example.org/new/");
            }

            if ((utility & 1) != 0 && !redeclareNamespace && !redeclareIdentifier) {
                fos.write(Constants.FRAME_IDENTIFIER_DECL | (1 << 6));
                writeVarInt(fos, 1);
                writeVarInt(fos, 5);
                writeString(fos, "invalid");
            }

            if (redeclareIdentifier) {
                fos.write(Constants.FRAME_IDENTIFIER_DECL | (1 << 6));
                writeVarInt(fos, 1);
                writeVarInt(fos, 5);
                writeString(fos, useNewIdentifier ? "new" : "invalid");
            }

            fos.write(Constants.FRAME_ONTOLOGY_IRI);
            writeVarInt(fos, (utility & 2) != 0 ? 0 : 1);
        }
        return resetFile;
    }

    @Test(expectedExceptions = OWLOntologyCreationException.class)
    public void testUnsupportedVersion() throws Exception {
        File badFile = File.createTempFile("bad_version", ".oprt");
        try (FileOutputStream fos = new FileOutputStream(badFile)) {
            fos.write(99); // Versione 99 (non supportata)
            fos.write(Constants.FRAME_END);
        }
        loadOntology(badFile.getAbsolutePath(), new ProtocOWLParserFactory());
    }

    @Test(expectedExceptions = OWLOntologyCreationException.class)
    public void testUnsupportedFrame() throws Exception {
        File badFile = File.createTempFile("bad_frame", ".oprt");
        try (FileOutputStream fos = new FileOutputStream(badFile)) {
            fos.write(Constants.PROTOCOWL_VERSION);
            fos.write(0x3F);
        }
        loadOntology(badFile.getAbsolutePath(), new ProtocOWLParserFactory());
    }

    @Test(expectedExceptions = OWLOntologyCreationException.class)
    public void testTruncatedInput() throws Exception {
        File badFile = File.createTempFile("truncated", ".oprt");
        try (FileOutputStream fos = new FileOutputStream(badFile)) {
            fos.write(1); // Versione 1
            fos.write(Constants.FRAME_NAMESPACE_DECL); // Dichiariamo un namespace
            fos.write(5); // dichiaro un prefisso di 5 byte
            // ma interrompo il file qui (Input troncato)
        }
        loadOntology(badFile.getAbsolutePath(), new ProtocOWLParserFactory());
    }

    /**
     * Verifica che la presenza di un frame FRAME_END moderno interrompa correttamente la lettura
     * anche quando lo stream sottostante contiene ulteriori byte bufferizzati/residui.
     */
    @Test
    public void testModernFrameEndFollowedByTrailingBytes() throws Exception {
        File testFile = File.createTempFile("frame_end_trailing", ".oprt");
        try {
            try (FileOutputStream fos = new FileOutputStream(testFile)) {
                fos.write(Constants.PROTOCOWL_VERSION);
                // Dichiara un namespace
                fos.write(Constants.FRAME_NAMESPACE_DECL);
                writeVarInt(fos, 1);
                writeString(fos, "http://example.org/test/");
                // Frame END
                fos.write(Constants.FRAME_END);
                // Byte residui / padding nello stream (che non devono essere parsati come frame legacy)
                fos.write(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05});
            }
            OWLOntology ont = loadOntology(testFile.getAbsolutePath(), new ProtocOWLParserFactory());
            Assert.assertNotNull(ont);
        } finally {
            testFile.delete();
        }
    }

    /**
     * Verifica che un file moderno che inizia direttamente con un frame di controllo (es. FRAME_END)
     * non attivi erroneamente il dialetto legacy.
     */
    @Test
    public void testModernControlFrameAsFirstFrame() throws Exception {
        File testFile = File.createTempFile("first_control_frame", ".oprt");
        try {
            try (FileOutputStream fos = new FileOutputStream(testFile)) {
                fos.write(Constants.PROTOCOWL_VERSION);
                // Il primo frame del payload e direttamente FRAME_END
                fos.write(Constants.FRAME_END);
                fos.write(new byte[]{0x00, 0x00}); // Byte extra
            }
            OWLOntology ont = loadOntology(testFile.getAbsolutePath(), new ProtocOWLParserFactory());
            Assert.assertNotNull(ont);
        } finally {
            testFile.delete();
        }
    }
}