package it.poliba.sisinflab.protocowl;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.OWLParserException;
import org.semanticweb.owlapi.model.*;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class CodecRobustnessTest {
    @DataProvider(name = "invalidIndexes")
    public Object[][] invalidIndexes() {
        return new Object[][]{{2147483648L}, {4294967296L}, {Long.MAX_VALUE}};
    }

    @Test(dataProvider = "invalidIndexes")
    public void resetCannotWrapUndeclaredIndexToZero(long index) throws Exception {
        var bytes = new ByteArrayOutputStream();
        bytes.write(1); bytes.write(Constants.FRAME_RESET | 128);
        bytes.write(Constants.FRAME_IDENTIFIER_DECL | 64); varint(bytes, 1);
        varint(bytes, 1); string(bytes, "new"); // Only identifier 0 exists.
        bytes.write(Constants.FRAME_ONTOLOGY_IRI); varint(bytes, index);
        bytes.write(Constants.FRAME_END);
        Assert.expectThrows(OWLParserException.class, () -> parse(bytes));
    }

    @Test(dataProvider = "invalidIndexes")
    public void namespaceCountsAndStringLengthsCannotOverflow(long value) throws Exception {
        for (boolean length : List.of(false, true)) {
            var bytes = new ByteArrayOutputStream();
            bytes.write(1); bytes.write(Constants.FRAME_NAMESPACE_DECL);
            if (length) varint(bytes, 1);
            varint(bytes, value);
            Assert.expectThrows(OWLParserException.class, () -> parse(bytes));
        }
    }

    @Test
    public void truncatedHugeStringDoesNotAllocateItsDeclaredSize() throws Exception {
        var bytes = new ByteArrayOutputStream();
        bytes.write(1); bytes.write(Constants.FRAME_NAMESPACE_DECL); varint(bytes, 1);
        varint(bytes, Integer.MAX_VALUE); bytes.write('x');
        Assert.expectThrows(OWLParserException.class, () -> parse(bytes));
    }

    @Test
    public void overlongAndTruncatedStructuralVarintsFailExplicitly() throws Exception {
        for (byte[] encoding : List.of(new byte[]{(byte) 128},
                new byte[]{(byte) 128, (byte) 128, (byte) 128, (byte) 128, (byte) 128, 0})) {
            var bytes = new ByteArrayOutputStream();
            bytes.write(1); bytes.write(Constants.FRAME_NAMESPACE_DECL); bytes.write(encoding);
            Assert.expectThrows(OWLParserException.class, () -> parse(bytes));
        }
    }

    @Test
    public void maximumOwlCardinalitiesSurvivePacking() throws Exception {
        var m = OWLManager.createOWLOntologyManager(); var d = m.getOWLDataFactory();
        var o = m.createOntology(); var a = d.getOWLClass(IRI.create("urn:bounds:A"));
        var p = d.getOWLObjectProperty(IRI.create("urn:bounds:p"));
        var dp = d.getOWLDataProperty(IRI.create("urn:bounds:dp"));
        int max = Integer.MAX_VALUE;
        for (var filler : List.of(a, d.getOWLThing())) {
            o.add(d.getOWLSubClassOfAxiom(a, d.getOWLObjectMinCardinality(max, p, filler)));
            o.add(d.getOWLSubClassOfAxiom(a, d.getOWLObjectMaxCardinality(max, p, filler)));
            o.add(d.getOWLSubClassOfAxiom(a, d.getOWLObjectExactCardinality(max, p, filler)));
        }
        for (var filler : List.of(d.getIntegerOWLDatatype(), d.getTopDatatype())) {
            o.add(d.getOWLSubClassOfAxiom(a, d.getOWLDataMinCardinality(max, dp, filler)));
            o.add(d.getOWLSubClassOfAxiom(a, d.getOWLDataMaxCardinality(max, dp, filler)));
            o.add(d.getOWLSubClassOfAxiom(a, d.getOWLDataExactCardinality(max, dp, filler)));
        }
        m.setOntologyFormat(o, new ProtocOWLDocumentFormat());
        roundTrip(o);
    }

    @Test
    public void preservesExplicitAliasesUnusedPrefixesAndLongUtf8Strings() throws Exception {
        var m = OWLManager.createOWLOntologyManager(); var d = m.getOWLDataFactory();
        var o = m.createOntology(IRI.create("urn:prefix:o"));
        var f = new ProtocOWLDocumentFormat();
        f.setPrefix(":", "https://example.org/sample/sample.owl#");
        f.setPrefix("alias:", "https://example.org/sample/sample.owl#");
        f.setPrefix("unused:", "urn:unused:");
        m.setOntologyFormat(o, f);
        o.add(d.getOWLAnnotationAssertionAxiom(d.getRDFSLabel(), IRI.create("urn:subject"),
                d.getOWLLiteral("è字".repeat(5000))));
        roundTrip(o); // Must not invent "sample:" from the namespace path.
    }

    @Test
    public void namedOntologyWithoutDefaultPrefixSurvivesProtocowlRoundTrip() throws Exception {
        var m = OWLManager.createOWLOntologyManager(); var o = m.createOntology(IRI.create("urn:prefix:no-default"));
        m.setOntologyFormat(o, new ProtocOWLDocumentFormat());
        roundTrip(o);
    }

    @Test
    public void prefixComparatorRejectsMissingExtraAndChangedBindings() throws Exception {
        var leftManager = OWLManager.createOWLOntologyManager();
        var rightManager = OWLManager.createOWLOntologyManager();
        var iri = IRI.create("urn:prefix:strict");
        var left = leftManager.createOntology(iri); var right = rightManager.createOntology(iri);
        var a = new ProtocOWLDocumentFormat(); var b = new ProtocOWLDocumentFormat();
        leftManager.setOntologyFormat(left, a); rightManager.setOntologyFormat(right, b);
        b.setPrefix(":", iri + "#");
        Assert.expectThrows(AssertionError.class, () -> ProtocOWLTest.assertEquals(left, right));
        Assert.expectThrows(AssertionError.class, () -> ProtocOWLTest.assertEquals(right, left));
        a.setPrefix(":", "urn:different:");
        Assert.expectThrows(AssertionError.class, () -> ProtocOWLTest.assertEquals(left, right));
        a.setPrefix(":", iri + "#");
        ProtocOWLTest.assertEquals(left, right);
    }

    @Test
    public void unrepresentableReservedAliasFailsBeforeWriting() throws Exception {
        var m = OWLManager.createOWLOntologyManager(); var o = m.createOntology();
        var f = new ProtocOWLDocumentFormat();
        f.setPrefix("aliasowl:", "http://www.w3.org/2002/07/owl#");
        var bytes = new ByteArrayOutputStream();
        IOException error = Assert.expectThrows(IOException.class, () -> new Renderer().render(o, bytes, f));
        Assert.assertTrue(error.getMessage().contains("aliasowl:"));
        Assert.assertEquals(bytes.size(), 0);
    }

    @Test
    public void missingImplicitPrefixesFailBeforeWriting() throws Exception {
        var m = OWLManager.createOWLOntologyManager(); var o = m.createOntology();
        var f = new ProtocOWLDocumentFormat(); f.clear();
        var bytes = new ByteArrayOutputStream();
        Assert.expectThrows(IOException.class, () -> new Renderer().render(o, bytes, f));
        Assert.assertEquals(bytes.size(), 0);
    }

    private static void parse(ByteArrayOutputStream bytes) throws Exception {
        new Parser().parse(new ByteArrayInputStream(bytes.toByteArray()),
                OWLManager.createOWLOntologyManager().createOntology());
    }

    private static void roundTrip(OWLOntology ontology) throws Exception {
        Path output = Files.createTempFile("codec-robustness-", ".oprt");
        try {
            DatasetRoundTripTest.writeProtocOWL(ontology, output);
            ProtocOWLTest.assertEquals(ontology, ProtocOWLTest.loadOntology(output.toString(), new ProtocOWLParserFactory()));
        } finally { Files.delete(output); }
    }

    private static void varint(OutputStream out, long value) throws IOException {
        do {
            int b = (int) (value & 127); value >>>= 7;
            out.write(b | (value == 0 ? 0 : 128));
        } while (value != 0);
    }

    private static void string(OutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); varint(out, bytes.length); out.write(bytes);
    }
}
