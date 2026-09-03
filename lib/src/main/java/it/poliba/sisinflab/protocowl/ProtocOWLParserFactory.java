package it.poliba.sisinflab.protocowl;

import org.semanticweb.owlapi.io.OWLParser;
import org.semanticweb.owlapi.io.OWLParserFactoryImpl;

/**
 * Factory del parser OWL personalizzato per il formato ProtocOWL.
 * Estende la factory base di OWLAPI registrando il relativo document format
 * e creando istanze del parser dedicato.
 */
public class ProtocOWLParserFactory extends OWLParserFactoryImpl {

    /**
     * Costruisce la factory associandola al document format ProtocOWL.
     */
    public ProtocOWLParserFactory() {
        super(new ProtocOWLDocumentFormatFactory());
    }

    /**
     * Crea una nuova istanza del parser ProtocOWL.
     *
     * @return parser OWL per documenti ProtocOWL
     */
    @Override
    public OWLParser createParser() {
        return new ProtocOWLParser();
    }

}
