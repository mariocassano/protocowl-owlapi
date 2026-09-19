package benchmark;

import it.poliba.sisinflab.protocowl.ProtocOWLDocumentFormat;
import it.poliba.sisinflab.protocowl.ProtocOWLParserFactory;
import it.poliba.sisinflab.protocowl.ProtocOWLStorerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.*;
import org.semanticweb.owlapi.model.*;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class DatasetPreparer {
    private static final int TARGET_COUNT = 140; // Definisce il numero massimo di ontologie valide da esportare nel dataset filtrato

    public static void main(String[] args) {

        // non è stato inserito l'intero dataset ORE2014 in quanto relativamente pesante, di conseguenza è necessario inserire il percorso locale del dataset ORE 2014 scaricato ed estratto manualmente
        File inputDir = new File("inserire_il_percorso_del_dataset_ore2014");
        // Salva il dataset filtrato nella cartella dataset_filtrato.
        File outputDir = new File("dataset_filtrato");

        if (!inputDir.exists() || !inputDir.isDirectory()) {
            System.err.println("Cartella di input non trovata.");
            return;
        }

        if (!outputDir.exists()) {
            outputDir.mkdirs();
        } else {
            // Esegue una pulizia opzionale della cartella di output se contiene risultati di una esecuzione precedente.
            for(File f: outputDir.listFiles()) { f.delete(); }
        }

        File[] files = inputDir.listFiles();
        if (files == null || files.length == 0) {
            System.err.println("La cartella del dataset è vuota.");
            return;
        }

        int currentCount = 0;
        int attempted = 0;

        System.out.println("Avvio scansione del dataset ORE 2014...");
        System.out.println("File totali disponibili da analizzare: " + files.length);

        for (File file : files) {
            if (currentCount >= TARGET_COUNT) break;
            if (!file.isFile()) continue;

            attempted++;


            // Usa Throwable per intercettare anche errori critici JVM (es. StackOverflow su ontologie ricorsive).
            try {
                OWLOntologyManager manager = buildManager();

                // 1. Caricamento dell'ontologia.
                OWLOntology ontology = manager.loadOntologyFromOntologyDocument(file);

                // 2. Filtraggio degli assiomi supportati.
                OWLOntology filteredOntology = buildFilteredOntology(ontology);

                // 3. Verifica dei requisiti minimi.
                if (filteredOntology.getLogicalAxiomCount() == 0) {
                    continue; // Scarta silenziosamente l'ontologia se risulta vuota dopo il filtro.
                }

                if (!isOntologySupported(filteredOntology)) {
                    continue; // Scarta l'ontologia se contiene costrutti non supportati.
                }

                // 4. Verifica di round-trip sul formato ProtocOWL.
                if (isRoundTripSafe(filteredOntology)) {
                    // Assegna un nome progressivo nel formato onto_<indice>.
                    String baseName = "onto_" + String.format("%03d", currentCount);
                    saveInAllFormats(filteredOntology, new File(outputDir, baseName));
                    currentCount++;
                    System.out.println("[" + currentCount + "/140] Ontologia salvata: " + baseName + " (Origine: " + file.getName() + ")");
                }
            } catch (Throwable t) {
                // Scarta silenziosamente i file che causano errori durante parsing o serializzazione.
            }
        }

        System.out.println("\n Processo terminato.");
        System.out.println("Ontologie esaminate: " + attempted);
        System.out.println("Ontologie salvate in '" + outputDir.getName() + "': " + currentCount);

        if (currentCount < TARGET_COUNT) {
            System.err.println("ATTENZIONE: Il dataset locale non conteneva abbastanza ontologie compatibili.");
        }
    }
    // Verifica che l'ontologia contenga solo assiomi e costrutti supportati da ProtocOWL.
    private static boolean isOntologySupported(OWLOntology ontology) {
        for (OWLAxiom axiom : ontology.getLogicalAxioms()) {
            boolean isSupportedAxiom =
                    axiom instanceof OWLSubClassOfAxiom ||
                            axiom instanceof OWLEquivalentClassesAxiom ||
                            axiom instanceof OWLClassAssertionAxiom ||
                            axiom instanceof OWLObjectPropertyAssertionAxiom ||
                            axiom instanceof OWLDataPropertyAssertionAxiom;

            if (!isSupportedAxiom || !areAxiomOperandsSupported(axiom)) {
                return false;
            }
        }
        return true;
    }
    // Costruisce una copia filtrata che include solo assiomi logici supportati e relative dichiarazioni.
    private static OWLOntology buildFilteredOntology(OWLOntology source) throws OWLOntologyCreationException {
        OWLOntologyManager filteredManager = buildManager();
        OWLOntology filtered = filteredManager.createOntology(source.getOntologyID());
        OWLDataFactory dataFactory = filteredManager.getOWLDataFactory();

        Set<OWLAxiom> keptLogicalAxioms = new LinkedHashSet<>();
        for (OWLAxiom axiom : source.getLogicalAxioms()) {
            boolean isSupportedAxiom =
                    axiom instanceof OWLSubClassOfAxiom ||
                            axiom instanceof OWLEquivalentClassesAxiom ||
                            axiom instanceof OWLClassAssertionAxiom ||
                            axiom instanceof OWLObjectPropertyAssertionAxiom ||
                            axiom instanceof OWLDataPropertyAssertionAxiom;
            if (isSupportedAxiom && areAxiomOperandsSupported(axiom)) {
                keptLogicalAxioms.add(axiom);
            }
        }

        Set<OWLAxiom> keptAxioms = new LinkedHashSet<>(keptLogicalAxioms);
        Set<OWLEntity> referencedEntities = new LinkedHashSet<>();
        for (OWLAxiom axiom : keptLogicalAxioms) {
            axiom.signature().forEach(referencedEntities::add);
        }
        for (OWLEntity entity : referencedEntities) {
            keptAxioms.add(dataFactory.getOWLDeclarationAxiom(entity));
        }

        filteredManager.addAxioms(filtered, keptAxioms);
        return filtered;
    }
    // Salva l'ontologia in tutti i formati supportati, applicando le stesse regole di gestione dei prefissi per garantire coerenza tra i formati.
    private static void saveInAllFormats(OWLOntology ontology, File baseFile) throws Exception {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDocumentFormat originalFormat = ontology.getFormat();

        List<OWLDocumentFormat> formats = Arrays.asList(
                new FunctionalSyntaxDocumentFormat(),
                new ManchesterSyntaxDocumentFormat(),
                new OWLXMLDocumentFormat(),
                new RDFXMLDocumentFormat(),
                new TurtleDocumentFormat(),
                new ProtocOWLDocumentFormat()
        );

        String[] extensions = {".func", ".omn", ".owx", ".rdf", ".ttl", ".oprt"};

        for (int i = 0; i < formats.size(); i++) {
            OWLDocumentFormat targetFormat = formats.get(i);

            if (targetFormat.isPrefixOWLDocumentFormat() && originalFormat != null && originalFormat.isPrefixOWLDocumentFormat()) {
                targetFormat.asPrefixOWLDocumentFormat().copyPrefixesFrom(originalFormat.asPrefixOWLDocumentFormat());
            } else if (targetFormat.isPrefixOWLDocumentFormat()) {
                PrefixDocumentFormat prefixFormat = targetFormat.asPrefixOWLDocumentFormat();
                prefixFormat.setDefaultPrefix(ontology.getOntologyID().getOntologyIRI().map(iri -> iri.toString() + "#").orElse("http://example.org/onto#"));
                prefixFormat.setPrefix("rdf:", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
                prefixFormat.setPrefix("rdfs:", "http://www.w3.org/2000/01/rdf-schema#");
                prefixFormat.setPrefix("xsd:", "http://www.w3.org/2001/XMLSchema#");
                prefixFormat.setPrefix("owl:", "http://www.w3.org/2002/07/owl#");
            }

            File outFile = new File(baseFile.getAbsolutePath() + extensions[i]);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                manager.saveOntology(ontology, targetFormat, fos);
            }
        }
    }
    // Crea e configura un OWLOntologyManager con parser e storer ProtocOWL registrati.
    private static OWLOntologyManager buildManager() {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        manager.getOntologyParsers().add(new ProtocOWLParserFactory());
        manager.getOntologyStorers().add(new ProtocOWLStorerFactory());
        return manager;
    }
    // Verifica che il rendering ProtocOWL e il successivo parse preservino il contenuto semantico
    // dell'ontologia originale, non solo che il file serializzato sia leggibile.
    private static boolean isRoundTripSafe(OWLOntology ontology) {
        File protocowlTmp = null;
        File functionalTmp = null;
        File functionalFromProtocowlTmp = null;

        try {
            OWLOntologyManager manager = ontology.getOWLOntologyManager();

            // 1) ProtocOWL -> file -> parse di nuovo, verificando uguaglianza semantica con l'originale.
            protocowlTmp = File.createTempFile("protoc_test", ".oprt");
            try (FileOutputStream fos = new FileOutputStream(protocowlTmp)) {
                manager.saveOntology(ontology, new ProtocOWLDocumentFormat(), fos);
            }
            OWLOntologyManager protocowlVerifier = buildManager();
            OWLOntology reparsedProtocowl = protocowlVerifier.loadOntologyFromOntologyDocument(protocowlTmp);
            if (!semanticEquivalent(ontology, reparsedProtocowl)) {
                return false;
            }

            // 2) Functional -> file -> parse di nuovo, per verificare che la serializzazione in OWLAPI
            //    non perda informazioni semantiche.
            functionalTmp = File.createTempFile("functional_roundtrip", ".owl");
            try (FileOutputStream fos = new FileOutputStream(functionalTmp)) {
                manager.saveOntology(ontology, new FunctionalSyntaxDocumentFormat(), fos);
            }
            OWLOntologyManager functionalVerifier = OWLManager.createOWLOntologyManager();
            OWLOntology reparsedFunctional = functionalVerifier.loadOntologyFromOntologyDocument(functionalTmp);
            if (!semanticEquivalent(ontology, reparsedFunctional)) {
                return false;
            }

            // 3) Functional -> ProtocOWL -> parse di nuovo -> verifica che il nostro renderer/parser
            //    preservi ontology IRI, version IRI, imports, prefissi, annotazioni ed assiomi.
            functionalFromProtocowlTmp = File.createTempFile("functional_from_protocowl", ".oprt");
            try (FileOutputStream fos = new FileOutputStream(functionalFromProtocowlTmp)) {
                manager.saveOntology(reparsedFunctional, new ProtocOWLDocumentFormat(), fos);
            }
            OWLOntologyManager roundtripVerifier = buildManager();
            OWLOntology protocowlRoundtrip = roundtripVerifier.loadOntologyFromOntologyDocument(functionalFromProtocowlTmp);

            return semanticEquivalent(ontology, protocowlRoundtrip);
        } catch (Throwable e) {
            return false;
        } finally {
            if (protocowlTmp != null && protocowlTmp.exists()) {
                protocowlTmp.delete();
            }
            if (functionalTmp != null && functionalTmp.exists()) {
                functionalTmp.delete();
            }
            if (functionalFromProtocowlTmp != null && functionalFromProtocowlTmp.exists()) {
                functionalFromProtocowlTmp.delete();
            }
        }
    }

    private static boolean semanticEquivalent(OWLOntology expected, OWLOntology actual) {
        if (expected.isNamed() != actual.isNamed()) {
            return false;
        }

        if (expected.isNamed()) {
            if (!Objects.equals(expected.getOntologyID().getOntologyIRI(), actual.getOntologyID().getOntologyIRI())) {
                return false;
            }
            if (!Objects.equals(expected.getOntologyID().getVersionIRI(), actual.getOntologyID().getVersionIRI())) {
                return false;
            }
        }

        if (!expected.getImportsDeclarations().equals(actual.getImportsDeclarations())) {
            return false;
        }

        OWLDocumentFormat expectedFormat = expected.getFormat();
        OWLDocumentFormat actualFormat = actual.getFormat();
        if (expectedFormat != null && expectedFormat.isPrefixOWLDocumentFormat() &&
                actualFormat != null && actualFormat.isPrefixOWLDocumentFormat()) {
            if (!expectedFormat.asPrefixOWLDocumentFormat().getPrefixName2PrefixMap()
                    .equals(actualFormat.asPrefixOWLDocumentFormat().getPrefixName2PrefixMap())) {
                return false;
            }
        }

        if (!expected.annotations().collect(Collectors.toSet()).equals(actual.annotations().collect(Collectors.toSet()))) {
            return false;
        }

        if (!expected.axioms().collect(Collectors.toSet()).equals(actual.axioms().collect(Collectors.toSet()))) {
            return false;
        }

        return true;
    }
    // Crea e configura un OWLOntologyManager con parser e storer ProtocOWL registrati.
    private static boolean areAxiomOperandsSupported(OWLAxiom axiom) {
        // Applica una verifica dettagliata sui costrutti utilizzati negli assiomi per assicurare la compatibilità con ProtocOWL.
        if (axiom instanceof OWLSubClassOfAxiom) {
            OWLSubClassOfAxiom sub = (OWLSubClassOfAxiom) axiom;
            return isSupportedClassExpression(sub.getSubClass()) && isSupportedClassExpression(sub.getSuperClass());
        }
        if (axiom instanceof OWLEquivalentClassesAxiom) {
            OWLEquivalentClassesAxiom eq = (OWLEquivalentClassesAxiom) axiom;
            // Usa una lambda esplicita per mantenere leggibilità e compatibilità.
            return eq.classExpressions().allMatch(c -> isSupportedClassExpression(c));
        }
        if (axiom instanceof OWLClassAssertionAxiom) {
            OWLClassAssertionAxiom ca = (OWLClassAssertionAxiom) axiom;
            return isSupportedClassExpression(ca.getClassExpression());
        }
        if (axiom instanceof OWLObjectPropertyAssertionAxiom) {
            OWLObjectPropertyAssertionAxiom opa = (OWLObjectPropertyAssertionAxiom) axiom;
            return isSupportedObjectPropertyExpression(opa.getProperty())
                    && opa.getSubject() != null
                    && opa.getObject() != null;
        }
        if (axiom instanceof OWLDataPropertyAssertionAxiom) {
            OWLDataPropertyAssertionAxiom dpa = (OWLDataPropertyAssertionAxiom) axiom;
            return isSupportedDataPropertyExpression(dpa.getProperty())
                    && dpa.getSubject() != null
                    && isSupportedLiteral(dpa.getObject());
        }
        return true;
    }


    // Verifica che l'espressione di classe sia composta solo da costrutti supportati (classi nominate, intersezioni, someValuesFrom, allValuesFrom).
    private static boolean isSupportedClassExpression(OWLClassExpression ce) {
        if (!ce.isAnonymous()) return true;

        if (ce instanceof OWLObjectIntersectionOf) {
            OWLObjectIntersectionOf intersection = (OWLObjectIntersectionOf) ce;
            // Usa una lambda esplicita per mantenere leggibilità e compatibilità.
            return intersection.operands().allMatch(op -> isSupportedClassExpression(op));
        }
        if (ce instanceof OWLObjectSomeValuesFrom) {
            OWLObjectSomeValuesFrom some = (OWLObjectSomeValuesFrom) ce;
            return isSupportedObjectPropertyExpression(some.getProperty()) && isSupportedClassExpression(some.getFiller());
        }
        if (ce instanceof OWLObjectAllValuesFrom) {
            OWLObjectAllValuesFrom all = (OWLObjectAllValuesFrom) ce;
            return isSupportedObjectPropertyExpression(all.getProperty()) && isSupportedClassExpression(all.getFiller());
        }
        return false;
    }

    private static boolean isSupportedObjectPropertyExpression(OWLObjectPropertyExpression ope) {
        return !ope.isAnonymous() || ope instanceof OWLObjectInverseOf;
    }

    private static boolean isSupportedDataPropertyExpression(OWLDataPropertyExpression dpe) {
        return !dpe.isAnonymous();
    }

    private static boolean isSupportedLiteral(OWLLiteral literal) {
        // Applica una validazione restrittiva per evitare datatype non gestibili o corrotti.
        if (literal.isRDFPlainLiteral()) return true;
        if (literal.getDatatype() == null || literal.getDatatype().getIRI() == null) return false;

        // Accetta solo datatype base noti come compatibili con ProtocOWL.
        String iri = literal.getDatatype().getIRI().toString();
        return iri.equals("http://www.w3.org/2001/XMLSchema#string") ||
                iri.equals("http://www.w3.org/2001/XMLSchema#integer") ||
                iri.equals("http://www.w3.org/2001/XMLSchema#int") ||
                iri.equals("http://www.w3.org/2001/XMLSchema#boolean") ||
                iri.equals("http://www.w3.org/2001/XMLSchema#float") ||
                iri.equals("http://www.w3.org/2001/XMLSchema#double") ||
                iri.equals("http://www.w3.org/1999/02/22-rdf-syntax-ns#PlainLiteral");
    }
}