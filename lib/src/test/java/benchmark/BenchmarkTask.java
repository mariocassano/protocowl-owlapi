package benchmark;

import it.poliba.sisinflab.protocowl.ProtocOWLDocumentFormat;
import it.poliba.sisinflab.protocowl.ProtocOWLParserFactory;
import it.poliba.sisinflab.protocowl.ProtocOWLStorerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.*;
import org.semanticweb.owlapi.model.*;

import java.io.File;
import java.io.FileOutputStream;

public class BenchmarkTask {
    // Valore sentinella usato quando l'operazione non produce un file di output (caso parse).
    private static final long NO_OUTPUT_SIZE = 0L;

    public static void main(String[] args) {
        // Contratto CLI: tipo di task, formato target e file da elaborare.
        if (args.length < 3) {
            System.err.println("Uso: java BenchmarkTask <parse|render> <FormatName> <InputFile>");
            System.exit(1);
        }

        String task = args[0];
        String formatName = args[1];
        File inputFile = new File(args[2]);

        // Il manager viene inizializzato una sola volta per il task corrente.
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        // Registra esplicitamente parser e storer ProtocOWL per evitare dipendenze dal classpath runtime.
        manager.getOntologyParsers().add(new ProtocOWLParserFactory());
        manager.getOntologyStorers().add(new ProtocOWLStorerFactory());

        try {
            if ("parse".equals(task)) {
                // Misura solo il tempo di parsing del documento in ontologia.
                long start = System.nanoTime();
                manager.loadOntologyFromOntologyDocument(inputFile);
                long end = System.nanoTime();

                double timeMs = (end - start) / 1_000_000.0;
                long inputSize = inputFile.length();

                // Output CSV: Task,Format,Ontology,TimeMs,InputSizeBytes,OutputSizeBytes.
                // Nel parse non esiste output serializzato, quindi OutputSizeBytes = 0.
                System.out.printf("%s,%s,%s,%.4f,%d,%d%n", task, formatName, extractBaseName(inputFile.getName()), timeMs, inputSize, NO_OUTPUT_SIZE);

            } else if ("render".equals(task)) {
                // La preparazione non rientra nella misura: include caricamento ontologia e scelta formato.
                OWLOntology ontology = manager.loadOntologyFromOntologyDocument(inputFile);
                OWLDocumentFormat format = getFormatByName(formatName);

                // Propaga i prefissi dal formato sorgente per mantenere serializzazione coerente.
                if (format.isPrefixOWLDocumentFormat() && ontology.getFormat() != null && ontology.getFormat().isPrefixOWLDocumentFormat()) {
                    format.asPrefixOWLDocumentFormat().copyPrefixesFrom(ontology.getFormat().asPrefixOWLDocumentFormat());
                }

                // Scrive su file temporaneo per misurare una serializzazione reale e ottenere la dimensione output.
                File dummyOut = File.createTempFile("bench_out", ".tmp");

                // Misura solo il tempo di rendering/serializzazione sullo stream di output.
                long start = System.nanoTime();
                try (FileOutputStream fos = new FileOutputStream(dummyOut)) {
                    manager.saveOntology(ontology, format, fos);
                }
                long end = System.nanoTime();

                double timeMs = (end - start) / 1_000_000.0;
                long inputSize = inputFile.length();
                long outputSize = dummyOut.length();

                // Output CSV: Task,Format,Ontology,TimeMs,InputSizeBytes,OutputSizeBytes.
                System.out.printf("%s,%s,%s,%.4f,%d,%d%n", task, formatName, extractBaseName(inputFile.getName()), timeMs, inputSize, outputSize);

                dummyOut.delete(); // Rimuove il file temporaneo creato per la misurazione.
            } else {
                // Interrompe il task in caso di valore non previsto per il primo argomento.
                throw new IllegalArgumentException("Task non riconosciuto: " + task);
            }
        } catch (Exception e) {
            // In benchmark batch, il fallimento deve essere esplicito e terminare con codice di errore.
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static OWLDocumentFormat getFormatByName(String name) {
        // Mappa un nome simbolico CLI alla relativa implementazione OWLAPI del formato.
        return switch (name) {
            case "Functional" -> new FunctionalSyntaxDocumentFormat();
            case "Manchester" -> new ManchesterSyntaxDocumentFormat();
            case "OWLXML" -> new OWLXMLDocumentFormat();
            case "RDFXML" -> new RDFXMLDocumentFormat();
            case "Turtle" -> new TurtleDocumentFormat();
            case "ProtocOWL" -> new ProtocOWLDocumentFormat();
            default -> throw new IllegalArgumentException("Formato non riconosciuto: " + name);
        };
    }

    private static String extractBaseName(String filename) {
        // Restituisce il nome ontologia senza estensione per uniformare l'identificativo nel CSV.
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex == -1 ? filename : filename.substring(0, dotIndex);
    }
}
