package it.poliba.sisinflab.protocowl;

/**
 * Classe che contiene le costanti globali del protocollo ProtocOWL,
 * inclusi i codici (tag) per identificare i vari frame e i costrutti complessi.
 */
final class Constants {
    static final int PROTOCOWL_VERSION = 1;

    // --- Frame Types (Valori esadecimali per i tipi di frame ProtocOWL) ---

    // Dichiarazioni ed Header
    static final int FRAME_ONTOLOGY_IRI = 0x20;
    static final int FRAME_ONTOLOGY_IRI_VERSIONED = 0x2C;
    static final int FRAME_NAMESPACE_DECL = 0x2A;
    static final int FRAME_IDENTIFIER_DECL = 0x2B;

    // Dichiarazioni delle Entità Primitive
    static final int FRAME_CLASS_DECL = 0x00;
    static final int FRAME_DATATYPE_DECL = 0x01;
    static final int FRAME_OBJ_PROP_DECL = 0x02;
    static final int FRAME_DATA_PROP_DECL = 0x03;
    static final int FRAME_ANNOTATION_PROP_DECL = 0x04;
    static final int FRAME_NAMED_IND_DECL = 0x05;

    // Assiomi Logici Principali
    static final int FRAME_SUBCLASS_OF = 0x06;
    static final int FRAME_EQUIVALENT_CLASSES = 0x07;

    // Asserzioni (ABox)
    static final int FRAME_CLASS_ASSERTION = 0x21;
    static final int FRAME_OBJ_PROP_ASSERTION = 0x22;
    static final int FRAME_DATA_PROP_ASSERTION = 0x24;

    // --- Complex Types Header Values ---
    static final int TMAX_CLASS_EXPRESSION = 17;
    static final int CLASS_EXPR_INTERSECTION = 0;
    static final int CLASS_EXPR_SOME_VALUES = 5;
    static final int CLASS_EXPR_ALL_VALUES = 6;

    // --- Tipi di Letterali ---
    static final int LITERAL_PLAIN = 0x00;
    static final int LITERAL_LANG = 0x01;
    static final int LITERAL_TYPED = 0x02;
}