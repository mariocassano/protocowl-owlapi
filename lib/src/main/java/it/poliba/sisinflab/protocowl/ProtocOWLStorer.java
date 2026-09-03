package it.poliba.sisinflab.protocowl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;

import javax.annotation.Nonnull;

import org.semanticweb.owlapi.io.OWLOntologyDocumentTarget;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.model.OWLStorer;

/**
 * Storer OWL personalizzato per il formato ProtocOWL.
 *
 * Implementa l'interfaccia {@link OWLStorer} e si occupa di instradare
 * la serializzazione verso un {@link OutputStream} valido, delegando poi
 * la scrittura binaria effettiva a {@link Renderer}.
 */
public class ProtocOWLStorer implements OWLStorer {

    /**
     * Prepara lo stream di output reale a partire da un document IRI assoluto.
     *
     * Se lo schema e {@code file}, crea le directory mancanti e apre un file locale.
     * In caso contrario, apre una connessione all'URL e restituisce lo stream remoto.
     *
     * @param documentIRI IRI assoluto della destinazione di scrittura
     * @return stream di output pronto per la serializzazione
     * @throws IOException se non riesce ad aprire la destinazione
     */
    private static OutputStream prepareActualOutput(IRI documentIRI) throws IOException {
        if ("file".equals(documentIRI.getScheme())) {
            File file = new File(documentIRI.toURI());
            file.getParentFile().mkdirs();
            return new FileOutputStream(file);
        }
        URL url = documentIRI.toURI().toURL();
        URLConnection conn = url.openConnection();
        return conn.getOutputStream();
    }

    /**
     * Indica se questo storer supporta il formato richiesto.
     *
     * @param ontologyFormat formato documento da verificare
     * @return {@code true} se il formato e ProtocOWL, {@code false} altrimenti
     */
    @Override
    public boolean canStoreOntology(@Nonnull OWLDocumentFormat ontologyFormat) {
        return ontologyFormat instanceof ProtocOWLDocumentFormat;
    }

    /**
     * Serializza l'ontologia verso la destinazione identificata da IRI.
     *
     * Verifica che l'IRI sia assoluto, apre lo stream di output e delega
     * la serializzazione al metodo interno {@code store}.
     *
     * @param ontology ontologia da serializzare
     * @param documentIRI IRI assoluto della destinazione
     * @param ontologyFormat formato di serializzazione richiesto
     * @throws OWLOntologyStorageException se l'IRI non e assoluto o se avviene un errore OWLAPI
     * @throws IOException se avviene un errore I/O in apertura/scrittura
     */
    @Override
    public void storeOntology(@Nonnull OWLOntology ontology, @Nonnull IRI documentIRI,
            @Nonnull OWLDocumentFormat ontologyFormat) throws OWLOntologyStorageException, IOException {
        if (!documentIRI.isAbsolute()) {
            throw new OWLOntologyStorageException("L'IRI del documento deve essere assoluto: " + documentIRI);
        }
        try (var stream = prepareActualOutput(documentIRI)) {
            store(ontology, stream, ontologyFormat);
        } catch (IOException e) {
            throw new OWLOntologyStorageException(e);
        }
    }

    /**
     * Serializza l'ontologia verso un target OWLAPI astratto.
     *
     * Tenta prima l'uso diretto dello stream, poi il fallback su document IRI.
     * Se nessuna delle due destinazioni e disponibile, solleva un errore esplicito.
     *
     * @param ontology ontologia da serializzare
     * @param target destinazione astratta OWLAPI
     * @param format formato di serializzazione richiesto
     * @throws OWLOntologyStorageException se il target non fornisce ne stream ne IRI,
     *         o se la serializzazione fallisce
     * @throws IOException se avviene un errore I/O durante la scrittura
     */
    @Override
    public void storeOntology(@Nonnull OWLOntology ontology, @Nonnull OWLOntologyDocumentTarget target,
            @Nonnull OWLDocumentFormat format) throws OWLOntologyStorageException, IOException {
        var outputStream = target.getOutputStream();
        if (outputStream.isPresent()) {
            store(ontology, outputStream.get(), format);
            return;
        }

        var documentIRI = target.getDocumentIRI();
        if (documentIRI.isPresent()) {
            storeOntology(ontology, documentIRI.get(), format);
            return;
        }

        throw new OWLOntologyStorageException(
                "Non è stato possibile ottenere un IRI o uno stream di output per serializzare l'ontologia con il formato richiesto: " +
                         format.getKey());
    }

    /**
     * Esegue la serializzazione effettiva tramite renderer ProtocOWL.
     *
     * @param ontology ontologia da serializzare
     * @param outputStream stream di destinazione gia aperto
     * @param format formato documento (atteso ProtocOWL)
     * @throws OWLOntologyStorageException se il renderer produce errore I/O
     */
    private void store(@Nonnull OWLOntology ontology, @Nonnull OutputStream outputStream,
            @Nonnull OWLDocumentFormat format) throws OWLOntologyStorageException {
        try {
            new Renderer().render(ontology, outputStream, (ProtocOWLDocumentFormat) format);
        } catch (IOException e) {
            throw new OWLOntologyStorageException(e);
        }
    }

}
