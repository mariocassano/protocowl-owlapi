package it.poliba.sisinflab.protocowl;

import org.semanticweb.owlapi.formats.PrefixDocumentFormatImpl;

public class ProtocOWLDocumentFormat extends PrefixDocumentFormatImpl {

    /**
     * Restituisce la chiave identificativa del formato documento.
     *
     * @return nome del formato ProtocOWL
     */
    @Override
    public String getKey() {
        return "ProtocOWL Format";
    }


    @Override
    public boolean isTextual() {
        return false;
    }

}
