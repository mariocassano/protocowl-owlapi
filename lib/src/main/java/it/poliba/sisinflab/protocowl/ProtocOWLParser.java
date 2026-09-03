package it.poliba.sisinflab.protocowl;

import java.io.IOException;

import javax.annotation.Nonnull;

import org.semanticweb.owlapi.io.DocumentSources;
import org.semanticweb.owlapi.io.OWLOntologyDocumentSource;
import org.semanticweb.owlapi.io.OWLOntologyInputSourceException;
import org.semanticweb.owlapi.io.OWLParser;
import org.semanticweb.owlapi.io.OWLParserException;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLDocumentFormatFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;

/**
 * Parser OWL personalizzato per il formato ProtocOWL.
 *
 * Implementa l'interfaccia {@link OWLParser} e delega il parsing
 * a {@link Parser}, occupandosi anche della gestione delle eccezioni
 * legate alla sorgente del documento.</p>
 */
public class ProtocOWLParser implements OWLParser {
    /**
     * Esegue il parsing di una sorgente documento OWL e popola l'ontologia fornita.
     *
     * @param documentSource sorgente del documento OWL da leggere
     * @param ontology ontologia di destinazione da popolare
     * @param configuration configurazione del loader OWL
     * @return formato del documento OWL rilevato/parso
     * @throws OWLParserException se si verifica un errore di lettura o parsing
     */
    public OWLDocumentFormat parse(@Nonnull OWLOntologyDocumentSource documentSource, @Nonnull OWLOntology ontology,
            @Nonnull OWLOntologyLoaderConfiguration configuration) throws OWLParserException {
        try (var stream = DocumentSources.wrapInput(documentSource, configuration)) {
            return new Parser().parse(stream, ontology);
        } catch (OWLOntologyInputSourceException | IOException e) {
            throw new OWLParserException(e);
        }
    }
    /**
     * Restituisce la factory del formato supportato da questo parser.
     *
     * @return factory del formato ProtocOWL
     */
    @Override
    public OWLDocumentFormatFactory getSupportedFormat() {
        return new ProtocOWLDocumentFormatFactory();
    }
}
