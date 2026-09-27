package it.poliba.sisinflab.protocowl;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.OWLParserException;
import org.semanticweb.owlapi.model.*;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;

public class BenchmarkComplianceTest {
    @Test
    public void annotationsAndPreviouslyOmittedAxiomsSurvive() throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var d = manager.getOWLDataFactory();
        var o = manager.createOntology(new OWLOntologyID(Optional.of(IRI.create("urn:test:o")), Optional.of(IRI.create("urn:test:v1"))));
        var a = d.getOWLClass(IRI.create("urn:test:A")); var b = d.getOWLClass(IRI.create("urn:test:B"));
        var c = d.getOWLClass(IRI.create("urn:test:C")); var p = d.getOWLObjectProperty(IRI.create("urn:test:p"));
        var q = d.getOWLObjectProperty(IRI.create("urn:test:q")); var dp = d.getOWLDataProperty(IRI.create("urn:test:dp"));
        var dq = d.getOWLDataProperty(IRI.create("urn:test:dq")); var i = d.getOWLNamedIndividual(IRI.create("urn:test:i"));
        var j = d.getOWLNamedIndividual(IRI.create("urn:test:j")); var ap = d.getOWLAnnotationProperty(IRI.create("urn:test:ap"));
        var dt = d.getOWLDatatype(IRI.create("urn:test:dt"));
        var nested = d.getOWLAnnotation(d.getRDFSLabel(), d.getOWLLiteral("nested"));
        var annotation = d.getOWLAnnotation(ap, IRI.create("urn:annotation:value"), Set.of(nested));
        List<OWLAxiom> axioms = List.of(
            d.getOWLDeclarationAxiom(a), d.getOWLSubClassOfAxiom(a,b), d.getOWLDisjointUnionAxiom(a,Set.of(b,c)),
            d.getOWLSubPropertyChainOfAxiom(List.of(p,q),p), d.getOWLSubDataPropertyOfAxiom(dp,dq),
            d.getOWLEquivalentDataPropertiesAxiom(dp,dq), d.getOWLDisjointDataPropertiesAxiom(dp,dq),
            d.getOWLDataPropertyDomainAxiom(dp,a), d.getOWLDataPropertyRangeAxiom(dp,d.getIntegerOWLDatatype()),
            d.getOWLFunctionalDataPropertyAxiom(dp), d.getOWLDatatypeDefinitionAxiom(dt,d.getIntegerOWLDatatype()),
            d.getOWLHasKeyAxiom(a,Set.of(p,dp)), d.getOWLNegativeObjectPropertyAssertionAxiom(p,i,j),
            d.getOWLNegativeDataPropertyAssertionAxiom(dp,i,d.getOWLLiteral("01",d.getIntegerOWLDatatype())),
            d.getOWLAnnotationAssertionAxiom(ap,a.getIRI(),IRI.create("urn:test:value")),
            d.getOWLSubAnnotationPropertyOfAxiom(ap,d.getRDFSLabel()),
            d.getOWLAnnotationPropertyDomainAxiom(ap,IRI.create("urn:domain:only")),
            d.getOWLAnnotationPropertyRangeAxiom(ap,IRI.create("urn:range:only")));
        for (OWLAxiom ax : axioms) { o.add(ax); o.add(ax.<OWLAxiom>getAnnotatedAxiom(Set.of(annotation))); }
        manager.applyChange(new AddImport(o,d.getOWLImportsDeclaration(IRI.create("urn:test:import"))));
        manager.applyChange(new AddOntologyAnnotation(o,annotation));
        var format = new ProtocOWLDocumentFormat(); format.setPrefix("test:","urn:test:");
        manager.setOntologyFormat(o, format);
        roundTrip(o);
    }

    @Test
    public void unsupportedAxiomFailsExplicitly() throws Exception {
        var m = OWLManager.createOWLOntologyManager(); var d = m.getOWLDataFactory(); var o = m.createOntology();
        o.add(d.getSWRLRule(Set.of(),Set.of()));
        try { new Renderer().render(o,new ByteArrayOutputStream(),new ProtocOWLDocumentFormat()); Assert.fail("SWRL must fail"); }
        catch (IOException e) { Assert.assertTrue(e.getMessage().contains("Unsupported axiom")); }
    }

    @DataProvider(name = "resetBits") public Object[][] resetBits() { return new Object[][]{{0},{1},{2},{3}}; }

    @Test(dataProvider = "resetBits")
    public void resetPreservesPrefixesAndAcceptsAllBitCombinations(int bits) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(1); bytes.write(Constants.FRAME_NAMESPACE_DECL | 64); bytes.write(1);
        string(bytes,"custom"); string(bytes,"urn:custom:");
        bytes.write(Constants.FRAME_RESET | (bits << 6)); bytes.write(Constants.FRAME_END);
        var ontology = OWLManager.createOWLOntologyManager().createOntology();
        var format = new Parser().parse(new ByteArrayInputStream(bytes.toByteArray()),ontology);
        Assert.assertEquals(format.getPrefix("custom:"),"urn:custom:");
    }

    @Test
    public void resetCanBeFirstFrame() throws Exception {
        var o = OWLManager.createOWLOntologyManager().createOntology();
        new Parser().parse(new ByteArrayInputStream(new byte[]{1,(byte)0xC2,3}),o);
        Assert.assertTrue(o.isEmpty());
    }

    @Test(expectedExceptions = OWLParserException.class)
    public void undeclaredIdentifierInDeclarationFailsWithParserException() throws Exception {
        var o = OWLManager.createOWLOntologyManager().createOntology();
        new Parser().parse(new ByteArrayInputStream(new byte[]{1,(byte)0x82,9,0,3}),o);
    }

    @Test
    public void functionalStageDoesNotInventDeclarations() throws Exception {
        var m = OWLManager.createOWLOntologyManager(); var d=m.getOWLDataFactory(); var o=m.createOntology();
        o.add(d.getOWLSubClassOfAxiom(d.getOWLClass(IRI.create("urn:test:A")),d.getOWLClass(IRI.create("urn:test:B"))));
        m.setOntologyFormat(o,new ProtocOWLDocumentFormat());
        Path output=Files.createTempFile("functional-lossless-", ".ofn");
        try {
            DatasetRoundTripTest.writeFunctional(o,output);
            ProtocOWLTest.assertEquals(o,ProtocOWLTest.loadOntology(output.toString(),new org.semanticweb.owlapi.functional.parser.OWLFunctionalSyntaxOWLParserFactory()));
        } finally { Files.delete(output); }
    }

    @Test
    public void fixedPointFractionIsStoredInReverse() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(1); bytes.write(Constants.FRAME_IDENTIFIER_DECL | 64); bytes.write(2);
        bytes.write(1); string(bytes,"label"); bytes.write(2); string(bytes,"float");
        bytes.write(Constants.FRAME_ANNOTATIONS); bytes.write(1);
        bytes.write(1); bytes.write(0); // annotation property #0, literal value
        bytes.write(22); bytes.write(2); bytes.write(16); bytes.write(1); // typed fixed point: 1.61, datatype #1
        bytes.write(Constants.FRAME_END);
        var manager=OWLManager.createOWLOntologyManager(); var ontology=manager.createOntology();
        new Parser().parse(new ByteArrayInputStream(bytes.toByteArray()),ontology);
        Assert.assertEquals(ontology.annotations().findFirst().orElseThrow().getValue(),
                manager.getOWLDataFactory().getOWLLiteral("1.61", org.semanticweb.owlapi.vocab.OWL2Datatype.XSD_FLOAT));
    }

    @Test
    public void integerBoundsAndLexicalFormsSurvive() throws Exception {
        var m=OWLManager.createOWLOntologyManager(); var d=m.getOWLDataFactory(); var o=m.createOntology();
        m.setOntologyFormat(o,new ProtocOWLDocumentFormat());
        for (String value : List.of("2147483647", "-2147483648", "01", "+1", " 1 "))
            o.add(d.getOWLAnnotationAssertionAxiom(d.getRDFSLabel(),IRI.create("urn:test:" + value.trim()),
                    d.getOWLLiteral(value,org.semanticweb.owlapi.vocab.OWL2Datatype.XSD_INTEGER)));
        o.add(d.getOWLAnnotationAssertionAxiom(d.getRDFSLabel(),IRI.create("urn:test:unsigned"),
                d.getOWLLiteral("4294967295",org.semanticweb.owlapi.vocab.OWL2Datatype.XSD_UNSIGNED_INT)));
        roundTrip(o);
    }

    private static void roundTrip(OWLOntology o) throws Exception {
        Path output=Files.createTempFile("compliance-", ".oprt");
        try { DatasetRoundTripTest.writeProtocOWL(o,output); ProtocOWLTest.assertEquals(o,ProtocOWLTest.loadOntology(output.toString(),new ProtocOWLParserFactory())); }
        finally { Files.delete(output); }
    }
    private static void string(OutputStream out,String s)throws IOException { byte[] data=s.getBytes(StandardCharsets.UTF_8);out.write(data.length);out.write(data); }
}
