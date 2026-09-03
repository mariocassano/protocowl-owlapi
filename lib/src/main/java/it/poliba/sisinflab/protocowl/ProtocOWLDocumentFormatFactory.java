package it.poliba.sisinflab.protocowl;

import java.util.ArrayList;

import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.util.OWLDocumentFormatFactoryImpl;

public class ProtocOWLDocumentFormatFactory extends OWLDocumentFormatFactoryImpl {


    /**
     * Costruttore della factory.
     * Nessuna estensione file specifica, flag non testuale disabilitato,
     * nome formato: ProtocOWL Format.
     */
    public ProtocOWLDocumentFormatFactory() {
        super(new ArrayList<String>(0), false, "ProtocOWL Format");
    }

    /**
     * Crea una nuova istanza del formato documento ProtocOWL
     *
     * @return nuovo oggetto ProtocOWLDocumentFormat
     */
    @Override
    public OWLDocumentFormat createFormat() {
        return new ProtocOWLDocumentFormat();
    }

}
