package org.perl6.nqp.truffle.sixmodel;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

import org.perl6.nqp.truffle.runtime.HLL;
import org.perl6.nqp.truffle.runtime.NQPCodeRef;
import org.perl6.nqp.truffle.runtime.NQPNull;
import org.perl6.nqp.truffle.runtime.NQPHash;

import org.perl6.nqp.truffle.sixmodel.TypeObject;
import org.perl6.nqp.truffle.sixmodel.reprs.VMArrayInstance;


public class SerializationReader {
    /* The current version of the serialization format. */
    private final int CURRENT_VERSION = 11;

    /* The minimum version of the serialization format. */
    private final int MIN_VERSION = 8;

    /* Various sizes (in bytes). */
    private final int V10_HEADER_SIZE           = 4 * 16;
    private final int HEADER_SIZE               = 4 * 18;
    private final int DEP_TABLE_ENTRY_SIZE      = 8;
    private final int STABLES_TABLE_ENTRY_SIZE  = 12;
    private final int OBJECTS_TABLE_ENTRY_SIZE  = 16;
    private final int CLOSURES_TABLE_ENTRY_SIZE = 24;
    private final int CONTEXTS_TABLE_ENTRY_SIZE = 16;
    private final int REPOS_TABLE_ENTRY_SIZE    = 16;

    /* Possible reference types we can serialize. */
    private final short REFVAR_NULL               = 1;
    private final short REFVAR_OBJECT             = 2;
    private final short REFVAR_VM_NULL            = 3;
    private final short REFVAR_VM_INT             = 4;
    private final short REFVAR_VM_NUM             = 5;
    private final short REFVAR_VM_STR             = 6;
    private final short REFVAR_VM_ARR_VAR         = 7;
    private final short REFVAR_VM_ARR_STR         = 8;
    private final short REFVAR_VM_ARR_INT         = 9;
    private final short REFVAR_VM_HASH_STR_VAR    = 10;
    private final short REFVAR_STATIC_CODEREF     = 11;
    private final short REFVAR_CLONED_CODEREF     = 12;
//
//    /* Starting state. */
//    private ThreadContext tc;
    private SerializationContext sc;

    private String[] sh;

    private NQPCodeRef[] cr;

//    private CallFrame[] contexts;

    private ByteBuffer orig;

    /* The version of the serialization format we're currently reading. */
    public int version;

    /* Various table offsets and entry counts. */
    private int depTableOffset;
    private int depTableEntries;
    private int stTableOffset;
    private int stTableEntries;
    private int stDataOffset;
    private int objTableOffset;
    private int objTableEntries;
    private int objDataOffset;
    private int closureTableOffset;
    private int closureTableEntries;
    private int contextTableOffset;
    private int contextTableEntries;
    private int contextDataOffset;
    private int reposTableOffset;
    private int reposTableEntries;
    private int stringHeapOffset;
    private int stringHeapEntries;

    /* Serialization contexts we depend on. */
    private HashMap<String, SerializationContext> scs;

    private HashMap<String, HLL> hlls;

    SerializationContext[] dependentSCs;

    /* The object we're currently deserializing. */
    Object currentObject;

    REPR[] reprs;

    TypeObject BOOTArray;

    public SerializationReader(SerializationContext sc, String[] sh, NQPCodeRef[] cr, ByteBuffer orig, HashMap<String, SerializationContext> scs, HashMap<String, HLL> hlls, TypeObject BOOTArray) {
        this.sc = sc;
        this.sh = sh;
        this.cr = cr;
        this.orig = orig;
        this.scs = scs;
        this.hlls = hlls;
        this.BOOTArray = BOOTArray;
    }

    public void deserialize() {
        // Serialized data is always little endian.
        orig.order(ByteOrder.LITTLE_ENDIAN);

        // Split the input into the various segments.
        checkAndDisectInput();

        deserializeStringHeap();

        resolveDependencies();

        // Put code refs in place.
        for (int i = 0; i < cr.length; i++) {
//            cr[i].isStaticCodeRef = true;
//            cr[i].sc = sc;
            sc.addCodeRef(cr[i]);
        }

        // Handle any STable repossessions, then stub STables.
        sc.initSTableList(stTableEntries);
//        if (reposTableEntries > 0)
//            repossess(1);
        stubSTables();
//
        // Handle any object repossessions, then stub objects.
        sc.initObjectList(objTableEntries);
//        if (reposTableEntries > 0)
//            repossess(0);
        stubObjects();
//
//        // Do first step of deserializing any closures.
//        deserializeClosures();
//
//        // Second passes over STables and objects.
        deserializeSTables();
        deserializeObjects();
//
//        // Finish up contexts and closures.
//        deserializeContexts();
//        attachClosureOuters(crCount);
//        attachContextOuters();
//        fixupContextOuters();
    }
//
//    /* Checks the header looks sane and all of the places it points to make sense.
//     * Also disects the input string into the tables and data segments and populates
//     * the reader data structure more fully. */
    private void checkAndDisectInput() {
        int prov_pos = 0;
        int data_len = orig.limit();

        /* Ensure that we have enough space to read a version number and check it. */
        if (data_len < 4)
            throw new RuntimeException("Serialized data too short to read a version number (< 4 bytes)");
        version = orig.getInt();
        if (version < MIN_VERSION || version > CURRENT_VERSION)
            throw new RuntimeException("Unknown serialization format version " + version);

        /* Ensure that the data is at least as long as the header is expected to be. */
        int headerSize = version >= 11 ? HEADER_SIZE : V10_HEADER_SIZE;
        if (data_len < headerSize)
            throw new RuntimeException("Serialized data shorter than header (< " + headerSize + " bytes)");
        prov_pos += headerSize;

        /* Get size and location of dependencies table. */
        depTableOffset = orig.getInt();
        depTableEntries = orig.getInt();
        if (depTableOffset < prov_pos)
            throw new RuntimeException("Corruption detected (dependencies table starts before header ends)");
        prov_pos += depTableEntries * DEP_TABLE_ENTRY_SIZE;
        if (prov_pos > data_len)
            throw new RuntimeException("Corruption detected (dependencies table overruns end of data)");

        /* Get size and location of STables table. */
        stTableOffset = orig.getInt();
        stTableEntries = orig.getInt();
        if (stTableOffset < prov_pos)
            throw new RuntimeException("Corruption detected (STables table starts before dependencies table ends)");
        prov_pos += stTableEntries * STABLES_TABLE_ENTRY_SIZE;
        if (prov_pos > data_len)
            throw new RuntimeException("Corruption detected (STables table overruns end of data)");

        /* Get location of STables data. */
        stDataOffset = orig.getInt();
        if (stDataOffset < prov_pos)
            throw new RuntimeException("Corruption detected (STables data starts before STables table ends)");
        prov_pos = stDataOffset;
        if (stDataOffset > data_len)
            throw new RuntimeException("Corruption detected (STables data starts after end of data)");

        /* Get size and location of objects table. */
        objTableOffset = orig.getInt();
        objTableEntries = orig.getInt();
        if (objTableOffset < prov_pos)
            throw new RuntimeException("Corruption detected (objects table starts before STables data ends)");
        prov_pos = objTableOffset + objTableEntries * OBJECTS_TABLE_ENTRY_SIZE;
        if (prov_pos > data_len)
            throw new RuntimeException("Corruption detected (objects table overruns end of data)");

        /* Get location of objects data. */
        objDataOffset = orig.getInt();
        if (objDataOffset < prov_pos)
            throw new RuntimeException("Corruption detected (objects data starts before objects table ends)");
        prov_pos = objDataOffset;
        if (prov_pos > data_len)
            throw new RuntimeException("Corruption detected (objects data starts after end of data)");

        /* Get size and location of closures table. */
        closureTableOffset = orig.getInt();
        closureTableEntries = orig.getInt();
        if (closureTableOffset < prov_pos)
            throw new RuntimeException("Corruption detected (Closures table starts before objects data ends)");
        prov_pos = closureTableOffset + closureTableEntries * CLOSURES_TABLE_ENTRY_SIZE;
        if (prov_pos > data_len)
            throw new RuntimeException("Corruption detected (Closures table overruns end of data)");

        /* Get size and location of contexts table. */
        contextTableOffset = orig.getInt();
        contextTableEntries = orig.getInt();
        if (contextTableOffset < prov_pos)
            throw new RuntimeException("Corruption detected (contexts table starts before closures table ends)");
        prov_pos = contextTableOffset + contextTableEntries * CONTEXTS_TABLE_ENTRY_SIZE;
        if (prov_pos > data_len)
            throw new RuntimeException("Corruption detected (contexts table overruns end of data)");

        /* Get location of contexts data. */
        contextDataOffset = orig.getInt();
        if (contextDataOffset < prov_pos)
            throw new RuntimeException("Corruption detected (contexts data starts before contexts table ends)");
        prov_pos = contextDataOffset;
        if (prov_pos > data_len)
            throw new RuntimeException("Corruption detected (contexts data starts after end of data)");

        /* Get size and location of repossessions table. */
        reposTableOffset = orig.getInt();
        reposTableEntries = orig.getInt();
        if (reposTableOffset < prov_pos)
            throw new RuntimeException("Corruption detected (repossessions table starts before contexts data ends)");
        prov_pos = reposTableOffset + reposTableEntries * REPOS_TABLE_ENTRY_SIZE;
        if (prov_pos > data_len)
            throw new RuntimeException("Corruption detected (repossessions table overruns end of data)");

        if (version >= 11) {
            /* Get size and location of string heap. */
            stringHeapOffset = orig.getInt();
            stringHeapEntries = orig.getInt();
            if (stringHeapOffset < prov_pos)
                throw new RuntimeException("Corruption detected (string table starts before repossessions tabke ends)");
            prov_pos = stringHeapOffset;
            if (prov_pos > data_len)
                throw new RuntimeException("Corruption detected (string table starts after end of data)");
        }
    }

    private void deserializeStringHeap() {
        if (version >= 11) {
            sh = new String[stringHeapEntries + 1];
            sh[0] = null;

            orig.position(stringHeapOffset);
            for (int i = 1; i <= stringHeapEntries; i++) {
                int len = orig.getInt();
                byte[] bytes = new byte[len];
                orig.get(bytes, 0, len);

                try {
                    sh[i] = new String(bytes, "utf-8");
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException("Failed to decode a utf-8 encoded string heap entry");
                }
            }
        }
    }

    private void resolveDependencies() {
        dependentSCs = new SerializationContext[depTableEntries];
        orig.position(depTableOffset);
        for (int i = 0; i < depTableEntries; i++) {
            String handle = readString();
            String desc = readString();
            SerializationContext sc = scs.get(handle);
            if (sc == null) {
                if (desc == null)
                    desc = handle;

                System.out.println("Missing or wrong version of dependency '" + desc + "' with handle " + handle);
                //throw new RuntimeException(
                //    "Missing or wrong version of dependency '" + desc + "'");
            } else {
                System.out.println("Found " + desc + ": " +  handle);
            }
            dependentSCs[i] = sc;
        }
    }

//
//    /* Repossess an object or STable. */
//    private void repossess(int chosenType) {
//        for (int i = 0; i < reposTableEntries; i++) {
//            /* Go to table row. */
//            orig.position(reposTableOffset + i * REPOS_TABLE_ENTRY_SIZE);
//
//            /* Do appropriate type of repossession. */
//            int repoType = orig.getInt();
//            if (repoType != chosenType)
//                continue;
//            int objIdx = orig.getInt();
//            int origSCIdx = orig.getInt();
//            int origObjIdx = orig.getInt();
//            if (repoType == 0) {
//                /* Get object to repossess. */
//                SerializationContext origSC = locateSC(origSCIdx);
//                SixModelObject origObj = origSC.getObject(origObjIdx);
//
//                /* Ensure we aren't already trying to repossess the object. */
//                /* XXX TODO */
//
//                /* Put it into objects root set at the appropriate slot. */
//                sc.addObject(origObj, objIdx);
//                origObj.sc = sc;
//
//                /* The object's STable may have changed as a result of the
//                 * repossession (perhaps due to mixing in to it), so put the
//                 * STable it should now have in place. */
//                orig.position(objTableOffset + objIdx * OBJECTS_TABLE_ENTRY_SIZE);
//                origObj.st = lookupSTable(orig.getInt(), orig.getInt());
//            }
//            else if (repoType == 1) {
//                /* Get STable to repossess. */
//                SerializationContext origSC = locateSC(origSCIdx);
//                STable origST = origSC.getSTable(origObjIdx);
//
//                /* Ensure we aren't already trying to repossess the STable. */
//                /* XXX TODO */
//
//                /* Put it into STables root set at the apporpriate slot. */
//                sc.setSTable(objIdx, origST);
//                origST.sc = sc;
//            }
//            else {
//                throw new RuntimeException("Unknown repossession type");
//            }
//        }
//    }
//
    private void stubSTables() {
        for (int i = 0; i < stTableEntries; i++) {
            // May already have it, due to repossession.
            if (sc.getSTable(i) != null)
                continue;

            // Look up representation.
            orig.position(stTableOffset + i * STABLES_TABLE_ENTRY_SIZE);

            // Create STable stub and add it to the root STable set.
            STable stable = new STable(null);
            REPR repr = REPRRegistry.createFromName(readString(), stable);
            stable.repr = repr;
            stable.sc = sc;
            sc.setSTable(i, stable);
        }
    }

    private void stubObjects() {
        reprs = new REPR[objTableOffset];

        for (int i = 0; i < objTableEntries; i++) {
            // May already have it, due to repossession.
            if (sc.getObject(i) != null)
                continue;

            // Look up STable.
            orig.position(objTableOffset + i * OBJECTS_TABLE_ENTRY_SIZE);
            STable st = lookupSTable(orig.getInt(), orig.getInt());

            // Now go by object flags.
            Object stubObj;
            orig.position(orig.position() + 4);
            int flags = orig.getInt();
            if (flags == 0) {
                // Type object.
                TypeObject typeObject = new TypeObject(st);
                typeObject.sc = sc;
                stubObj = typeObject;
            }
            else {
                // Concrete object; defer to the REPR.
                stubObj = st.repr.deserializeStub();
                st.repr.setSc(stubObj, sc);

                reprs[i] = st.repr;
            }

            // Place object in SC root set.
            sc.addObject(stubObj, i);
        }
    }

//    private void deserializeClosures() {
//        for (int i = 0; i < closureTableEntries; i++) {
//            /* Seek to the closure's table row. */
//            orig.position(closureTableOffset + i * CLOSURES_TABLE_ENTRY_SIZE);
//
//            /* Resolve the reference to the static code object. */
//            CodeRef staticCode = readCodeRef();
//
//            /* Clone it and add it to this SC's code refs list. */
//            CodeRef closure = (CodeRef)staticCode.clone(tc);
//            closure.sc = sc;
//            sc.addCodeRef(closure);
//
//            /* See if there's a code object we need to attach. */
//            orig.position(orig.position() + 4);
//            if (orig.getInt() != 0)
//                closure.codeObject = readObjRef();
//        }
//    }
//
    private void deserializeSTables() {
        for (int i = 0; i < stTableEntries; i++) {
            // Seek to the right position in the data chunk.
            orig.position(stTableOffset + i * STABLES_TABLE_ENTRY_SIZE + 4);
            orig.position(stDataOffset + orig.getInt());

            // Get the STable we need to deserialize into.
            STable st = sc.getSTable(i);

            // Read the HOW, WHAT and WHO.
            st.how = readObjRef();
            st.what = readObjRef();
            st.who = readRef();

            /* Method cache */
            Object methodCache = readRef();
            if (methodCache != NQPNull.SINGLETON) {
                st.methodCache = ((NQPHash) methodCache).getContents();
            }

            long vtableLength = orig.getLong();

            if (vtableLength != 0) {
                throw new RuntimeException("VTables shouldn't be ever deserialized");
            }

            /* Type check cache. */
            int typeCheckCacheSize = (int)orig.getLong();
            if (typeCheckCacheSize > 0) {
                st.typeCheckCache = new Object[typeCheckCacheSize];
                for (int j = 0; j < st.typeCheckCache.length; j++)
                    st.typeCheckCache[j] = readRef();
            }

            /* Mode flags. */
            st.modeFlags = (int)orig.getLong();

            /* Boolification spec. */
            if (orig.getLong() != 0) {
                st.boolificationSpec = new BoolificationSpec();
                st.boolificationSpec.mode = (int)orig.getLong();
                st.boolificationSpec.method = readRef();
            }

            /* Container spec. */
            if (orig.getLong() != 0) {
                if (version >= 5) {
                    String specType = readString();
                    throw new RuntimeException("Unknown container config " + specType);
                }
                else {
                    throw new RuntimeException("Unable to deserialize old container spec format");
                }
            }

            /* Invocation spec. */
            if (version >= 5) {
                if (orig.getLong() != 0) {
                    st.invocationSpec = new InvocationSpec();
                    st.invocationSpec.classHandle = readRef();
                    st.invocationSpec.attrName = lookupString(orig.getInt());
                    orig.getLong(); /* The hint is unused */
                    st.invocationSpec.invocationHandler = readRef();
                }
            }

            /* HLL stuff. */
            if (version >= 6) {
                st.hllOwner = hlls.get(readString());
                st.hllRole = orig.getLong();
            }

            /* Type parametricity. */
            if (version >= 9) {
                long paraFlag = orig.getLong();
//                /* If it's a parametric type... */
//                if (paraFlag == 1) {
//                    ParametricType pt = new ParametricType();
//                    pt.parameterizer = readRef();
//                    pt.lookup = new ArrayList<Map.Entry<SixModelObject, SixModelObject>>();
//                    st.parametricity = pt;
//                }
//                else if (paraFlag == 2) {
//                    ParameterizedType pt = new ParameterizedType();
//                    pt.parametricType = readObjRef();
//                    SixModelObject BOOTArray = tc.gc.BOOTArray;
//                    pt.parameters = BOOTArray.st.REPR.allocate(tc, BOOTArray.st);
//                    int elems = orig.getInt();
//                    for (int j = 0; j < elems; j++)
//                        pt.parameters.bind_pos_boxed(tc, j, readRef());
//                    st.parametricity = pt;
//                }
//                else
                if (paraFlag != 0) {
                    throw new RuntimeException("Unknown STable parametricity flag");
                }
            }

            /* If the REPR has a function to deserialize representation data, call it. */
            st.repr.deserializeReprData(this, st);
        }
    }

    private void deserializeObjects() {
        for (int i = 0; i < objTableEntries; i++) {
            // Can skip if it's a type object.
            Object obj = sc.getObject(i);
            if (!(obj instanceof TypeObject)) {
                // Seek reader to object data offset.
                orig.position(objTableOffset + i * OBJECTS_TABLE_ENTRY_SIZE + 8);
                orig.position(objDataOffset + orig.getInt());

                // Complete the object's deserialization.
                this.currentObject = obj;

                reprs[i].deserializeFinish(this, obj);
                this.currentObject = null;

            }
        }
    }

//    private void deserializeContexts() {
//        contexts = new CallFrame[contextTableEntries];
//        for (int i = 0; i < contextTableEntries; i++) {
//            /* Seek to the context's table row. */
//            orig.position(contextTableOffset + i * CONTEXTS_TABLE_ENTRY_SIZE);
//
//            /* Resolve the reference to the static code object this context is for. */
//            CodeRef staticCode = readCodeRef();
//
//            /* Create a context and set it up. */
//            CallFrame ctx = new CallFrame();
//            ctx.tc = tc;
//            ctx.codeRef = staticCode;
//            StaticCodeInfo sci = staticCode.staticInfo;
//            if (sci.oLexicalNames != null)
//                ctx.oLex = sci.oLexStatic.clone();
//            if (sci.iLexicalNames != null)
//                ctx.iLex = new long[sci.iLexicalNames.length];
//            if (sci.nLexicalNames != null)
//                ctx.nLex = new double[sci.nLexicalNames.length];
//            if (sci.sLexicalNames != null)
//                ctx.sLex = new String[sci.sLexicalNames.length];
//
//            /* Set context data read position, and set current read buffer to the correct thing. */
//            orig.position(contextDataOffset + orig.getInt());
//
//            /* Deserialize lexicals. */
//            long syms = orig.getLong();
//            for (long j = 0; j < syms; j++) {
//                String sym = readStr();
//                Integer idx;
//                if ((idx = sci.oTryGetLexicalIdx(sym)) != null)
//                    ctx.oLex[idx] = readRef();
//                else if ((idx = sci.iTryGetLexicalIdx(sym)) != null)
//                    ctx.iLex[idx] = orig.getLong();
//                else if ((idx = sci.nTryGetLexicalIdx(sym)) != null)
//                    ctx.nLex[idx] = orig.getDouble();
//                else if ((idx = sci.sTryGetLexicalIdx(sym)) != null)
//                    ctx.sLex[idx] = readStr();
//                else
//                    throw new RuntimeException("Failed to deserialize lexical " + sym);
//            }
//
//            /* Put context in place. */
//            contexts[i] = ctx;
//        }
//    }
//
//    private void attachClosureOuters(int closureBaseIdx) {
//        for (int i = 0; i < closureTableEntries; i++) {
//            orig.position(closureTableOffset + i * CLOSURES_TABLE_ENTRY_SIZE + 8);
//            int idx = orig.getInt();
//            if (idx > 0)
//                sc.getCodeRef(closureBaseIdx + i).outer = contexts[idx - 1];
//        }
//    }
//
//    private void attachContextOuters() {
//        for (int i = 0; i < contextTableEntries; i++) {
//            orig.position(contextTableOffset + i * CONTEXTS_TABLE_ENTRY_SIZE + 12);
//            int idx = orig.getInt();
//            if (idx > 0)
//                contexts[i].outer = contexts[idx - 1];
//        }
//    }
//
//    private void fixupContextOuters() {
//        for (int i = 0; i < contextTableEntries; i++) {
//            if (contexts[i].outer == null &&
//                contexts[i].codeRef.staticInfo.priorInvocation != null &&
//                contexts[i].codeRef.staticInfo.priorInvocation.outer != null)
//                contexts[i].outer = contexts[i].codeRef.staticInfo.priorInvocation.outer;
//        }
//    }
//
    public Object readRef() {
        short discrim = orig.getShort();
        switch (discrim) {
        case REFVAR_NULL:
        case REFVAR_VM_NULL:
            return NQPNull.SINGLETON;
        case REFVAR_OBJECT:
            return readObjRef();
        case REFVAR_VM_INT:
            return orig.getLong();
//        case REFVAR_VM_NUM:
//            SixModelObject BOOTNum = tc.gc.BOOTNum;
//            SixModelObject nResult = BOOTNum.st.REPR.allocate(tc, BOOTNum.st);
//            nResult.set_num(tc, orig.getDouble());
//            return nResult;
        case REFVAR_VM_STR:
            return readString();
        case REFVAR_VM_ARR_VAR: {
            VMArrayInstance array = (VMArrayInstance) BOOTArray.stable.repr.allocate();
              int elems = orig.getInt();
            for (int i = 0; i < elems; i++) {
                array.push(readRef());
            }
            if (this.currentObject != null) {
                array.sc = sc;
                sc.ownedObjects.put(array, this.currentObject);
            }

            return array;
        }
//        case REFVAR_VM_ARR_STR: {
//            SixModelObject BOOTStrArray = tc.gc.BOOTStrArray;
//            SixModelObject resArray = BOOTStrArray.st.REPR.allocate(tc, BOOTStrArray.st);
//            elems = orig.getInt();
//            for (int i = 0; i < elems; i++) {
//                tc.native_s = readStr();
//                resArray.bind_pos_native(tc, i);
//            }
//            return resArray;
//        }
//        case REFVAR_VM_ARR_INT: {
//            SixModelObject BOOTIntArray = tc.gc.BOOTIntArray;
//            SixModelObject resArray = BOOTIntArray.st.REPR.allocate(tc, BOOTIntArray.st);
//            elems = orig.getInt();
//            for (int i = 0; i < elems; i++) {
//                tc.native_i = readLong();
//                resArray.bind_pos_native(tc, i);
//            }
//            return resArray;
//        }
        case REFVAR_VM_HASH_STR_VAR: {
            NQPHash hash = new NQPHash();
            int elems = orig.getInt();
            for (int i = 0; i < elems; i++) {
                String key = lookupString(orig.getInt());
                hash.bindkey(key, readRef());
            }
            if (this.currentObject != null) {
                hash.sc = sc;
                sc.ownedObjects.put(hash, this.currentObject);
            }
            return hash;
        }
        case REFVAR_STATIC_CODEREF:
        case REFVAR_CLONED_CODEREF:
            return readCodeRef();
        default:
            throw new RuntimeException("Unimplemented case of read_ref: " + discrim);
        }
    }

    public Object readObjRef() {
        SerializationContext sc = locateSC(orig.getInt());
        int idx = orig.getInt();
        if (idx < 0 || idx >= sc.objectCount())
            throw new RuntimeException("Invalid SC object index " + idx);
        return sc.getObject(idx);
    }

    public STable readSTableRef() {
        return lookupSTable(orig.getInt(), orig.getInt());
    }


    public Object readCodeRef() {
        SerializationContext sc = locateSC(orig.getInt());
        int idx = orig.getInt();
        if (idx < 0 || idx >= sc.codeRefCount()) {
              System.out.println("Invalid SC code index " + idx);
              return NQPNull.SINGLETON;
//            throw new RuntimeException("Invalid SC code index " + idx);
        }
        return sc.getCodeRef(idx);
    }

//
    public long readLong() {
        return orig.getLong();
    }

//    public int readInt32() {
//        return orig.getInt();
//    }
//

    public double readDouble() {
        return orig.getDouble();
    }

    public String readString() {
        return lookupString(orig.getInt());
    }

    private STable lookupSTable(int scIdx, int idx) {
        SerializationContext sc = locateSC(scIdx);
        if (idx < 0 || idx >= sc.stableCount())
            throw new RuntimeException("Invalid STable index (scIdx=" + scIdx + ",idx=" + idx + ")");
        return sc.getSTable(idx);
    }

    private SerializationContext locateSC(int scIdx) {
        if (scIdx == 0)
            return sc;
        if (scIdx < 1 || scIdx > dependentSCs.length)
            throw new RuntimeException("Invalid dependencies table index encountered (index " + scIdx + ")");
        return dependentSCs[scIdx - 1];
    }

    private String lookupString(int idx) {
        if (idx >= sh.length)
            throw new RuntimeException("Attempt to read past end of string heap (index " + idx + ")");
        return sh[idx];
    }
}
