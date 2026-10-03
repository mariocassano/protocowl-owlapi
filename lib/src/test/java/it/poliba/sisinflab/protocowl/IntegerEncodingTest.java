package it.poliba.sisinflab.protocowl;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.vocab.OWL2Datatype;
import org.testng.Assert;
import org.testng.annotations.*;

/** Independent wire examples from the revised specification, not just round trips. */
public class IntegerEncodingTest {
    @DataProvider(name = "integers")
    public Object[][] integers() {
        return new Object[][] {
            {"0", "integer", 3, "00"},
            {"5", "integer", 3, "05"},
            {"-5", "integer", 4, "05"},
            {"127", "positiveInteger", 3, "7f"},
            {"128", "unsignedShort", 3, "8001"},
            {"-128", "byte", 4, "8001"},
            {"2147483647", "int", 3, "ffffffff07"},
            {"-2147483648", "int", 4, "8080808008"},
            {"4294967295", "unsignedInt", 3, "ffffffff0f"},
            {"9223372036854775807", "long", 3, "ffffffffffffffff7f"},
            {"-9223372036854775808", "long", 4, "80808080808080808001"},
            {"18446744073709551615", "unsignedLong", 3, "ffffffffffffffffff01"},
            {"1180591620717411303424", "integer", 3, "8080808080808080808001"},
            {"-1180591620717411303424", "integer", 4, "8080808080808080808001"}
        };
    }

    @Test(dataProvider = "integers")
    public void readsIndependentWireExamples(String lexical, String datatype, int tag, String hex) throws Exception {
        var o = parse(wire(datatype, tag, HexFormat.of().parseHex(hex)), new Parser());
        var literal = (OWLLiteral) o.annotations().findFirst().orElseThrow().getValue();
        Assert.assertEquals(literal.getLiteral(), lexical);
        Assert.assertEquals(literal.getDatatype().getIRI().toString(), "http://www.w3.org/2001/XMLSchema#" + datatype);
    }

    @Test(dataProvider = "integers")
    public void writesExpectedTagAndMagnitude(String lexical, String datatype, int tag, String hex) throws Exception {
        var m = OWLManager.createOWLOntologyManager(); var d = m.getOWLDataFactory();
        var o = m.createOntology(); var f = new ProtocOWLDocumentFormat(); m.setOntologyFormat(o, f);
        var literal = d.getOWLLiteral(lexical, d.getOWLDatatype(IRI.create("http://www.w3.org/2001/XMLSchema#" + datatype)));
        m.applyChange(new AddOntologyAnnotation(o, d.getOWLAnnotation(d.getRDFSLabel(), literal)));
        var output = new ByteArrayOutputStream(); new Renderer().render(o, output, f);
        byte[] actual = output.toByteArray(); byte[] magnitude = HexFormat.of().parseHex(hex);
        // The only literal ends the annotation frame, followed by datatype ID and End.
        int start = actual.length - magnitude.length - 3;
        Assert.assertEquals(actual[start] & 255, (tag << 2) | 2);
        Assert.assertEquals(Arrays.copyOfRange(actual, start + 1, start + 1 + magnitude.length), magnitude);
        ProtocOWLTest.assertEquals(o, parse(actual, new Parser()));
    }

    @Test
    public void previousIntegerRevisionRequiresExplicitSelection() throws Exception {
        var bytes = wire("integer", 3, new byte[]{9});
        Assert.assertEquals(value(parse(bytes, new Parser())), "9");
        Assert.assertEquals(value(parse(bytes, new Parser(Parser.EncodingRevision.LEGACY))), "-5");
        bytes = wire("unsignedInt", 4, new byte[]{5});
        Assert.assertEquals(value(parse(bytes, new Parser(Parser.EncodingRevision.LEGACY))), "5");
    }

    @Test
    public void decimalFormatsStillUseZigZagAndReversedFraction() throws Exception {
        Assert.assertEquals(value(parse(wire("float", 5, new byte[]{4, 40}), new Parser())), "2.04");
        Assert.assertEquals(value(parse(wire("decimal", 5, new byte[]{5, 40}), new Parser())), "-2.04");
        // -2.04E-3: signed whole=-2, reversed fraction=40, signed exponent=-3.
        var o = parse(wire("double", 6, new byte[]{3, 40, 5}), new Parser());
        Assert.assertEquals(o.annotations().findFirst().orElseThrow().getValue(),
                o.getOWLOntologyManager().getOWLDataFactory().getOWLLiteral("-2.04E-3", OWL2Datatype.XSD_DOUBLE));
    }

    @DataProvider(name = "fixedPoints")
    public Object[][] fixedPoints() {
        // Payloads include SVarInt(whole - 1) for negative values only.
        return new Object[][] {
            {"0.5", "0005"}, {"-0.5", "0105"},
            {"0.0", "0000"}, {"-0.0", "0100"},
            {"-1.0", "0300"}, {"-3.0", "0700"},
            {"2.04", "0428"}, {"-2.04", "0528"},
            {"-16.7", "2107"}, {"-64.05", "810132"}
        };
    }

    @Test(dataProvider = "fixedPoints")
    public void fixedPointNegativeWholeOffsetPreservesSign(String expected, String payload) throws Exception {
        Assert.assertEquals(value(parse(wire("decimal", 5, HexFormat.of().parseHex(payload)), new Parser())), expected);
    }

    @Test
    public void historicalFixedPointUsesExplicitLegacyRevision() throws Exception {
        Assert.assertEquals(value(parse(wire("decimal", 5, new byte[]{3, 40}),
                new Parser(Parser.EncodingRevision.LEGACY))), "-2.04");
    }

    @Test
    public void truncatedIntegerMagnitudeIsRejected() throws Exception {
        byte[] complete = wire("integer", 3, new byte[]{(byte) 128, 1});
        byte[] truncated = Arrays.copyOf(complete, complete.length - 3);
        Assert.expectThrows(IOException.class, () -> parse(truncated, new Parser()));
    }

    @Test
    public void noncanonicalIntegerLexicalFormsStayTextual() throws Exception {
        for (String lexical : List.of("01", "+1", "-0", " 1 ")) {
            var m = OWLManager.createOWLOntologyManager(); var d = m.getOWLDataFactory(); var o = m.createOntology();
            var f = new ProtocOWLDocumentFormat(); m.setOntologyFormat(o, f);
            var literal = new uk.ac.manchester.cs.owl.owlapi.OWLLiteralImplNoCompression(
                    lexical, "", d.getOWLDatatype(OWL2Datatype.XSD_LONG.getIRI()));
            Assert.assertEquals(literal.getLiteral(), lexical);
            m.applyChange(new AddOntologyAnnotation(o, d.getOWLAnnotation(d.getRDFSLabel(), literal)));
            var output = new ByteArrayOutputStream(); new Renderer().render(o, output, f);
            byte[] bytes = output.toByteArray();
            Assert.assertEquals(bytes[bytes.length - lexical.length() - 4] & 255, 2); // typed, string format
            ProtocOWLTest.assertEquals(o, parse(bytes, new Parser()));
        }
    }

    private static String value(OWLOntology o) {
        return ((OWLLiteral) o.annotations().findFirst().orElseThrow().getValue()).getLiteral();
    }

    private static OWLOntology parse(byte[] bytes, Parser parser) throws Exception {
        var m = OWLManager.createOWLOntologyManager(); var o = m.createOntology();
        m.setOntologyFormat(o, parser.parse(new ByteArrayInputStream(bytes), o)); return o;
    }

    private static byte[] wire(String datatype, int tag, byte[] payload) throws Exception {
        var bytes = new ByteArrayOutputStream();
        bytes.write(1); bytes.write(0x45); bytes.write(2); // named identifiers
        bytes.write(1); string(bytes, "label"); bytes.write(2); string(bytes, datatype);
        bytes.write(0x08); bytes.write(1); // ontology annotations
        bytes.write(1); bytes.write(0); // property ID + 1, literal value
        bytes.write((tag << 2) | 2); bytes.write(payload); bytes.write(1); bytes.write(3);
        return bytes.toByteArray();
    }

    private static void string(OutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.write(bytes.length); out.write(bytes);
    }
}
