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

    /**
     * Punto di ingresso del parser.
     * Decodifica lo stream ProtocOWL e popola l'ontologia fornita.
     */
    ProtocOWLDocumentFormat parse(InputStream stream, OWLOntology ontology) throws OWLParserException, IOException {
        var format = new ProtocOWLDocumentFormat();

        // Inizializza i namespace riservati prima di leggere i frame del payload.
        initReservedNamespaces(format);

        // Verifica la compatibilita della versione del protocollo.
        parseVersion(stream);

        // Legge i frame fino a fine stream.
        int header;
        while ((header = stream.read()) != -1) {
            // Estrae tipo frame (6 bit bassi) e utility flag (2 bit alti).
            int type = header & 0x3F;
            int utility = header >> 6;

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

    /**
     * Smista la decodifica in base al tag del frame corrente.
     */
    private void parseFrame(int type, int utility, InputStream stream, OWLOntology ontology, ProtocOWLDocumentFormat format) throws IOException {
        switch (type) {
            // Namespace declaration.
            case Constants.FRAME_NAMESPACE_DECL:
                parseNamespaceDeclaration(stream, utility, format);
                break;
            // Identifier declaration.
            case Constants.FRAME_IDENTIFIER_DECL:
                parseIdentifierDeclaration(stream, utility);
                break;
            // Ontology IRI (con o senza versione).
            case Constants.FRAME_ONTOLOGY_IRI:
            // case Constants.FRAME_ONTOLOGY_IRI_VERSIONED:
            //     parseOntologyIRI(stream, utility, ontology);
            //     break;
            case Constants.FRAME_CLASS_DECL:
            case Constants.FRAME_DATATYPE_DECL:
            case Constants.FRAME_OBJ_PROP_DECL:
            case Constants.FRAME_DATA_PROP_DECL:
            case Constants.FRAME_ANNOTATION_PROP_DECL:
            // Entity declaration, incluso NamedIndividual.
            case Constants.FRAME_NAMED_IND_DECL:
                parseEntityDeclaration(type, stream, ontology);
                break;
            // Logical axioms supportati dal formato.
            case Constants.FRAME_SUBCLASS_OF:
                parseSubClassOf(stream, ontology);
                break;
            case Constants.FRAME_EQUIVALENT_CLASSES:
                parseEquivalentClasses(stream, ontology);
                break;
            case Constants.FRAME_CLASS_ASSERTION:
                parseClassAssertion(stream, ontology);
                break;
            case Constants.FRAME_OBJ_PROP_ASSERTION:
                parseObjectPropertyAssertion(stream, ontology);
                break;
            case Constants.FRAME_DATA_PROP_ASSERTION:
                parseDataPropertyAssertion(stream, ontology);
                break;
            default:
                // I frame non supportati o di controllo vengono ignorati.
                break;
        }
    }

    // ========================================================================
    // PARSING DEI FRAME BASE
    // ========================================================================

    private void parseNamespaceDeclaration(InputStream stream, int utility, ProtocOWLDocumentFormat format) throws IOException {
        int count = readVarInt(stream);
        // Utility bit 0: 1 indica presenza esplicita del prefisso.
        boolean prefixed = (utility & 0x01) != 0;

        for (int i = 0; i < count; i++) {
            if (prefixed) {
                String prefix = readString(stream);
                String namespace = readString(stream);
                namespaces.add(namespace);
                format.setPrefix(prefix, namespace);
            } else {
                String namespace = readString(stream);
                namespaces.add(namespace);
            }
        }
    }

    private void parseIdentifierDeclaration(InputStream stream, int utility) throws IOException {
        int count = readVarInt(stream);
        // Utility bit 0: 1 indica IRI, 0 indica anonymous individual.
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

        // Aggiorna l'OntologyID per mantenere coerenti API e test basati su isNamed().
        if (ontology.getOWLOntologyManager() != null) {
            ontology.getOWLOntologyManager().applyChange(new SetOntologyID(ontology, ontologyIRI));
        }

        // Utility bit 0: se presente la versione, il parser consuma il relativo ID.
        if ((utility & 0x01) != 0) {
            readVarInt(stream);
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

    private void parseSubClassOf(InputStream stream, OWLOntology ontology) throws IOException {
        OWLClassExpression sub = parseClassExpression(stream);
        OWLClassExpression sup = parseClassExpression(stream);
        ontology.add(dataFactory.getOWLSubClassOfAxiom(sub, sup));
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

    private void parseClassAssertion(InputStream stream, OWLOntology ontology) throws IOException {
        OWLClassExpression ce = parseClassExpression(stream);
        OWLIndividual ind = parseIndividual(stream);
        ontology.add(dataFactory.getOWLClassAssertionAxiom(ce, ind));
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

    // ========================================================================
    // PARSING DEI TIPI COMPLESSI (COSTRUTTI OWL)
    // ========================================================================

    /**
     * Decodifica una class expression.
     * Se il valore supera TMAX, viene interpretato come riferimento a classe nominata con offset.
     */
    private OWLClassExpression parseClassExpression(InputStream stream) throws IOException {
        int header = readVarInt(stream);

        // Header oltre soglia: riferimento a classe named tramite indice offsettato.
        if (header > Constants.TMAX_CLASS_EXPRESSION) {
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
                case Constants.CLASS_EXPR_SOME_VALUES:
                    OWLObjectPropertyExpression propSome = parseObjectPropertyExpression(stream);
                    OWLClassExpression fillerSome = parseClassExpression(stream);
                    return dataFactory.getOWLObjectSomeValuesFrom(propSome, fillerSome);
                case Constants.CLASS_EXPR_ALL_VALUES:
                    OWLObjectPropertyExpression propAll = parseObjectPropertyExpression(stream);
                    OWLClassExpression fillerAll = parseClassExpression(stream);
                    return dataFactory.getOWLObjectAllValuesFrom(propAll, fillerAll);
                default:
                    throw new OWLParserException("Unsupported ClassExpression type: " + header);
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
        int type = stream.read();
        String value = readString(stream);

        // Lo switch rende esplicita la corrispondenza tra type tag e payload atteso.
        switch (type) {
            case Constants.LITERAL_PLAIN:
                return dataFactory.getOWLLiteral(value);
            case Constants.LITERAL_LANG:
                String lang = readString(stream);
                return dataFactory.getOWLLiteral(value, lang);
            case Constants.LITERAL_TYPED:
                int datatypeId = readVarInt(stream);
                OWLDatatype datatype = dataFactory.getOWLDatatype((IRI) getIdentifier(datatypeId));
                return dataFactory.getOWLLiteral(value, datatype);
            default:
                throw new OWLParserException("Unknown literal type: " + type);
        }
    }

    // ========================================================================
    // UTILITY DI LETTURA BINARIA
    // ========================================================================

    private OWLObject getIdentifier(int id) {
        if (id < 0 || id >= identifiers.size()) throw new OWLRuntimeException("Identifier index out of bounds: " + id);
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