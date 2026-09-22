package it.poliba.sisinflab.protocowl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.semanticweb.owlapi.io.OWLParserException;
import org.semanticweb.owlapi.model.*;
import uk.ac.manchester.cs.owl.owlapi.OWLDataFactoryImpl;

class Parser {

    private final OWLDataFactory dataFactory = new OWLDataFactoryImpl();

    // Mantiene la tabella dei namespace e la tabella degli identificatori referenziati per indice.
    private final List<String> namespaces = new ArrayList<>();
    private final List<OWLObject> identifiers = new ArrayList<>();
    private boolean isLegacyDialect = false;

    /**
     * Punto di ingresso del parser.
     * Decodifica lo stream ProtocOWL e popola l'ontologia fornita.
     */
    private static class CountingInputStream extends InputStream {
        private final InputStream in;
        private long count = 0;

        public CountingInputStream(InputStream in) {
            this.in = in;
        }

        public long getCount() { return count; }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b != -1) count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }

        @Override
        public int available() throws IOException {
            return in.available();
        }
    }

    ProtocOWLDocumentFormat parse(InputStream rawStream, OWLOntology ontology) throws OWLParserException, IOException {
        CountingInputStream stream = (rawStream instanceof CountingInputStream cis) ? cis : new CountingInputStream(rawStream);
        var format = new ProtocOWLDocumentFormat();

        // Inizializza i namespace riservati prima di leggere i frame del payload.
        initReservedNamespaces(format);

        // Verifica la compatibilita della versione del protocollo.
        parseVersion(stream);

        boolean firstFrame = true;

        // Legge i frame fino a fine stream.
        int header;
        while ((header = stream.read()) != -1) {
            long framePos = stream.getCount() - 1;
            // Estrae tipo frame (6 bit bassi) e utility flag (2 bit alti).
            int type = header & 0x3F;
            int utility = header >> 6;

            if (firstFrame) {
                firstFrame = false;
                // Il dialetto legacy si riconosce dalla presenza degli opcodes 0x2A (namespace),
                // 0x2B (identificatori) o 0x2C (ontology IRI) in testa al payload.
                // I codici 0x00..0x03 non devono mai attivare la modalita legacy poiche rappresentano
                // i frame di controllo standard della specifica moderna (ADD, REMOVE, RESET, END).
                if (type == 0x2A || type == 0x2B || type == 0x2C) {
                    isLegacyDialect = true;
                }
            }

            // Gestione della terminazione dello stream:
            // Nel formato moderno, il frame FRAME_END (0x03) indica incondizionatamente la fine del flusso/documento.
            if (!isLegacyDialect && type == Constants.FRAME_END) break;
            if (isLegacyDialect && type == 0x03 && utility == 0 && stream.available() == 0) break;

            logFrame(String.format("FRAME at pos %d (avail %d): 0x%02X (type=%d, util=%d)",
                    framePos, stream.available(), header, type, utility));

            // Instrada il frame al parser specializzato.
            parseFrame(type, utility, stream, ontology, format);
        }

        return format;
    }

    /**
     * Carica i namespace riservati del protocollo e sincronizza i prefissi nel formato documento.
     */
    private void initReservedNamespaces(ProtocOWLDocumentFormat format) {
        namespaces.add("http://www.w3.org/1999/02/22-rdf-syntax-ns#"); // ID 0
        namespaces.add("http://www.w3.org/2000/01/rdf-schema#");       // ID 1
        namespaces.add("http://www.w3.org/2001/XMLSchema#");           // ID 2
        namespaces.add("http://www.w3.org/2002/07/owl#");              // ID 3
        namespaces.add("http://www.w3.org/XML/1998/namespace");        // ID 4

        // Registra i prefissi noti per preservare una serializzazione coerente in output.
        initReservedPrefixes(format);
    }

    private void initReservedPrefixes(ProtocOWLDocumentFormat format) {
        format.setPrefix("rdf:", namespaces.get(0));
        format.setPrefix("rdfs:", namespaces.get(1));
        format.setPrefix("xsd:", namespaces.get(2));
        format.setPrefix("owl:", namespaces.get(3));
        format.setPrefix("xml:", namespaces.get(4));
    }

    /**
     * Legge e valida la versione del file ProtocOWL.
     */
    private void parseVersion(InputStream stream) throws IOException {
        int version = stream.read();
        if (version != Constants.PROTOCOWL_VERSION) {
            throw new OWLParserException("Unsupported ProtocOWL version: " + version);
        }
    }

    private static final java.util.List<String> lastFrames = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static long byteOffset = 0;

    private void parseFrame(int type, int utility, InputStream stream, OWLOntology ontology, ProtocOWLDocumentFormat format) throws IOException {
        String msg = String.format("FRAME: isLegacy=%b, type=0x%02X (%d), utility=%d, avail=%d", isLegacyDialect, type, type, utility, stream.available());
        lastFrames.add(msg);
        if (lastFrames.size() > 50) lastFrames.remove(0);
        try {
            if (isLegacyDialect) {
                parseLegacyFrame(type, utility, stream, ontology, format);
            } else {
                parseModernFrame(type, utility, stream, ontology, format);
            }
        } catch (Throwable t) {
            System.err.println("=== LAST 20 FRAMES BEFORE ERROR ===");
            for (String f : lastFrames.subList(Math.max(0, lastFrames.size() - 20), lastFrames.size())) {
                System.err.println("  " + f);
            }
            System.err.println("=== LAST 5 AXIOMS BEFORE ERROR ===");
            for (OWLAxiom a : lastAxioms.subList(Math.max(0, lastAxioms.size() - 5), lastAxioms.size())) {
                System.err.println("  " + a);
            }
            throw t;
        }
    }

    private final java.util.List<String> lastDebugLogs = new java.util.ArrayList<>();

    private void logFrame(String msg) {
        if (lastDebugLogs.size() > 50) {
            lastDebugLogs.remove(0);
        }
        lastDebugLogs.add(msg);
    }

    private void parseLegacyFrame(int type, int utility, InputStream stream, OWLOntology ontology, ProtocOWLDocumentFormat format) throws IOException {
        logFrame(String.format("LEGACY FRAME: 0x%02X (%d), utility=%d, avail=%d, axioms=%d", type, type, utility, stream.available(), ontology.getAxiomCount()));
        switch (type) {
            case 0x2A: // FRAME_NAMESPACE_DECL legacy
                parseNamespaceDeclaration(stream, utility, format);
                break;
            case 0x2B: // FRAME_IDENTIFIER_DECL legacy
                parseIdentifierDeclaration(stream, utility);
                break;
            case 0x2C: // FRAME_ONTOLOGY_IRI legacy
                parseOntologyIRI(stream, utility, ontology);
                break;

            case 0x00: // FRAME_CLASS_DECL legacy
                parseEntityDeclaration(Constants.FRAME_CLASS_DECL, stream, ontology);
                break;
            case 0x01: // FRAME_DATATYPE_DECL legacy
                parseEntityDeclaration(Constants.FRAME_DATATYPE_DECL, stream, ontology);
                break;
            case 0x02: // FRAME_OBJ_PROP_DECL legacy
                parseEntityDeclaration(Constants.FRAME_OBJ_PROP_DECL, stream, ontology);
                break;
            case 0x03: // FRAME_DATA_PROP_DECL legacy
                parseEntityDeclaration(Constants.FRAME_DATA_PROP_DECL, stream, ontology);
                break;
            case 0x04: // FRAME_ANNOTATION_PROP_DECL legacy
                parseEntityDeclaration(Constants.FRAME_ANNOTATION_PROP_DECL, stream, ontology);
                break;
            case 0x05: // FRAME_NAMED_IND_DECL legacy
                parseEntityDeclaration(Constants.FRAME_NAMED_IND_DECL, stream, ontology);
                break;
            case 0x06: // FRAME_SUBCLASS_OF legacy
                parseSubClassOf(stream, ontology);
                break;
            case 0x07: // FRAME_EQUIVALENT_CLASSES legacy
                parseEquivalentClasses(stream, ontology);
                break;
            case 0x08: // FRAME_DISJOINT_CLASSES legacy
                parseDisjointClasses(stream, ontology);
                break;
            case 0x09: // FRAME_DISJOINT_UNION legacy
                parseDisjointUnion(stream, ontology);
                break;
            case 0x0A: // FRAME_SUB_OBJ_PROP legacy
                parseSubObjectPropertyOf(stream, utility, ontology);
                break;
            case 0x0B: // FRAME_EQUIVALENT_OBJ_PROPS legacy
                parseEquivalentObjectProperties(stream, ontology);
                break;
            case 0x0C: // FRAME_DISJOINT_OBJ_PROPS legacy
                parseDisjointObjectProperties(stream, ontology);
                break;
            case 0x0D: // FRAME_INVERSE_OBJ_PROP legacy
                parseInverseObjectProperties(stream, ontology);
                break;
            case 0x0E: // FRAME_OBJ_PROP_DOMAIN legacy
                parseObjectPropertyDomain(stream, ontology);
                break;
            case 0x0F: // FRAME_OBJ_PROP_RANGE legacy
                parseObjectPropertyRange(stream, ontology);
                break;
            case 0x10: // FRAME_FUNCTIONAL_OBJ_PROP legacy
                ontology.add(dataFactory.getOWLFunctionalObjectPropertyAxiom(
                        parseObjectPropertyExpression(stream)));
                break;
            case 0x11: // FRAME_INVERSE_FUNCTIONAL_OBJ_PROP legacy
                ontology.add(dataFactory.getOWLInverseFunctionalObjectPropertyAxiom(
                        parseObjectPropertyExpression(stream)));
                break;
            case 0x12: // FRAME_REFLEXIVE_OBJ_PROP legacy
                ontology.add(dataFactory.getOWLReflexiveObjectPropertyAxiom(
                        parseObjectPropertyExpression(stream)));
                break;
            case 0x13: // FRAME_IRREFLEXIVE_OBJ_PROP legacy
                ontology.add(dataFactory.getOWLIrreflexiveObjectPropertyAxiom(
                        parseObjectPropertyExpression(stream)));
                break;
            case 0x14: // FRAME_SYMMETRIC_OBJ_PROP legacy
                ontology.add(dataFactory.getOWLSymmetricObjectPropertyAxiom(
                        parseObjectPropertyExpression(stream)));
                break;
            case 0x15: // FRAME_ASYMMETRIC_OBJ_PROP legacy
                ontology.add(dataFactory.getOWLAsymmetricObjectPropertyAxiom(
                        parseObjectPropertyExpression(stream)));
                break;
            case 0x16: // FRAME_TRANSITIVE_OBJ_PROP legacy
                ontology.add(dataFactory.getOWLTransitiveObjectPropertyAxiom(
                        parseObjectPropertyExpression(stream)));
                break;
            case 0x17: // FRAME_SUB_DATA_PROP legacy
                parseSubDataPropertyOf(stream, ontology);
                break;
            case 0x18: // FRAME_EQUIVALENT_DATA_PROPS legacy
                parseEquivalentDataProperties(stream, ontology);
                break;
            case 0x19: // FRAME_DISJOINT_DATA_PROPS legacy
                parseDisjointDataProperties(stream, ontology);
                break;
            case 0x1A: // FRAME_DATA_PROP_DOMAIN legacy
            case 0x23:
                parseDataPropertyDomain(stream, ontology);
                break;
            case 0x1B: // FRAME_DATA_PROP_RANGE legacy
            case 0x24:
                parseDataPropertyRange(stream, ontology);
                break;
            case 0x1C: // FRAME_FUNCTIONAL_DATA_PROP legacy
            case 0x25:
                parseFunctionalDataProperty(stream, ontology);
                break;
            case 0x1D: // FRAME_DATA_TYPE_DEFINITION legacy
            case 0x26:
                parseDatatypeDefinition(stream, ontology);
                break;
            case 0x1E: // FRAME_HAS_KEY legacy
            case 0x27:
                parseHasKey(stream, ontology);
                break;
            case 0x1F: // FRAME_SAME_INDIVIDUAL legacy
            case 0x28:
                parseSameIndividuals(stream, ontology);
                break;
            case 0x20: // FRAME_DIFFERENT_INDIVIDUALS legacy
            case 0x29:
                parseDifferentIndividuals(stream, ontology);
                break;
            case 0x21: // FRAME_CLASS_ASSERTION legacy
                parseClassAssertion(stream, ontology);
                break;
            case 0x22: // FRAME_OBJ_PROP_ASSERTION legacy
                parseObjectPropertyAssertion(stream, ontology);
                break;
            case 0x2D: // FRAME_DATA_PROP_ASSERTION
                parseDataPropertyAssertion(stream, ontology);
                break;
            case 0x2E: // FRAME_NEG_DATA_PROP_ASSERTION
                parseNegativeDataPropertyAssertion(stream, ontology);
                break;
            case 0x2F: // FRAME_ANNOTATION_ASSERTION
                parseAnnotationAssertion(stream, ontology);
                break;
            case 0x30: // FRAME_SUB_ANNOTATION_PROP
                parseSubAnnotationPropertyOf(stream, ontology);
                break;
            default:
                parseModernFrame(type, utility, stream, ontology, format);
                break;
        }
    }

    private void parseModernFrame(int type, int utility, InputStream stream, OWLOntology ontology, ProtocOWLDocumentFormat format) throws IOException {
        logFrame(String.format("MODERN/FALLBACK FRAME: 0x%02X (%d), utility=%d, avail=%d, axioms=%d", type, type, utility, stream.available(), ontology.getAxiomCount()));
        switch (type) {
            case Constants.FRAME_END:
                return;
            case Constants.FRAME_IMPORTS:
                parseImports(stream, ontology);
                break;
            case Constants.FRAME_ANNOTATIONS:
                parseAnnotations(stream, ontology);
                break;
            case Constants.FRAME_NAMESPACE_DECL:
                parseNamespaceDeclaration(stream, utility, format);
                break;
            case Constants.FRAME_RESET:
                parseReset(utility, format);
                break;
            case Constants.FRAME_IDENTIFIER_DECL:
                parseIdentifierDeclaration(stream, utility);
                break;
            case Constants.FRAME_ONTOLOGY_IRI:
                parseOntologyIRI(stream, utility, ontology);
                break;
            case Constants.FRAME_CLASS_DECL:
            case Constants.FRAME_DATATYPE_DECL:
            case Constants.FRAME_OBJ_PROP_DECL:
            case Constants.FRAME_DATA_PROP_DECL:
            case Constants.FRAME_ANNOTATION_PROP_DECL:
            case Constants.FRAME_NAMED_IND_DECL:
                parseEntityDeclaration(type, stream, ontology);
                break;
            case Constants.FRAME_SUBCLASS_OF:
                parseSubClassOf(stream, ontology);
                break;
            case Constants.FRAME_SUB_OBJ_PROP:
                parseSubObjectPropertyOf(stream, utility, ontology);
                break;
            case Constants.FRAME_EQUIVALENT_OBJ_PROPS:
                parseEquivalentObjectProperties(stream, ontology);
                break;
            case Constants.FRAME_DISJOINT_OBJ_PROPS:
                parseDisjointObjectProperties(stream, ontology);
                break;
            case Constants.FRAME_INVERSE_OBJ_PROP:
                parseInverseObjectProperties(stream, ontology);
                break;
            case Constants.FRAME_OBJ_PROP_DOMAIN:
                parseObjectPropertyDomain(stream, ontology);
                break;
            case Constants.FRAME_OBJ_PROP_RANGE:
                parseObjectPropertyRange(stream, ontology);
                break;
            case Constants.FRAME_FUNCTIONAL_OBJ_PROP:
                ontology.add(dataFactory.getOWLFunctionalObjectPropertyAxiom(
                        parseObjectPropertyExpression(stream)));
                break;
            case Constants.FRAME_INVERSE_FUNCTIONAL_OBJ_PROP:
                ontology.add(dataFactory.getOWLInverseFunctionalObjectPropertyAxiom(
                        parseObjectPropertyExpression(stream)));
                break;
            case Constants.FRAME_REFLEXIVE_OBJ_PROP:
                ontology.add(dataFactory.getOWLReflexiveObjectPropertyAxiom(
                        parseObjectPropertyExpression(stream)));
                break;
            case Constants.FRAME_IRREFLEXIVE_OBJ_PROP:
                ontology.add(dataFactory.getOWLIrreflexiveObjectPropertyAxiom(
                        parseObjectPropertyExpression(stream)));
                break;
            case Constants.FRAME_SYMMETRIC_OBJ_PROP:
                ontology.add(dataFactory.getOWLSymmetricObjectPropertyAxiom(
                        parseObjectPropertyExpression(stream)));
                break;
            case Constants.FRAME_ASYMMETRIC_OBJ_PROP:
                ontology.add(dataFactory.getOWLAsymmetricObjectPropertyAxiom(
                        parseObjectPropertyExpression(stream)));
                break;
            case Constants.FRAME_TRANSITIVE_OBJ_PROP:
                ontology.add(dataFactory.getOWLTransitiveObjectPropertyAxiom(
                        parseObjectPropertyExpression(stream)));
                break;
            case Constants.FRAME_SAME_INDIVIDUAL:
                parseSameIndividuals(stream, ontology);
                break;
            case Constants.FRAME_DIFFERENT_INDIVIDUALS:
                parseDifferentIndividuals(stream, ontology);
                break;
            case Constants.FRAME_EQUIVALENT_CLASSES:
                parseEquivalentClasses(stream, ontology);
                break;
            case Constants.FRAME_DISJOINT_CLASSES:
                parseDisjointClasses(stream, ontology);
                break;
            case Constants.FRAME_DISJOINT_UNION:
                parseDisjointUnion(stream, ontology);
                break;
            case Constants.FRAME_SUB_DATA_PROP:
                parseSubDataPropertyOf(stream, ontology);
                break;
            case Constants.FRAME_EQUIVALENT_DATA_PROPS:
                parseEquivalentDataProperties(stream, ontology);
                break;
            case Constants.FRAME_DISJOINT_DATA_PROPS:
                parseDisjointDataProperties(stream, ontology);
                break;
            case Constants.FRAME_DATA_PROP_DOMAIN:
                parseDataPropertyDomain(stream, ontology);
                break;
            case Constants.FRAME_DATA_PROP_RANGE:
                parseDataPropertyRange(stream, ontology);
                break;
            case Constants.FRAME_FUNCTIONAL_DATA_PROP:
                parseFunctionalDataProperty(stream, ontology);
                break;
            case Constants.FRAME_DATA_TYPE_DEFINITION:
                parseDatatypeDefinition(stream, ontology);
                break;
            case Constants.FRAME_HAS_KEY:
                parseHasKey(stream, ontology);
                break;
            case Constants.FRAME_CLASS_ASSERTION:
                parseClassAssertion(stream, ontology);
                break;
            case Constants.FRAME_OBJ_PROP_ASSERTION:
                parseObjectPropertyAssertion(stream, ontology);
                break;
            case Constants.FRAME_NEG_OBJ_PROP_ASSERTION:
                parseNegativeObjectPropertyAssertion(stream, ontology);
                break;
            case Constants.FRAME_DATA_PROP_ASSERTION:
                parseDataPropertyAssertion(stream, ontology);
                break;
            case Constants.FRAME_NEG_DATA_PROP_ASSERTION:
                parseNegativeDataPropertyAssertion(stream, ontology);
                break;
            case Constants.FRAME_ANNOTATION_ASSERTION:
                parseAnnotationAssertion(stream, ontology);
                break;
            case Constants.FRAME_SUB_ANNOTATION_PROP:
                parseSubAnnotationPropertyOf(stream, ontology);
                break;
            default:
                for (String log : lastDebugLogs) {
                    System.err.println("TRACE: " + log);
                }
                throw new OWLParserException("Unsupported or unrecognized frame type: " + type + " (0x" + Integer.toHexString(type) + ", utility=" + utility + ", avail=" + stream.available() + ")");
        }
    }

    // ========================================================================
    // PARSING DEI FRAME BASE
    // ========================================================================

    private void parseReset(int utility, ProtocOWLDocumentFormat format) {
        boolean resetNamespaces = (utility & 0x01) != 0;
        boolean resetIdentifiers = (utility & 0x02) != 0;

        if (resetNamespaces) {
            while (namespaces.size() > 5) {
                namespaces.remove(namespaces.size() - 1);
            }
            format.clear();
            initReservedPrefixes(format);
        }

        if (resetIdentifiers) {
            identifiers.clear();
        }
    }

    private void parseNamespaceDeclaration(InputStream stream, int utility, ProtocOWLDocumentFormat format) throws IOException {
        int count = readVarInt(stream);
        // Utility bit 0: 1 indica presenza esplicita del prefisso.
        boolean prefixed = (utility & 0x01) != 0;

        for (int i = 0; i < count; i++) {
            if (prefixed) {
                String prefix = readString(stream);
                String namespace = readString(stream);
                namespaces.add(namespace);
                String normalizedPrefix = prefix.isEmpty() ? ":" : prefix;
                format.setPrefix(normalizedPrefix, namespace);
                addConventionalAlias(format, normalizedPrefix, namespace);
            } else {
                String namespace = readString(stream);
                namespaces.add(namespace);
            }
        }
    }

    private void addConventionalAlias(ProtocOWLDocumentFormat format, String prefix, String namespace) {
        if (!prefix.equals(":")) return;

        String path = namespace.endsWith("#") ? namespace.substring(0, namespace.length() - 1) : namespace;
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 1) return;

        String fileName = path.substring(lastSlash + 1);
        if (!fileName.endsWith(".owl")) return;

        String alias = fileName.substring(0, fileName.length() - 4);
        String parent = path.substring(0, lastSlash);
        int parentSlash = parent.lastIndexOf('/');
        if (parentSlash < 0 || !parent.substring(parentSlash + 1).equals(alias)) return;

        format.setPrefix(alias + ":", namespace);
    }

    private void parseIdentifierDeclaration(InputStream stream, int utility) throws IOException {
        int count = readVarInt(stream);
        // Bit 0 di utility distingue IRI (1) da individui anonimi (0) sia in dialetto moderno che legacy.
        boolean isIRI = (utility & 0x01) != 0;

        for (int i = 0; i < count; i++) {
            if (isIRI) {
                int nsIndex = readVarInt(stream);
                String remainder = readString(stream);
                if (nsIndex < 0 || nsIndex >= namespaces.size()) {
                    throw new OWLParserException("Invalid namespace index: " + nsIndex);
                }
                IRI iri = IRI.create(namespaces.get(nsIndex) + remainder);
                identifiers.add(iri);
            } else {
                String nodeId = readString(stream);
                identifiers.add(dataFactory.getOWLAnonymousIndividual(nodeId));
            }
        }
    }

    private void parseOntologyIRI(InputStream stream, int utility, OWLOntology ontology) throws IOException {
        int iriId = readVarInt(stream);
        IRI ontologyIRI = (IRI) getIdentifier(iriId);
        IRI versionIRI = null;

        // Utility bit 1: se presente la versione, il parser legge l'IRI della versione.
        if ((utility & 0x01) != 0) {
            int versionId = readVarInt(stream);
            versionIRI = (IRI) getIdentifier(versionId);
        }

        // Aggiorna l'ontology ID con l'IRI e la versione (se presente).
        OWLOntologyID ontId;
        if (versionIRI != null) {
            ontId = new OWLOntologyID(ontologyIRI, versionIRI);
        } else {
            ontId = new OWLOntologyID(ontologyIRI);
        }

        // Aggiorna l'OntologyID per mantenere coerenti API e test basati su isNamed().
        if (ontology.getOWLOntologyManager() != null) {
            ontology.getOWLOntologyManager().applyChange(new SetOntologyID(ontology, ontId));
        }
    }


    private void parseImports(InputStream stream, OWLOntology ontology) throws IOException {
        int count = readVarInt(stream);
        for (int i = 0; i < count; i++) {
            int id = readVarInt(stream);
            IRI importIri = (IRI) getIdentifier(id);
            OWLImportsDeclaration importDecl = dataFactory.getOWLImportsDeclaration(importIri);
            ontology.applyChange(new AddImport(ontology, importDecl));
        }
    }

    private void parseAnnotations(InputStream stream, OWLOntology ontology) throws IOException {
        int count = readVarInt(stream);
        for (int i = 0; i < count; i++) {
            OWLAnnotation annotation = parseAnnotation(stream);
            ontology.applyChange(new AddOntologyAnnotation(ontology, annotation));
        }
    }

     // Metodo helper per leggere una singola annotazione (Sezione 4.6 del PDF)
    private OWLAnnotation parseAnnotation(InputStream stream) throws IOException {
        int header = readVarInt(stream);
        List<OWLAnnotation> annotations = new ArrayList<>();
        
        // Se header == 0, l'annotazione è a sua volta annotata
        if (header == 0) {
            int count = readVarInt(stream);
            for (int i = 0; i < count; i++) {
                annotations.add(parseAnnotation(stream));
            }
            header = readVarInt(stream); // Leggiamo l'header vero e proprio
        }
        
        OWLAnnotationProperty ap = dataFactory.getOWLAnnotationProperty((IRI) getIdentifier(header - 1));
        OWLAnnotationValue value = parseAnnotationValue(stream);
        return dataFactory.getOWLAnnotation(ap, value, annotations.stream());
    }

    // Metodo helper per leggere il valore di un'annotazione
    private OWLAnnotationValue parseAnnotationValue(InputStream stream) throws IOException {
        int header = readVarInt(stream);
        if (header > 0) {
            OWLObject obj = getIdentifier(header - 1);
            if (obj instanceof IRI) return (IRI) obj;
            if (obj instanceof OWLAnonymousIndividual) return (OWLAnonymousIndividual) obj;
            throw new OWLParserException("Valore annotazione non valido");
        } else {
            return parseLiteral(stream);
        }
    }   

    private void parseEntityDeclaration(int type, InputStream stream, OWLOntology ontology) throws IOException {
        int id = readVarInt(stream);
        OWLObject entity = identifiers.get(id);
        OWLEntity owlEntity = null;

        // Converte l'identificatore in OWLEntity solo quando l'elemento e un IRI.
        if (entity instanceof IRI iri) {
            switch (type) {
                case Constants.FRAME_CLASS_DECL: owlEntity = dataFactory.getOWLClass(iri); break;
                case Constants.FRAME_DATATYPE_DECL: owlEntity = dataFactory.getOWLDatatype(iri); break;
                case Constants.FRAME_OBJ_PROP_DECL: owlEntity = dataFactory.getOWLObjectProperty(iri); break;
                case Constants.FRAME_DATA_PROP_DECL: owlEntity = dataFactory.getOWLDataProperty(iri); break;
                case Constants.FRAME_ANNOTATION_PROP_DECL: owlEntity = dataFactory.getOWLAnnotationProperty(iri); break;
                case Constants.FRAME_NAMED_IND_DECL: owlEntity = dataFactory.getOWLNamedIndividual(iri); break;
            }
        }

        if (owlEntity != null) {
            ontology.add(dataFactory.getOWLDeclarationAxiom(owlEntity));
        }
    }

    private static final java.util.List<OWLAxiom> lastAxioms = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final java.util.List<String> actiniumTrace = new java.util.ArrayList<>();
    static boolean debugClassExpr = false;

    private void parseSubClassOf(InputStream stream, OWLOntology ontology) throws IOException {
        OWLClassExpression subClass = parseClassExpression(stream);
        boolean isActinium = subClass.toString().contains("Actinium");
        if (isActinium) {
            actiniumTrace.add("=== START PARSING SUPERCLASS OF ACTINIUM ===");
            debugClassExpr = true;
        }
        OWLClassExpression superClass;
        try {
            superClass = parseClassExpression(stream);
        } finally {
            if (isActinium) {
                debugClassExpr = false;
                try {
                    java.nio.file.Files.write(java.nio.file.Paths.get("actinium_trace.txt"), actiniumTrace);
                } catch (Exception e) {}
            }
        }
        if (isActinium) {
            actiniumTrace.add("=== FINISHED PARSING SUPERCLASS OF ACTINIUM ===");
            actiniumTrace.add("RESULT: " + superClass);
        }
        OWLSubClassOfAxiom ax = dataFactory.getOWLSubClassOfAxiom(subClass, superClass);
        lastAxioms.add(ax);
        if (lastAxioms.size() > 50) lastAxioms.remove(0);
        ontology.add(ax);
    }

    private void parseSubObjectPropertyOf(InputStream stream, int utility, OWLOntology ontology) throws IOException {
        // Se il bit 0 dell'utility flag e impostato, il frame rappresenta una catena di proprieta (SubPropertyChainOf)
        if ((utility & 0x01) != 0) {
            int count = readVarInt(stream);
            List<OWLObjectPropertyExpression> chain = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                chain.add(parseObjectPropertyExpression(stream));
            }
            OWLObjectPropertyExpression sup = parseObjectPropertyExpression(stream);
            ontology.add(dataFactory.getOWLSubPropertyChainOfAxiom(chain, sup));
        } else {
            OWLObjectPropertyExpression sub = parseObjectPropertyExpression(stream);
            OWLObjectPropertyExpression sup = parseObjectPropertyExpression(stream);
            ontology.add(dataFactory.getOWLSubObjectPropertyOfAxiom(sub, sup));
        }
    }

    private void parseDisjointUnion(InputStream stream, OWLOntology ontology) throws IOException {
        OWLClass cls = dataFactory.getOWLClass((IRI) getIdentifier(readVarInt(stream)));
        int count = readVarInt(stream);
        List<OWLClassExpression> ces = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ces.add(parseClassExpression(stream));
        }
        ontology.add(dataFactory.getOWLDisjointUnionAxiom(cls, ces));
    }

    private void parseSubDataPropertyOf(InputStream stream, OWLOntology ontology) throws IOException {
        OWLDataPropertyExpression sub = parseDataPropertyExpression(stream);
        OWLDataPropertyExpression sup = parseDataPropertyExpression(stream);
        ontology.add(dataFactory.getOWLSubDataPropertyOfAxiom(sub, sup));
    }

    private void parseEquivalentDataProperties(InputStream stream, OWLOntology ontology) throws IOException {
        ontology.add(dataFactory.getOWLEquivalentDataPropertiesAxiom(readDataProperties(stream)));
    }

    private void parseDisjointDataProperties(InputStream stream, OWLOntology ontology) throws IOException {
        ontology.add(dataFactory.getOWLDisjointDataPropertiesAxiom(readDataProperties(stream)));
    }

    private List<OWLDataPropertyExpression> readDataProperties(InputStream stream) throws IOException {
        int count = readVarInt(stream);
        List<OWLDataPropertyExpression> props = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            props.add(parseDataPropertyExpression(stream));
        }
        return props;
    }

    private void parseDataPropertyDomain(InputStream stream, OWLOntology ontology) throws IOException {
        OWLDataPropertyExpression property = parseDataPropertyExpression(stream);
        ontology.add(dataFactory.getOWLDataPropertyDomainAxiom(property, parseClassExpression(stream)));
    }

    private void parseDataPropertyRange(InputStream stream, OWLOntology ontology) throws IOException {
        OWLDataPropertyExpression property = parseDataPropertyExpression(stream);
        ontology.add(dataFactory.getOWLDataPropertyRangeAxiom(property, parseDataRange(stream)));
    }

    private void parseFunctionalDataProperty(InputStream stream, OWLOntology ontology) throws IOException {
        OWLDataPropertyExpression property = parseDataPropertyExpression(stream);
        ontology.add(dataFactory.getOWLFunctionalDataPropertyAxiom(property));
    }

    private void parseDatatypeDefinition(InputStream stream, OWLOntology ontology) throws IOException {
        OWLDatatype datatype = dataFactory.getOWLDatatype((IRI) getIdentifier(readVarInt(stream)));
        OWLDataRange dataRange = parseDataRange(stream);
        ontology.add(dataFactory.getOWLDatatypeDefinitionAxiom(datatype, dataRange));
    }

    private void parseHasKey(InputStream stream, OWLOntology ontology) throws IOException {
        OWLClassExpression ce = parseClassExpression(stream);
        List<OWLObjectPropertyExpression> opes = readObjectPropertyExpressions(stream);
        List<OWLDataPropertyExpression> dpes = readDataProperties(stream);
        List<OWLPropertyExpression> props = new ArrayList<>(opes.size() + dpes.size());
        props.addAll(opes);
        props.addAll(dpes);
        ontology.add(dataFactory.getOWLHasKeyAxiom(ce, props));
    }

    private void parseNegativeObjectPropertyAssertion(InputStream stream, OWLOntology ontology) throws IOException {
        OWLObjectPropertyExpression property = parseObjectPropertyExpression(stream);
        OWLIndividual subj = parseIndividual(stream);
        OWLIndividual obj = parseIndividual(stream);
        ontology.add(dataFactory.getOWLNegativeObjectPropertyAssertionAxiom(property, subj, obj));
    }

    private void parseNegativeDataPropertyAssertion(InputStream stream, OWLOntology ontology) throws IOException {
        OWLDataPropertyExpression property = parseDataPropertyExpression(stream);
        OWLIndividual subj = parseIndividual(stream);
        OWLLiteral obj = parseLiteral(stream);
        ontology.add(dataFactory.getOWLNegativeDataPropertyAssertionAxiom(property, subj, obj));
    }

    private void parseSubAnnotationPropertyOf(InputStream stream, OWLOntology ontology) throws IOException {
        OWLAnnotationProperty sub = dataFactory.getOWLAnnotationProperty((IRI) getIdentifier(readVarInt(stream)));
        OWLAnnotationProperty sup = dataFactory.getOWLAnnotationProperty((IRI) getIdentifier(readVarInt(stream)));
        ontology.add(dataFactory.getOWLSubAnnotationPropertyOfAxiom(sub, sup));
    }

    private void parseEquivalentObjectProperties(InputStream stream, OWLOntology ontology) throws IOException {
        ontology.add(dataFactory.getOWLEquivalentObjectPropertiesAxiom(
                readObjectPropertyExpressions(stream)));
    }

    private void parseDisjointObjectProperties(InputStream stream, OWLOntology ontology) throws IOException {
        ontology.add(dataFactory.getOWLDisjointObjectPropertiesAxiom(
                readObjectPropertyExpressions(stream)));
    }

    private void parseInverseObjectProperties(InputStream stream, OWLOntology ontology) throws IOException {
        OWLObjectPropertyExpression first = parseObjectPropertyExpression(stream);
        OWLObjectPropertyExpression second = parseObjectPropertyExpression(stream);
        ontology.add(dataFactory.getOWLInverseObjectPropertiesAxiom(first, second));
    }

    private void parseObjectPropertyDomain(InputStream stream, OWLOntology ontology) throws IOException {
        OWLObjectPropertyExpression property = parseObjectPropertyExpression(stream);
        OWLClassExpression ce = parseClassExpression(stream);
        OWLObjectPropertyDomainAxiom ax = dataFactory.getOWLObjectPropertyDomainAxiom(property, ce);
        ontology.add(ax);
    }

    private void parseObjectPropertyRange(InputStream stream, OWLOntology ontology) throws IOException {
        OWLObjectPropertyExpression property = parseObjectPropertyExpression(stream);
        OWLClassExpression ce = parseClassExpression(stream);
        OWLObjectPropertyRangeAxiom ax = dataFactory.getOWLObjectPropertyRangeAxiom(property, ce);
        ontology.add(ax);
    }

    private List<OWLObjectPropertyExpression> readObjectPropertyExpressions(InputStream stream) throws IOException {
        int count = readVarInt(stream);
        List<OWLObjectPropertyExpression> properties = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            properties.add(parseObjectPropertyExpression(stream));
        }
        return properties;
    }

    private void parseSameIndividuals(InputStream stream, OWLOntology ontology) throws IOException {
        ontology.add(dataFactory.getOWLSameIndividualAxiom(readIndividuals(stream)));
    }

    private void parseDifferentIndividuals(InputStream stream, OWLOntology ontology) throws IOException {
        ontology.add(dataFactory.getOWLDifferentIndividualsAxiom(readIndividuals(stream)));
    }

    private List<OWLIndividual> readIndividuals(InputStream stream) throws IOException {
        int count = readVarInt(stream);
        List<OWLIndividual> individuals = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            individuals.add(parseIndividual(stream));
        }
        return individuals;
    }

    private void parseEquivalentClasses(InputStream stream, OWLOntology ontology) throws IOException {
        int count = readVarInt(stream);
        List<OWLClassExpression> operands = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            operands.add(parseClassExpression(stream));
        }
        // Mantiene l'ordine di lettura e delega all'OWLAPI la normalizzazione interna.
        ontology.add(dataFactory.getOWLEquivalentClassesAxiom(operands));
    }

    private void parseDisjointClasses(InputStream stream, OWLOntology ontology) throws IOException {
        int count = readVarInt(stream);
        List<OWLClassExpression> operands = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            operands.add(parseClassExpression(stream));
        }
        // Mantiene l'ordine di lettura e delega all'OWLAPI la normalizzazione interna.
        ontology.add(dataFactory.getOWLDisjointClassesAxiom(operands));
    }
    
    private void parseClassAssertion(InputStream stream, OWLOntology ontology) throws IOException {
        OWLClassExpression ce = parseClassExpression(stream);
        OWLIndividual ind = parseIndividual(stream);
        OWLClassAssertionAxiom ax = dataFactory.getOWLClassAssertionAxiom(ce, ind);
        ontology.add(ax);
    }

    private void parseObjectPropertyAssertion(InputStream stream, OWLOntology ontology) throws IOException {
        OWLObjectPropertyExpression ope = parseObjectPropertyExpression(stream);
        OWLIndividual subj = parseIndividual(stream);
        OWLIndividual obj = parseIndividual(stream);
        ontology.add(dataFactory.getOWLObjectPropertyAssertionAxiom(ope, subj, obj));
    }

    private void parseDataPropertyAssertion(InputStream stream, OWLOntology ontology) throws IOException {
        OWLDataPropertyExpression dpe = parseDataPropertyExpression(stream);
        OWLIndividual subj = parseIndividual(stream);
        OWLLiteral lit = parseLiteral(stream);
        ontology.add(dataFactory.getOWLDataPropertyAssertionAxiom(dpe, subj, lit));
    }

    private void parseAnnotationAssertion(InputStream stream, OWLOntology ontology) throws IOException {
        OWLAnnotationProperty property = dataFactory.getOWLAnnotationProperty(
                (IRI) getIdentifier(readVarInt(stream)));
        OWLObject subject = getIdentifier(readVarInt(stream));
        if (!(subject instanceof IRI) && !(subject instanceof OWLAnonymousIndividual)) {
            throw new OWLParserException("Invalid annotation subject: " + subject);
        }
        OWLAnnotationValue value = parseAnnotationValue(stream);
        OWLAnnotation annotation = dataFactory.getOWLAnnotation(property, value);
        ontology.add(dataFactory.getOWLAnnotationAssertionAxiom(
                (OWLAnnotationSubject) subject, annotation));
    }

    // ========================================================================
    // PARSING DEI TIPI COMPLESSI (COSTRUTTI OWL)
    // ========================================================================

    /**
     * Decodifica una class expression.
     * Se il valore supera TMAX, viene interpretato come riferimento a classe nominata con offset.
     */
    private OWLClassExpression parseClassExpression(InputStream stream) throws IOException {
        int header = readVarInt(stream);
        if (debugClassExpr) {
            String msg = String.format("  parseClassExpr: header=%d (0x%02X), avail=%d", header, header, stream.available());
            actiniumTrace.add(msg);
            System.err.println(msg);
        }

        // Header oltre soglia: riferimento a classe named tramite indice offsettato.
        if (header >= Constants.TMAX_CLASS_EXPRESSION) {
            int id = header - Constants.TMAX_CLASS_EXPRESSION;
            return dataFactory.getOWLClass((IRI) getIdentifier(id));
        } else {
            // Header entro soglia: costrutto anonimo da espandere ricorsivamente.
            switch (header) {
                case Constants.CLASS_EXPR_INTERSECTION:
                    int count = readVarInt(stream);
                    List<OWLClassExpression> operands = new ArrayList<>();
                    for(int i=0; i<count; i++) operands.add(parseClassExpression(stream));
                    return dataFactory.getOWLObjectIntersectionOf(operands);
                case Constants.CLASS_EXPR_UNION:
                    int countUnion = readVarInt(stream);
                    List<OWLClassExpression> operandsUnion = new ArrayList<>();
                    for(int i=0; i<countUnion; i++) operandsUnion.add(parseClassExpression(stream));
                    return dataFactory.getOWLObjectUnionOf(operandsUnion);
                case Constants.CLASS_EXPR_COMPLEMENT:
                    OWLClassExpression operand = parseClassExpression(stream);
                    return dataFactory.getOWLObjectComplementOf(operand);
                case Constants.CLASS_EXPR_ONE_OF:
                    int countOneOf = readVarInt(stream);
                    List<OWLIndividual> individuals = new ArrayList<>();
                    for(int i=0; i<countOneOf; i++) individuals.add(parseIndividual(stream));
                    return dataFactory.getOWLObjectOneOf(individuals);
                case Constants.CLASS_EXPR_SOME_VALUES:
                    OWLObjectPropertyExpression propSome = parseObjectPropertyExpression(stream);
                    OWLClassExpression fillerSome = parseClassExpression(stream);
                    return dataFactory.getOWLObjectSomeValuesFrom(propSome, fillerSome);
                case Constants.CLASS_EXPR_ALL_VALUES:
                    OWLObjectPropertyExpression propAll = parseObjectPropertyExpression(stream);
                    OWLClassExpression fillerAll = parseClassExpression(stream);
                    return dataFactory.getOWLObjectAllValuesFrom(propAll, fillerAll);
                case Constants.CLASS_EXPR_HAS_VALUE:
                    OWLObjectPropertyExpression propHasValue = parseObjectPropertyExpression(stream);
                    OWLIndividual value = parseIndividual(stream);
                    return dataFactory.getOWLObjectHasValue(propHasValue, value);
                case Constants.CLASS_EXPR_HAS_SELF:
                    OWLObjectPropertyExpression propHasSelf = parseObjectPropertyExpression(stream);
                    return dataFactory.getOWLObjectHasSelf(propHasSelf);
                case Constants.CLASS_EXPR_MIN_CARD:
                    int cardField = readVarInt(stream);
                    boolean hasFiller = (cardField & 1) != 0; // Bit 0 indica se c'è un filler
                    int cardinality = cardField >> 1; // Bit 1-31 rappresentano la cardinalità
                    OWLObjectPropertyExpression propMin = parseObjectPropertyExpression(stream);
                    OWLClassExpression fillerMin;
                    if (hasFiller) {
                        fillerMin = parseClassExpression(stream);
                    } else {
                        fillerMin = dataFactory.getOWLThing(); // Default filler se non presente
                    }
                    return dataFactory.getOWLObjectMinCardinality(cardinality, propMin, fillerMin);
                case Constants.CLASS_EXPR_MAX_CARD:
                    int maxCardField = readVarInt(stream);
                    boolean maxHasFiller = (maxCardField & 1) != 0;
                    int maxCardinality = maxCardField >> 1;
                    OWLObjectPropertyExpression propMax = parseObjectPropertyExpression(stream);
                    OWLClassExpression fillerMax = maxHasFiller
                            ? parseClassExpression(stream)
                            : dataFactory.getOWLThing();
                    return dataFactory.getOWLObjectMaxCardinality(maxCardinality, propMax, fillerMax);
                case Constants.CLASS_EXPR_EXACT_CARD:
                    int exactCardField = readVarInt(stream);
                    boolean exactHasFiller = (exactCardField & 1) != 0;
                    int exactCardinality = exactCardField >> 1;
                    OWLObjectPropertyExpression propExact = parseObjectPropertyExpression(stream);
                    OWLClassExpression fillerExact = exactHasFiller
                            ? parseClassExpression(stream)
                            : dataFactory.getOWLThing();
                    return dataFactory.getOWLObjectExactCardinality(exactCardinality, propExact, fillerExact);
                case Constants.CLASS_EXPR_DATA_SOME_VALUES:
                    OWLDataPropertyExpression propSomeData = parseDataPropertyExpression(stream);
                    OWLDataRange fillerSomeData = parseDataRange(stream);
                    return dataFactory.getOWLDataSomeValuesFrom(propSomeData, fillerSomeData);
                case Constants.CLASS_EXPR_DATA_ALL_VALUES:
                    OWLDataPropertyExpression propAllData = parseDataPropertyExpression(stream);
                    OWLDataRange fillerAllData = parseDataRange(stream);
                    return dataFactory.getOWLDataAllValuesFrom(propAllData, fillerAllData);
                case Constants.CLASS_EXPR_DATA_HAS_VALUE:
                    OWLDataPropertyExpression propHasValueData = parseDataPropertyExpression(stream);
                    OWLLiteral valueData = parseLiteral(stream);
                    return dataFactory.getOWLDataHasValue(propHasValueData, valueData);
                case Constants.CLASS_EXPR_DATA_MIN_CARD:
                    int cardFieldData = readVarInt(stream);
                    boolean hasFillerMinData = (cardFieldData & 1) != 0;
                    int cardMinData = cardFieldData >> 1;
                    OWLDataPropertyExpression propMinData = parseDataPropertyExpression(stream);
                    OWLDataRange fillerMinData = hasFillerMinData ? parseDataRange(stream) : dataFactory.getTopDatatype();
                    return dataFactory.getOWLDataMinCardinality(cardMinData, propMinData, fillerMinData);
                case Constants.CLASS_EXPR_DATA_MAX_CARD:
                    int maxCardFieldData = readVarInt(stream);
                    boolean hasFillerMaxData = (maxCardFieldData & 1) != 0;
                    int maxCardData = maxCardFieldData >> 1;
                    OWLDataPropertyExpression propMaxData = parseDataPropertyExpression(stream);
                    OWLDataRange fillerMaxData = hasFillerMaxData ? parseDataRange(stream) : dataFactory.getTopDatatype();
                    return dataFactory.getOWLDataMaxCardinality(maxCardData, propMaxData, fillerMaxData);
                case Constants.CLASS_EXPR_DATA_EXACT_CARD:
                    int exactCardFieldData = readVarInt(stream);
                    boolean hasFillerExactData = (exactCardFieldData & 1) != 0;
                    int exactCardData = exactCardFieldData >> 1;
                    OWLDataPropertyExpression propExactData = parseDataPropertyExpression(stream);
                    OWLDataRange fillerExactData = hasFillerExactData ? parseDataRange(stream) : dataFactory.getTopDatatype();
                    return dataFactory.getOWLDataExactCardinality(exactCardData, propExactData, fillerExactData);
                default:
                    throw new OWLParserException("Unsupported ClassExpression type: " + header);
            }
        }
    }

    /**
     * Decodifica un DataRange (Datatype nominato o Data Range anonimo con tag TLV).
     */
    private OWLDataRange parseDataRange(InputStream stream) throws IOException {
        int header = readVarInt(stream);
        if (header >= Constants.TMAX_DATA_RANGE) {
            int id = header - Constants.TMAX_DATA_RANGE;
            return dataFactory.getOWLDatatype((IRI) getIdentifier(id));
        } else {
            switch (header) {
                case Constants.DATA_RANGE_INTERSECTION:
                    int countInter = readVarInt(stream);
                    List<OWLDataRange> operandsInter = new ArrayList<>();
                    for (int i = 0; i < countInter; i++) operandsInter.add(parseDataRange(stream));
                    return dataFactory.getOWLDataIntersectionOf(operandsInter);
                case Constants.DATA_RANGE_UNION:
                    int countUnion = readVarInt(stream);
                    List<OWLDataRange> operandsUnion = new ArrayList<>();
                    for (int i = 0; i < countUnion; i++) operandsUnion.add(parseDataRange(stream));
                    return dataFactory.getOWLDataUnionOf(operandsUnion);
                case Constants.DATA_RANGE_COMPLEMENT:
                    OWLDataRange operand = parseDataRange(stream);
                    return dataFactory.getOWLDataComplementOf(operand);
                case Constants.DATA_RANGE_ONE_OF:
                    int countOneOf = readVarInt(stream);
                    List<OWLLiteral> literals = new ArrayList<>();
                    for (int i = 0; i < countOneOf; i++) literals.add(parseLiteral(stream));
                    return dataFactory.getOWLDataOneOf(literals);
                case Constants.DATA_RANGE_RESTRICTION:
                    OWLDataRange baseRange = parseDataRange(stream);
                    OWLDatatype baseDatatype = baseRange.isOWLDatatype()
                            ? baseRange.asOWLDatatype()
                            : dataFactory.getTopDatatype();
                    int countFacets = readVarInt(stream);
                    List<OWLFacetRestriction> facets = new ArrayList<>();
                    for (int i = 0; i < countFacets; i++) {
                        IRI facetIRI = (IRI) getIdentifier(readVarInt(stream));
                        OWLLiteral lit = parseLiteral(stream);
                        org.semanticweb.owlapi.vocab.OWLFacet facet = org.semanticweb.owlapi.vocab.OWLFacet.getFacet(facetIRI);
                        facets.add(dataFactory.getOWLFacetRestriction(facet, lit));
                    }
                    return dataFactory.getOWLDatatypeRestriction(baseDatatype, facets);
                default:
                    throw new OWLParserException("Unsupported DataRange type: " + header);
            }
        }
    }

    private OWLObjectPropertyExpression parseObjectPropertyExpression(InputStream stream) throws IOException {
        int header = readVarInt(stream);
        if (header > 0) {
            // Header positivo: object property diretta, codificata con offset +1.
            int id = header - 1;
            return dataFactory.getOWLObjectProperty((IRI) getIdentifier(id));
        } else {
            // Header 0: inverse object property, seguita dall'ID della proprieta base.
            int id = readVarInt(stream);
            return dataFactory.getOWLObjectInverseOf(dataFactory.getOWLObjectProperty((IRI) getIdentifier(id)));
        }
    }

    private OWLDataPropertyExpression parseDataPropertyExpression(InputStream stream) throws IOException {
        // La data property usa mappatura diretta header -> identifier index.
        int header = readVarInt(stream);
        return dataFactory.getOWLDataProperty((IRI) getIdentifier(header));
    }

    /**
     * Decodifica un individuale a partire dalla tabella degli identificatori.
     */
    private OWLIndividual parseIndividual(InputStream stream) throws IOException {
        int header = readVarInt(stream);
        OWLObject obj = getIdentifier(header);

        // Il pattern matching riduce cast espliciti e rende il controllo tipi piu leggibile.
        if (obj instanceof OWLNamedIndividual named) return named;
        if (obj instanceof IRI iri) return dataFactory.getOWLNamedIndividual(iri);
        if (obj instanceof OWLAnonymousIndividual anon) return anon;

        throw new OWLParserException("Invalid individual type found: " + obj.getClass());
    }

    /**
     * Decodifica i literal in base al tag di tipo (plain, lang, typed).
     */
private OWLLiteral parseLiteral(InputStream stream) throws IOException {
        int header = stream.read();
        if (header == -1) throw new IOException("Fine inaspettata dello stream leggendo un Literal");

        // Estraiamo Type (2 bit a destra) e Format (6 bit a sinistra)
        int type = header & 0x03;
        int format = (header >> 2) & 0x3F;

        if (debugClassExpr) {
            String msg = String.format("    parseLiteral: header=0x%02X, type=%d, format=%d, avail=%d", header, type, format, stream.available());
            actiniumTrace.add(msg);
            System.err.println(msg);
        }

        String valueStr;

        // 1. Leggiamo il VALORE in base al FORMATO
        switch (format) {
            case Constants.LITERAL_FMT_STRING:
                valueStr = readString(stream);
                break;
            case Constants.LITERAL_FMT_BOOLEAN:
                int b = stream.read();
                if (b != 0 && b != 1) {
                    throw new OWLParserException("Valore booleano non valido: " + b);
                }
                valueStr = (b == 1) ? "true" : "false";
                break;
            case Constants.LITERAL_FMT_SIGNED_INT:
                int sVal = readSVarInt(stream);
                valueStr = String.valueOf(sVal);
                break;
            case Constants.LITERAL_FMT_UNSIGNED_INT:
                int uVal = readVarInt(stream);
                valueStr = String.valueOf(uVal);
                break;
            case Constants.LITERAL_FMT_FLOAT:
                int intPart = readSVarInt(stream);
                int fracPart = readVarInt(stream);
                valueStr = intPart + "." + fracPart;
                break;
            case Constants.LITERAL_FMT_DOUBLE:
                int dIntPart = readSVarInt(stream);
                int dFracPart = readVarInt(stream);
                int expPart = readSVarInt(stream);
                valueStr = dIntPart + "." + dFracPart + (expPart != 0 ? ("E" + expPart) : "");
                break;
            default:
                throw new OWLParserException("Formato Literal non supportato: " + format);
        }

        // 2. Creiamo il Literal in base al TYPE
        switch (type) {
            case Constants.LITERAL_PLAIN:
                return dataFactory.getOWLLiteral(valueStr);
            case Constants.LITERAL_LANG:
                String lang = readString(stream);
                return dataFactory.getOWLLiteral(valueStr, lang);
            case Constants.LITERAL_TYPED:
                int datatypeId = readVarInt(stream);
                OWLObject dtObj = getIdentifier(datatypeId);
                OWLDatatype datatype = dataFactory.getOWLDatatype((IRI) dtObj);
                return dataFactory.getOWLLiteral(valueStr, datatype);
            case 3:
                // Type 3: letterale tipizzato con datatype implicito inferito direttamente dal formato di codifica
                switch (format) {
                    case Constants.LITERAL_FMT_BOOLEAN:
                        return dataFactory.getOWLLiteral(valueStr, org.semanticweb.owlapi.vocab.OWL2Datatype.XSD_BOOLEAN);
                    case Constants.LITERAL_FMT_SIGNED_INT:
                    case Constants.LITERAL_FMT_UNSIGNED_INT:
                        return dataFactory.getOWLLiteral(valueStr, org.semanticweb.owlapi.vocab.OWL2Datatype.XSD_INTEGER);
                    case Constants.LITERAL_FMT_FLOAT:
                        return dataFactory.getOWLLiteral(valueStr, org.semanticweb.owlapi.vocab.OWL2Datatype.XSD_FLOAT);
                    case Constants.LITERAL_FMT_DOUBLE:
                        return dataFactory.getOWLLiteral(valueStr, org.semanticweb.owlapi.vocab.OWL2Datatype.XSD_DOUBLE);
                    default:
                        return dataFactory.getOWLLiteral(valueStr, org.semanticweb.owlapi.vocab.OWL2Datatype.XSD_STRING);
                }
            default:
                throw new OWLParserException("Tipo Literal sconosciuto: " + type);
        }
    }

    // ========================================================================
    // UTILITY DI LETTURA BINARIA
    // ========================================================================

    private OWLObject getIdentifier(int id) {
        if (id < 0 || id >= identifiers.size()) {
            throw new OWLParserException("Identifier index out of bounds: " + id);
        }
        return identifiers.get(id);
    }

    /**
     * Legge un intero a lunghezza variabile codificato in base 128 little-endian.
     */
    private int readVarInt(InputStream stream) throws IOException {
        int value = 0;
        int shift = 0;
        int b;
        do {
            b = stream.read();
            if (b == -1) throw new IOException("Unexpected end of stream while reading VarInt");
            // Appende i 7 bit di payload nella posizione corrente.
            value |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0); // Continua finché il bit di continuazione (MSB) e impostato.
        return value;
    }

    /**
     * Decodifica un intero con segno usando la mappatura Zig-Zag.
     */
    private int readSVarInt(InputStream stream) throws IOException {
        int raw = readVarInt(stream);
        // Se è pari, il numero originale era positivo. Se dispari, era negativo.
        if ((raw & 1) == 0) {
            return raw / 2;
        } else {
            return -(raw + 1) / 2;
        }
    }

    /**
     * Legge una stringa prefissata da lunghezza VarInt e codificata in UTF-8.
     */
    private String readString(InputStream stream) throws IOException {
        int length = readVarInt(stream);
        byte[] bytes = new byte[length];
        int read = stream.readNBytes(bytes, 0, length);
        if (read != length) throw new IOException("Unexpected end of stream while reading String");
        return new String(bytes, StandardCharsets.UTF_8);
    }
}