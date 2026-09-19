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
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ProtocOWLTest {

    // Metodi helper per generare i percorsi dei file dinamicamente
    private String getOwlPath(String name) { return "src/test/resources/" + name + ".owl"; }
    private String getOprtPath(String name) { return "src/test/resources/" + name + ".oprt"; }
    private String getOutputPath(String name) { return "build/out_" + name + ".oprt"; }

    static void assertEquals(OWLOntology in, OWLOntology out) {
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

        // 2. Controlla se i prefissi sono uguali 
        var inFormat = in.getNonnullFormat().asPrefixOWLDocumentFormat();
        var outFormat = out.getNonnullFormat().asPrefixOWLDocumentFormat();
        Assert.assertEquals(inFormat.getPrefixName2PrefixMap(), outFormat.getPrefixName2PrefixMap(), diagnostic);

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

    @Test(expectedExceptions = OWLOntologyCreationException.class)
    public void testResetClearsNamespacesAndIdentifiers() throws Exception {
        File resetFile = File.createTempFile("reset_mappings", ".oprt");
        try (FileOutputStream fos = new FileOutputStream(resetFile)) {
            fos.write(Constants.PROTOCOWL_VERSION);

            fos.write(Constants.FRAME_NAMESPACE_DECL);
            writeVarInt(fos, 1);
            writeString(fos, "http://example.org/");

            fos.write(Constants.FRAME_IDENTIFIER_DECL | (1 << 6));
            writeVarInt(fos, 1);
            writeVarInt(fos, 5);
            writeString(fos, "ontology");

            fos.write(Constants.FRAME_RESET | (3 << 6));

            fos.write(Constants.FRAME_ONTOLOGY_IRI);
            writeVarInt(fos, 0);
        }

        loadOntology(resetFile.getAbsolutePath(), new ProtocOWLParserFactory());
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
}