package it.poliba.sisinflab.protocowl;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.vocab.OWL2Datatype;
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
        collectPrefixNamespaces(format);

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
        ontology.getAxioms(AxiomType.ANNOTATION_ASSERTION).forEach(axiom -> {
            OWLAnnotationSubject subject = axiom.getSubject();
            if (subject instanceof IRI iri) registerIRI(iri);
            else if (subject instanceof OWLAnonymousIndividual anon) registerAnon(anon);
            collectAnnotationEntities(axiom.getAnnotation());
        });
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

    private void collectPrefixNamespaces(ProtocOWLDocumentFormat format) {
        if (format != null) {
            format.getPrefixName2PrefixMap().values().forEach(namespace ->
                    namespaceTable.computeIfAbsent(namespace, ignored -> namespaceTable.size()));
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
        Map<String, List<String>> prefixesByNamespace = new HashMap<>();
        if (format != null) {
            format.getPrefixName2PrefixMap().forEach((prefix, namespace) -> {
                if (namespaceTable.getOrDefault(namespace, -1) >= 5) {
                    prefixesByNamespace.computeIfAbsent(namespace, ignored -> new ArrayList<>()).add(prefix);
                }
            });
        }

        // Gli identificatori usano gli indici assegnati in namespaceTable: l'ordine
        // delle dichiarazioni deve quindi restare identico a quello della tabella.
        List<Map.Entry<String, String>> aliases = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : namespaceTable.entrySet()) {
            if (entry.getValue() < 5) continue;

            String namespace = entry.getKey();
            List<String> prefixes = prefixesByNamespace.get(namespace);
            if (prefixes == null || prefixes.isEmpty()) {
                writeNamespaceDeclaration(stream, namespace, null);
            } else {
                writeNamespaceDeclaration(stream, namespace, prefixes.get(0));
                for (int i = 1; i < prefixes.size(); i++) {
                    aliases.add(Map.entry(prefixes.get(i), namespace));
                }
            }
        }

        // Gli alias aggiuntivi non devono inserirsi prima degli indici degli IRI.
        for (Map.Entry<String, String> alias : aliases) {
            writeNamespaceDeclaration(stream, alias.getValue(), alias.getKey());
        }
    }

    private void writeNamespaceDeclaration(OutputStream stream, String namespace, String prefix)
            throws IOException {
        if (prefix == null) {
            stream.write(Constants.FRAME_NAMESPACE_DECL);
            writeVarInt(stream, 1);
            writeString(stream, namespace);
            return;
        }

        stream.write(Constants.FRAME_NAMESPACE_DECL | (1 << 6));
        writeVarInt(stream, 1);
        if (prefix.endsWith(":")) prefix = prefix.substring(0, prefix.length() - 1);
        writeString(stream, prefix);
        writeString(stream, namespace);
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
            } else if (ax instanceof OWLSubObjectPropertyOfAxiom subObjectPropertyAx) {
                writeSubObjectPropertyOf(stream, subObjectPropertyAx);
            } else if (ax instanceof OWLEquivalentObjectPropertiesAxiom equivalentObjectPropertiesAx) {
                writeObjectPropertySetAxiom(stream, Constants.FRAME_EQUIVALENT_OBJ_PROPS,
                        equivalentObjectPropertiesAx.getProperties());
            } else if (ax instanceof OWLDisjointObjectPropertiesAxiom disjointObjectPropertiesAx) {
                writeObjectPropertySetAxiom(stream, Constants.FRAME_DISJOINT_OBJ_PROPS,
                        disjointObjectPropertiesAx.getProperties());
            } else if (ax instanceof OWLInverseObjectPropertiesAxiom inverseObjectPropertiesAx) {
                stream.write(Constants.FRAME_INVERSE_OBJ_PROP);
                writeObjectPropertyExpression(stream, inverseObjectPropertiesAx.getFirstProperty());
                writeObjectPropertyExpression(stream, inverseObjectPropertiesAx.getSecondProperty());
            } else if (ax instanceof OWLObjectPropertyDomainAxiom domainAx) {
                stream.write(Constants.FRAME_OBJ_PROP_DOMAIN);
                writeObjectPropertyExpression(stream, domainAx.getProperty());
                writeClassExpression(stream, domainAx.getDomain());
            } else if (ax instanceof OWLObjectPropertyRangeAxiom rangeAx) {
                stream.write(Constants.FRAME_OBJ_PROP_RANGE);
                writeObjectPropertyExpression(stream, rangeAx.getProperty());
                writeClassExpression(stream, rangeAx.getRange());
            } else if (ax instanceof OWLFunctionalObjectPropertyAxiom functionalAx) {
                writeObjectPropertyCharacteristic(stream, Constants.FRAME_FUNCTIONAL_OBJ_PROP,
                        functionalAx.getProperty());
            } else if (ax instanceof OWLInverseFunctionalObjectPropertyAxiom inverseFunctionalAx) {
                writeObjectPropertyCharacteristic(stream, Constants.FRAME_INVERSE_FUNCTIONAL_OBJ_PROP,
                        inverseFunctionalAx.getProperty());
            } else if (ax instanceof OWLReflexiveObjectPropertyAxiom reflexiveAx) {
                writeObjectPropertyCharacteristic(stream, Constants.FRAME_REFLEXIVE_OBJ_PROP,
                        reflexiveAx.getProperty());
            } else if (ax instanceof OWLIrreflexiveObjectPropertyAxiom irreflexiveAx) {
                writeObjectPropertyCharacteristic(stream, Constants.FRAME_IRREFLEXIVE_OBJ_PROP,
                        irreflexiveAx.getProperty());
            } else if (ax instanceof OWLSymmetricObjectPropertyAxiom symmetricAx) {
                writeObjectPropertyCharacteristic(stream, Constants.FRAME_SYMMETRIC_OBJ_PROP,
                        symmetricAx.getProperty());
            } else if (ax instanceof OWLAsymmetricObjectPropertyAxiom asymmetricAx) {
                writeObjectPropertyCharacteristic(stream, Constants.FRAME_ASYMMETRIC_OBJ_PROP,
                        asymmetricAx.getProperty());
            } else if (ax instanceof OWLTransitiveObjectPropertyAxiom transitiveAx) {
                writeObjectPropertyCharacteristic(stream, Constants.FRAME_TRANSITIVE_OBJ_PROP,
                        transitiveAx.getProperty());
            } else if (ax instanceof OWLSameIndividualAxiom sameIndividualAx) {
                writeIndividualSetAxiom(stream, Constants.FRAME_SAME_INDIVIDUAL,
                        sameIndividualAx.getIndividuals());
            } else if (ax instanceof OWLDifferentIndividualsAxiom differentIndividualsAx) {
                writeIndividualSetAxiom(stream, Constants.FRAME_DIFFERENT_INDIVIDUALS,
                        differentIndividualsAx.getIndividuals());
            } else if (ax instanceof OWLEquivalentClassesAxiom equivAx) {
                writeEquivalentClasses(stream, equivAx);
            } else if (ax instanceof OWLDisjointClassesAxiom disjointAx) {
                stream.write(Constants.FRAME_DISJOINT_CLASSES);
                writeVarInt(stream, disjointAx.classExpressions().count());
                for (OWLClassExpression ce : disjointAx.getClassExpressions()) {
                    writeClassExpression(stream, ce);
                }   
            } else if (ax instanceof OWLClassAssertionAxiom clsAssAx) {
                writeClassAssertion(stream, clsAssAx);
            } else if (ax instanceof OWLObjectPropertyAssertionAxiom objPropAx) {
                writeObjectPropertyAssertion(stream, objPropAx);
            } else if (ax instanceof OWLDataPropertyAssertionAxiom dataPropAx) {
                writeDataPropertyAssertion(stream, dataPropAx);
            }
        }

        for (OWLAnnotationAssertionAxiom ax : ontology.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
            stream.write(Constants.FRAME_ANNOTATION_ASSERTION);
            writeVarInt(stream, getIdentifierId(ax.getAnnotation().getProperty().getIRI()));
            writeVarInt(stream, getIdentifierId((OWLObject) ax.getSubject()));
            writeAnnotationValue(stream, ax.getAnnotation().getValue());
        }
    }

    private void writeSubClassOf(OutputStream stream, OWLSubClassOfAxiom ax) throws IOException {
        stream.write(Constants.FRAME_SUBCLASS_OF);
        writeClassExpression(stream, ax.getSubClass());
        writeClassExpression(stream, ax.getSuperClass());
    }

    private void writeSubObjectPropertyOf(OutputStream stream, OWLSubObjectPropertyOfAxiom ax)
            throws IOException {
        stream.write(Constants.FRAME_SUB_OBJ_PROP);
        writeObjectPropertyExpression(stream, ax.getSubProperty());
        writeObjectPropertyExpression(stream, ax.getSuperProperty());
    }

    private void writeObjectPropertySetAxiom(OutputStream stream, int type,
            Collection<OWLObjectPropertyExpression> properties) throws IOException {
        stream.write(type);
        writeVarInt(stream, properties.size());
        for (OWLObjectPropertyExpression property : properties) {
            writeObjectPropertyExpression(stream, property);
        }
    }

    private void writeObjectPropertyCharacteristic(OutputStream stream, int type,
            OWLObjectPropertyExpression property) throws IOException {
        stream.write(type);
        writeObjectPropertyExpression(stream, property);
    }

    private void writeIndividualSetAxiom(OutputStream stream, int type,
            Collection<OWLIndividual> individuals) throws IOException {
        stream.write(type);
        writeVarInt(stream, individuals.size());
        for (OWLIndividual individual : individuals) {
            writeIndividual(stream, individual);
        }
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
            } else if (ce instanceof OWLObjectUnionOf union) {
                writeVarInt(stream, Constants.CLASS_EXPR_UNION);
                Set<OWLClassExpression> operands = union.getOperands();
                writeVarInt(stream, operands.size());
                for (OWLClassExpression op : operands) writeClassExpression(stream, op);
            } else if (ce instanceof OWLObjectComplementOf complement) {
                writeVarInt(stream, Constants.CLASS_EXPR_COMPLEMENT);
                writeClassExpression(stream, complement.getOperand());
            } else if (ce instanceof OWLObjectOneOf oneOf) {
                writeVarInt(stream, Constants.CLASS_EXPR_ONE_OF);
                Set<OWLIndividual> individuals = oneOf.getIndividuals();
                writeVarInt(stream, individuals.size());
                for (OWLIndividual ind : individuals) writeIndividual(stream, ind);
            } else if (ce instanceof OWLObjectSomeValuesFrom some) {
                writeVarInt(stream, Constants.CLASS_EXPR_SOME_VALUES);
                writeObjectPropertyExpression(stream, some.getProperty());
                writeClassExpression(stream, some.getFiller());
            } else if (ce instanceof OWLObjectAllValuesFrom all) {
                writeVarInt(stream, Constants.CLASS_EXPR_ALL_VALUES);
                writeObjectPropertyExpression(stream, all.getProperty());
                writeClassExpression(stream, all.getFiller());
            } else if (ce instanceof OWLObjectHasValue hasValue) {
                writeVarInt(stream, Constants.CLASS_EXPR_HAS_VALUE);
                writeObjectPropertyExpression(stream, hasValue.getProperty());
                writeIndividual(stream, hasValue.getFiller());
            } else if (ce instanceof OWLObjectHasSelf hasSelf) {
                writeVarInt(stream, Constants.CLASS_EXPR_HAS_SELF);
                writeObjectPropertyExpression(stream, hasSelf.getProperty());
            } else if (ce instanceof OWLObjectMinCardinality minCard) {
                writeVarInt(stream, Constants.CLASS_EXPR_MIN_CARD);
                int cardinality = minCard.getCardinality();
                OWLClassExpression filler = minCard.getFiller();
                boolean isThing = filler.isOWLThing();
                int cardField = (cardinality << 1) | (isThing ? 0 : 1);
                writeVarInt(stream, cardField);
                writeObjectPropertyExpression(stream, minCard.getProperty());
                if (!isThing) writeClassExpression(stream, filler);
            } else if (ce instanceof OWLObjectMaxCardinality maxCard) {
                writeCardinalityExpression(stream, Constants.CLASS_EXPR_MAX_CARD,
                        maxCard.getCardinality(), maxCard.getProperty(), maxCard.getFiller());
            } else if (ce instanceof OWLObjectExactCardinality exactCard) {
                writeCardinalityExpression(stream, Constants.CLASS_EXPR_EXACT_CARD,
                        exactCard.getCardinality(), exactCard.getProperty(), exactCard.getFiller());
            } else {
                throw new IOException("Espressione non supportata: " + ce);
            }
        }
    }

    private void writeCardinalityExpression(OutputStream stream, int type, int cardinality,
            OWLObjectPropertyExpression property, OWLClassExpression filler) throws IOException {
        writeVarInt(stream, type);
        boolean isThing = filler.isOWLThing();
        writeVarInt(stream, (cardinality << 1) | (isThing ? 0 : 1));
        writeObjectPropertyExpression(stream, property);
        if (!isThing) writeClassExpression(stream, filler);
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
        int type;
        int format = Constants.LITERAL_FMT_STRING;

        // Se è plain o se il datatype è xsd:string, forziamo Type 0 (Plain)
        if (lit.hasLang()) {
            type = Constants.LITERAL_LANG;
        } else if (lit.isRDFPlainLiteral() || lit.getDatatype().isString()) {
            type = Constants.LITERAL_PLAIN;
        } else {
            type = Constants.LITERAL_TYPED;
            
            // Controlliamo se possiamo usare un formato compatto
            if (lit.getDatatype().isBoolean()) {
                format = Constants.LITERAL_FMT_BOOLEAN;
            } else if (isUnsignedInteger(lit.getDatatype())) {
                format = Constants.LITERAL_FMT_UNSIGNED_INT;
            } else if (lit.getDatatype().isInteger()) {
                format = Constants.LITERAL_FMT_SIGNED_INT;
            }
        }

        // Costruiamo l'header: Type nei 2 bit a destra, Format nei 6 bit a sinistra
        int header = type | (format << 2);
        stream.write(header);

        // 1. Scriviamo il VALORE in base al formato
        if (format == Constants.LITERAL_FMT_STRING) {
            writeString(stream, lit.getLiteral());
        } else if (format == Constants.LITERAL_FMT_BOOLEAN) {
            stream.write(lit.parseBoolean() ? 1 : 0);
        } else if (format == Constants.LITERAL_FMT_SIGNED_INT) {
            writeSVarInt(stream, Integer.parseInt(lit.getLiteral()));
        } else if (format == Constants.LITERAL_FMT_UNSIGNED_INT) {
            writeVarInt(stream, Long.parseLong(lit.getLiteral()));
        }

        // 2. Scriviamo i campi extra in base al TYPE
        if (type == Constants.LITERAL_LANG) {
            writeString(stream, lit.getLang());
        } else if (type == Constants.LITERAL_TYPED) {
            // Scriviamo sempre l'ID del datatype se il type è 2
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
     * Codifica un intero con segno usando la mappatura Zig-Zag (Sezione 3 del PDF).
     */
    private void writeSVarInt(OutputStream stream, int value) throws IOException {
        // Formula: 2n se n >= 0, altrimenti -2n - 1
        int zigzag = (value >= 0) ? (value * 2) : (-value * 2 - 1);
        writeVarInt(stream, zigzag);
    }

    private boolean isUnsignedInteger(OWLDatatype datatype) {
        IRI iri = datatype.getIRI();
        return iri.equals(OWL2Datatype.XSD_NON_NEGATIVE_INTEGER.getIRI())
                || iri.equals(OWL2Datatype.XSD_POSITIVE_INTEGER.getIRI())
                || iri.equals(OWL2Datatype.XSD_UNSIGNED_INT.getIRI())
                || iri.equals(OWL2Datatype.XSD_UNSIGNED_LONG.getIRI());
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
