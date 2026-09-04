package it.poliba.sisinflab.protocowl;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.functional.parser.OWLFunctionalSyntaxOWLParserFactory;
import org.semanticweb.owlapi.io.OWLParserException;
import org.semanticweb.owlapi.io.OWLParserFactory;
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

    private void assertEquals(OWLOntology in, OWLOntology out) {
        // 1. Controlla se gli ID delle ontologie sono uguali (incluso il Version IRI)
        Assert.assertEquals(in.isNamed(), out.isNamed());
        if (in.isNamed()) {
            Assert.assertEquals(in.getOntologyID().getOntologyIRI(), out.getOntologyID().getOntologyIRI());
            // RAFFORZAMENTO: Controllo del Version IRI 
            Assert.assertEquals(in.getOntologyID().getVersionIRI(), out.getOntologyID().getVersionIRI());
        }

        // 2. Controlla se i prefissi sono uguali 
        var inFormat = in.getNonnullFormat().asPrefixOWLDocumentFormat();
        var outFormat = out.getNonnullFormat().asPrefixOWLDocumentFormat();
        Assert.assertEquals(inFormat.getPrefixName2PrefixMap(), outFormat.getPrefixName2PrefixMap());

        // 3. Controllo delle Annotazioni dell'Ontologia 
        var annA = in.annotations().collect(Collectors.toSet());
        var annB = out.annotations().collect(Collectors.toSet());
        Assert.assertEquals(annA, annB, "Le annotazioni dell'ontologia non coincidono!");

        // 4. Controlla se gli assiomi sono uguali (senza considerare l'ordine)
        var axiomsA = in.axioms().collect(Collectors.toSet());
        var axiomsB = out.axioms().collect(Collectors.toSet());
        Assert.assertEquals(axiomsA, axiomsB, "Gli assiomi non coincidono!");
    }

    private OWLOntology loadOntology(String filePath, OWLParserFactory parser) throws OWLOntologyCreationException {
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

    @Test(expectedExceptions = OWLParserException.class)
    public void testUnsupportedVersion() throws Exception {
        File badFile = File.createTempFile("bad_version", ".oprt");
        try (FileOutputStream fos = new FileOutputStream(badFile)) {
            fos.write(99); // Versione 99 (non supportata)
            fos.write(Constants.FRAME_END);
        }
        loadOntology(badFile.getAbsolutePath(), new ProtocOWLParserFactory());
    }

    @Test(expectedExceptions = OWLParserException.class)
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