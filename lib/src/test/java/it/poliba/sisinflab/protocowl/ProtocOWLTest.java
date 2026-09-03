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
import org.semanticweb.owlapi.io.OWLParserFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ProtocOWLTest {
    private static final String FUNC_ONTOLOGY = "src/test/resources/home.owl";
    private static final String OPRT_ONTOLOGY = "src/test/resources/home.oprt";
    private static final String OUTPUT_FILE = "build/out.oprt";

    private void assertEquals(OWLOntology in, OWLOntology out) {
        // Check if the ontology IDs are equal.
        Assert.assertEquals(in.isNamed(), out.isNamed());
        if (in.isNamed()) Assert.assertEquals(in.getOntologyID(), out.getOntologyID());

        // Check if the prefixes are equal.
        var inFormat = in.getNonnullFormat().asPrefixOWLDocumentFormat();
        var outFormat = out.getNonnullFormat().asPrefixOWLDocumentFormat();
        Assert.assertEquals(inFormat.getPrefixName2PrefixMap(), outFormat.getPrefixName2PrefixMap());

        // Check if the axioms are equal.
        var axiomsA = in.axioms().collect(Collectors.toSet());
        var axiomsB = out.axioms().collect(Collectors.toSet());
        Assert.assertEquals(axiomsA, axiomsB);
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

    @Test
    public void parserTest() throws OWLOntologyCreationException {
        var func = loadOntology(FUNC_ONTOLOGY, new OWLFunctionalSyntaxOWLParserFactory());
        var oprt = loadOntology(OPRT_ONTOLOGY, new ProtocOWLParserFactory());
        assertEquals(func, oprt);
    }

    @Test
    public void rendererTest() throws OWLOntologyCreationException, OWLOntologyStorageException {
        var func = loadOntology(FUNC_ONTOLOGY, new OWLFunctionalSyntaxOWLParserFactory());
        writeOntology(func, OUTPUT_FILE);
        var oprt = loadOntology(OUTPUT_FILE, new ProtocOWLParserFactory());
        assertEquals(func, oprt);
    }
}
