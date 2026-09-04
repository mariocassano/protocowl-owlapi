package it.poliba.sisinflab.protocowl;

/**
 * Classe che contiene le costanti globali del protocollo ProtocOWL,
 * inclusi i codici (tag) per identificare i vari frame e i costrutti complessi.
 */
final class Constants {
    static final int PROTOCOWL_VERSION = 1;

    // FRAME TYPES (Valori esadecimali per i tipi di frame ProtocOWL)

    // --- Frame di Controllo ---
    static final int FRAME_ADD                         = 0x00;
    static final int FRAME_REMOVE                      = 0x01;
    static final int FRAME_RESET                       = 0x02;
    static final int FRAME_END                         = 0x03;

    // --- Dichiarazioni e Metadati ---
    static final int FRAME_NAMESPACE_DECL              = 0x04;
    static final int FRAME_IDENTIFIER_DECL             = 0x05;
    static final int FRAME_ONTOLOGY_IRI                = 0x06;
    static final int FRAME_IMPORTS                     = 0x07;
    static final int FRAME_ANNOTATIONS                 = 0x08;

    // --- Dichiarazioni delle Entità Primitive ---
    static final int FRAME_CLASS_DECL                  = 0x09;
    static final int FRAME_DATATYPE_DECL               = 0x0A;
    static final int FRAME_OBJ_PROP_DECL               = 0x0B;
    static final int FRAME_DATA_PROP_DECL              = 0x0C;
    static final int FRAME_ANNOTATION_PROP_DECL        = 0x0D;
    static final int FRAME_NAMED_IND_DECL              = 0x0E;

    // --- Assiomi Logici Principali (0x0F - 0x29) ---
    static final int FRAME_SUBCLASS_OF                 = 0x0F;
    static final int FRAME_EQUIVALENT_CLASSES          = 0x10;
    static final int FRAME_DISJOINT_CLASSES            = 0x11;
    static final int FRAME_DISJOINT_UNION              = 0x12;
    static final int FRAME_SUB_OBJ_PROP                = 0x13;
    static final int FRAME_EQUIVALENT_OBJ_PROPS        = 0x14;
    static final int FRAME_DISJOINT_OBJ_PROPS          = 0x15;
    static final int FRAME_INVERSE_OBJ_PROP            = 0x16;
    static final int FRAME_OBJ_PROP_DOMAIN             = 0x17;
    static final int FRAME_OBJ_PROP_RANGE              = 0x18;
    static final int FRAME_FUNCTIONAL_OBJ_PROP         = 0x19;
    static final int FRAME_INVERSE_FUNCTIONAL_OBJ_PROP = 0x1A;
    static final int FRAME_REFLEXIVE_OBJ_PROP          = 0x1B;
    static final int FRAME_IRREFLEXIVE_OBJ_PROP        = 0x1C;
    static final int FRAME_SYMMETRIC_OBJ_PROP          = 0x1D;
    static final int FRAME_ASYMMETRIC_OBJ_PROP         = 0x1E;
    static final int FRAME_TRANSITIVE_OBJ_PROP         = 0x1F;
    static final int FRAME_SUB_DATA_PROP               = 0x20;
    static final int FRAME_EQUIVALENT_DATA_PROPS       = 0x21;
    static final int FRAME_DISJOINT_DATA_PROPS         = 0x22;
    static final int FRAME_DATA_PROP_DOMAIN            = 0x23;
    static final int FRAME_DATA_PROP_RANGE             = 0x24;
    static final int FRAME_FUNCTIONAL_DATA_PROP        = 0x25;
    static final int FRAME_DATA_TYPE_DEFINITION        = 0x26;
    static final int FRAME_HAS_KEY                     = 0x27;
    static final int FRAME_SAME_INDIVIDUAL             = 0x28;
    static final int FRAME_DIFFERENT_INDIVIDUALS       = 0x29;

    // --- Asserzioni (ABox) e Annotazioni ---
    static final int FRAME_CLASS_ASSERTION             = 0x2A;
    static final int FRAME_OBJ_PROP_ASSERTION          = 0x2B;
    static final int FRAME_NEG_OBJ_PROP_ASSERTION      = 0x2C;
    static final int FRAME_DATA_PROP_ASSERTION         = 0x2D;
    static final int FRAME_NEG_DATA_PROP_ASSERTION     = 0x2E;
    static final int FRAME_ANNOTATION_ASSERTION        = 0x2F;
    static final int FRAME_SUB_ANNOTATION_PROP         = 0x30;

    // COMPLEX TYPES & LITERALS

    // --- Complex Types Header Values ---
    static final int TMAX_CLASS_EXPRESSION             = 17;
    static final int CLASS_EXPR_INTERSECTION           = 0x00;
    static final int CLASS_EXPR_UNION                  = 0x01; 
    static final int CLASS_EXPR_COMPLEMENT             = 0x02;
    static final int CLASS_EXPR_ONE_OF                 = 0x03;
    static final int CLASS_EXPR_SOME_VALUES            = 0x04; 
    static final int CLASS_EXPR_ALL_VALUES             = 0x05; 
    static final int CLASS_EXPR_HAS_VALUE              = 0x06;
    static final int CLASS_EXPR_HAS_SELF               = 0x07;  
    static final int CLASS_EXPR_MIN_CARD               = 0x08;
    static final int CLASS_EXPR_MAX_CARD               = 0x09;
    static final int CLASS_EXPR_EXACT_CARD             = 0x0A;

    // --- Tipi di Letterali (Type Bits) ---
    static final int LITERAL_PLAIN                     = 0x00;
    static final int LITERAL_LANG                      = 0x01;
    static final int LITERAL_TYPED                     = 0x02;

    // --- Formati di Letterali (Format Bits) ---
    static final int LITERAL_FMT_STRING                = 0x00;
    static final int LITERAL_FMT_BOOLEAN               = 0x02;
    static final int LITERAL_FMT_SIGNED_INT            = 0x03;
    static final int LITERAL_FMT_UNSIGNED_INT          = 0x04;
}