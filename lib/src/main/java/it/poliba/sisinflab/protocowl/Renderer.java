package it.poliba.sisinflab.protocowl;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;

class Renderer {

    // Utilizzando LinkedHashMap si mantiene l'ordine di inserimento e
    // si riducono le strutture dati necessarie
    private final Map<String, Integer> namespaceTable = new LinkedHashMap<>();
    private final Map<OWLObject, Integer> identifierTable = new LinkedHashMap<>();

    /**
     * Entry point della fase di codifica dell'ontologia.
     */
    void render(OWLOntology ontology, OutputStream stream, ProtocOWLDocumentFormat format)
            throws IOException {

        // 1. Inserisce i namespace riservati nella mappa per pre-occupare gli ID 0-4
        initReservedNamespaces();

        // 2. Colleziona tutte le entità in gioco (Namespace e Identificatori) assegnando loro un ID
        collectEntities(ontology);

        // 3. Scrive la versione del protocollo
        writeVersion(stream);

        // 4. Scrive le tabelle dei simboli (Namespace ed Entità scoperte)
        writeNamespaceDeclarations(stream, format);
        writeIdentifierDeclarations(stream);

        // 5. Scrive le meta-informazioni dell'ontologia
        var ontId = ontology.getOntologyID();
        if (ontId.getOntologyIRI().isPresent()) {
            int utility = 0;
            if (ontId.getVersionIRI().isPresent()) {
                utility = 1; // Utility bit a 1 se c'è la versione
            }
            
            stream.write(Constants.FRAME_ONTOLOGY_IRI | (utility << 6));
            writeVarInt(stream, getIdentifierId(ontId.getOntologyIRI().get()));
            
            if (utility == 1) {
                writeVarInt(stream, getIdentifierId(ontId.getVersionIRI().get()));
            }
        }

        // Scrive gli Imports
        var imports = ontology.importsDeclarations().toList();
        if (!imports.isEmpty()) {
            stream.write(Constants.FRAME_IMPORTS);
            writeVarInt(stream, imports.size());
            for (OWLImportsDeclaration decl : imports) {
                writeVarInt(stream, getIdentifierId(decl.getIRI()));
            }
        }

        // Scrive le Annotazioni dell'Ontologia
        var annotations = ontology.annotations().toList();
        if (!annotations.isEmpty()) {
            stream.write(Constants.FRAME_ANNOTATIONS);
            writeVarInt(stream, annotations.size());
            for (OWLAnnotation ann : annotations) {
                writeAnnotation(stream, ann);
            }
        }

        // 6. Traduce gli assiomi in stream binario
        writeAxioms(stream, ontology);
    }

    /**
     * Assicura che i primi 5 namespace mappati corrispondano a quelli riservati
     * previsti dalle specifiche di formato di ProtocOWL.
     */
    private void initReservedNamespaces() {
        addReservedNamespace("http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        addReservedNamespace("http://www.w3.org/2000/01/rdf-schema#");
        addReservedNamespace("http://www.w3.org/2001/XMLSchema#");
        addReservedNamespace("http://www.w3.org/2002/07/owl#");
        addReservedNamespace("http://www.w3.org/XML/1998/namespace");
    }

    private void addReservedNamespace(String ns) {
        namespaceTable.put(ns, namespaceTable.size());
    }

    /**
     * Percorre la firma dell'ontologia e registra entità logiche e namespace per
     * generare un identificativo univoco (un indice numerico) per ciascuno.
     */
    private void collectEntities(OWLOntology ontology) {
        // Registra Ontology IRI e Version IRI
        var ontId = ontology.getOntologyID();
        if (ontId.getOntologyIRI().isPresent()) {
            registerIRI(ontId.getOntologyIRI().get());
        }
        if (ontId.getVersionIRI().isPresent()) {
            registerIRI(ontId.getVersionIRI().get());
        }

        // Registra gli IRI degli Imports
        ontology.importsDeclarations().forEach(decl -> registerIRI(decl.getIRI()));

        // Recupera e mappa le entità della firma dell'ontologia 
        ontology.getSignature(Imports.INCLUDED).stream()
                .map(OWLEntity::getIRI)
                .forEach(this::registerIRI);

        ontology.getReferencedAnonymousIndividuals(Imports.INCLUDED)
                .forEach(this::registerAnon);

        // Registra gli IRI usati nelle annotazioni dell'ontologia
        ontology.annotations().forEach(this::collectAnnotationEntities);
    }

    // Metodo helper per estrarre gli IRI dalle annotazioni
    private void collectAnnotationEntities(OWLAnnotation annotation) {
        registerIRI(annotation.getProperty().getIRI());
        if (annotation.getValue() instanceof IRI) {
            registerIRI((IRI) annotation.getValue());
        }
        // Se l'annotazione ha sotto-annotazioni, esplorale ricorsivamente
        annotation.annotations().forEach(this::collectAnnotationEntities);
    }

    private void registerIRI(IRI iri) {
        if (!identifierTable.containsKey(iri)) {
            String ns = iri.getNamespace();
            if (!namespaceTable.containsKey(ns)) {
                namespaceTable.put(ns, namespaceTable.size());
            }
            identifierTable.put(iri, identifierTable.size());
        }
    }

    private void registerAnon(OWLAnonymousIndividual anon) {
        if (!identifierTable.containsKey(anon)) {
            identifierTable.put(anon, identifierTable.size());
        }
    }

    private void writeVersion(OutputStream stream) throws IOException {
        stream.write(Constants.PROTOCOWL_VERSION);
    }

    /**
     * Iterando sulla namespaceTable, scrive a stream le dichiarazioni solo per i
     * namespace non riservati (ovvero quelli con ID >= 5).
     */
    private void writeNamespaceDeclarations(OutputStream stream, ProtocOWLDocumentFormat format) throws IOException {
        List<String> prefixedGroup = new ArrayList<>();
        List<String> plainGroup = new ArrayList<>();
        Map<String, String> reversePrefixMap = new HashMap<>();

        if (format != null) {
            format.getPrefixName2PrefixMap().forEach((p, ns) -> reversePrefixMap.put(ns, p));
        }

        // Filtra ed estrapola solo i namespace personalizzati
        for (Map.Entry<String, Integer> entry : namespaceTable.entrySet()) {
            if (entry.getValue() < 5) continue; // Salta i primi 5 riservati

            String ns = entry.getKey();
            if (reversePrefixMap.containsKey(ns)) prefixedGroup.add(ns);
            else plainGroup.add(ns);
        }

        // Scrittura dei namespace senza prefisso
        if (!plainGroup.isEmpty()) {
            stream.write(Constants.FRAME_NAMESPACE_DECL); // Header pulito
            writeVarInt(stream, plainGroup.size());
            for (String ns : plainGroup) writeString(stream, ns);
        }

        // Scrittura dei namespace prefissati
        if (!prefixedGroup.isEmpty()) {
            int header = Constants.FRAME_NAMESPACE_DECL | (1 << 6); // Set Utility Bit 0
            stream.write(header);
            writeVarInt(stream, prefixedGroup.size());
            for (String ns : prefixedGroup) {
                String prefix = reversePrefixMap.get(ns);
                // Rimuove i due punti finali come richiesto dalla specifica ProtocOWL
                if (prefix.endsWith(":")) {
                    prefix = prefix.substring(0, prefix.length() - 1);
                }
                writeString(stream, prefix);
                writeString(stream, ns);
            }
        }
    }

    /**
     * Invece di liste di supporto aggiuntive (come da feedback ing. Di Ceglie), itera
     * direttamente sulla Map e smista gli identificatori basati sul runtime type.
     */
    private void writeIdentifierDeclarations(OutputStream stream) throws IOException {
        List<OWLAnonymousIndividual> anons = new ArrayList<>();
        List<IRI> iris = new ArrayList<>();

        for (OWLObject obj : identifierTable.keySet()) {
            if (obj instanceof OWLAnonymousIndividual anon) anons.add(anon);
            else if (obj instanceof IRI iri) iris.add(iri);
        }

        // Scrittura degli individui anonimi
        if (!anons.isEmpty()) {
            stream.write(Constants.FRAME_IDENTIFIER_DECL);
            writeVarInt(stream, anons.size());
            for (OWLAnonymousIndividual anon : anons) {
                writeString(stream, anon.getID().toString());
            }
        }

        // Scrittura degli IRI
        if (!iris.isEmpty()) {
            int header = Constants.FRAME_IDENTIFIER_DECL | (1 << 6); // Set Utility Bit 0
            stream.write(header);
            writeVarInt(stream, iris.size());
            for (IRI iri : iris) {
                String ns = iri.getNamespace();
                String remainder = iri.getRemainder().orElse("");
                Integer nsIdx = namespaceTable.get(ns);
                if (nsIdx == null) throw new IOException("Namespace non registrato: " + ns);

                writeVarInt(stream, nsIdx);
                writeString(stream, remainder);
            }
        }
    }

    private void writeOntologyIRI(OutputStream stream, IRI iri) throws IOException {
        stream.write(Constants.FRAME_ONTOLOGY_IRI);
        writeVarInt(stream, getIdentifierId(iri));
    }

    private void writeAxioms(OutputStream stream, OWLOntology ontology) throws IOException {
        // Scrittura dei Frame Entità
        for (OWLDeclarationAxiom ax : ontology.getAxioms(AxiomType.DECLARATION)) {
            OWLEntity entity = ax.getEntity();
            int type = -1;

            if (entity.isOWLClass()) type = Constants.FRAME_CLASS_DECL;
            else if (entity.isOWLDatatype()) type = Constants.FRAME_DATATYPE_DECL;
            else if (entity.isOWLObjectProperty()) type = Constants.FRAME_OBJ_PROP_DECL;
            else if (entity.isOWLDataProperty()) type = Constants.FRAME_DATA_PROP_DECL;
            else if (entity.isOWLAnnotationProperty()) type = Constants.FRAME_ANNOTATION_PROP_DECL;
            else if (entity.isOWLNamedIndividual()) type = Constants.FRAME_NAMED_IND_DECL;

            if (type != -1) {
                stream.write(type);
                writeVarInt(stream, getIdentifierId(entity));
            }
        }

        // Scrittura degli Assiomi Logici
        for (OWLAxiom ax : ontology.getLogicalAxioms()) {
            if (ax instanceof OWLSubClassOfAxiom subClassAx) {
                writeSubClassOf(stream, subClassAx);
            } else if (ax instanceof OWLEquivalentClassesAxiom equivAx) {
                writeEquivalentClasses(stream, equivAx);
            } else if (ax instanceof OWLClassAssertionAxiom clsAssAx) {
                writeClassAssertion(stream, clsAssAx);
            } else if (ax instanceof OWLObjectPropertyAssertionAxiom objPropAx) {
                writeObjectPropertyAssertion(stream, objPropAx);
            } else if (ax instanceof OWLDataPropertyAssertionAxiom dataPropAx) {
                writeDataPropertyAssertion(stream, dataPropAx);
            }
        }
    }

    private void writeSubClassOf(OutputStream stream, OWLSubClassOfAxiom ax) throws IOException {
        stream.write(Constants.FRAME_SUBCLASS_OF);
        writeClassExpression(stream, ax.getSubClass());
        writeClassExpression(stream, ax.getSuperClass());
    }

    private void writeEquivalentClasses(OutputStream stream, OWLEquivalentClassesAxiom ax) throws IOException {
        stream.write(Constants.FRAME_EQUIVALENT_CLASSES);
        writeVarInt(stream, ax.classExpressions().count());
        for (OWLClassExpression ce : ax.getClassExpressions()) {
            writeClassExpression(stream, ce);
        }
    }

    private void writeClassAssertion(OutputStream stream, OWLClassAssertionAxiom ax) throws IOException {
        stream.write(Constants.FRAME_CLASS_ASSERTION);
        writeClassExpression(stream, ax.getClassExpression());
        writeIndividual(stream, ax.getIndividual());
    }

    private void writeObjectPropertyAssertion(OutputStream stream, OWLObjectPropertyAssertionAxiom ax) throws IOException {
        stream.write(Constants.FRAME_OBJ_PROP_ASSERTION);
        writeObjectPropertyExpression(stream, ax.getProperty());
        writeIndividual(stream, ax.getSubject());
        writeIndividual(stream, ax.getObject());
    }

    private void writeDataPropertyAssertion(OutputStream stream, OWLDataPropertyAssertionAxiom ax) throws IOException {
        stream.write(Constants.FRAME_DATA_PROP_ASSERTION);
        writeDataPropertyExpression(stream, ax.getProperty());
        writeIndividual(stream, ax.getSubject());
        writeLiteral(stream, ax.getObject());
    }

    // ========================================================================
    // SERIALIZZAZIONE TIPI COMPLESSI
    // ========================================================================

    private void writeClassExpression(OutputStream stream, OWLClassExpression ce) throws IOException {
        if (!ce.isAnonymous()) {
            OWLClass cls = ce.asOWLClass();
            int id = getIdentifierId(cls);
            writeVarInt(stream, id + Constants.TMAX_CLASS_EXPRESSION);
        } else {
            if (ce instanceof OWLObjectIntersectionOf intersection) {
                writeVarInt(stream, Constants.CLASS_EXPR_INTERSECTION);
                Set<OWLClassExpression> operands = intersection.getOperands();
                writeVarInt(stream, operands.size());
                for (OWLClassExpression op : operands) writeClassExpression(stream, op);
            } else if (ce instanceof OWLObjectSomeValuesFrom some) {
                writeVarInt(stream, Constants.CLASS_EXPR_SOME_VALUES);
                writeObjectPropertyExpression(stream, some.getProperty());
                writeClassExpression(stream, some.getFiller());
            } else if (ce instanceof OWLObjectAllValuesFrom all) {
                writeVarInt(stream, Constants.CLASS_EXPR_ALL_VALUES);
                writeObjectPropertyExpression(stream, all.getProperty());
                writeClassExpression(stream, all.getFiller());
            } else {
                throw new IOException("Espressione non supportata: " + ce);
            }
        }
    }

    private void writeObjectPropertyExpression(OutputStream stream, OWLObjectPropertyExpression ope) throws IOException {
        if (!ope.isAnonymous()) {
            int id = getIdentifierId(ope.asOWLObjectProperty());
            writeVarInt(stream, id + 1); // +1 per compensare lo 0 riservato all'InverseProperty
        } else {
            writeVarInt(stream, 0);
            OWLObjectProperty inv = ope.getInverseProperty().asOWLObjectProperty();
            writeVarInt(stream, getIdentifierId(inv));
        }
    }

    private void writeDataPropertyExpression(OutputStream stream, OWLDataPropertyExpression dpe) throws IOException {
        writeVarInt(stream, getIdentifierId(dpe.asOWLDataProperty()));
    }

    private void writeIndividual(OutputStream stream, OWLIndividual ind) throws IOException {
        OWLObject obj = ind;
        if (ind.isNamed()) obj = ind.asOWLNamedIndividual().getIRI();
        writeVarInt(stream, getIdentifierId(obj));
    }

    private void writeLiteral(OutputStream stream, OWLLiteral lit) throws IOException {
        if (lit.isRDFPlainLiteral()) {
            if (lit.hasLang()) {
                stream.write(Constants.LITERAL_LANG);
                writeString(stream, lit.getLiteral());
                writeString(stream, lit.getLang());
            } else {
                stream.write(Constants.LITERAL_PLAIN);
                writeString(stream, lit.getLiteral());
            }
        } else {
            stream.write(Constants.LITERAL_TYPED);
            writeString(stream, lit.getLiteral());
            writeVarInt(stream, getIdentifierId(lit.getDatatype().getIRI()));
        }
    }

    // ========================================================================
    // UTILITIES
    // ========================================================================

    /**
     * Ottimizzato unendo il controllo in un unico Type Check tramite OWLEntity.
     */
    private int getIdentifierId(OWLObject obj) {
        if (obj instanceof OWLEntity entity) {
            obj = entity.getIRI();
        }
        Integer id = identifierTable.get(obj);
        if (id == null) throw new RuntimeException("Identifier not registered: " + obj);
        return id;
    }

    /**
     * Scrittura in formato VarInt per comprimere i numeri.
     */
    private void writeVarInt(OutputStream stream, long value) throws IOException {
        while (true) {
            int bits = (int) (value & 0x7F);
            value >>>= 7;
            if (value == 0) {
                stream.write(bits);
                break;
            } else {
                stream.write(bits | 0x80);
            }
        }
    }

    /**
     * Scrive prima la lunghezza della stringa come VarInt e poi i byte codificati in UTF-8.
     */
    private void writeString(OutputStream stream, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(stream, bytes.length);
        stream.write(bytes);
    }
    private void writeAnnotation(OutputStream stream, OWLAnnotation annotation) throws IOException {
        var subAnnotations = annotation.annotations().toList();
        
        if (!subAnnotations.isEmpty()) {
            writeVarInt(stream, 0); // Header = 0 indica che è annotata
            writeVarInt(stream, subAnnotations.size());
            for (OWLAnnotation subAnn : subAnnotations) {
                writeAnnotation(stream, subAnn);
            }
        }
        
        // Scrive la property (Header = Id + 1)
        writeVarInt(stream, getIdentifierId(annotation.getProperty().getIRI()) + 1);
        writeAnnotationValue(stream, annotation.getValue());
    }

    private void writeAnnotationValue(OutputStream stream, OWLAnnotationValue value) throws IOException {
        if (value instanceof IRI || value instanceof OWLAnonymousIndividual) {
            writeVarInt(stream, getIdentifierId((OWLObject) value) + 1);
        } else if (value instanceof OWLLiteral) {
            writeVarInt(stream, 0);
            writeLiteral(stream, (OWLLiteral) value);
        } else {
            throw new IOException("Tipo di AnnotationValue non supportato");
        }
    }
}
