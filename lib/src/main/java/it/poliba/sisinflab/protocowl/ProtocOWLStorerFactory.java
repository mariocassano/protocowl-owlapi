package it.poliba.sisinflab.protocowl;

import org.semanticweb.owlapi.model.OWLStorer;
import org.semanticweb.owlapi.util.OWLStorerFactoryImpl;

/**
 * Factory per la creazione di istanze di {@link ProtocOWLStorer}.
 * Estende {@link OWLStorerFactoryImpl} per fornire un'implementazione personalizzata
 * del meccanismo di memorizzazione dei documenti OWL nel formato ProtocOWL.
 */
public class ProtocOWLStorerFactory extends OWLStorerFactoryImpl {
    /**
     * Costruttore che inizializza la factory con un'istanza personalizzata di
     * {@link ProtocOWLDocumentFormatFactory} per gestire il formato dei documenti.
     */
    public ProtocOWLStorerFactory() {
        super(new ProtocOWLDocumentFormatFactory());
    }
    /**
     * Crea e restituisce una nuova istanza di {@link ProtocOWLStorer}.
     *
     * @return una nuova istanza di ProtocOWLStorer utilizzata per memorizzare
     *         i documenti OWL nel formato ProtocOWL
     */
    @Override
    public OWLStorer createStorer() {
        return new ProtocOWLStorer();
    }

}
